package xyz.nyist.http.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicChannelBootstrap;
import io.netty.resolver.AddressResolverGroup;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.TransportConfig;
import reactor.netty.transport.TransportConnector;
import reactor.util.context.Context;
import xyz.nyist.http.Http3Transport;
import xyz.nyist.quic.QuicConnection;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static reactor.netty.ConnectionObserver.State.CONFIGURED;
import static reactor.netty.ReactorNetty.format;

/**
 * @author: fucong
 * @Date: 2022/8/17 16:34
 * @Description:
 */
@Slf4j
public abstract class Http3Client extends Http3Transport<Http3Client, Http3ClientConfig> {

    public static Http3Client create() {
        return Http3ClientConnect.INSTANCE.initialSettings(spec -> spec.maxData(10000000)
                .maxStreamDataBidirectionalLocal(1000000)
                .maxStreamDataUnidirectional(3)
                .maxStreamsUnidirectional(1024)
        ).idleTimeout(Duration.ofSeconds(5));
    }

    /**
     * Connect the {@link Http3Client} and return a {@link Mono} of {@link QuicConnection}. If
     * {@link Mono} is cancelled, the underlying connection will be aborted. Once the
     * {@link QuicConnection} has been emitted and is not necessary anymore, disposing must be
     * done by the user via {@link QuicConnection#dispose()}.
     *
     * @return a {@link Mono} of {@link QuicConnection}
     */
    public Mono<? extends Connection> connect() {
        Http3ClientConfig config = configuration();


        Mono<? extends Connection> mono = Mono.create(sink -> {
            SocketAddress local = Objects.requireNonNull(config.bindAddress().get(), "Bind Address supplier returned null");
            if (local instanceof InetSocketAddress) {
                InetSocketAddress localInet = (InetSocketAddress) local;

                if (localInet.isUnresolved()) {
                    local = AddressUtils.createResolved(localInet.getHostName(), localInet.getPort());
                }
            }

            DisposableConnect disposableConnect = new DisposableConnect(config, local, sink);
            TransportConnector.bind(config, config.parentChannelInitializer(), local, false)
                    .subscribe(disposableConnect);
        });


        if (config.doOnConnect != null) {
            mono = mono.doOnSubscribe(s -> config.doOnConnect.accept(config));
        }
        return mono;
    }

    /**
     * Block the {@link Http3Client} and return a {@link QuicConnection}. Disposing must be
     * done by the user via {@link QuicConnection#dispose()}. The max connection
     * timeout is 45 seconds.
     *
     * @return a {@link QuicConnection}
     */
    public final Connection connectNow() {
        return connectNow(Duration.ofSeconds(45));
    }

