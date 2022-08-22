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

import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicCongestionControlAlgorithm;
import io.netty.incubator.codec.quic.QuicSslEngine;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.TransportConfig;
import reactor.util.annotation.Nullable;
import xyz.nyist.core.Http3RequestStreamInitializer;
import xyz.nyist.http.client.Http3ClientOperations;
import xyz.nyist.http.server.Http3ServerRequest;
import xyz.nyist.http.server.Http3ServerResponse;
import xyz.nyist.http.temp.ConnectionInfo;
import xyz.nyist.quic.QuicInitialSettingsSpec;
import xyz.nyist.quic.QuicResources;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static reactor.netty.ConnectionObserver.State.CONFIGURED;
import static reactor.netty.ConnectionObserver.State.CONNECTED;
import static reactor.netty.ReactorNetty.format;

/**
 * Encapsulate all necessary configuration for QUIC transport. The public API is read-only.
 *
 * @param <CONF> Configuration implementation
 * @author Violeta Georgieva
 */
@Slf4j
public abstract class Http3TransportConfig<CONF extends TransportConfig> extends TransportConfig {


    static final long DEFAULT_ACK_DELAY_EXPONENT = 3;

    static final boolean DEFAULT_ACTIVE_MIGRATION = true;

    static final boolean DEFAULT_GREASE = true;

    static final boolean DEFAULT_HYSTART = true;

    static final int DEFAULT_LOCAL_CONNECTION_ID_LENGTH = 20;

    static final Duration DEFAULT_MAX_ACK_DELAY = Duration.ofMillis(25);

    static final long DEFAULT_MAX_RECV_UDP_PAYLOAD_SIZE = 65527;

    static final long DEFAULT_MAX_SEND_UDP_PAYLOAD_SIZE = 1200;

    protected long ackDelayExponent;

    protected boolean activeMigration;

    protected QuicCongestionControlAlgorithm congestionControlAlgorithm;

    protected Consumer<? super CONF> doOnBind;

    protected Consumer<? super Connection> doOnBound;

    protected Consumer<? super Connection> doOnUnbound;

    protected boolean grease;

    protected boolean hystart;

    protected Duration idleTimeout;

    protected QuicInitialSettingsSpec initialSettings;

    protected int localConnectionIdLength;

    protected Duration maxAckDelay;

    protected long maxRecvUdpPayloadSize;

    protected long maxSendUdpPayloadSize;

    protected int recvQueueLen;

    protected int sendQueueLen;

    protected Function<QuicChannel, ? extends QuicSslEngine> sslEngineProvider;

    protected Map<AttributeKey<?>, ?> streamAttrs;

    protected BiFunction<? super Http3ServerRequest, ? super Http3ServerResponse, ? extends Publisher<Void>> streamHandler;

    protected ConnectionObserver streamObserver;

    protected Map<ChannelOption<?>, ?> streamOptions;

    protected Http3TransportConfig(
            Map<ChannelOption<?>, ?> options,
            Map<ChannelOption<?>, ?> streamOptions,
            Supplier<? extends SocketAddress> bindAddress) {
        super(options, bindAddress);
        this.ackDelayExponent = DEFAULT_ACK_DELAY_EXPONENT;
        this.activeMigration = DEFAULT_ACTIVE_MIGRATION;
        this.congestionControlAlgorithm = QuicCongestionControlAlgorithm.CUBIC;
        this.grease = DEFAULT_GREASE;
        this.hystart = DEFAULT_HYSTART;
        this.localConnectionIdLength = DEFAULT_LOCAL_CONNECTION_ID_LENGTH;
        this.maxAckDelay = DEFAULT_MAX_ACK_DELAY;
        this.maxRecvUdpPayloadSize = DEFAULT_MAX_RECV_UDP_PAYLOAD_SIZE;
        this.maxSendUdpPayloadSize = DEFAULT_MAX_SEND_UDP_PAYLOAD_SIZE;
        this.streamAttrs = Collections.emptyMap();
        this.streamObserver = ConnectionObserver.emptyListener();
        this.streamOptions = Objects.requireNonNull(streamOptions, "streamOptions");
    }

