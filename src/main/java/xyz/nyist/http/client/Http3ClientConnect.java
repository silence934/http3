package xyz.nyist.http.client;

import io.netty.channel.ChannelOption;
import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import xyz.nyist.quic.QuicConnection;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Objects;

import static reactor.netty.ConnectionObserver.State.CONFIGURED;

/**
 * @author: fucong
 * @Date: 2022/8/17 16:41
 * @Description:
 */
@Slf4j
public class Http3ClientConnect extends Http3Client {

    static final Http3ClientConnect INSTANCE = new Http3ClientConnect();

    /**
     * The default port for reactor-netty QUIC clients. Defaults to 12012 but can be tuned via
     * the {@code QUIC_PORT} <b>environment variable</b>.
     */
    static final int DEFAULT_PORT =
            System.getenv("QUIC_PORT") != null ? Integer.parseInt(System.getenv("QUIC_PORT")) : 12012;

    final Http3ClientConfig config;

    Http3ClientConnect() {
        this.config = new Http3ClientConfig(
                new Http3ConnectionProvider(),
                Collections.emptyMap(),
                Collections.singletonMap(ChannelOption.AUTO_READ, false),
                () -> new InetSocketAddress(NetUtil.LOCALHOST, 0),
                () -> new InetSocketAddress(NetUtil.LOCALHOST, DEFAULT_PORT));
    }

    Http3ClientConnect(Http3ClientConfig config) {
        this.config = config;
    }

    static void validate(Http3ClientConfig config) {
        Objects.requireNonNull(config.bindAddress(), "bindAddress");
        Objects.requireNonNull(config.remoteAddress(), "remoteAddress");
        Objects.requireNonNull(config.sslEngineProvider(), "sslEngineProvider");
    }

    @Override
    public Http3ClientConfig configuration() {
        return config;
    }

    @Override
    public Mono<? extends Connection> connect() {
        Http3ClientConfig config = configuration();
        validate(config);
        return super.connect();
    }

