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

import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.incubator.codec.quic.*;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.util.annotation.Nullable;
import xyz.nyist.core.Http3ServerConnectionHandler;
import xyz.nyist.http.Http3ServerRequest;
import xyz.nyist.http.Http3ServerResponse;
import xyz.nyist.http.Http3TransportConfig;
import xyz.nyist.http.QuicOperations;
import xyz.nyist.quic.QuicConnection;
import xyz.nyist.quic.QuicInitialSettingsSpec;
import xyz.nyist.quic.QuicServer;

import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static reactor.netty.ConnectionObserver.State.CONFIGURED;
import static reactor.netty.ConnectionObserver.State.CONNECTED;
import static reactor.netty.ReactorNetty.format;

/**
 * Encapsulate all necessary configuration for QUIC server transport. The public API is read-only.
 *
 * @author Violeta Georgieva
 */
@Slf4j
public final class Http3ServerConfig extends Http3TransportConfig<Http3ServerConfig> {

    /**
     * Name prefix that will be used for the QUIC server's metrics
     * registered in Micrometer's global registry
     */
    public static final String HTTP3_SERVER_PREFIX = "reactor.netty.http3.server";

    static final QuicConnectionIdGenerator DEFAULT_CONNECTION_ID_ADDRESS_GENERATOR = QuicConnectionIdGenerator.randomGenerator();

    static final LoggingHandler LOGGING_HANDLER =
            AdvancedByteBufFormat.HEX_DUMP
                    .toLoggingHandler(QuicServer.class.getName(), LogLevel.DEBUG, Charset.defaultCharset());

    QuicConnectionIdGenerator connectionIdAddressGenerator;

    Consumer<? super QuicConnection> doOnConnection;

    QuicTokenHandler tokenHandler;

    Http3ServerConfig(
            Map<ChannelOption<?>, ?> options,
            Map<ChannelOption<?>, ?> streamOptions,
            Supplier<? extends SocketAddress> bindAddress) {
        super(options, streamOptions, bindAddress);
        this.connectionIdAddressGenerator = DEFAULT_CONNECTION_ID_ADDRESS_GENERATOR;
    }

    Http3ServerConfig(Http3ServerConfig parent) {
        super(parent);
        this.connectionIdAddressGenerator = parent.connectionIdAddressGenerator;
        this.doOnConnection = parent.doOnConnection;
        this.tokenHandler = parent.tokenHandler;
    }

    /**
     * Return the configured {@link QuicConnectionIdGenerator} or the default.
     *
     * @return the configured {@link QuicConnectionIdGenerator} or the default
     */
    public QuicConnectionIdGenerator connectionIdAddressGenerator() {
        return connectionIdAddressGenerator;
    }

    /**
     * Return the configured callback or null
     *
     * @return the configured callback or null
     */
    @Nullable
    public Consumer<? super QuicConnection> doOnConnection() {
        return doOnConnection;
    }

    /**
     * Return the configured {@link QuicTokenHandler} or null.
     *
     * @return the configured {@link QuicTokenHandler} or null
     */
    @Nullable
    public QuicTokenHandler tokenHandler() {
        return tokenHandler;
    }

    @Override
    protected ConnectionObserver defaultConnectionObserver() {
        if (channelGroup() == null && doOnConnection() == null) {
            return super.defaultConnectionObserver();
        }
        return super.defaultConnectionObserver()
                .then(new QuicServerDoOnConnection(channelGroup(), doOnConnection()));
    }

    @Override
    protected LoggingHandler defaultLoggingHandler() {
        return LOGGING_HANDLER;
    }


    Function<QuicChannel, ? extends QuicSslEngine> sslEngineProvider() {
        return sslEngineProvider;
    }

    @Override
    protected ChannelMetricsRecorder defaultMetricsRecorder() {
        // TODO where we want metrics on QUIC channel or on QUIC stream
        return MicrometerQuicServerMetricsRecorder.INSTANCE;
    }


    @Override
    protected ChannelPipelineConfigurer defaultOnChannelInit() {
        return new QuicChannelInitializer(this);
    }

    @Override
    protected ChannelInitializer<Channel> parentChannelInitializer() {
        return new ParentChannelInitializer(this);
    }

    static final class MicrometerQuicServerMetricsRecorder extends MicrometerChannelMetricsRecorder {

        static final MicrometerQuicServerMetricsRecorder INSTANCE = new MicrometerQuicServerMetricsRecorder();

