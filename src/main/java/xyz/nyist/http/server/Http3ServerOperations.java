package xyz.nyist.http.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DefaultHeaders;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.*;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.server.HttpServerState;
import reactor.netty.http.server.WebsocketServerSpec;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import xyz.nyist.core.*;
import xyz.nyist.http.*;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static reactor.netty.ReactorNetty.format;
import static reactor.netty.http.server.HttpServerState.REQUEST_DECODING_FAILED;

/**
 * @author: fucong
 * @Date: 2022/7/27 18:36
 * @Description:
 */
@Slf4j
public class Http3ServerOperations extends Http3Operations<Http3ServerRequest, Http3ServerResponse>
        implements Http3ServerRequest, Http3ServerResponse {


    final static AsciiString EVENT_STREAM = new AsciiString("text/event-stream");

    final static Http3HeadersFrame CONTINUE = new DefaultHttp3HeadersFrame(new DefaultHttp3Headers().status(HttpResponseStatus.CONTINUE.codeAsText()));

    static final Http3ServerFormDecoderProvider DEFAULT_FORM_DECODER_SPEC = new Http3ServerFormDecoderProvider.Build().build();

    final BiPredicate<Http3ServerRequest, Http3ServerResponse> compressionPredicate;

    final ConnectionInfo connectionInfo;

    final ServerCookieDecoder cookieDecoder;

    final ServerCookieEncoder cookieEncoder;

    final ServerCookies cookieHolder;

    final Http3ServerFormDecoderProvider formDecoderProvider;

    final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle;

    final Http3HeadersFrame requestHeadersFrame;

    final Http3HeadersFrame responseHeadsFrame;

    final Http3Headers responseHeaders;


    String path;

    Consumer<? super HttpHeaders> trailerHeadersConsumer;

    volatile Context currentContext;

    public Http3ServerOperations(Connection c, ConnectionObserver listener, Http3HeadersFrame requestHeadersFrame,
                                 @Nullable BiPredicate<Http3ServerRequest, Http3ServerResponse> compressionPredicate,
                                 @Nullable ConnectionInfo connectionInfo,
                                 Http3ServerFormDecoderProvider formDecoderProvider,
                                 @Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
                                 boolean resolvePath) {
        super(c, listener);
        //this.nettyRequest = nettyRequest;
        this.requestHeadersFrame = requestHeadersFrame;
        this.compressionPredicate = compressionPredicate;
        this.connectionInfo = connectionInfo;
        this.cookieDecoder = ServerCookieDecoder.STRICT;
        this.cookieEncoder = ServerCookieEncoder.STRICT;
        this.cookieHolder = ServerCookies.newServerRequestHolder(requestHeadersFrame, this.cookieDecoder);
        this.currentContext = Context.empty();
        this.formDecoderProvider = formDecoderProvider;
        this.mapHandle = mapHandle;
        this.responseHeadsFrame = new DefaultHttp3HeadersFrame();
        if (resolvePath) {
            this.path = resolvePath(extractPath(requestHeadersFrame.headers()).toString());
        } else {
            this.path = null;
        }
        this.responseHeaders = responseHeadsFrame.headers();
        this.responseHeaders.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        this.responseHeaders.status(HttpResponseStatus.OK.codeAsText());
    }

    static void cleanHandlerTerminate(Channel ch) {
        ChannelOperations<?, ?> ops = get(ch);

        if (ops == null) {
            return;
        }

        ops.discard();

        //Try to defer the disposing to leave a chance for any synchronous complete following this callback
        if (!ops.isSubscriptionDisposed()) {
            ch.eventLoop()
                    .execute(((Http3ServerOperations) ops)::terminate);
        } else {
            //if already disposed, we can immediately call terminate
            ((Http3ServerOperations) ops).terminate();
        }
    }

    static long requestsCounter(Channel channel) {
        Http3ServerOperations ops = Connection.from(channel).as(Http3ServerOperations.class);

        if (ops == null) {
            return -1;
        }

        return ((AtomicLong) ops.connection()).get();
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    static void sendDecodingFailures(ChannelHandlerContext ctx, ConnectionObserver listener, Throwable t, Object msg) throws Http3Exception {

        Throwable cause = t.getCause() != null ? t.getCause() : t;

        if (log.isWarnEnabled()) {
            log.warn(format(ctx.channel(), "Decoding failed: " + msg + " : "), cause);
        }

        ReferenceCountUtil.release(msg);

        HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                            cause instanceof TooLongFrameException ? HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE :
                                                                    HttpResponseStatus.BAD_REQUEST);
        response.headers()
                .setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        Connection ops = ChannelOperations.get(ctx.channel());
        if (ops == null) {
            Connection conn = Connection.from(ctx.channel());
            if (msg instanceof HttpRequest) {
                ops = new Http3ServerOperations.FailedHttp3ServerRequest(conn, listener, (Http3HeadersFrame) msg, response);
                ops.bind();
            } else {
                ops = conn;
            }
        }

        //"FutureReturnValueIgnored" this is deliberate
        ctx.channel().writeAndFlush(response);

        listener.onStateChange(ops, REQUEST_DECODING_FAILED);
    }

    @Override
    public NettyOutbound sendHeaders() {
        if (hasSentHeaders()) {
            return this;
        }

        return then(Mono.empty());
    }

    @Override
    public Http3ServerOperations withConnection(Consumer<? super Connection> withConnection) {
        Objects.requireNonNull(withConnection, "withConnection");
        withConnection.accept(this);
        return this;
    }

//    @Override
//    protected HttpMessage newFullBodyMessage(ByteBuf body) {
//        HttpResponse res = new DefaultFullHttpResponse(version(), status(), body);
//
//        if (!HttpMethod.HEAD.equals(method())) {
//            responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
//            if (!HttpResponseStatus.NOT_MODIFIED.equals(status())) {
//
//                if (HttpUtil.getContentLength(nettyResponse, -1) == -1) {
//                    responseHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
//                }
//            }
//        }
//        // For HEAD requests:
//        // - if there is Transfer-Encoding and Content-Length, Transfer-Encoding will be removed
//        // - if there is only Transfer-Encoding, it will be kept and not replaced by
//        // Content-Length: body.readableBytes()
//        // For HEAD requests, the I/O handler may decide to provide only the headers and complete
//        // the response. In that case body will be EMPTY_BUFFER and if we set Content-Length: 0,
//        // this will not be correct
//        // https://github.com/reactor/reactor-netty/issues/1333
//        else if (HttpUtil.getContentLength(nettyResponse, -1) != -1) {
//            responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
//        }
//
//        res.headers().set(responseHeaders);
//        return res;
//    }


    @Override
    public Http3ServerResponse addHeader(CharSequence name, CharSequence value) {
        if (!hasSentHeaders()) {
            this.responseHeaders.add(name, value);
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }

    @Override
    public Http3ServerResponse header(CharSequence name, CharSequence value) {
        if (!hasSentHeaders()) {
            this.responseHeaders.set(name, value);
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }

    @Override
    public Http3ServerResponse addCookie(Cookie cookie) {
        if (!hasSentHeaders()) {
            this.responseHeaders.add(HttpHeaderNames.SET_COOKIE,
                                     cookieEncoder.encode(cookie));
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }

    @Override
    public Map<CharSequence, Set<Cookie>> cookies() {
        if (cookieHolder != null) {
            return cookieHolder.getCachedCookies();
        }
        throw new IllegalStateException("request not parsed");
    }

    @Override
    public Map<CharSequence, List<Cookie>> allCookies() {
        if (cookieHolder != null) {
            return cookieHolder.getAllCachedCookies();
        }
        throw new IllegalStateException("request not parsed");
    }

    @Override
    public Context currentContext() {
        return currentContext;
    }


    @Override
    public Http3ServerResponse headers(Http3Headers headers) {
        if (!hasSentHeaders()) {
            this.responseHeaders.set(headers);
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }

    @Override
    public boolean isKeepAlive() {
        return Http3Util.isKeepAlive(responseHeaders);
    }


    @Override
    public HttpMethod method() {
        return HttpMethod.valueOf(requestHeadersFrame.headers().method().toString());
    }


    @Override
    public Flux<?> receiveObject() {
        // Handle the 'Expect: 100-continue' header if necessary.
        // TODO: Respond with 413 Request Entity Too Large
        //   and discard the traffic or close the connection.
        //       No need to notify the upstream handlers - just log.
        //       If decoding a response, just throw an error.
        if (Http3Util.is100ContinueExpected(requestHeadersFrame)) {
            return FutureMono.deferFuture(() -> {
                        if (!hasSentHeaders()) {
                            return channel().writeAndFlush(CONTINUE);
                        }
                        return channel().newSucceededFuture();
                    })
                    .thenMany(super.receiveObject());
        } else {
            return super.receiveObject();
        }
    }

    @Override
    @Nullable
    public InetSocketAddress hostAddress() {
        if (connectionInfo != null) {
            return this.connectionInfo.getHostAddress();
        } else {
            return null;
        }
    }

    @Override
    @Nullable
    public InetSocketAddress remoteAddress() {
        if (connectionInfo != null) {
            return this.connectionInfo.getRemoteAddress();
        } else {
            return null;
        }
    }

    @Override
    public Http3HeadersFrame requestHeaders() {
        if (requestHeadersFrame != null) {
            return requestHeadersFrame;
        }
        throw new IllegalStateException("request not parsed");
    }

    @Override
    public String scheme() {
        return "https";
    }

//    @Override
//    public Http3Headers responseHeaders() {
//        return responseHeaders;
//    }

    @Override
    public Mono<Void> send() {
        if (markSentHeaderAndBody()) {
            return FutureMono.deferFuture(() -> writeMessage(EMPTY_BUFFER));
        } else {
            return Mono.empty();
        }
    }


    @Override
    public Mono<Void> sendNotFound() {
        return this.status(HttpResponseStatus.NOT_FOUND)
                .send();
    }

    @Override
    public Mono<Void> sendRedirect(String location) {
        Objects.requireNonNull(location, "location");
        return this.status(HttpResponseStatus.FOUND)
                .header(HttpHeaderNames.LOCATION, location)
                .send();
    }

    /**
     * @return the Transfer setting SSE for this http connection (e.g. event-stream)
     */
    @Override
    public Http3ServerResponse sse() {
        header(HttpHeaderNames.CONTENT_TYPE, EVENT_STREAM);
        return this;
    }

    @Override
    public CharSequence status() {
        return this.responseHeaders.status();
    }

    @Override
    public Http3ServerResponse status(HttpResponseStatus status) {
        if (!hasSentHeaders()) {
            this.responseHeaders.status(status.codeAsText());
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }

    @Override
    public Http3ServerResponse trailerHeaders(Consumer<? super HttpHeaders> trailerHeaders) {
        this.trailerHeadersConsumer = Objects.requireNonNull(trailerHeaders, "trailerHeaders");
        return this;
    }

    @Override
    public Mono<Void> sendWebsocket(
            BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler,
            WebsocketServerSpec configurer) {
        return withWebsocketSupport(uri(), configurer, websocketHandler);
    }

    @Override
    public String uri() {
        if (requestHeadersFrame != null) {
            return requestHeadersFrame.headers().path().toString();
        }
        throw new IllegalStateException("request not parsed");
    }

    @Override
    public String fullPath() {
        if (path != null) {
            return path;
        }
        throw new IllegalStateException("request not parsed");
    }


    @Override
    public HttpVersion version() {
        return Http3Version.INSTANCE;
    }

    @Override
    public Http3ServerResponse compression(boolean compress) {
        if (!compress) {
            removeHandler(NettyPipeline.CompressionHandler);
        } else if (channel().pipeline()
                .get(NettyPipeline.CompressionHandler) == null) {
//            SimpleCompressionHandler handler = new SimpleCompressionHandler();
//            try {
//                //Do not invoke handler.channelRead as it will trigger ctx.fireChannelRead
//                handler.decode(channel().pipeline().context(NettyPipeline.ReactiveBridge), nettyRequest);
//
//                addHandlerFirst(NettyPipeline.CompressionHandler, handler);
//            } catch (Throwable e) {
//                log.error(format(channel(), ""), e);
//            }
        }
        return this;
    }

    @Override
    protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Http3HeadersFrame) {
            try {
                listener().onStateChange(this, HttpServerState.REQUEST_RECEIVED);
            } catch (Exception e) {
                onInboundError(e);
                ReferenceCountUtil.release(msg);
                return;
            }
            return;
        }
        if (msg instanceof Http3DataFrame) {
            super.onInboundNext(ctx, msg);
        } else {
            super.onInboundNext(ctx, msg);
        }
    }

    @Override
    protected void onInboundClose() {
        discardWhenNoReceiver();
        if (!(isInboundCancelled() || isInboundDisposed())) {
            onInboundError(new AbortedException("Connection has been closed"));
        }
        terminate();
    }

    @Override
    protected void afterMarkSentHeaders() {
        if (HttpResponseStatus.NOT_MODIFIED.codeAsText().equals(status())) {
            responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
            responseHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
        }
        if (compressionPredicate != null && compressionPredicate.test(this, this)) {
            compression(true);
        }
    }

    @Override
    protected void beforeMarkSentHeaders() {}

    @Override
    protected void onHeadersSent() {}

    @Override
    protected void onOutboundComplete() {
        if (isWebsocket()) {
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug(format(channel(), "Last HTTP response frame"));
        }
        if (markSentHeaderAndBody()) {
            if (log.isDebugEnabled()) {
                log.debug(format(channel(), "No sendHeaders() called before complete, sending zero-length header"));
            }

            writeMessage(EMPTY_BUFFER).addListener(s -> {
                discard();
                if (!s.isSuccess() && log.isDebugEnabled()) {
                    log.debug(format(channel(), "Failed flushing last frame"), s.cause());
                }
                if (channel().isActive()) {
                    shutdownOutput();
                }
            });

        } else if (markSentBody()) {
            shutdownOutput();
        } else {
            discard();
            shutdownOutput();
        }


    }

    /**
     * There is no need of invoking {@link #discard()}, the inbound will
     * be canceled on channel inactive event if there is no subscriber available
     *
     * @param err the {@link Throwable} cause
     */
    @Override
    protected void onOutboundError(Throwable err) {

        if (!channel().isActive()) {
            super.onOutboundError(err);
            return;
        }

        if (markSentHeaders()) {
            log.error(format(channel(), "Error starting response. Replying error status"), err);

            responseHeaders.status(HttpResponseStatus.INTERNAL_SERVER_ERROR.codeAsText());
            responseHeaders.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            channel().writeAndFlush(responseHeadsFrame)
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        markSentBody();
        log.error(format(channel(), "Error finishing response. Closing connection"), err);
        ((QuicStreamChannel) channel()).shutdownOutput();
    }

    @Override
    public Http3HeadersFrame outboundHttpMessage() {
        return responseHeadsFrame;
    }


    final Mono<Void> withWebsocketSupport(String url,
                                          WebsocketServerSpec websocketServerSpec,
                                          BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {

        return Mono.error(new IllegalStateException("Failed to upgrade to websocket"));
    }

    static final class FailedHttp3ServerRequest extends Http3ServerOperations {

        final HttpResponse customResponse;

        FailedHttp3ServerRequest(
                Connection c,
                ConnectionObserver listener,
                Http3HeadersFrame nettyRequest,
                HttpResponse nettyResponse) throws Http3Exception {

            //todo
            super(c, listener, nettyRequest, null, null,
                  DEFAULT_FORM_DECODER_SPEC, null, false);
            this.customResponse = nettyResponse;
            String tempPath = "";
            try {
                //tempPath = resolvePath(nettyRequest.uri());
                tempPath = xyz.nyist.core.HttpConversionUtil.extractPath(nettyRequest.headers()).toString();
            } catch (RuntimeException e) {
                tempPath = "/bad-request";
            } finally {
                this.path = tempPath;
            }
        }

        @Override
        public Http3HeadersFrame outboundHttpMessage() {
            // return customResponse;
            return null;
        }

        @Override
        public CharSequence status() {
            return null;//customResponse.status();
        }

    }

    static final class TrailerHeaders extends DefaultHttpHeaders {

        static final Set<String> DISALLOWED_TRAILER_HEADER_NAMES = new HashSet<>(14);

        static {
            // https://datatracker.ietf.org/doc/html/rfc7230#section-4.1.2
            // A sender MUST NOT generate a trailer that contains a field necessary
            // for message framing (e.g., Transfer-Encoding and Content-Length),
            // routing (e.g., Host), request modifiers (e.g., controls and
            // conditionals in Section 5 of [RFC7231]), authentication (e.g., see
            // [RFC7235] and [RFC6265]), response control data (e.g., see Section
            // 7.1 of [RFC7231]), or determining how to process the payload (e.g.,
            // Content-Encoding, Content-Type, Content-Range, and Trailer).
            DISALLOWED_TRAILER_HEADER_NAMES.add("age");
            DISALLOWED_TRAILER_HEADER_NAMES.add("cache-control");
            DISALLOWED_TRAILER_HEADER_NAMES.add("content-encoding");
            DISALLOWED_TRAILER_HEADER_NAMES.add("content-length");
            DISALLOWED_TRAILER_HEADER_NAMES.add("content-range");
            DISALLOWED_TRAILER_HEADER_NAMES.add("content-type");
            DISALLOWED_TRAILER_HEADER_NAMES.add("date");
            DISALLOWED_TRAILER_HEADER_NAMES.add("expires");
            DISALLOWED_TRAILER_HEADER_NAMES.add("location");
            DISALLOWED_TRAILER_HEADER_NAMES.add("retry-after");
            DISALLOWED_TRAILER_HEADER_NAMES.add("trailer");
            DISALLOWED_TRAILER_HEADER_NAMES.add("transfer-encoding");
            DISALLOWED_TRAILER_HEADER_NAMES.add("vary");
            DISALLOWED_TRAILER_HEADER_NAMES.add("warning");
        }

        TrailerHeaders(String declaredHeaderNames) {
            super(true, new Http3ServerOperations.TrailerHeaders.TrailerNameValidator(filterHeaderNames(declaredHeaderNames)));
        }

        static Set<String> filterHeaderNames(String declaredHeaderNames) {
            Objects.requireNonNull(declaredHeaderNames, "declaredHeaderNames");
            Set<String> result = new HashSet<>();
            String[] names = declaredHeaderNames.split(",", -1);
            for (String name : names) {
                String trimmedStr = name.trim();
                if (trimmedStr.isEmpty() ||
                        DISALLOWED_TRAILER_HEADER_NAMES.contains(trimmedStr.toLowerCase(Locale.ENGLISH))) {
                    continue;
                }
                result.add(trimmedStr);
            }
            return result;
        }

        static final class TrailerNameValidator implements DefaultHeaders.NameValidator<CharSequence> {

            /**
             * Contains the headers names specified with {@link HttpHeaderNames#TRAILER}
             */
            final Set<String> declaredHeaderNames;

            TrailerNameValidator(Set<String> declaredHeaderNames) {
                this.declaredHeaderNames = declaredHeaderNames;
            }

            @Override
            public void validateName(CharSequence name) {
                if (!declaredHeaderNames.contains(name.toString())) {
                    throw new IllegalArgumentException("Trailer header name [" + name +
                                                               "] not declared with [Trailer] header, or it is not a valid trailer header name");
                }
            }

        }

    }

}