    protected Http3TransportConfig(Http3TransportConfig<CONF> parent) {
        super(parent);
        this.ackDelayExponent = parent.ackDelayExponent;
        this.activeMigration = parent.activeMigration;
        this.congestionControlAlgorithm = parent.congestionControlAlgorithm;
        this.doOnBind = parent.doOnBind;
        this.doOnBound = parent.doOnBound;
        this.doOnUnbound = parent.doOnUnbound;
        this.grease = parent.grease;
        this.hystart = parent.hystart;
        this.idleTimeout = parent.idleTimeout;
        this.localConnectionIdLength = parent.localConnectionIdLength;
        this.maxAckDelay = parent.maxAckDelay;
        this.maxRecvUdpPayloadSize = parent.maxRecvUdpPayloadSize;
        this.maxSendUdpPayloadSize = parent.maxSendUdpPayloadSize;
        this.recvQueueLen = parent.recvQueueLen;
        this.sendQueueLen = parent.sendQueueLen;
        this.sslEngineProvider = parent.sslEngineProvider;
        this.streamAttrs = parent.streamAttrs;
        this.streamHandler = parent.streamHandler;
        this.streamObserver = parent.streamObserver;
        this.streamOptions = parent.streamOptions;
        this.initialSettings = parent.initialSettings;
    }

    protected static <K, V> Map<K, V> updateMap(Map<K, V> parentMap, Object key, @Nullable Object value) {
        return TransportConfig.updateMap(parentMap, key, value);
    }

    public static ChannelInitializer<QuicStreamChannel> streamChannelInitializer(
            @Nullable ChannelHandler loggingHandler, ConnectionObserver streamListener, boolean inbound) {
        return new QuicStreamChannelInitializer(loggingHandler, streamListener, inbound);
    }

    /**
     * Return the configured delay exponent used for ACKs or the default.
     *
     * @return the configured delay exponent used for ACKs or the default
     */
    public final long ackDelayExponent() {
        return ackDelayExponent;
    }

    /**
     * Return the configured {@link QuicCongestionControlAlgorithm} or the default.
     *
     * @return the configured {@link QuicCongestionControlAlgorithm} or the default
     */
    public final QuicCongestionControlAlgorithm congestionControlAlgorithm() {
        return congestionControlAlgorithm;
    }

    /**
     * Return the configured callback or null.
     *
     * @return the configured callback or null
     */
    @Nullable
    public final Consumer<? super CONF> doOnBind() {
        return doOnBind;
    }

    /**
     * Return the configured callback or null.
     *
     * @return the configured callback or null
     */
    @Nullable
    public final Consumer<? super Connection> doOnBound() {
        return doOnBound;
    }

    /**
     * Return the configured callback or null.
     *
     * @return the configured callback or null
     */
    @Nullable
    public final Consumer<? super Connection> doOnUnbound() {
        return doOnUnbound;
    }

    /**
     * Return the configured idle timeout or null.
     *
     * @return the configured idle timeout or null
     */
    @Nullable
    public final Duration idleTimeout() {
        return idleTimeout;
    }


    /**
     * Return true if active migration is enabled.
     *
     * @return true if active migration is enabled
     */
    public final boolean isActiveMigration() {
        return activeMigration;
    }

    /**
     * Return true if greasing is enabled.
     *
     * @return true if greasing is enabled
     */
    public final boolean isGrease() {
        return grease;
    }

    /**
     * Return true if Hystart is enabled.
     *
     * @return true if Hystart is enabled
     */
    public final boolean isHystart() {
        return hystart;
    }

    /**
     * Return the configured local connection id length that is used or the default.
     *
     * @return the configured local connection id length that is used or the default
     */
    public final int localConnectionIdLength() {
        return localConnectionIdLength;
    }

    /**
     * Return the configured the max ACK delay or the default.
     *
     * @return the configured the max ACK delay or the default
     */
    public final Duration maxAckDelay() {
        return maxAckDelay;
    }

    /**
     * Return the configured the maximum payload size or the default.
     *
     * @return the configured the maximum payload size or the default
     */
    public final long maxRecvUdpPayloadSize() {
        return maxRecvUdpPayloadSize;
    }

