package xyz.nyist.http.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.*;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.http.client.HttpClientState;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;
import xyz.nyist.core.DefaultHttp3HeadersFrame;
import xyz.nyist.core.Http3Headers;
import xyz.nyist.core.Http3HeadersFrame;
import xyz.nyist.core.Http3Util;
import xyz.nyist.http.Cookies;
import xyz.nyist.http.Http3Operations;
import xyz.nyist.http.Http3Version;
import xyz.nyist.http.temp.ConnectionInfo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static reactor.netty.ReactorNetty.format;

/**
 * @author: fucong
 * @Date: 2022/7/27 18:36
 * @Description:
 */
@Slf4j
public class Http3ClientOperations extends Http3Operations<NettyInbound, NettyOutbound>
        implements Http3ClientRequest, Http3ClientResponse {

    static final int MAX_REDIRECTS = 50;

    @SuppressWarnings({"unchecked"})
    static final Supplier<String>[] EMPTY_REDIRECTIONS = (Supplier<String>[]) new Supplier[0];


    final Http3HeadersFrame nettyRequest;

    final Http3Headers requestHeaders;

    final ClientCookieEncoder cookieEncoder;

    final ClientCookieDecoder cookieDecoder;

    final Sinks.One<Http3HeadersFrame> trailerHeaders;

    final ConnectionInfo connectionInfo;

    Supplier<String>[] redirectedFrom = EMPTY_REDIRECTIONS;

    String resourceUrl;

    String path;

    Duration responseTimeout;

    volatile Http3ClientOperations.ResponseState responseState;

    boolean started;

    boolean retrying;


    BiPredicate<Http3ClientRequest, HttpClientResponse> followRedirectPredicate;

    Consumer<Http3ClientRequest> redirectRequestConsumer;

    HttpHeaders previousRequestHeaders;

    BiConsumer<HttpHeaders, Http3ClientRequest> redirectRequestBiConsumer;


    public Http3ClientOperations(Connection c, ConnectionObserver listener,
                                 @Nullable ConnectionInfo connectionInfo) {
        super(c, listener);
        this.nettyRequest = new DefaultHttp3HeadersFrame();
        this.requestHeaders = nettyRequest.headers();
        this.connectionInfo = connectionInfo;
        this.requestHeaders.method(HttpMethod.GET.asciiName())
                .authority("www.nyist.xyz:443")
                .path("/api")
                .scheme("https");


        this.cookieEncoder = ClientCookieEncoder.STRICT;
        this.cookieDecoder = ClientCookieDecoder.STRICT;
        this.trailerHeaders = Sinks.unsafe().one();
    }

    @Override
    public Http3ClientRequest addCookie(Cookie cookie) {
        if (!hasSentHeaders()) {
            this.requestHeaders.add(HttpHeaderNames.COOKIE, cookieEncoder.encode(cookie));
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }

    @Override
    public Map<CharSequence, Set<Cookie>> cookies() {
        Http3ClientOperations.ResponseState responseState = this.responseState;
        if (responseState != null && responseState.cookieHolder != null) {
            return responseState.cookieHolder.getCachedCookies();
        }
        return Collections.emptyMap();
    }


    @Override
    public Http3ClientRequest addHeader(CharSequence name, CharSequence value) {
        if (!hasSentHeaders()) {
            this.requestHeaders.add(name, value);
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }

    @Override
    public Http3ClientRequest setHeader(CharSequence name, CharSequence value) {
        if (!hasSentHeaders()) {
            this.requestHeaders.set(name, value);
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }

    @Override
    public Http3ClientRequest headers(Http3Headers headers) {
        if (!hasSentHeaders()) {
            this.requestHeaders.set(headers);
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }


    @Override
    public InetSocketAddress address() {
        if (connectionInfo != null) {
            return connectionInfo.getRemoteAddress();

        }
        return null;
    }

    public void chunkedTransfer(boolean chunked) {
//        if (!hasSentHeaders() && HttpUtil.isTransferEncodingChunked(nettyRequest) != chunked) {
//            requestHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
//            HttpUtil.setTransferEncodingChunked(nettyRequest, chunked);
//        }
    }


    void followRedirectPredicate(BiPredicate<Http3ClientRequest, HttpClientResponse> predicate) {
        this.followRedirectPredicate = predicate;
    }

    void redirectRequestConsumer(@Nullable Consumer<Http3ClientRequest> redirectRequestConsumer) {
        this.redirectRequestConsumer = redirectRequestConsumer;
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    protected void onInboundCancel() {
        if (isInboundDisposed()) {
            return;
        }
        //"FutureReturnValueIgnored" this is deliberate
        channel().close();
    }

    @Override
    protected void onInboundClose() {
        if (isInboundCancelled() || isInboundDisposed()) {
            listener().onStateChange(this, ConnectionObserver.State.DISCONNECTING);
            return;
        }
        listener().onStateChange(this, HttpClientState.RESPONSE_INCOMPLETE);
        //todo PrematureCloseException
        if (responseState == null) {
            if (markSentHeaderAndBody()) {
                listener().onUncaughtException(this, AbortedException.beforeSend());
            } else if (markSentBody()) {
                listener().onUncaughtException(this, new IOException("Connection has been closed BEFORE response, while sending request body"));
            } else {
                listener().onUncaughtException(this, new IOException("Connection prematurely closed BEFORE response"));
            }
            return;
        }
        super.onInboundError(new IOException("Connection prematurely closed DURING response"));
    }

    @Override
    protected void afterInboundComplete() {
//        if (redirecting != null) {
//            listener().onUncaughtException(this, redirecting);
//        } else {
//            listener().onStateChange(this, HttpClientState.RESPONSE_COMPLETED);
//        }

        listener().onStateChange(this, HttpClientState.RESPONSE_COMPLETED);

    }


    @Override
    public boolean isFollowRedirect() {
        return followRedirectPredicate != null && redirectedFrom.length <= MAX_REDIRECTS;
    }

    @Override
    public Http3ClientRequest responseTimeout(Duration maxReadOperationInterval) {
        if (!hasSentHeaders()) {
            this.responseTimeout = maxReadOperationInterval;
        } else {
            throw new IllegalStateException("Status and headers already sent");
        }
        return this;
    }

    @Override
    public boolean isKeepAlive() {
        Http3ClientOperations.ResponseState rs = responseState;
        if (rs != null) {
            return Http3Util.isKeepAlive(rs.response.headers());
        }
        return Http3Util.isKeepAlive(nettyRequest.headers());
    }


    @Override
    public HttpMethod method() {
        return HttpMethod.valueOf(nettyRequest.headers().method().toString());
    }

    @Override
    public CharSequence status() {
        Http3ClientOperations.ResponseState responseState = this.responseState;
        if (responseState != null) {
            return responseState.response.headers().status();
        }
        throw new IllegalStateException("Trying to access status() while missing response");
    }


    @Override
    public final String uri() {
        if (requestHeaders != null) {
            return requestHeaders.path().toString();
        }
        throw new IllegalStateException("request not parsed");
    }


    @Override
    public ContextView currentContextView() {
        return currentContext();
    }

    @Override
    public String[] redirectedFrom() {
        Supplier<String>[] redirectedFrom = this.redirectedFrom;
        String[] dest = new String[redirectedFrom.length];
        for (int i = 0; i < redirectedFrom.length; i++) {
            dest[i] = redirectedFrom[i].get();
        }
        return dest;
    }

    @Override
    public Http3HeadersFrame requestHeaders() {
        return nettyRequest;
    }

    @Override
    public Http3HeadersFrame responseHeaders() {
        Http3ClientOperations.ResponseState responseState = this.responseState;
        if (responseState != null) {
            return responseState.response;
        }
        throw new IllegalStateException("Response headers cannot be accessed without " + "server response");
    }

    @Override
    public NettyOutbound send(Publisher<? extends ByteBuf> source) {
        if (!channel().isActive()) {
            return then(Mono.error(AbortedException.beforeSend()));
        }
        if (source instanceof Mono) {
            return super.send(source);
        }
        if (Objects.equals(method(), HttpMethod.GET) || Objects.equals(method(), HttpMethod.HEAD)) {

            ByteBufAllocator alloc = channel().alloc();
            return new PostHeadersNettyOutbound(Flux.from(source)
                                                        .collectList()
                                                        .doOnDiscard(ByteBuf.class, ByteBuf::release)
                                                        .flatMap(list -> {
                                                            if (markSentHeaderAndBody(list.toArray())) {
                                                                if (list.isEmpty()) {
                                                                    return FutureMono.from(writeMessage(EMPTY_BUFFER));
                                                                }

                                                                ByteBuf output;
                                                                int i = list.size();
                                                                if (i == 1) {
                                                                    output = list.get(0);
                                                                } else {
                                                                    CompositeByteBuf agg = alloc.compositeBuffer(list.size());

                                                                    for (ByteBuf component : list) {
                                                                        agg.addComponent(true, component);
                                                                    }

                                                                    output = agg;
                                                                }

                                                                if (output.readableBytes() > 0) {
                                                                    return FutureMono.from(writeMessage(output));
                                                                }
                                                                output.release();
                                                                return FutureMono.from(writeMessage(EMPTY_BUFFER));
                                                            }
                                                            for (ByteBuf bb : list) {
                                                                if (log.isDebugEnabled()) {
                                                                    log.debug(format(channel(), "Ignoring accumulated bytebuf on http GET {}"), ByteBufUtil.prettyHexDump(bb));
                                                                }
                                                                bb.release();
                                                            }
                                                            return Mono.empty();
                                                        }), this, null);
        }

        return super.send(source);
    }


    @Override
    public Mono<Http3HeadersFrame> trailerHeaders() {
        return trailerHeaders.asMono();
    }


    @Override
    public final String fullPath() {
        return this.path;
    }

    @Override
    public String resourceUrl() {
        return resourceUrl;
    }

    @Override
    public final HttpVersion version() {
        return Http3Version.INSTANCE;
    }

    @Override
    protected void afterMarkSentHeaders() {
        //Noop
    }

    @Override
    protected void beforeMarkSentHeaders() {
        if (redirectedFrom.length > 0) {
            if (redirectRequestConsumer != null) {
                redirectRequestConsumer.accept(this);
            }
            if (redirectRequestBiConsumer != null && previousRequestHeaders != null) {
                redirectRequestBiConsumer.accept(previousRequestHeaders, this);
                previousRequestHeaders = null;
            }
        }
    }

    @Override
    protected void onHeadersSent() {
        channel().read();
        if (channel().parent() != null) {
            channel().parent().read();
        }
    }


    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    protected void onOutboundComplete() {
        if (isWebsocket() || isInboundCancelled()) {
            return;
        }
        if (markSentHeaderAndBody()) {
            if (log.isDebugEnabled()) {
                log.debug(format(channel(), "No sendHeaders() called before complete, sending " +
                        "zero-length header"));
            }
            //"FutureReturnValueIgnored" this is deliberate
            writeMessage(EMPTY_BUFFER);
            //channel().writeAndFlush(newFullBodyMessage(Unpooled.EMPTY_BUFFER));
        } else if (markSentBody()) {
            //"FutureReturnValueIgnored" this is deliberate
            // channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        }
        listener().onStateChange(this, HttpClientState.REQUEST_SENT);
        if (responseTimeout != null) {
            addHandlerFirst(NettyPipeline.ResponseTimeoutHandler,
                            new ReadTimeoutHandler(responseTimeout.toMillis(), TimeUnit.MILLISECONDS));
        }
        channel().read();
        if (channel().parent() != null) {
            channel().parent().read();
        }
    }

    @Override
    protected void onOutboundError(Throwable err) {
        if (isPersistent() && responseState == null) {
            if (log.isDebugEnabled()) {
                log.debug(format(channel(), "Outbound error happened"), err);
            }
            listener().onUncaughtException(this, err);
            if (markSentBody()) {
                markPersistent(false);
            }
            terminate();
            return;
        }
        super.onOutboundError(err);
    }


    @Override
    protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Http3HeadersFrame) {
            Http3HeadersFrame response = (Http3HeadersFrame) msg;
            setNettyResponse(response);
        } else {
            super.onInboundNext(ctx, msg);
        }
    }

    @Override
    protected Http3HeadersFrame outboundHttpMessage() {
        return nettyRequest;
    }


    final void setNettyResponse(Http3HeadersFrame nettyResponse) {
        if (responseState == null) {
            this.responseState = new Http3ClientOperations.ResponseState(nettyResponse, cookieDecoder);
        }
    }


    static final class ResponseState {

        final Http3HeadersFrame response;

        final Cookies cookieHolder;

        ResponseState(Http3HeadersFrame response, ClientCookieDecoder decoder) {
            this.response = response;
            this.cookieHolder = Cookies.newClientResponseHolder(response, decoder);
        }

    }

//    static final class SendForm extends Mono<Void> {
//
//        static final HttpDataFactory DEFAULT_FACTORY = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);
//
//        final Http3ClientOperations parent;
//
//        final BiConsumer<? super Http3ClientRequest, HttpClientForm> formCallback;
//
//        final Consumer<Flux<Long>> progressCallback;
//
//        SendForm(Http3ClientOperations parent,
//                 BiConsumer<? super Http3ClientRequest, HttpClientForm> formCallback,
//                 @Nullable Consumer<Flux<Long>> progressCallback) {
//            this.parent = parent;
//            this.formCallback = formCallback;
//            this.progressCallback = progressCallback;
//        }
//
//        @Override
//        public void subscribe(CoreSubscriber<? super Void> s) {
//            if (!parent.markSentHeaders()) {
//                Operators.error(s,
//                                new IllegalStateException("headers have already been sent"));
//                return;
//            }
//            Subscription subscription = Operators.emptySubscription();
//            s.onSubscribe(subscription);
//            if (parent.channel()
//                    .eventLoop()
//                    .inEventLoop()) {
//                _subscribe(s);
//            } else {
//                parent.channel()
//                        .eventLoop()
//                        .execute(() -> _subscribe(s));
//            }
//        }
//
//        @SuppressWarnings("FutureReturnValueIgnored")
//        void _subscribe(CoreSubscriber<? super Void> s) {
//            HttpDataFactory df = DEFAULT_FACTORY;
//
//            try {
//                HttpClientFormEncoder encoder = new HttpClientFormEncoder(df,
//                                                                          parent.nettyRequest,
//                                                                          false,
//                                                                          HttpConstants.DEFAULT_CHARSET,
//                                                                          HttpPostRequestEncoder.EncoderMode.RFC1738);
//
//                formCallback.accept(parent, encoder);
//
//                encoder = encoder.applyChanges(parent.nettyRequest);
//                df = encoder.newFactory;
//
//                if (!encoder.isMultipart()) {
//                    parent.requestHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
//                }
//
//                // Returned value is deliberately ignored
//                parent.addHandlerFirst(NettyPipeline.ChunkedWriter, new ChunkedWriteHandler());
//
//                boolean chunked = HttpUtil.isTransferEncodingChunked(parent.nettyRequest);
//
//                HttpRequest r = encoder.finalizeRequest();
//
//                if (!chunked) {
//                    HttpUtil.setTransferEncodingChunked(r, false);
//                    HttpUtil.setContentLength(r, encoder.length());
//                }
//
//                ChannelFuture f = parent.channel()
//                        .writeAndFlush(r);
//
//                Flux<Long> tail = encoder.progressSink.asFlux().onBackpressureLatest();
//
//                if (encoder.cleanOnTerminate) {
//                    tail = tail.doOnCancel(encoder)
//                            .doAfterTerminate(encoder);
//                }
//
//                if (encoder.isChunked()) {
//                    if (progressCallback != null) {
//                        progressCallback.accept(tail);
//                    }
//                    //"FutureReturnValueIgnored" this is deliberate
//                    parent.channel()
//                            .writeAndFlush(encoder);
//                } else {
//                    if (progressCallback != null) {
//                        progressCallback.accept(FutureMono.from(f)
//                                                        .cast(Long.class)
//                                                        .switchIfEmpty(Mono.just(encoder.length()))
//                                                        .flux());
//                    }
//                }
//                s.onComplete();
//
//
//            } catch (Throwable e) {
//                Exceptions.throwIfJvmFatal(e);
//                df.cleanRequestHttpData(parent.nettyRequest);
//                s.onError(Exceptions.unwrap(e));
//            }
//        }
//
//    }

}