    /**
     * Block the {@link Http3Client} and return a {@link QuicConnection}. Disposing must be
     * done by the user via {@link QuicConnection#dispose()}.
     *
     * @param timeout connect timeout (resolution: ns)
     * @return a {@link QuicConnection}
     */
    public final Connection connectNow(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try {
            return Objects.requireNonNull(connect().block(timeout), "aborted");
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("blocking read")) {
                throw new IllegalStateException("Http3Client couldn't be started within " + timeout.toMillis() + "ms");
            }
            throw e;
        }
    }

    /**
     * Set or add a callback called when {@link Http3Client} is about to connect to the remote endpoint.
     *
     * @param doOnConnect a consumer observing connect events
     * @return a {@link Http3Client} reference
     */
    public final Http3Client doOnConnect(Consumer<? super Http3ClientConfig> doOnConnect) {
        Objects.requireNonNull(doOnConnect, "doOnConnect");
        Http3Client dup = duplicate();
        @SuppressWarnings("unchecked")
        Consumer<Http3ClientConfig> current = (Consumer<Http3ClientConfig>) configuration().doOnConnect;
        dup.configuration().doOnConnect = current == null ? doOnConnect : current.andThen(doOnConnect);
        return dup;
    }

    /**
     * Set or add a callback called after {@link QuicConnection} has been connected.
     *
     * @param doOnConnected a consumer observing connected events
     * @return a {@link Http3Client} reference
     */
    public final Http3Client doOnConnected(Consumer<? super QuicConnection> doOnConnected) {
        Objects.requireNonNull(doOnConnected, "doOnConnected");
        Http3Client dup = duplicate();
        @SuppressWarnings("unchecked")
        Consumer<QuicConnection> current = (Consumer<QuicConnection>) configuration().doOnConnected;
        dup.configuration().doOnConnected = current == null ? doOnConnected : current.andThen(doOnConnected);
        return dup;
    }

    /**
     * Set or add a callback called after {@link QuicConnection} has been disconnected.
     *
     * @param doOnDisconnected a consumer observing disconnected events
     * @return a {@link Http3Client} reference
     */
    public final Http3Client doOnDisconnected(Consumer<? super QuicConnection> doOnDisconnected) {
        Objects.requireNonNull(doOnDisconnected, "doOnDisconnected");
        Http3Client dup = duplicate();
        @SuppressWarnings("unchecked")
        Consumer<QuicConnection> current = (Consumer<QuicConnection>) configuration().doOnDisconnected;
        dup.configuration().doOnDisconnected = current == null ? doOnDisconnected : current.andThen(doOnDisconnected);
        return dup;
    }

    /**
     * The host to which this client should connect.
     *
     * @param host the host to connect to
     * @return a {@link Http3Client} reference
     */
    public final Http3Client host(String host) {
        Objects.requireNonNull(host, "host");
        return remoteAddress(() -> AddressUtils.updateHost(configuration().remoteAddress(), host));
    }

    /**
     * The port to which this client should connect.
     *
     * @param port the port to connect to
     * @return a {@link Http3Client} reference
     */
    public final Http3Client port(int port) {
        return remoteAddress(() -> AddressUtils.updatePort(configuration().remoteAddress(), port));
    }

    /**
     * The address to which this client should connect on each subscribe.
     *
     * @param remoteAddressSupplier A supplier of the address to connect to.
     * @return a {@link Http3Client}
     */
    public final Http3Client remoteAddress(Supplier<? extends SocketAddress> remoteAddressSupplier) {
        Objects.requireNonNull(remoteAddressSupplier, "remoteAddressSupplier");
        Http3Client dup = duplicate();
        dup.configuration().remoteAddress = remoteAddressSupplier;
        return dup;
    }

    public Http3Client resolver(AddressResolverGroup<?> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        Http3Client dup = duplicate();
        dup.configuration().resolver = resolver;
        return dup;
    }


    public final Http3Client handleStream(
            BiFunction<? super Http3ClientRequest, ? super Http3ClientResponse, ? extends Publisher<Void>> streamHandler) {
        Objects.requireNonNull(streamHandler, "streamHandler");
        return observe(new Http3ClientHandle(streamHandler));
    }


    public final Http3Client sendHandler(
            Function<? super Http3ClientRequest, ? extends Publisher<Void>> sendHandler) {
        Objects.requireNonNull(sendHandler, "sendHandler");
        Http3Client dup = duplicate();
        dup.configuration().sendHandler = sendHandler;
        return dup;
    }

    public final Http3Client response(
            Function<? super Http3ClientResponse, ? extends Publisher<Void>> responseHandler) {
        Objects.requireNonNull(responseHandler, "responseHandler");
        Http3Client dup = duplicate();
        dup.configuration().responseHandler = responseHandler;
        return dup;
    }


    public final void executeNow() {
        executeNow(Duration.ofSeconds(45));
    }

    public final void executeNow(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try {
            execute().block(timeout);
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("blocking read")) {
                throw new IllegalStateException("Http3Client couldn't be started within " + timeout.toMillis() + "ms");
            }
            throw e;
        }
    }

    public Mono<Void> execute() {
        Http3ClientConfig config = configuration();


        Mono<Connection> mono = Mono.create(sink -> {
            SocketAddress local = Objects.requireNonNull(config.bindAddress().get(), "Bind Address supplier returned null");
            if (local instanceof InetSocketAddress) {
                InetSocketAddress localInet = (InetSocketAddress) local;

                if (localInet.isUnresolved()) {
                    local = AddressUtils.createResolved(localInet.getHostName(), localInet.getPort());
                }
            }

            DisposableConnect disposableConnect = new DisposableConnect(config, local, sink);
            TransportConnector.bind(config, config.parentChannelInitializer(), local, false)
                    .subscribe(disposableConnect);
        });


        if (config.doOnConnect != null) {
            mono = mono.doOnSubscribe(s -> config.doOnConnect.accept(config));
        }


        return mono.flatMap(c -> {
            Http3ClientOperations operations = (Http3ClientOperations) Connection.from(c.channel());
            return new Mono<Void>() {
                @Override
                public void subscribe(CoreSubscriber<? super Void> actual) {
                    config.responseHandler.apply(operations).subscribe(actual);
                }
            };
        });
    }