        MicrometerQuicServerMetricsRecorder() {
            super(HTTP3_SERVER_PREFIX, "http3");
        }

    }

    static final class ParentChannelInitializer extends ChannelInitializer<Channel> {

        final long ackDelayExponent;

        final boolean activeMigration;

        final Map<AttributeKey<?>, ?> attributes;

        final QuicCongestionControlAlgorithm congestionControlAlgorithm;

        final QuicConnectionIdGenerator connectionIdAddressGenerator;

        final boolean grease;

        final boolean hystart;

        final Duration idleTimeout;

        final QuicInitialSettingsSpec initialSettings;

        final int localConnectionIdLength;

        final ChannelHandler loggingHandler;

        final Duration maxAckDelay;

        final long maxRecvUdpPayloadSize;

        final long maxSendUdpPayloadSize;

        final Map<ChannelOption<?>, ?> options;

        final ChannelInitializer<Channel> quicChannelInitializer;

        final int recvQueueLen;

        final int sendQueueLen;

        final Map<AttributeKey<?>, ?> streamAttrs;

        //final ConnectionObserver streamObserver;

        final Map<ChannelOption<?>, ?> streamOptions;

        final Function<QuicChannel, ? extends QuicSslEngine>
                sslEngineProvider;

        final QuicTokenHandler tokenHandler;

        ParentChannelInitializer(Http3ServerConfig config) {
            this.ackDelayExponent = config.ackDelayExponent();
            this.activeMigration = config.activeMigration;
            this.attributes = config.attributes();
            this.congestionControlAlgorithm = config.congestionControlAlgorithm();
            this.connectionIdAddressGenerator = config.connectionIdAddressGenerator;
            this.grease = config.grease;
            this.hystart = config.hystart;
            this.idleTimeout = config.idleTimeout;
            this.initialSettings = config.initialSettings;
            this.localConnectionIdLength = config.localConnectionIdLength;
            this.loggingHandler = config.loggingHandler();
            this.maxAckDelay = config.maxAckDelay;
            this.maxRecvUdpPayloadSize = config.maxRecvUdpPayloadSize;
            this.maxSendUdpPayloadSize = config.maxSendUdpPayloadSize;
            this.options = config.options();
            ConnectionObserver observer = config.defaultConnectionObserver().then(config.connectionObserver());
            this.quicChannelInitializer = config.channelInitializer(observer, null, true);
            this.recvQueueLen = config.recvQueueLen;
            this.sendQueueLen = config.sendQueueLen;
            this.streamAttrs = config.streamAttrs;
            //this.streamObserver = config.streamObserver.then(new QuicStreamChannelObserver(config.streamHandler));
            this.streamOptions = config.streamOptions;
            this.sslEngineProvider = config.sslEngineProvider;
            this.tokenHandler = config.tokenHandler;
        }

        @SuppressWarnings("unchecked")
        static void attributes(QuicServerCodecBuilder quicServerCodecBuilder, Map<AttributeKey<?>, ?> attrs) {
            for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
                quicServerCodecBuilder.attr((AttributeKey<Object>) e.getKey(), e.getValue());
            }
        }

