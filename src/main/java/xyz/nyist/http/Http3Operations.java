package xyz.nyist.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.*;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import xyz.nyist.core.DefaultHttp3DataFrame;
import xyz.nyist.core.Http3Headers;
import xyz.nyist.core.Http3HeadersFrame;
import xyz.nyist.core.Http3Util;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static reactor.netty.ReactorNetty.format;
import static reactor.netty.ReactorNetty.toPrettyHexDump;

/**
 * @author: fucong
 * @Date: 2022/7/27 18:34
 * @Description:
 */
public abstract class Http3Operations<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
        extends ChannelOperations<INBOUND, OUTBOUND> implements Http3StreamInfo {

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


    public static CharSequence extractPath(Http3Headers headers) {
        final CharSequence method = checkNotNull(headers.method(),
                                                 "method header cannot be null in conversion to HTTP/1.x");
        if (HttpMethod.CONNECT.asciiName().contentEqualsIgnoreCase(method)) {
            // See https://tools.ietf.org/html/rfc7231#section-4.3.6
            return checkNotNull(headers.authority(),
                                "authority header cannot be null in the conversion to HTTP/1.x");
        } else {
            return checkNotNull(headers.path(),
                                "path header cannot be null in conversion to HTTP/1.x");
        }
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
                                                                if (Http3Util.getContentLength(outboundHttpMessage(), -1) == 0) {
                                                                    log.debug(format(channel(), "Dropped HTTP content, " +
                                                                            "since response has Content-Length: 0 {}"), toPrettyHexDump(msg));
                                                                    msg.release();
                                                                    return FutureMono.from(writeMessage(Unpooled.EMPTY_BUFFER));
                                                                }
                                                                return FutureMono.from(writeMessage(msg));
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
                if (Http3Util.getContentLength(outboundHttpMessage(), -1) == 0) {
                    log.debug(format(channel(), "Dropped HTTP content, " +
                            "since response has Content-Length: 0 {}"), toPrettyHexDump(b));
                    b.release();
                    return writeMessage(Unpooled.EMPTY_BUFFER);
                }
                return writeMessage(b);
            }
            return channel().writeAndFlush(b);
        }), this, b);
    }


    @Override
    public final NettyOutbound sendFile(Path file, long position, long count) {
        Objects.requireNonNull(file);

        if (hasSentHeaders()) {
            return super.sendFile(file, position, count);
        }

        if (!Http3Util.isTransferEncodingChunked(outboundHttpMessage()) && !Http3Util.isContentLengthSet(
                outboundHttpMessage()) && count < Integer.MAX_VALUE) {
            outboundHttpMessage().headers()
                    .setInt(HttpHeaderNames.CONTENT_LENGTH, (int) count);
        } else if (!Http3Util.isContentLengthSet(outboundHttpMessage())) {
            Http3Headers headers = outboundHttpMessage().headers();
            headers.remove(HttpHeaderNames.CONTENT_LENGTH);
            headers.remove(HttpHeaderNames.TRANSFER_ENCODING);
            Http3Util.setTransferEncodingChunked(outboundHttpMessage(), true);
        }

        //todo zero-copy
        return sendUsing(() -> FileChannel.open(file, StandardOpenOption.READ),
                         (c, fc) -> {


                             ByteBuffer var6 = ByteBuffer.allocate((int) count);
                             try {
                                 fc.read(var6, position);
                             } catch (IOException e) {
                                 throw new RuntimeException(e);
                             }
                             var6.flip();

                             ByteBuf byteBuf = Unpooled.wrappedBuffer(var6);

                             return new DefaultHttp3DataFrame(byteBuf);
                         },
                         fc -> {
                             try {
                                 fc.close();
                             } catch (Throwable e) {
                                 if (log.isTraceEnabled()) {
                                     log.trace("", e);
                                 }
                             }
                         });
    }


    @Override
    public Mono<Void> then() {
        if (!channel().isActive()) {
            return Mono.error(AbortedException.beforeSend());
        }

        if (hasSentHeaders()) {
            return Mono.empty();
        }

        return FutureMono.deferFuture(() -> {
            if (markSentHeaders(outboundHttpMessage())) {
                Http3HeadersFrame msg;

                if (Http3Util.isContentLengthSet(outboundHttpMessage())) {
                    outboundHttpMessage().headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
                    if (Http3Util.getContentLength(outboundHttpMessage()) == 0) {
                        //todo heads设置了contentLength=0
                        markSentBody();
                        //msg = newFullBodyMessage(Unpooled.EMPTY_BUFFER);
                    }
                    msg = outboundHttpMessage();
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

    protected ChannelFuture writeMessage(ByteBuf body) {
        // For HEAD requests:
        // - if there is Transfer-Encoding and Content-Length, Transfer-Encoding will be removed
        // - if there is only Transfer-Encoding, it will be kept and not replaced by
        // Content-Length: body.readableBytes()
        // For HEAD requests, the I/O handler may decide to provide only the headers and complete
        // the response. In that case body will be EMPTY_BUFFER and if we set Content-Length: 0,
        // this will not be correct
        // https://github.com/reactor/reactor-netty/issues/1333
        if (!HttpMethod.HEAD.equals(method())) {
            outboundHttpMessage().remove(HttpHeaderNames.TRANSFER_ENCODING);
//            if (!HttpResponseStatus.NOT_MODIFIED.codeAsText().equals(status())) {
//                if (!Http3Util.isContentLengthSet(nettyRequest)) {
//                    nettyRequest.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
//                }
//            }
        } else if (Http3Util.isContentLengthSet(outboundHttpMessage())) {
            //head request and  there is  Content-Length
            outboundHttpMessage().remove(HttpHeaderNames.TRANSFER_ENCODING);
        }
        ChannelFuture writeHeads = channel().write(outboundHttpMessage());

        if (body == null || body == EMPTY_BUFFER) {
            return writeHeads;
        }
        ChannelFuture writeBody = channel().writeAndFlush(new DefaultHttp3DataFrame(body));
        return CombinationChannelFuture.create(writeHeads, writeBody);
    }

    @SuppressWarnings("all")
    protected ChannelFuture shutdownOutput() {
        return ((QuicStreamChannel) channel()).shutdownOutput();
    }

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
    protected abstract Http3HeadersFrame outboundHttpMessage();


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
