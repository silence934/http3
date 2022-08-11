package xyz.nyist.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DefaultHeaders;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.*;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.server.HttpServerState;
import reactor.netty.http.server.ServerCookies;
import reactor.netty.http.server.WebsocketServerSpec;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import xyz.nyist.http.Http3Operations;
import xyz.nyist.http.Http3ServerFormDecoderProvider;
import xyz.nyist.http.Http3ServerRequest;
import xyz.nyist.http.Http3ServerResponse;
import xyz.nyist.http.temp.ConnectionInfo;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.handler.codec.http.HttpUtil.isTransferEncodingChunked;
import static reactor.netty.ReactorNetty.format;
import static reactor.netty.http.server.HttpServerState.REQUEST_DECODING_FAILED;

/**
 * @author: fucong
 * @Date: 2022/7/27 18:36
 * @Description:
 */
public class Http3ServerOperations extends Http3Operations<Http3ServerRequest, Http3ServerResponse>
        implements Http3ServerRequest, Http3ServerResponse {


    static final Logger log = Loggers.getLogger(Http3ServerOperations.class);

    final static AsciiString EVENT_STREAM = new AsciiString("text/event-stream");

    final static FullHttpResponse CONTINUE =
            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                        HttpResponseStatus.CONTINUE,
                                        EMPTY_BUFFER);

    static final Http3ServerFormDecoderProvider DEFAULT_FORM_DECODER_SPEC = new Http3ServerFormDecoderProvider.Build().build();

    final BiPredicate<Http3ServerRequest, Http3ServerResponse> compressionPredicate;

    final ConnectionInfo connectionInfo;


    final ServerCookieDecoder cookieDecoder;

    final ServerCookieEncoder cookieEncoder;

    final ServerCookies cookieHolder;

    final Http3ServerFormDecoderProvider formDecoderProvider;

    final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle;

    final HttpRequest nettyRequest;

    final HttpResponse nettyResponse;

    final HttpHeaders responseHeaders;

    final String scheme;

    Function<? super String, Map<String, String>> paramsResolver;