//    static final class DisposableRequest implements CoreSubscriber<Channel>, Disposable {
//
//
//        final SocketAddress bindAddress;
//
//        final Context currentContext;
//
//        final Supplier<? extends SocketAddress> remoteAddress;
//
//        final TransportConfig config;
//
//        final ChannelInitializer<Channel> quicChannelInitializer;
//
//        final MonoSink<Void> sink;
//
//        Subscription subscription;
//
//        DisposableRequest(Http3ClientConfig config, SocketAddress bindAddress, MonoSink<Void> sink) {
//            this.bindAddress = bindAddress;
//            this.currentContext = Context.of(sink.contextView());
//            ConnectionObserver connectionObserver = config.defaultConnectionObserver().then(config.connectionObserver());
//            ConnectionObserver observer = new ConnectionObserver() {
//                @Override
//                public void onStateChange(Connection connection, State newState) {
//                    connectionObserver.onStateChange(connection, newState);
//                    if (newState == CONFIGURED) {
//                        QuicConnection quicConnection = (QuicConnection) Connection.from(connection.channel());
//
//                        quicConnection.createStream((http3ClientRequest, http3ClientResponse) -> {
//                            config.responseHandler.apply(http3ClientResponse).subscribe(new CoreSubscriber<Void>() {
//                                @Override
//                                public void onSubscribe(Subscription s) {
//                                    s.request(Long.MAX_VALUE);
//                                }
//
//                                @Override
//                                public void onNext(Void unused) {
//
//                                }
//
//                                @Override
//                                public void onError(Throwable t) {
//                                    sink.error(t);
//                                }
//
//                                @Override
//                                public void onComplete() {
//                                    sink.success();
//                                }
//                            });
//                            return config.sendHandler.apply(http3ClientRequest);
//                        }).subscribe();
//                    }
//                }
//
//            };
//            this.quicChannelInitializer = config.channelInitializer(connectionObserver, null, false);
//            this.remoteAddress = config.remoteAddress;
//            this.sink = sink;
//            this.config = config;
//        }
//
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
//                            .handler(quicChannelInitializer);
////                            .streamHandler(
////                                    QuicTransportConfig.streamChannelInitializer(loggingHandler, streamObserver, true));
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

    static final class DisposableConnect implements CoreSubscriber<Channel>, Disposable {


        final SocketAddress bindAddress;

        final Context currentContext;

        final Supplier<? extends SocketAddress> remoteAddress;

        final TransportConfig config;

        final ChannelInitializer<Channel> quicChannelInitializer;

        final MonoSink<Connection> sink;

        Subscription subscription;

        DisposableConnect(Http3ClientConfig config, SocketAddress bindAddress, MonoSink<Connection> sink) {
            this.bindAddress = bindAddress;
            this.currentContext = Context.of(sink.contextView());
            ConnectionObserver observer = new QuicChannelObserver(config.defaultConnectionObserver().then(config.connectionObserver()), sink);
            this.quicChannelInitializer = config.channelInitializer(observer.then(config.observer(sink)), null, false);
            this.remoteAddress = config.remoteAddress;
            this.sink = sink;
            this.config = config;
        }


        @Override
        public Context currentContext() {
            return currentContext;
        }

        @Override
        public void dispose() {
            subscription.cancel();
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable t) {
            if (t instanceof BindException ||
                    // With epoll/kqueue transport it is
                    // io.netty.channel.unix.Errors$NativeIoException: bind(..) failed: Address already in use
                    (t instanceof IOException && t.getMessage() != null && t.getMessage().contains("bind(..)"))) {
                sink.error(ChannelBindException.fail(bindAddress, null));
            } else {
                sink.error(t);
            }
        }

        @Override
        public void onNext(Channel channel) {
            if (log.isDebugEnabled()) {
                log.debug(format(channel, "Bound new channel"));
            }

            final SocketAddress remote = Objects.requireNonNull(remoteAddress.get(), "Remote Address supplier returned null");

            QuicChannelBootstrap bootstrap =
                    QuicChannel.newBootstrap(channel)
                            .remoteAddress(remote)
                            .handler(quicChannelInitializer);
//                            .streamHandler(
//                                    QuicTransportConfig.streamChannelInitializer(loggingHandler, streamObserver, true));

            bootstrap.connect()
                    .addListener(f -> {
                        // We don't need to handle success case, we've already configured QuicChannelObserver
                        if (!f.isSuccess()) {
                            if (f.cause() != null) {
                                sink.error(f.cause());
                            } else {
                                sink.error(new IOException("Cannot connect to [" + remote + "]"));
                            }
                        }
                    });
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (Operators.validate(subscription, s)) {
                this.subscription = s;
                sink.onCancel(this);
                s.request(Long.MAX_VALUE);
            }
        }

    }

    static final class QuicChannelObserver implements ConnectionObserver {

        final ConnectionObserver connectionObserver;

        final MonoSink<Connection> sink;

        QuicChannelObserver(ConnectionObserver connectionObserver, MonoSink<Connection> sink) {
            this.connectionObserver = connectionObserver;
            this.sink = sink;
        }

        @Override
        public void onStateChange(Connection connection, State newState) {
            if (newState == CONFIGURED) {
                //sink.success(Connection.from(connection.channel()));
            }

            connectionObserver.onStateChange(connection, newState);
        }

        @Override
        public void onUncaughtException(Connection connection, Throwable error) {
            sink.error(error);
            connectionObserver.onUncaughtException(connection, error);
        }

    }

    static final class Http3ClientHandle implements ConnectionObserver {

        BiFunction<? super Http3ClientRequest, ? super Http3ClientResponse, ? extends Publisher<Void>> streamHandler;

        public Http3ClientHandle(BiFunction<? super Http3ClientRequest, ? super Http3ClientResponse, ? extends Publisher<Void>> streamHandler) {
            this.streamHandler = streamHandler;
        }

        @Override
        public void onStateChange(Connection connection, State newState) {
            if (newState == CONFIGURED) {
                QuicConnection quicConnection = (QuicConnection) Connection.from(connection.channel());
                quicConnection.createStream(streamHandler).block();
            }
        }

    }

}
