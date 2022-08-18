/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.nyist.http.server;

import io.netty.channel.Channel;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.incubator.codec.quic.QuicConnectionIdGenerator;
import io.netty.incubator.codec.quic.QuicTokenHandler;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServerState;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.TransportConfig;
import reactor.netty.transport.TransportConnector;
import reactor.util.Logger;
import reactor.util.Loggers;
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

import static reactor.netty.ReactorNetty.format;

/**
 * A QuicServer allows building in a safe immutable way a QUIC server that is materialized
 * and bound when {@link #bind()} is ultimately called.
 * <p>
 * <p> Example:
 * <pre>
 * {@code
 *     QuicServer.create()
 *               .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
 *               .port(7777)
 *               .wiretap(true)
 *               .secure(serverCtx)
 *               .idleTimeout(Duration.ofSeconds(5))
 *               .initialSettings(spec ->
 *                   spec.maxData(10000000)
 *                       .maxStreamDataBidirectionalLocal(1000000)
 *                       .maxStreamDataBidirectionalRemote(1000000)
 *                       .maxStreamsBidirectional(100)
 *                       .maxStreamsUnidirectional(100))
 *               .bindNow();
 * }
 * </pre>
 *
 * @author Violeta Georgieva
 */
public abstract class Http3Server extends Http3Transport<Http3Server, Http3ServerConfig> {

    static final Logger log = Loggers.getLogger(Http3Server.class);

    /**
     * Prepare a {@link Http3Server}
     *
     * @return a {@link Http3Server}
     */
    public static Http3Server create() {
        return Http3ServerBind.INSTANCE;
    }


    /**
     * Set the {@link QuicConnectionIdGenerator} to use.
     * Default to {@link QuicConnectionIdGenerator#randomGenerator()}.
     *
     * @param connectionIdAddressGenerator the {@link QuicConnectionIdGenerator} to use.
     * @return a {@link Http3Server} reference
     */
    public final Http3Server connectionIdAddressGenerator(QuicConnectionIdGenerator connectionIdAddressGenerator) {
        Objects.requireNonNull(connectionIdAddressGenerator, "connectionIdAddressGenerator");
        Http3Server dup = duplicate();
        dup.configuration().connectionIdAddressGenerator = connectionIdAddressGenerator;
        return dup;
    }

    /**
     * Set or add a callback called on new remote {@link QuicConnection}.
     *
     * @param doOnConnection a consumer observing remote QUIC connections
     * @return a {@link Http3Server} reference
     */
    public final Http3Server doOnConnection(Consumer<? super QuicConnection> doOnConnection) {
        Objects.requireNonNull(doOnConnection, "doOnConnected");
        Http3Server dup = duplicate();
        @SuppressWarnings("unchecked")
        Consumer<QuicConnection> current = (Consumer<QuicConnection>) configuration().doOnConnection;
        dup.configuration().doOnConnection = current == null ? doOnConnection : current.andThen(doOnConnection);
        return dup;
    }

    /**
     * The host to which this server should bind.
     *
     * @param host the host to bind to.
     * @return a {@link Http3Server} reference
     */
    public final Http3Server host(String host) {
        return bindAddress(() -> AddressUtils.updateHost(configuration().bindAddress(), host));
    }

    /**
     * The port to which this server should bind.
     *
     * @param port The port to bind to.
     * @return a {@link Http3Server} reference
     */
    public final Http3Server port(int port) {
        return bindAddress(() -> AddressUtils.updatePort(configuration().bindAddress(), port));
    }

    /**
     * Configure the {@link QuicTokenHandler} that is used to generate and validate tokens.
     *
     * @param tokenHandler the {@link QuicTokenHandler} to use
     * @return a {@link Http3Server}
     */
    public final Http3Server tokenHandler(QuicTokenHandler tokenHandler) {
        Objects.requireNonNull(tokenHandler, "tokenHandler");
        Http3Server dup = duplicate();
        dup.configuration().tokenHandler = tokenHandler;
        return dup;
    }


    /**
     * Attach an IO handler to react on incoming stream.
     * <p>Note: If an IO handler is not specified the incoming streams will be closed automatically.
     *
     * @param streamHandler an IO handler that can dispose underlying connection when {@link Publisher} terminates.
     * @return a {@link Http3Transport} reference
     */
    public final Http3Server handleStream(
            BiFunction<? super Http3ServerRequest, ? super Http3ServerResponse, ? extends Publisher<Void>> streamHandler) {
        Objects.requireNonNull(streamHandler, "streamHandler");
        return streamObserve(new Http3ServerHandle(streamHandler));
    }


    public final DisposableServer bindNow() {
        return bindNow(Duration.ofSeconds(45));
    }

