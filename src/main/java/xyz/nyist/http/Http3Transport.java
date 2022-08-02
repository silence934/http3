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
package xyz.nyist.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.incubator.codec.quic.*;
import io.netty.util.AttributeKey;
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
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.Transport;
import reactor.netty.transport.TransportConfig;
import reactor.netty.transport.TransportConnector;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import xyz.nyist.quic.QuicInitialSettingsSpec;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static reactor.netty.ReactorNetty.format;

/**
 * A generic QUIC {@link Transport}
 *
 * @param <T>    {@link Http3Transport} implementation
 * @param <CONF> {@link Http3TransportConfig} implementation
 * @author Violeta Georgieva
 */
public abstract class Http3Transport<T extends Transport<T, CONF>, CONF extends Http3TransportConfig<CONF>>
        extends Transport<T, CONF> {

    static final Logger log = Loggers.getLogger(Http3Transport.class);

    /**
     * Set the delay exponent used for ACKs.
     * See
     * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_ack_delay_exponent">
     * set_ack_delay_exponent</a>.
     * Default to 3.
     *
     * @param ackDelayExponent the delay exponent used for ACKs.
     * @return a {@link Http3Transport} reference
     */
    public final T ackDelayExponent(long ackDelayExponent) {
        if (ackDelayExponent < 0) {
            throw new IllegalArgumentException("ackDelayExponent must be positive or zero");
        }
        if (ackDelayExponent == configuration().ackDelayExponent) {
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().ackDelayExponent = ackDelayExponent;
        return dup;
    }

    /**
     * Enable/disable active migration.
     * See
     * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_disable_active_migration">
     * set_disable_active_migration</a>.
     * Default to {@code true}.
     *
     * @param enable {@code true} if migration should be enabled, {@code false} otherwise
     * @return a {@link Http3Transport} reference
     */
    public final T activeMigration(boolean enable) {
        if (enable == configuration().activeMigration) {
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().activeMigration = enable;
        return dup;
    }

    /**
     * Set the congestion control algorithm to use.
     * Default to {@link QuicCongestionControlAlgorithm#CUBIC}.
     *
     * @param congestionControlAlgorithm the {@link QuicCongestionControlAlgorithm} to use.
     * @return a {@link Http3Transport} reference
     */
    public final T congestionControlAlgorithm(QuicCongestionControlAlgorithm congestionControlAlgorithm) {
        Objects.requireNonNull(congestionControlAlgorithm, "congestionControlAlgorithm");
        if (congestionControlAlgorithm == configuration().congestionControlAlgorithm) {
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().congestionControlAlgorithm = congestionControlAlgorithm;
        return dup;
    }

    /**
     * If configured this will enable <a href="https://tools.ietf.org/html/draft-ietf-quic-datagram-01">
     * Datagram support.</a>
     *
     * @param recvQueueLen the RECV queue length.
     * @param sendQueueLen the SEND queue length.
     * @return a {@link Http3Transport} reference
     */
    public final T datagram(int recvQueueLen, int sendQueueLen) {
        if (recvQueueLen < 1) {
            throw new IllegalArgumentException("recvQueueLen must be positive");
        }
        if (sendQueueLen < 1) {
            throw new IllegalArgumentException("sendQueueLen must be positive");
        }
        if (recvQueueLen == configuration().recvQueueLen && sendQueueLen == configuration().sendQueueLen) {
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().recvQueueLen = recvQueueLen;
        dup.configuration().sendQueueLen = sendQueueLen;
        return dup;
    }

    /**
     * Set or add a callback called when {@link Http3Transport} is about to start listening for incoming traffic.
     *
     * @param doOnBind a consumer observing connected events
     * @return a {@link Http3Transport} reference
     */
    public final T doOnBind(Consumer<? super CONF> doOnBind) {
        Objects.requireNonNull(doOnBind, "doOnBind");
        T dup = duplicate();
        @SuppressWarnings("unchecked")
        Consumer<CONF> current = (Consumer<CONF>) dup.configuration().doOnBind;
        dup.configuration().doOnBind = current == null ? doOnBind : current.andThen(doOnBind);
        return dup;
    }

    /**
     * Set or add a callback called after {@link Http3Transport} has been started.
     *
     * @param doOnBound a consumer observing connected events
     * @return a {@link Http3Transport} reference
     */
    public final T doOnBound(Consumer<? super Connection> doOnBound) {
        Objects.requireNonNull(doOnBound, "doOnBound");
        T dup = duplicate();
        @SuppressWarnings("unchecked")
        Consumer<Connection> current = (Consumer<Connection>) dup.configuration().doOnBound;
        dup.configuration().doOnBound = current == null ? doOnBound : current.andThen(doOnBound);
        return dup;
    }

    /**
     * Set or add a callback called after {@link Http3Transport} has been shutdown.
     *
     * @param doOnUnbound a consumer observing unbound events
     * @return a {@link Http3Transport} reference
     */
    public final T doOnUnbound(Consumer<? super Connection> doOnUnbound) {
        Objects.requireNonNull(doOnUnbound, "doOnUnbound");
        T dup = duplicate();
        @SuppressWarnings("unchecked")
        Consumer<Connection> current = (Consumer<Connection>) dup.configuration().doOnUnbound;
        dup.configuration().doOnUnbound = current == null ? doOnUnbound : current.andThen(doOnUnbound);
        return dup;
    }

    /**
     * Set if <a href="https://tools.ietf.org/html/draft-thomson-quic-bit-grease-00">greasing</a> should be enabled
     * or not.
     * Default to {@code true}.
     *
     * @param enable {@code true} if enabled, {@code false} otherwise.
     * @return a {@link Http3Transport} reference
     */
    public final T grease(boolean enable) {
        if (enable == configuration().grease) {
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().grease = enable;
        return dup;
    }

    /**
     * Attach an IO handler to react on incoming stream.
     * <p>Note: If an IO handler is not specified the incoming streams will be closed automatically.
     *
     * @param streamHandler an IO handler that can dispose underlying connection when {@link Publisher} terminates.
     * @return a {@link Http3Transport} reference
     */
    public final T handleStream(
            BiFunction<? super Http3ServerRequest, ? super Http3ServerResponse, ? extends Publisher<Void>> streamHandler) {
        Objects.requireNonNull(streamHandler, "streamHandler");
        T dup = duplicate();
        dup.configuration().streamHandler = streamHandler;
        return dup;
    }

    /**
     * Enable/disable Hystart.
     * See
     * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.enable_hystart">
     * enable_hystart</a>.
     * Default to {@code true}.
     *
     * @param enable {@code true} if Hystart should be enabled
     * @return a {@link Http3Transport} reference
     */
    public final T hystart(boolean enable) {
        if (enable == configuration().hystart) {
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().hystart = enable;
        return dup;
    }

    /**
     * Set the maximum idle timeout (resolution: ms)
     * See <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_max_idle_timeout">
     * set_max_idle_timeout</a>.
     * <p>By default {@code idleTimeout} is not specified.
     *
     * @param idleTimeout the maximum idle timeout (resolution: ms)
     * @return a {@link Http3Transport} reference
     */
    public final T idleTimeout(Duration idleTimeout) {
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.equals(configuration().idleTimeout)) {
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().idleTimeout = idleTimeout;
        return dup;
    }


    public final T initialSettings(Consumer<QuicInitialSettingsSpec.Builder> initialSettings) {
        Objects.requireNonNull(initialSettings, "initialSettings");
        QuicInitialSettingsSpec.Build builder = new QuicInitialSettingsSpec.Build();
        initialSettings.accept(builder);
        QuicInitialSettingsSpec settings = builder.build();
        if (settings.equals(configuration().initialSettings)) {
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().initialSettings = settings;
        return dup;
    }


    /**
     * Sets the local connection id length that is used.
     * Default 20, which is also the maximum that is supported.
     *
     * @param localConnectionIdLength the length of local generated connections ids
     * @return a {@link Http3Transport} reference
     */
    public final T localConnectionIdLength(int localConnectionIdLength) {
        if (localConnectionIdLength < 0 || localConnectionIdLength > 20) {
            throw new IllegalArgumentException("localConnectionIdLength must be between zero and 20");
        }
        if (localConnectionIdLength == configuration().localConnectionIdLength) {
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().localConnectionIdLength = localConnectionIdLength;
        return dup;
    }

    /**
     * Set max ACK delay (resolution: ms).
     * See
     * <a href="https://docs.rs/quiche/0.6.0/quiche/struct.Config.html#method.set_max_ack_delay">
     * set_max_ack_delay</a>.
     * Default to 25 ms.
     *
     * @param maxAckDelay the max ACK delay (resolution: ms)
     * @return a {@link Http3Transport} reference
     */
    public final T maxAckDelay(Duration maxAckDelay) {
        Objects.requireNonNull(maxAckDelay, "maxAckDelay");
        if (maxAckDelay.equals(configuration().maxAckDelay)) {
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().maxAckDelay = maxAckDelay;
        return dup;
    }

    /**
     * See <a href="https://github.com/cloudflare/quiche/blob/35e38d987c1e53ef2bd5f23b754c50162b5adac8/src/lib.rs#L662">
     * set_max_recv_udp_payload_size</a>.
     * <p>
     * The default value is 65527.
     *
     * @param maxRecvUdpPayloadSize the maximum payload size that is advertised to the remote peer.
     * @return a {@link Http3Transport} reference
     */
    public final T maxRecvUdpPayloadSize(long maxRecvUdpPayloadSize) {
        if (maxRecvUdpPayloadSize < 0) {
            throw new IllegalArgumentException("maxUdpPayloadSize must be positive or zero");
        }
        if (maxRecvUdpPayloadSize == configuration().maxRecvUdpPayloadSize) {
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().maxRecvUdpPayloadSize = maxRecvUdpPayloadSize;
        return dup;
    }

    /**
     * See <a href="https://github.com/cloudflare/quiche/blob/35e38d987c1e53ef2bd5f23b754c50162b5adac8/src/lib.rs#L669">
     * set_max_send_udp_payload_size</a>.
     * <p>
     * The default and minimum value is 1200.
     *
     * @param maxSendUdpPayloadSize the maximum payload size that is advertised to the remote peer.
     * @return a {@link Http3Transport} reference
     */
    public final T maxSendUdpPayloadSize(long maxSendUdpPayloadSize) {
        if (maxSendUdpPayloadSize < 0) {
            throw new IllegalArgumentException("maxUdpPayloadSize must be positive or zero");
        }
        if (maxSendUdpPayloadSize == configuration().maxSendUdpPayloadSize) {
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().maxSendUdpPayloadSize = maxSendUdpPayloadSize;
        return dup;
    }

    /**
     * The {@link QuicSslContext} that will be used to create {@link QuicSslEngine}s for {@link QuicChannel}s.
     *
     * @param sslContext the {@link QuicSslContext}
     * @return a {@link Http3Transport} reference
     */
    public final T secure(QuicSslContext sslContext) {
        Objects.requireNonNull(sslContext, "sslContext");
        return secure(quicChannel -> sslContext.newEngine(quicChannel.alloc()));
    }

    /**
     * The {@link Function} that will return the {@link QuicSslEngine} that should be used for the
     * {@link QuicChannel}.
     *
     * @param sslEngineProvider the {@link Function} that will return the {@link QuicSslEngine}
     *                          that should be used for the {@link QuicChannel}
     * @return a {@link Http3Transport} reference
     */
    public final T secure(Function<QuicChannel, ? extends QuicSslEngine> sslEngineProvider) {
        Objects.requireNonNull(sslEngineProvider, "sslEngineProvider");
        T dup = duplicate();
        dup.configuration().sslEngineProvider = sslEngineProvider;
        return dup;
    }

    /**
     * Injects default attribute to the future {@link QuicStreamChannel}. It
     * will be available via {@link QuicStreamChannel#attr(AttributeKey)}.
     * If the {@code value} is {@code null}, the attribute of the specified {@code key}
     * is removed.
     *
     * @param key   the attribute key
     * @param value the attribute value - null to remove a key
     * @param <A>   the attribute type
     * @return a {@link Http3Transport} reference
     * @see QuicStreamChannelBootstrap#attr(AttributeKey, Object)
     * @see QuicChannelBootstrap#streamAttr(AttributeKey, Object)
     */
    public final <A> T streamAttr(AttributeKey<A> key, @Nullable A value) {
        Objects.requireNonNull(key, "key");
        T dup = duplicate();
        dup.configuration().streamAttrs = Http3TransportConfig.updateMap(configuration().streamAttrs, key, value);
        return dup;
    }

    /**
     * Set or add the given {@link ConnectionObserver} for each stream
     *
     * @param observer the {@link ConnectionObserver} addition
     * @return a {@link Http3Transport} reference
     */
    public T streamObserve(ConnectionObserver observer) {
        Objects.requireNonNull(observer, "observer");
        T dup = duplicate();
        ConnectionObserver current = configuration().streamObserver;
        dup.configuration().streamObserver = current == null ? observer : current.then(observer);
        return dup;
    }

    /**
     * Injects default options to the future {@link QuicStreamChannel}. It
     * will be available via {@link QuicStreamChannel#config()}.
     * If the {@code value} is {@code null}, the attribute of the specified {@code key}
     * is removed.
     *
     * @param key   the option key
     * @param value the option value - null to remove a key
     * @param <A>   the option type
     * @return a {@link Http3Transport} reference
     * @see QuicStreamChannelBootstrap#option(ChannelOption, Object)
     * @see QuicChannelBootstrap#streamOption(ChannelOption, Object)
     */
    @SuppressWarnings("ReferenceEquality")
    public final <A> T streamOption(ChannelOption<A> key, @Nullable A value) {
        Objects.requireNonNull(key, "key");
        // Reference comparison is deliberate
        if (ChannelOption.AUTO_READ == key) {
            if (value instanceof Boolean && Boolean.TRUE.equals(value)) {
                log.error("ChannelOption.AUTO_READ is configured to be [false], it cannot be set to [true]");
            }
            @SuppressWarnings("unchecked")
            T dup = (T) this;
            return dup;
        }
        T dup = duplicate();
        dup.configuration().streamOptions = Http3TransportConfig.updateMap(configuration().streamOptions, key, value);
        return dup;
    }

    /**
     * Based on the actual configuration, returns a {@link Mono} that triggers:
     * <ul>
     *     <li>an initialization of the event loop group</li>
     *     <li>loads the necessary native libraries for the transport</li>
     * </ul>
     * By default, when method is not used, the {@code bind operation} absorbs the extra time needed to load resources.
     *
     * @return a {@link Mono} representing the completion of the warmup
     */
    public final Mono<Void> warmup() {
        return Mono.fromRunnable(() -> configuration().eventLoopGroup());
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
        CONF config = configuration();

        Mono<? extends DisposableServer> mono = Mono.create(sink -> {
            SocketAddress local = Objects.requireNonNull(config.bindAddress().get(), "Address不能为空");


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

}