//    public Http3ServerOperations(Connection connection, ConnectionObserver listener) {
//        super(connection, listener);
//    }

    String path;

    Consumer<? super HttpHeaders> trailerHeadersConsumer;

    volatile Context currentContext;

    public Http3ServerOperations(Connection c, ConnectionObserver listener, HttpRequest nettyRequest,
                                 @Nullable BiPredicate<Http3ServerRequest, Http3ServerResponse> compressionPredicate,
                                 @Nullable ConnectionInfo connectionInfo,
                                 Http3ServerFormDecoderProvider formDecoderProvider,
                                 @Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
                                 boolean resolvePath,
                                 boolean secured) {
        super(c, listener);


        this.compressionPredicate = compressionPredicate;
        this.connectionInfo = connectionInfo;
        this.cookieDecoder = ServerCookieDecoder.STRICT;
        this.cookieEncoder = ServerCookieEncoder.STRICT;
        this.cookieHolder = ServerCookies.newServerRequestHolder(nettyRequest.headers(), this.cookieDecoder);
        this.currentContext = Context.empty();
        this.formDecoderProvider = formDecoderProvider;
        this.mapHandle = mapHandle;
        this.nettyRequest = nettyRequest;
        this.nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        if (resolvePath) {
            this.path = resolvePath(nettyRequest.uri());
        } else {
            this.path = null;
        }
        this.responseHeaders = nettyResponse.headers();
        this.responseHeaders.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        this.scheme = secured ? "https" : "http";
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
    static void sendDecodingFailures(
            ChannelHandlerContext ctx,
            ConnectionObserver listener,
            boolean secure,
            Throwable t,
            Object msg) {

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
                ops = new Http3ServerOperations.FailedHttp3ServerRequest(conn, listener, (HttpRequest) msg, response, secure);
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
    public boolean isLocalStream() {
        return ((QuicStreamChannel) connection().channel()).isLocalCreated();
    }

    @Override
    public long streamId() {
        return ((QuicStreamChannel) connection().channel()).streamId();
    }

    @Override
    public QuicStreamType streamType() {
        return ((QuicStreamChannel) connection().channel()).type();
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

    @Override
    protected HttpMessage newFullBodyMessage(ByteBuf body) {
        HttpResponse res =
                new DefaultFullHttpResponse(version(), status(), body);

        if (!HttpMethod.HEAD.equals(method())) {
            responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
            if (!HttpResponseStatus.NOT_MODIFIED.equals(status())) {

                if (HttpUtil.getContentLength(nettyResponse, -1) == -1) {
                    responseHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
                }
            }
        }
        // For HEAD requests:
        // - if there is Transfer-Encoding and Content-Length, Transfer-Encoding will be removed
        // - if there is only Transfer-Encoding, it will be kept and not replaced by
        // Content-Length: body.readableBytes()
        // For HEAD requests, the I/O handler may decide to provide only the headers and complete
        // the response. In that case body will be EMPTY_BUFFER and if we set Content-Length: 0,
        // this will not be correct
        // https://github.com/reactor/reactor-netty/issues/1333
        else if (HttpUtil.getContentLength(nettyResponse, -1) != -1) {
            responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
        }

        res.headers().set(responseHeaders);
        return res;
    }

    @Override
    public Http3ServerResponse addCookie(io.netty.handler.codec.http.cookie.Cookie cookie) {
        if (!hasSentHeaders()) {
            this.responseHeaders.add(HttpHeaderNames.SET_COOKIE,
                                     cookieEncoder.encode(cookie));
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }

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
    public Http3ServerOperations chunkedTransfer(boolean chunked) {
        if (!hasSentHeaders() && isTransferEncodingChunked(nettyResponse) != chunked) {
            responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
            HttpUtil.setTransferEncodingChunked(nettyResponse, chunked);
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
    public Http3ServerResponse header(CharSequence name, CharSequence value) {
        if (!hasSentHeaders()) {
            this.responseHeaders.set(name, value);
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }

    @Override
    public Http3ServerResponse headers(HttpHeaders headers) {
        if (!hasSentHeaders()) {
            this.responseHeaders.set(headers);
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }

    @Override
    public boolean isFormUrlencoded() {
        CharSequence mimeType = HttpUtil.getMimeType(nettyRequest);
        return mimeType != null &&
                HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.contentEqualsIgnoreCase(mimeType.toString().trim());
    }

    @Override
    public boolean isKeepAlive() {
        return HttpUtil.isKeepAlive(nettyRequest);
    }

    @Override
    public boolean isMultipart() {
        return HttpPostRequestDecoder.isMultipart(nettyRequest);
    }


    final boolean isHttp2() {
        return requestHeaders().contains(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text());
    }

    @Override
    public Http3ServerResponse keepAlive(boolean keepAlive) {
        HttpUtil.setKeepAlive(nettyResponse, keepAlive);
        return this;
    }

    @Override
    public HttpMethod method() {
        return nettyRequest.method();
    }

    @Override
    @Nullable
    public String param(CharSequence key) {
        Objects.requireNonNull(key, "key");
        Map<String, String> params = null;
        if (paramsResolver != null) {
            params = this.paramsResolver.apply(uri());
        }
        return null != params ? params.get(key.toString()) : null;
    }

    @Override
    @Nullable
    public Map<String, String> params() {
        return null != paramsResolver ? paramsResolver.apply(uri()) : null;
    }

    @Override
    public Http3ServerRequest paramsResolver(Function<? super String, Map<String, String>> paramsResolver) {
        this.paramsResolver = paramsResolver;
        return this;
    }

    @Override
    public Flux<HttpData> receiveForm() {
        return receiveFormInternal(formDecoderProvider);
    }

    @Override
    public Flux<HttpData> receiveForm(Consumer<Http3ServerFormDecoderProvider.Builder> formDecoderBuilder) {
        Objects.requireNonNull(formDecoderBuilder, "formDecoderBuilder");
        Http3ServerFormDecoderProvider.Build builder = new Http3ServerFormDecoderProvider.Build();
        formDecoderBuilder.accept(builder);
        Http3ServerFormDecoderProvider config = builder.build();
        return receiveFormInternal(config);
    }

    @Override
    public Flux<?> receiveObject() {
        // Handle the 'Expect: 100-continue' header if necessary.
        // TODO: Respond with 413 Request Entity Too Large
        //   and discard the traffic or close the connection.
        //       No need to notify the upstream handlers - just log.
        //       If decoding a response, just throw an error.
        if (HttpUtil.is100ContinueExpected(nettyRequest)) {
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
    public HttpHeaders requestHeaders() {
        if (nettyRequest != null) {
            return nettyRequest.headers();
        }
        throw new IllegalStateException("request not parsed");
    }

    @Override
    public String scheme() {
        return "https";
    }

    @Override
    public HttpHeaders responseHeaders() {
        return responseHeaders;
    }

    @Override
    public Mono<Void> send() {
        if (markSentHeaderAndBody()) {
            HttpMessage response = newFullBodyMessage(EMPTY_BUFFER);
            return FutureMono.deferFuture(() -> channel().writeAndFlush(response));
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
    public HttpResponseStatus status() {
        return this.nettyResponse.status();
    }

    @Override
    public Http3ServerResponse status(HttpResponseStatus status) {
        if (!hasSentHeaders()) {
            this.nettyResponse.setStatus(status);
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
        if (nettyRequest != null) {
            return nettyRequest.uri();
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
        if (nettyRequest != null) {
            return nettyRequest.protocolVersion();
        }
        throw new IllegalStateException("request not parsed");
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
        if (msg instanceof HttpRequest) {
            try {
                listener().onStateChange(this, HttpServerState.REQUEST_RECEIVED);
            } catch (Exception e) {
                onInboundError(e);
                ReferenceCountUtil.release(msg);
                return;
            }
            if (msg instanceof FullHttpRequest) {
                FullHttpRequest request = (FullHttpRequest) msg;
                if (request.content().readableBytes() > 0) {
                    super.onInboundNext(ctx, msg);
                } else {
                    request.release();
                }
                if (isHttp2()) {
                    //force auto read to enable more accurate close selection now inbound is done
                    channel().config().setAutoRead(true);
                    onInboundComplete();
                }
            }
            return;
        }
        if (msg instanceof HttpContent) {
            if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
                super.onInboundNext(ctx, msg);
            }
            if (msg instanceof LastHttpContent) {
                //force auto read to enable more accurate close selection now inbound is done
                channel().config().setAutoRead(true);
                onInboundComplete();
            }
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
        if (HttpResponseStatus.NOT_MODIFIED.equals(status())) {
            responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING)
                    .remove(HttpHeaderNames.CONTENT_LENGTH);
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

        final ChannelFuture f;
        if (log.isDebugEnabled()) {
            log.debug(format(channel(), "Last HTTP response frame"));
        }
        if (markSentHeaderAndBody()) {
            if (log.isDebugEnabled()) {
                log.debug(format(channel(), "No sendHeaders() called before complete, sending " +
                        "zero-length header"));
            }

            f = channel().writeAndFlush(newFullBodyMessage(EMPTY_BUFFER));
        } else if (markSentBody()) {
            LastHttpContent lastHttpContent = LastHttpContent.EMPTY_LAST_CONTENT;
            // https://datatracker.ietf.org/doc/html/rfc7230#section-4.1.2
            // A trailer allows the sender to include additional fields at the end
            // of a chunked message in order to supply metadata that might be
            // dynamically generated while the message body is sent, such as a
            // message integrity check, digital signature, or post-processing
            // status.
            if (trailerHeadersConsumer != null && isTransferEncodingChunked(nettyResponse)) {
                // https://datatracker.ietf.org/doc/html/rfc7230#section-4.4
                // When a message includes a message body encoded with the chunked
                // transfer coding and the sender desires to send metadata in the form
                // of trailer fields at the end of the message, the sender SHOULD
                // generate a Trailer header field before the message body to indicate
                // which fields will be present in the trailers.
                String declaredHeaderNames = responseHeaders.get(HttpHeaderNames.TRAILER);
                if (declaredHeaderNames != null) {
                    HttpHeaders trailerHeaders = new Http3ServerOperations.TrailerHeaders(declaredHeaderNames);
                    try {
                        trailerHeadersConsumer.accept(trailerHeaders);
                    } catch (IllegalArgumentException e) {
                        // A sender MUST NOT generate a trailer when header names are
                        // Http3ServerOperations.TrailerHeaders.DISALLOWED_TRAILER_HEADER_NAMES
                        log.error(format(channel(), "Cannot apply trailer headers [{0}]"), declaredHeaderNames, e);
                    }
                    if (!trailerHeaders.isEmpty()) {
                        lastHttpContent = new DefaultLastHttpContent();
                        lastHttpContent.trailingHeaders().set(trailerHeaders);
                    }
                }
            }
            f = channel().writeAndFlush(lastHttpContent);
        } else {
            discard();
            return;
        }
        f.addListener(s -> {
            discard();
            if (!s.isSuccess() && log.isDebugEnabled()) {
                log.debug(format(channel(), "Failed flushing last frame"), s.cause());
            }
        });

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

            nettyResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            responseHeaders.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            channel().writeAndFlush(newFullBodyMessage(EMPTY_BUFFER))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        markSentBody();
        log.error(format(channel(), "Error finishing response. Closing connection"), err);
        channel().writeAndFlush(EMPTY_BUFFER)
                .addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    protected HttpMessage outboundHttpMessage() {
        return nettyResponse;
    }

    final Flux<HttpData> receiveFormInternal(Http3ServerFormDecoderProvider config) {
        boolean isMultipart = isMultipart();

        if (!Objects.equals(method(), HttpMethod.POST) || !(isFormUrlencoded() || isMultipart)) {
            return Flux.error(new IllegalStateException(
                    "Request is not POST or does not have Content-Type " +
                            "with value 'application/x-www-form-urlencoded' or 'multipart/form-data'"));
        }
        return Flux.defer(() ->
                                  config.newHttpPostRequestDecoder(nettyRequest, isMultipart).flatMapMany(decoder ->
                                                                                                                  receiveObject() // receiveContent uses filter operator, this operator buffers, but we don't want it
                                                                                                                          .concatMap(object -> {
                                                                                                                              if (!(object instanceof HttpContent)) {
                                                                                                                                  //decoder;
                                                                                                                                  return Mono.empty();
                                                                                                                              }
                                                                                                                              HttpContent httpContent = (HttpContent) object;
                                                                                                                              if (config.maxInMemorySize > -1) {
                                                                                                                                  httpContent.retain();
                                                                                                                              }
                                                                                                                              return config.maxInMemorySize == -1 ?
                                                                                                                                      Flux.using(
                                                                                                                                              () -> decoder.offer(httpContent),
                                                                                                                                              d -> Flux.fromIterable(decoder.currentHttpData(!config.streaming())),
                                                                                                                                              d -> decoder.cleanCurrentHttpData(!config.streaming())) :
                                                                                                                                      Flux.usingWhen(
                                                                                                                                              Mono.fromCallable(() -> decoder.offer(httpContent))
                                                                                                                                                      .subscribeOn(config.scheduler)
                                                                                                                                                      .doFinally(sig -> httpContent.release()),
                                                                                                                                              d -> Flux.fromIterable(decoder.currentHttpData(true)),
                                                                                                                                              // FIXME Can we have cancellation for the resourceSupplier that will
                                                                                                                                              // cause this one to not be invoked?
                                                                                                                                              d -> Mono.fromRunnable(() -> decoder.cleanCurrentHttpData(true)));
                                                                                                                          }, 0) // There is no need of prefetch, we already have the buffers in the Reactor Netty inbound queue
                                                                                                                          .doFinally(sig -> decoder.destroy())));
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
                HttpRequest nettyRequest,
                HttpResponse nettyResponse,
                boolean secure) {


            super(c, listener, nettyRequest, null, null,
                  DEFAULT_FORM_DECODER_SPEC, null, false, secure);
            this.customResponse = nettyResponse;
            String tempPath = "";
            try {
                tempPath = resolvePath(nettyRequest.uri());
            } catch (RuntimeException e) {
                tempPath = "/bad-request";
            } finally {
                this.path = tempPath;
            }
        }

        @Override
        protected HttpMessage outboundHttpMessage() {
            return customResponse;
        }

        @Override
        public HttpResponseStatus status() {
            return customResponse.status();
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
