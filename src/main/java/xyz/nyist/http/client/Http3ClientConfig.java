package xyz.nyist.http.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicCongestionControlAlgorithm;
import io.netty.incubator.codec.quic.QuicSslEngine;
import io.netty.resolver.AddressResolverGroup;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.util.annotation.Nullable;
import xyz.nyist.core.Http3ClientConnectionHandler;
import xyz.nyist.http.Http3TransportConfig;
import xyz.nyist.quic.QuicClient;
import xyz.nyist.quic.QuicConnection;
import xyz.nyist.quic.QuicInitialSettingsSpec;

import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static reactor.netty.ConnectionObserver.State.CONFIGURED;
import static reactor.netty.ConnectionObserver.State.CONNECTED;

/**
 * @author: fucong
 * @Date: 2022/8/17 16:35
 * @Description:
 */
@Slf4j
public class Http3ClientConfig extends Http3TransportConfig<Http3ClientConfig> {

    public static final String QUIC_CLIENT_PREFIX = "reactor.netty.quic.client";

    static final LoggingHandler LOGGING_HANDLER =
            AdvancedByteBufFormat.HEX_DUMP
                    .toLoggingHandler(QuicClient.class.getName(), LogLevel.DEBUG, Charset.defaultCharset());

    final ConnectionProvider connectionProvider;


    Consumer<? super Http3ClientConfig> doOnConnect;

    Consumer<? super QuicConnection> doOnConnected;

    Consumer<? super QuicConnection> doOnDisconnected;

    Supplier<? extends SocketAddress> remoteAddress;

    AddressResolverGroup<?> resolver;

    Function<? super Http3ClientRequest, ? extends Publisher<Void>> sendHandler;

    Function<? super Http3ClientResponse, ? extends Publisher<Void>> responseHandler;


    Http3ClientConfig(
            ConnectionProvider connectionProvider,
            Map<ChannelOption<?>, ?> options,
            Map<ChannelOption<?>, ?> streamOptions,
            Supplier<? extends SocketAddress> bindAddress,
            Supplier<? extends SocketAddress> remoteAddress) {
        super(options, streamOptions, bindAddress);
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
    }

    Http3ClientConfig(Http3ClientConfig parent) {
        super(parent);
        this.connectionProvider = parent.connectionProvider;
        this.doOnConnect = parent.doOnConnect;
        this.doOnConnected = parent.doOnConnected;
        this.doOnDisconnected = parent.doOnDisconnected;
        this.remoteAddress = parent.remoteAddress;
        this.resolver = parent.resolver;
        this.sendHandler = parent.sendHandler;
        this.responseHandler = parent.responseHandler;
    }


    public final ConnectionProvider connectionProvider() {
        return connectionProvider;
    }


    /**
     * Return the configured callback or null
     *
     * @return the configured callback or null
     */
    @Nullable
    public Consumer<? super Http3ClientConfig> doOnConnect() {
        return doOnConnect;
    }

    /**
     * Return the configured callback or null
     *
     * @return the configured callback or null
     */
    @Nullable
    public Consumer<? super QuicConnection> doOnConnected() {
        return doOnConnected;
    }

    /**
     * Return the configured callback or null
     *
     * @return the configured callback or null
     */
    @Nullable
    public Consumer<? super QuicConnection> doOnDisconnected() {
        return doOnDisconnected;
    }

    /**
     * Return the remote configured {@link SocketAddress}
     *
     * @return the remote configured {@link SocketAddress}
     */
    public Supplier<? extends SocketAddress> remoteAddress() {
        return remoteAddress;
    }

    Function<QuicChannel, ? extends QuicSslEngine> sslEngineProvider() {
        return sslEngineProvider;
    }

    @Override
    protected ConnectionObserver defaultConnectionObserver() {
        ConnectionObserver observer = (connection, newState) -> {
            if (newState == CONNECTED) {
                connection.channel().pipeline()
                        .addBefore(NettyPipeline.ReactiveBridge, "Http3ClientConnectionHandler", new Http3ClientConnectionHandler());
            }
        };
        if (channelGroup() == null && doOnConnected() == null && doOnDisconnected() == null) {
            return observer.then(super.defaultConnectionObserver());
        }
        return observer.then(super.defaultConnectionObserver())
                .then(new QuicClientDoOn(channelGroup(), doOnConnected(), doOnDisconnected()));
    }

    @Override
    protected LoggingHandler defaultLoggingHandler() {
        return LOGGING_HANDLER;
    }

    @Override
    protected ChannelMetricsRecorder defaultMetricsRecorder() {
        // TODO where we want metrics on QUIC channel or on QUIC stream
        return MicrometerQuicClientMetricsRecorder.INSTANCE;
    }

    @Override
    protected ChannelInitializer<Channel> parentChannelInitializer() {
        return new ParentChannelInitializer(this);
    }


    static final class MicrometerQuicClientMetricsRecorder extends MicrometerChannelMetricsRecorder {