    @Override
    protected Http3Client duplicate() {
        return new Http3ClientConnect(new Http3ClientConfig(config));
    }

//    static final class DisposableConnect implements CoreSubscriber<Channel>, Disposable {
//
//        final Map<AttributeKey<?>, ?> attributes;
//
//        final SocketAddress bindAddress;
//
//        final Context currentContext;
//
//        final ChannelHandler loggingHandler;
//
//        final Map<ChannelOption<?>, ?> options;
//
//        final ChannelInitializer<Channel> quicChannelInitializer;
//
//        final Supplier<? extends SocketAddress> remoteAddress;
//
//        final MonoSink<Connection> sink;
//
//        final Map<AttributeKey<?>, ?> streamAttrs;
//
//        final ConnectionObserver streamObserver;
//
//        final Map<ChannelOption<?>, ?> streamOptions;
//
//        Subscription subscription;
//
//        DisposableConnect(Http3ClientConfig config, SocketAddress bindAddress, MonoSink<Connection> sink) {
//            this.attributes = config.attributes();
//            this.bindAddress = bindAddress;
//            this.currentContext = Context.of(sink.contextView());
//            this.loggingHandler = config.loggingHandler();
//            this.options = config.options();
//            ConnectionObserver observer = new QuicChannelObserver(
//                    config.defaultConnectionObserver().then(config.connectionObserver()),
//                    sink);
//            this.quicChannelInitializer = config.channelInitializer(observer, null, false);
//            this.remoteAddress = config.remoteAddress;
//            this.sink = sink;
//            this.streamAttrs = config.streamAttrs();
//            this.streamObserver =
//                    config.streamObserver.then(new Http3TransportConfig.QuicStreamChannelObserver(config.streamHandler));
//            this.streamOptions = config.streamOptions;
//        }
//
//        @SuppressWarnings("unchecked")
//        static void attributes(QuicChannelBootstrap bootstrap, Map<AttributeKey<?>, ?> attrs) {
//            for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
//                bootstrap.attr((AttributeKey<Object>) e.getKey(), e.getValue());
//            }
//        }
//
//        @SuppressWarnings("unchecked")
//        static void channelOptions(QuicChannelBootstrap bootstrap, Map<ChannelOption<?>, ?> options) {
//            for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
//                bootstrap.option((ChannelOption<Object>) e.getKey(), e.getValue());
//            }
//        }
//
//        @SuppressWarnings("unchecked")
//        static void streamAttributes(QuicChannelBootstrap bootstrap, Map<AttributeKey<?>, ?> attrs) {
//            for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
//                bootstrap.streamAttr((AttributeKey<Object>) e.getKey(), e.getValue());
//            }
//        }
//
//        @SuppressWarnings("unchecked")
//        static void streamChannelOptions(QuicChannelBootstrap bootstrap, Map<ChannelOption<?>, ?> options) {
//            for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
//                bootstrap.streamOption((ChannelOption<Object>) e.getKey(), e.getValue());
//            }
//        }
//
//        @Override
//        public Context currentContext() {
//            return currentContext;
//        }
//
//        @Override
//        public void dispose() {
//            subscription.cancel();
//        }
//
//        @Override
//        public void onComplete() {
//        }
//
//        @Override
//        public void onError(Throwable t) {
//            if (t instanceof BindException ||
//                    // With epoll/kqueue transport it is
//                    // io.netty.channel.unix.Errors$NativeIoException: bind(..) failed: Address already in use
//                    (t instanceof IOException && t.getMessage() != null && t.getMessage().contains("bind(..)"))) {
//                sink.error(ChannelBindException.fail(bindAddress, null));
//            } else {
//                sink.error(t);
//            }
//        }
//
//        @Override
//        public void onNext(Channel channel) {
//            if (log.isDebugEnabled()) {
//                log.debug(format(channel, "Bound new channel"));
//            }
//
//            final SocketAddress remote = Objects.requireNonNull(remoteAddress.get(), "Remote Address supplier returned null");
//
//            QuicChannelBootstrap bootstrap =
//                    QuicChannel.newBootstrap(channel)
//                            .remoteAddress(remote)
//                            .handler(quicChannelInitializer)
//                            .streamHandler(Http3TransportConfig.streamChannelInitializer(loggingHandler, streamObserver, true));
//
//            attributes(bootstrap, attributes);
//            channelOptions(bootstrap, options);
//            streamAttributes(bootstrap, streamAttrs);
//            streamChannelOptions(bootstrap, streamOptions);
//
//            bootstrap.connect()
//                    .addListener(f -> {
//                        // We don't need to handle success case, we've already configured QuicChannelObserver
//                        if (!f.isSuccess()) {
//                            if (f.cause() != null) {
//                                sink.error(f.cause());
//                            } else {
//                                sink.error(new IOException("Cannot connect to [" + remote + "]"));
//                            }
//                        }
//                    });
//        }
//
//        @Override
//        public void onSubscribe(Subscription s) {
//            if (Operators.validate(subscription, s)) {
//                this.subscription = s;
//                sink.onCancel(this);
//                s.request(Long.MAX_VALUE);
//            }
//        }
//
//    }

    static final class QuicChannelObserver implements ConnectionObserver {

        final ConnectionObserver childObs;

        final MonoSink<QuicConnection> sink;

        QuicChannelObserver(ConnectionObserver childObs, MonoSink<QuicConnection> sink) {
            this.childObs = childObs;
            this.sink = sink;
        }

        @Override
        public void onStateChange(Connection connection, State newState) {
            if (newState == CONFIGURED) {
                sink.success((QuicConnection) Connection.from(connection.channel()));
            }

            childObs.onStateChange(connection, newState);
        }

        @Override
        public void onUncaughtException(Connection connection, Throwable error) {
            sink.error(error);
            childObs.onUncaughtException(connection, error);
        }

    }

}
