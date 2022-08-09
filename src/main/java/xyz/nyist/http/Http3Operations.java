package xyz.nyist.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.*;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.HttpInfos;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.ReactorNetty.toPrettyHexDump;

/**
 * @author: fucong
 * @Date: 2022/7/27 18:34
 * @Description:
 */
public abstract class Http3Operations<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
        extends ChannelOperations<INBOUND, OUTBOUND> implements HttpInfos {

    static final Pattern SCHEME_PATTERN = Pattern.compile("^(https?|wss?)://.*$");

    static final Logger log = Loggers.getLogger(Http3Operations.class);

    static final int READY = 0;

    static final int HEADERS_SENT = 1;

    static final int BODY_SENT = 2;

    final static AtomicIntegerFieldUpdater<Http3Operations> HTTP_STATE =
            AtomicIntegerFieldUpdater.newUpdater(Http3Operations.class,
                                                 "statusAndHeadersSent");

    final static ChannelInboundHandler HTTP_EXTRACTOR = NettyPipeline.inboundHandler(
            (ctx, msg) -> {
                if (msg instanceof ByteBufHolder) {
                    if (msg instanceof FullHttpMessage) {
                        // TODO convert into 2 messages if FullHttpMessage
                        ctx.fireChannelRead(msg);
                    } else {
                        ByteBuf bb = ((ByteBufHolder) msg).content();
                        ctx.fireChannelRead(bb);
                        if (msg instanceof LastHttpContent) {
                            ctx.fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
                        }
                    }
                } else {
                    ctx.fireChannelRead(msg);
                }
            }
    );

    volatile int finSent;

    volatile int statusAndHeadersSent;

    protected Http3Operations(Connection connection, ConnectionObserver listener) {
        super(connection, listener);
        markPersistent(false);
    }

    static void callTerminate(Channel ch) {
        ChannelOperations<?, ?> ops = get(ch);

        if (ops == null) {
            return;
        }

        ((Http3Operations<?, ?>) ops).terminate();
    }

    /**
     * Returns the decoded path portion from the provided {@code uri}
     *
     * @param uri an HTTP URL that may contain a path with query/fragment
     * @return the decoded path portion from the provided {@code uri}
     */
    public static String resolvePath(String uri) {
        Objects.requireNonNull(uri, "uri");

        String tempUri = uri;

        int index = tempUri.indexOf('?');
        if (index > -1) {
            tempUri = tempUri.substring(0, index);
        }

        index = tempUri.indexOf('#');
        if (index > -1) {
            tempUri = tempUri.substring(0, index);
        }

        if (tempUri.isEmpty()) {
            return tempUri;
        }

        if (tempUri.charAt(0) == '/') {
            if (tempUri.length() == 1) {
                return tempUri;
            }
            tempUri = "http://localhost:8080" + tempUri;
        } else if (!SCHEME_PATTERN.matcher(tempUri).matches()) {
            tempUri = "http://" + tempUri;
        }

        return URI.create(tempUri)
                .getPath();
    }

    static void autoAddHttpExtractor(Connection c, String name, ChannelHandler handler) {

        if (handler instanceof ByteToMessageDecoder
                || handler instanceof ByteToMessageCodec
                || handler instanceof CombinedChannelDuplexHandler) {
            String extractorName = name + "$extractor";

            if (c.channel().pipeline().context(extractorName) != null) {
                return;
            }

            c.channel().pipeline().addBefore(name, extractorName, HTTP_EXTRACTOR);

            if (c.isPersistent()) {
                c.onTerminate().subscribe(null, null, () -> c.removeHandler(extractorName));
            }

        }
    }

    @Override
    public String requestId() {
        return asShortText();
    }

    @Override
    public String asLongText() {
        // local and remote addresses are the same, see:
        //io.netty.incubator.codec.quic.QuicheQuicStreamChannel.localAddress
        //io.netty.incubator.codec.quic.QuicheQuicStreamChannel.remoteAddress
        return asShortText() + ", " + channel().localAddress();
    }

    @Override
    @SuppressWarnings("unchecked")
    public NettyOutbound send(Publisher<? extends ByteBuf> source) {
        if (!channel().isActive()) {
            return then(Mono.error(AbortedException.beforeSend()));
        }
        if (source instanceof Mono) {
            return new PostHeadersNettyOutbound(((Mono<ByteBuf>) source)
                                                        .flatMap(msg -> {
                                                            if (markSentHeaderAndBody(msg)) {
                                                                try {
                                                                    afterMarkSentHeaders();
                                                                } catch (RuntimeException e) {
                                                                    ReferenceCountUtil.release(msg);
                                                                    return Mono.error(e);
                                                                }
                                                                if (HttpUtil.getContentLength(outboundHttpMessage(), -1) == 0) {
                                                                    log.debug(format(channel(), "Dropped HTTP content, " +
                                                                            "since response has Content-Length: 0 {}"), toPrettyHexDump(msg));
                                                                    msg.release();
                                                                    return FutureMono.from(channel().writeAndFlush(newFullBodyMessage(Unpooled.EMPTY_BUFFER)));
                                                                }
                                                                return FutureMono.from(channel().writeAndFlush(newFullBodyMessage(msg)));
                                                            }
                                                            return FutureMono.from(channel().writeAndFlush(msg));
                                                        })
                                                        .doOnDiscard(ByteBuf.class, ByteBuf::release), this, null);
        }
        return super.send(source);
    }

    @Override
    public NettyOutbound sendObject(Object message) {
        if (!channel().isActive()) {
            ReactorNetty.safeRelease(message);
            return then(Mono.error(AbortedException.beforeSend()));
        }
        if (!(message instanceof ByteBuf)) {
            return super.sendObject(message);
        }
        ByteBuf b = (ByteBuf) message;
        return new PostHeadersNettyOutbound(FutureMono.deferFuture(() -> {
            if (markSentHeaderAndBody(b)) {
                try {
                    afterMarkSentHeaders();
                } catch (RuntimeException e) {
                    b.release();
                    throw e;
                }
                if (HttpUtil.getContentLength(outboundHttpMessage(), -1) == 0) {
                    log.debug(format(channel(), "Dropped HTTP content, " +
                            "since response has Content-Length: 0 {}"), toPrettyHexDump(b));
                    b.release();
                    return channel().writeAndFlush(newFullBodyMessage(Unpooled.EMPTY_BUFFER));
                }
                return channel().writeAndFlush(newFullBodyMessage(b));
            }
            return channel().writeAndFlush(b);
        }), this, b);
    }

    @Override
    public Mono<Void> then() {
        if (!channel().isActive()) {
            return Mono.error(AbortedException.beforeSend());
        }

//        if (true) {
//            return Mono.defer(() -> {
//                System.out.println("Http3Operations的then()");
//                return Mono.empty();
//            });
//        }

        if (hasSentHeaders()) {
            return Mono.empty();
        }

        return FutureMono.deferFuture(() -> {
            if (markSentHeaders(outboundHttpMessage())) {
                HttpMessage msg;

                if (HttpUtil.isContentLengthSet(outboundHttpMessage())) {
                    outboundHttpMessage().headers()
                            .remove(HttpHeaderNames.TRANSFER_ENCODING);
                    if (HttpUtil.getContentLength(outboundHttpMessage(), 0) == 0) {
                        markSentBody();
                        msg = newFullBodyMessage(Unpooled.EMPTY_BUFFER);
                    } else {
                        msg = outboundHttpMessage();
                    }
                } else {
                    msg = outboundHttpMessage();
                }

                try {
                    afterMarkSentHeaders();
                } catch (RuntimeException e) {
                    ReferenceCountUtil.release(msg);
                    throw e;
                }

                return channel().writeAndFlush(msg)
                        .addListener(f -> onHeadersSent());
            } else {
                return channel().newSucceededFuture();
            }
        });
    }


    @Override
    protected final void onInboundComplete() {
        super.onInboundComplete();
    }


    public final boolean hasSentHeaders() {
        return statusAndHeadersSent != READY;
    }

    @Override
    public boolean isWebsocket() {
        return false;
    }


    protected abstract void beforeMarkSentHeaders();

    protected abstract void afterMarkSentHeaders();

    protected abstract void onHeadersSent();

    protected abstract HttpMessage newFullBodyMessage(ByteBuf body);

    @Override
    @SuppressWarnings("deprecation")
    public Http3Operations<INBOUND, OUTBOUND> addHandler(String name, ChannelHandler handler) {
        super.addHandler(name, handler);

        if (channel().pipeline().context(handler) == null) {
            return this;
        }

        autoAddHttpExtractor(this, name, handler);
        return this;
    }


    /**
     * Outbound Netty HttpMessage
     *
     * @return Outbound Netty HttpMessage
     */
    protected abstract HttpMessage outboundHttpMessage();

    @Override
    protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
        super.onInboundNext(ctx, msg);
    }

    /**
     * Mark the headers sent
     *
     * @return true if marked for the first time
     */
    protected final boolean markSentHeaders(Object... objectsToRelease) {
        try {
            if (!hasSentHeaders()) {
                beforeMarkSentHeaders();
            }
        } catch (RuntimeException e) {
            for (Object o : objectsToRelease) {
                try {
                    ReferenceCountUtil.release(o);
                } catch (Throwable e2) {
                    // keep going
                }
            }
            throw e;
        }
        return HTTP_STATE.compareAndSet(this, READY, HEADERS_SENT);
    }

    /**
     * Mark the body sent
     *
     * @return true if marked for the first time
     */
    protected final boolean markSentBody() {
        return HTTP_STATE.compareAndSet(this, HEADERS_SENT, BODY_SENT);
    }

    /**
     * Mark the headers and body sent
     *
     * @return true if marked for the first time
     */
    protected final boolean markSentHeaderAndBody(Object... objectsToRelease) {
        try {
            if (!hasSentHeaders()) {
                beforeMarkSentHeaders();
            }
        } catch (RuntimeException e) {
            for (Object o : objectsToRelease) {
                try {
                    ReferenceCountUtil.release(o);
                } catch (Throwable e2) {
                    // keep going
                }
            }
            throw e;
        }
        return HTTP_STATE.compareAndSet(this, READY, BODY_SENT);
    }

    protected static final class PostHeadersNettyOutbound implements NettyOutbound, Consumer<Throwable>, Runnable {

        final Mono<Void> source;

        final Http3Operations<?, ?> parent;

        final ByteBuf msg;

        public PostHeadersNettyOutbound(Mono<Void> source, Http3Operations<?, ?> parent, @Nullable ByteBuf msg) {
            this.msg = msg;
            if (msg != null) {
                this.source = source.doOnError(this)
                        .doOnCancel(this);
            } else {
                this.source = source;
            }
            this.parent = parent;
        }

        @Override
        public void run() {
            if (msg != null && msg.refCnt() > 0) {
                msg.release();
            }
        }

        @Override
        public void accept(Throwable throwable) {
            if (msg != null && msg.refCnt() > 0) {
                msg.release();
            }
        }

        @Override
        public Mono<Void> then() {
            return source;
        }

        @Override
        public ByteBufAllocator alloc() {
            return parent.alloc();
        }

        @Override
        public NettyOutbound send(Publisher<? extends ByteBuf> dataStream, Predicate<ByteBuf> predicate) {
            return parent.send(dataStream, predicate);
        }

        @Override
        public NettyOutbound sendObject(Publisher<?> dataStream, Predicate<Object> predicate) {
            return parent.sendObject(dataStream, predicate);
        }

        @Override
        public NettyOutbound sendObject(Object message) {
            return parent.sendObject(message);
        }

        @Override
        public <S> NettyOutbound sendUsing(Callable<? extends S> sourceInput,
                                           BiFunction<? super Connection, ? super S, ?> mappedInput,
                                           Consumer<? super S> sourceCleanup) {
            return parent.sendUsing(sourceInput, mappedInput, sourceCleanup);
        }

        @Override
        public NettyOutbound withConnection(Consumer<? super Connection> withConnection) {
            return parent.withConnection(withConnection);
        }

    }

}