    /**
     * Return the configured the maximum payload size or the default.
     *
     * @return the configured the maximum payload size or the default
     */
    public final long maxSendUdpPayloadSize() {
        return maxSendUdpPayloadSize;
    }

    /**
     * Return the configured RECV queue length.
     *
     * @return the configured RECV queue length
     */
    public final int recvQueueLen() {
        return recvQueueLen;
    }

    /**
     * Return the configured SEND queue length.
     *
     * @return the configured SEND queue length
     */
    public final int sendQueueLen() {
        return sendQueueLen;
    }

    /**
     * Return the read-only default stream attributes.
     *
     * @return the read-only default stream attributes
     */
    public final Map<AttributeKey<?>, ?> streamAttributes() {
        if (streamAttrs == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(streamAttrs);
    }

    /**
     * Return the configured {@link ConnectionObserver} if any or
     * {@link ConnectionObserver#emptyListener()} for each stream
     *
     * @return the configured {@link ConnectionObserver} if any or
     * {@link ConnectionObserver#emptyListener()} for each stream
     */
    public final ConnectionObserver streamObserver() {
        return streamObserver;
    }

    /**
     * Return the read-only {@link ChannelOption} map for each stream.
     *
     * @return the read-only {@link ChannelOption} map for each stream
     */
    public final Map<ChannelOption<?>, ?> streamOptions() {
        if (streamOptions == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(streamOptions);
    }

    @Override
    protected final Class<? extends Channel> channelType(boolean isDomainSocket) {
        if (isDomainSocket) {
            throw new UnsupportedOperationException();
        }
        return DatagramChannel.class;
    }

    @Override
    protected ConnectionObserver defaultConnectionObserver() {
        if (channelGroup() == null && doOnBound() == null && doOnUnbound() == null) {
            return ConnectionObserver.emptyListener();
        }
        return new QuicTransportDoOn(channelGroup(), doOnBound(), doOnUnbound());
    }

    @Override
    protected final LoopResources defaultLoopResources() {
        return QuicResources.get();
    }


    @Override
    protected ChannelPipelineConfigurer defaultOnChannelInit() {
        return new QuicChannelInitializer(this);
    }

    @Override
    protected final EventLoopGroup eventLoopGroup() {
        return loopResources().onClient(isPreferNative());
    }

    protected abstract ChannelInitializer<Channel> parentChannelInitializer();

    static final class QuicChannelInitializer implements ChannelPipelineConfigurer {

        final ChannelHandler loggingHandler;

        final Map<AttributeKey<?>, ?> streamAttrs;

        final ConnectionObserver streamObserver;

        final Map<ChannelOption<?>, ?> streamOptions;

        QuicChannelInitializer(Http3TransportConfig<?> config) {
            this.loggingHandler = config.loggingHandler();
            this.streamAttrs = config.streamAttrs;
            this.streamObserver = config.streamObserver;
            this.streamOptions = config.streamOptions;
        }

        @Override
        public void onChannelInit(ConnectionObserver observer, Channel channel, @Nullable SocketAddress remoteAddress) {
            if (log.isDebugEnabled()) {
                log.debug(format(channel, "Created a new QUIC channel."));
            }
            channel.pipeline().addLast(new ConnectionChangeHandler());
            channel.pipeline().remove(NettyPipeline.ReactiveBridge);
            channel.pipeline().addLast(NettyPipeline.ReactiveBridge,
                                       new QuicChannelInboundHandler(observer, loggingHandler, streamAttrs, streamObserver, streamOptions));
        }

    }


    /**
     * Do not handle channelRead, it will be handled by
     * io.netty.incubator.codec.quic.QuicheQuicChannel#newChannelPipeline()
     * It will register the stream.
     */
    static final class QuicChannelInboundHandler extends ChannelInboundHandlerAdapter {

        final ConnectionObserver listener;

        final ChannelHandler loggingHandler;

        final Map<AttributeKey<?>, ?> streamAttrs;

        final ConnectionObserver streamObserver;

        final Map<ChannelOption<?>, ?> streamOptions;

        QuicChannelInboundHandler(
                ConnectionObserver listener,
                @Nullable ChannelHandler loggingHandler,
                Map<AttributeKey<?>, ?> streamAttrs,
                ConnectionObserver streamObserver,
                Map<ChannelOption<?>, ?> streamOptions) {
            this.listener = listener;
            this.loggingHandler = loggingHandler;
            this.streamAttrs = streamAttrs;
            this.streamObserver = streamObserver;
            this.streamOptions = streamOptions;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            if (ctx.channel().isActive()) {
                Connection c = Connection.from(ctx.channel());
                listener.onStateChange(c, CONNECTED);
                QuicOperations ops = new QuicOperations((QuicChannel) ctx.channel(), loggingHandler, streamObserver, streamAttrs, streamOptions);
                ops.bind();
                listener.onStateChange(ops, CONFIGURED);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // TODO need more here
            Connection connection = Connection.from(ctx.channel());
            listener.onStateChange(connection, ConnectionObserver.State.DISCONNECTING);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // TODO need more here
            Connection connection = Connection.from(ctx.channel());
            listener.onUncaughtException(connection, cause);
        }

    }

    protected static final class QuicStreamChannelInitializer extends ChannelInitializer<QuicStreamChannel> {

        final ChannelHandler loggingHandler;

        final ConnectionObserver streamListener;

        final boolean inbound;

        QuicStreamChannelInitializer(
                @Nullable ChannelHandler loggingHandler,
                ConnectionObserver streamListener,
                boolean inbound) {
            this.loggingHandler = loggingHandler;
            this.streamListener = streamListener;
            this.inbound = inbound;
        }

        @Override
        protected void initChannel(QuicStreamChannel ch) {
            if (log.isDebugEnabled()) {
                log.debug(format(ch, "init a quicStreamChannel"));
            }

            if (loggingHandler != null) {
                ch.pipeline().addLast(loggingHandler);
            }
            if (inbound) {
                //Http3ServerOperations 可以在收到http3HeadersFrame后再创建

                //Http3InboundStreamTrafficHandler 需要在 ChannelOperationsHandler 之后添加
                //因为Http3InboundStreamTrafficHandler的handlerAdded触发ctx.read()之后
                //才开始读取数据，这样才能触发 ChannelOperationsHandler 的 channelRead()
                ChannelOperations.addReactiveBridge(ch, ChannelOperations.OnSetup.empty(), streamListener);
                ch.pipeline().addBefore(NettyPipeline.ReactiveBridge,
                                        NettyPipeline.HttpTrafficHandler, new Http3InboundStreamTrafficHandler(streamListener));
            } else {
                //Http3ClientOperations 必须在quicStreamChannel  Active的时候就创建
                //因为需要使用它发送消息
                ch.pipeline().addLast(new Http3RequestStreamInitializer() {
                    @Override
                    protected void initRequestStream(QuicStreamChannel ch) {}
                });
                ch.pipeline().addLast(NettyPipeline.HttpTrafficHandler, new Http3OutboundStreamTrafficHandler());
                ChannelOperations.addReactiveBridge(ch, (conn, observer, msg) -> new Http3ClientOperations(conn, observer, ConnectionInfo.from(ch)), streamListener);
            }
        }

    }


    static final class QuicTransportDoOn implements ConnectionObserver {

        final Consumer<? super Connection> doOnBound;

        final Consumer<? super Connection> doOnUnbound;

        QuicTransportDoOn(
                @Nullable ChannelGroup channelGroup,
                @Nullable Consumer<? super Connection> doOnBound,
                @Nullable Consumer<? super Connection> doOnUnbound) {
            this.doOnBound = doOnBound;
            this.doOnUnbound = doOnUnbound;
        }

        @Override
        public void onStateChange(Connection connection, State newState) {
            if (doOnBound != null && newState == State.CONFIGURED) {
                doOnBound.accept(connection);
                return;
            }
            if (doOnUnbound != null && newState == State.DISCONNECTING) {
                connection.onDispose(() -> doOnUnbound.accept(connection));
            }
        }

    }

}