    public final DisposableServer bindNow(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try {
            return Objects.requireNonNull(bind().block(timeout), "aborted");
        } catch (IllegalStateException e) {
            if (e.getMessage()
                    .contains("blocking read")) {
                throw new IllegalStateException(getClass().getSimpleName() + " couldn't be started within " + timeout.toMillis() + "ms");
            }
            throw e;
        }
    }

    public Mono<? extends DisposableServer> bind() {
        Http3ServerConfig config = configuration();

        Mono<? extends DisposableServer> mono = Mono.create(sink -> {
            SocketAddress local = Objects.requireNonNull(config.bindAddress().get(), "Address cannot be empty");


            if (local instanceof InetSocketAddress) {
                InetSocketAddress localInet = (InetSocketAddress) local;

                if (localInet.isUnresolved()) {
                    local = AddressUtils.createResolved(localInet.getHostName(), localInet.getPort());
                }
            }

            boolean isDomainSocket = false;
            DisposableBind disposableServer;
            if (local instanceof DomainSocketAddress) {
                isDomainSocket = true;
                disposableServer = new UdsDisposableBind(sink, config, local);
            } else {
                disposableServer = new InetDisposableBind(sink, config, local);
            }


            TransportConnector.bind(config, config.parentChannelInitializer(), local, isDomainSocket)
                    .subscribe(disposableServer);
        });

        if (config.doOnBind() != null) {
            mono = mono.doOnSubscribe(s -> config.doOnBind().accept(config));
        }

        return mono;
    }


    static class DisposableBind implements CoreSubscriber<Channel>, DisposableServer, Connection {

        final SocketAddress bindAddress;

        final Context currentContext;

        final TransportConfig config;

        final MonoSink<DisposableServer> sink;

        Subscription subscription;

        Channel channel;

        DisposableBind(MonoSink<DisposableServer> sink, TransportConfig config, SocketAddress bindAddress) {
            this.bindAddress = bindAddress;
            this.currentContext = Context.of(sink.contextView());
            this.config = config;
            this.sink = sink;
        }


        @Override
        public Channel channel() {
            return channel;
        }

        @Override
        public Context currentContext() {
            return currentContext;
        }

        @Override
        public void dispose() {
            if (channel != null) {
                if (channel.isActive()) {
                    //"FutureReturnValueIgnored" this is deliberate
                    channel.close();

                    LoopResources loopResources = config.loopResources();
                    if (loopResources instanceof ConnectionProvider) {
                        ((ConnectionProvider) loopResources).disposeWhen(bindAddress);
                    }
                }
            } else {
                subscription.cancel();
            }
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
            this.channel = channel;
            if (log.isDebugEnabled()) {
                log.debug(format(channel, "udp channel ready to complete"));
            }
            sink.success(this);
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


    static final class InetDisposableBind extends DisposableBind {

        InetDisposableBind(MonoSink<DisposableServer> sink, TransportConfig config, SocketAddress bindAddress) {
            super(sink, config, bindAddress);
        }

        @Override
        public InetSocketAddress address() {
            return (InetSocketAddress) channel().localAddress();
        }

        @Override
        public String host() {
            return address().getHostString();
        }

        @Override
        public int port() {
            return address().getPort();
        }

    }

    static final class UdsDisposableBind extends DisposableBind {

        UdsDisposableBind(MonoSink<DisposableServer> sink, TransportConfig config, SocketAddress bindAddress) {
            super(sink, config, bindAddress);
        }

        @Override
        public DomainSocketAddress address() {
            return (DomainSocketAddress) channel().localAddress();
        }

        @Override
        public String path() {
            return address().path();
        }

    }

    static final class Http3ServerHandle implements ConnectionObserver {

        final BiFunction<? super Http3ServerRequest, ? super Http3ServerResponse, ? extends Publisher<Void>> handler;

        Http3ServerHandle(BiFunction<? super Http3ServerRequest, ? super Http3ServerResponse, ? extends Publisher<Void>> handler) {
            this.handler = handler;
        }

        @Override
        @SuppressWarnings("FutureReturnValueIgnored")
        public void onStateChange(Connection connection, State newState) {
            if (newState == HttpServerState.REQUEST_RECEIVED) {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug(format(connection.channel(), "Handler is being applied: {}"), handler);
                    }
                    Http3ServerOperations ops = (Http3ServerOperations) connection;
                    Publisher<Void> publisher = handler.apply(ops, ops);
                    Mono<Void> mono = Mono.deferContextual(ctx -> {
                        ops.currentContext = Context.of(ctx);
                        return Mono.fromDirect(publisher);
                    });
                    if (ops.mapHandle != null) {
                        mono = ops.mapHandle.apply(mono, connection);
                    }
                    mono.subscribe(ops.disposeSubscriber());
                } catch (Throwable t) {
                    log.error(format(connection.channel(), ""), t);
                    //"FutureReturnValueIgnored" this is deliberate
                    connection.channel()
                            .close();
                }
            }
        }

    }

}