        static final MicrometerQuicClientMetricsRecorder INSTANCE = new MicrometerQuicClientMetricsRecorder();

        MicrometerQuicClientMetricsRecorder() {
            super(QUIC_CLIENT_PREFIX, "quic");
        }

    }

    static final class ParentChannelInitializer extends ChannelInitializer<Channel> {

        final long ackDelayExponent;

        final boolean activeMigration;

        final QuicCongestionControlAlgorithm congestionControlAlgorithm;

        final boolean grease;

        final boolean hystart;

        final Duration idleTimeout;

        final QuicInitialSettingsSpec initialSettings;

        final int localConnectionIdLength;

        final ChannelHandler loggingHandler;

        final Duration maxAckDelay;

        final long maxRecvUdpPayloadSize;

        final long maxSendUdpPayloadSize;

        final int recvQueueLen;

        final int sendQueueLen;

        final Function<QuicChannel, ? extends QuicSslEngine>
                sslEngineProvider;

        ParentChannelInitializer(Http3ClientConfig config) {
            this.ackDelayExponent = config.ackDelayExponent;
            this.activeMigration = config.activeMigration;
            this.congestionControlAlgorithm = config.congestionControlAlgorithm;
            this.grease = config.grease;
            this.hystart = config.hystart;
            this.idleTimeout = config.idleTimeout;
            this.initialSettings = config.initialSettings;
            this.localConnectionIdLength = config.localConnectionIdLength;
            this.loggingHandler = config.loggingHandler();
            this.maxAckDelay = config.maxAckDelay;
            this.maxRecvUdpPayloadSize = config.maxRecvUdpPayloadSize;
            this.maxSendUdpPayloadSize = config.maxSendUdpPayloadSize;
            this.recvQueueLen = config.recvQueueLen;
            this.sendQueueLen = config.sendQueueLen;
            this.sslEngineProvider = config.sslEngineProvider;
        }

        @Override
        protected void initChannel(Channel channel) {
            QuicClientCodecBuilder quicClientCodecBuilder = new QuicClientCodecBuilder();
            quicClientCodecBuilder.ackDelayExponent(ackDelayExponent)
                    .activeMigration(activeMigration)
                    .congestionControlAlgorithm(congestionControlAlgorithm)
                    .grease(grease)
                    .hystart(hystart)
                    .initialMaxData(initialSettings.maxData())
                    .initialMaxStreamDataBidirectionalLocal(initialSettings.maxStreamDataBidirectionalLocal())
                    .initialMaxStreamDataBidirectionalRemote(initialSettings.maxStreamDataBidirectionalRemote())
                    .initialMaxStreamDataUnidirectional(initialSettings.maxStreamDataUnidirectional())
                    .initialMaxStreamsBidirectional(initialSettings.maxStreamsBidirectional())
                    .initialMaxStreamsUnidirectional(initialSettings.maxStreamsUnidirectional())
                    .localConnectionIdLength(localConnectionIdLength)
                    .maxAckDelay(maxAckDelay.toMillis(), TimeUnit.MILLISECONDS)
                    .maxRecvUdpPayloadSize(maxRecvUdpPayloadSize)
                    .maxSendUdpPayloadSize(maxSendUdpPayloadSize)
                    .sslEngineProvider(sslEngineProvider);

            if (recvQueueLen > 0 && sendQueueLen > 0) {
                quicClientCodecBuilder.datagram(recvQueueLen, sendQueueLen);
            }

            if (idleTimeout != null) {
                quicClientCodecBuilder.maxIdleTimeout(idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }

            if (loggingHandler != null) {
                channel.pipeline().addLast(loggingHandler);
            }
            channel.pipeline().addLast(quicClientCodecBuilder.build());
        }

    }

    static final class QuicClientDoOn implements ConnectionObserver {

        final ChannelGroup channelGroup;

        final Consumer<? super QuicConnection> doOnConnected;

        final Consumer<? super QuicConnection> doOnDisconnected;

        QuicClientDoOn(
                @Nullable ChannelGroup channelGroup,
                @Nullable Consumer<? super QuicConnection> doOnConnected,
                @Nullable Consumer<? super QuicConnection> doOnDisconnected) {
            this.channelGroup = channelGroup;
            this.doOnConnected = doOnConnected;
            this.doOnDisconnected = doOnDisconnected;
        }

        @Override
        public void onStateChange(Connection connection, State newState) {
            if (channelGroup != null && newState == State.CONNECTED) {
                channelGroup.add(connection.channel());
                return;
            }
            if (doOnConnected != null && newState == CONFIGURED) {
                doOnConnected.accept((QuicConnection) connection);
                return;
            }
            if (doOnDisconnected != null) {
                if (newState == State.DISCONNECTING) {
                    connection.onDispose(() -> doOnDisconnected.accept((QuicConnection) connection));
                } else if (newState == State.RELEASED) {
                    doOnDisconnected.accept((QuicConnection) connection);
                }
            }
        }

    }


}