        @SuppressWarnings("unchecked")
        static void channelOptions(QuicServerCodecBuilder quicServerCodecBuilder, Map<ChannelOption<?>, ?> options) {
            for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
                quicServerCodecBuilder.option((ChannelOption<Object>) e.getKey(), e.getValue());
            }
        }

        @SuppressWarnings("unchecked")
        static void streamAttributes(QuicServerCodecBuilder quicServerCodecBuilder, Map<AttributeKey<?>, ?> attrs) {
            for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
                quicServerCodecBuilder.streamAttr((AttributeKey<Object>) e.getKey(), e.getValue());
            }
        }

        @SuppressWarnings("unchecked")
        static void streamChannelOptions(QuicServerCodecBuilder quicServerCodecBuilder, Map<ChannelOption<?>, ?> options) {
            for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
                quicServerCodecBuilder.streamOption((ChannelOption<Object>) e.getKey(), e.getValue());
            }
        }

        @Override
        protected void initChannel(Channel channel) {
            QuicServerCodecBuilder quicServerCodecBuilder = new QuicServerCodecBuilder();
            quicServerCodecBuilder.ackDelayExponent(ackDelayExponent)
                    .activeMigration(activeMigration)
                    .congestionControlAlgorithm(congestionControlAlgorithm)
                    .connectionIdAddressGenerator(connectionIdAddressGenerator)
                    .grease(grease)
                    .hystart(hystart)
                    .initialMaxData(initialSettings.maxData())
                    .initialMaxStreamDataBidirectionalLocal(initialSettings.maxStreamDataBidirectionalLocal())
                    .initialMaxStreamDataBidirectionalRemote(initialSettings.maxStreamDataBidirectionalRemote())
                    .initialMaxStreamsBidirectional(initialSettings.maxStreamsBidirectional())
                    .initialMaxStreamsUnidirectional(initialSettings.maxStreamsUnidirectional())
                    .initialMaxStreamDataUnidirectional(initialSettings.maxStreamDataUnidirectional())
                    .localConnectionIdLength(localConnectionIdLength)
                    .maxAckDelay(maxAckDelay.toMillis(), TimeUnit.MILLISECONDS)
                    .maxRecvUdpPayloadSize(maxRecvUdpPayloadSize)
                    .maxSendUdpPayloadSize(maxSendUdpPayloadSize)
                    .sslEngineProvider(sslEngineProvider);

            if (recvQueueLen > 0 && sendQueueLen > 0) {
                quicServerCodecBuilder.datagram(recvQueueLen, sendQueueLen);
            }

            if (idleTimeout != null) {
                quicServerCodecBuilder.maxIdleTimeout(idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }

            if (tokenHandler != null) {
                quicServerCodecBuilder.tokenHandler(tokenHandler);
            }

            attributes(quicServerCodecBuilder, attributes);
            channelOptions(quicServerCodecBuilder, options);
            streamAttributes(quicServerCodecBuilder, streamAttrs);
            streamChannelOptions(quicServerCodecBuilder, streamOptions);

            quicServerCodecBuilder
                    .handler(quicChannelInitializer)
            //.streamHandler(streamChannelInitializer(loggingHandler, streamObserver, true))
            ;

            if (loggingHandler != null) {
                channel.pipeline().addLast(loggingHandler);
            }
            ChannelHandler handler = quicServerCodecBuilder.build();
            channel.pipeline().addLast(handler);
        }

    }

    static final class QuicServerDoOnConnection implements ConnectionObserver {

        final ChannelGroup channelGroup;

        final Consumer<? super QuicConnection> doOnConnection;

        QuicServerDoOnConnection(
                @Nullable ChannelGroup channelGroup,
                @Nullable Consumer<? super QuicConnection> doOnConnection) {
            this.channelGroup = channelGroup;
            this.doOnConnection = doOnConnection;
        }

        @Override
        @SuppressWarnings("FutureReturnValueIgnored")
        public void onStateChange(Connection connection, State newState) {
            if (channelGroup != null && newState == State.CONNECTED) {
                channelGroup.add(connection.channel());
                return;
            }
            if (doOnConnection != null && newState == State.CONFIGURED) {
                try {
                    doOnConnection.accept((QuicConnection) connection);
                } catch (Throwable t) {
                    log.error(format(connection.channel(), ""), t);
                    //"FutureReturnValueIgnored" this is deliberate
                    connection.channel().close();
                }
            }
        }

    }


    static final class QuicChannelInitializer implements ChannelPipelineConfigurer {

        final ChannelHandler loggingHandler;

        final Map<AttributeKey<?>, ?> streamAttrs;

        final ConnectionObserver streamObserver;


        final Map<ChannelOption<?>, ?> streamOptions;

        final BiFunction<? super Http3ServerRequest, ? super Http3ServerResponse, ? extends Publisher<Void>> streamHandler;

        QuicChannelInitializer(Http3ServerConfig config) {
            this.loggingHandler = config.loggingHandler();
            this.streamAttrs = config.streamAttrs;
            this.streamObserver = config.streamObserver;
            this.streamOptions = config.streamOptions;
            this.streamHandler = config.streamHandler;
        }

        @Override
        public void onChannelInit(ConnectionObserver observer, Channel channel, @Nullable SocketAddress remoteAddress) {
            if (log.isDebugEnabled()) {
                log.debug(format(channel, "初始化一个QuicheQuicChannel"));
            }

            ChannelPipeline pipeline = channel.pipeline();

            pipeline.remove(NettyPipeline.ReactiveBridge);

            pipeline.addLast(new Http3ServerConnectionHandler(
                            streamChannelInitializer(loggingHandler, streamObserver, true)
                    ))
                    .addLast(NettyPipeline.ReactiveBridge,
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

}
