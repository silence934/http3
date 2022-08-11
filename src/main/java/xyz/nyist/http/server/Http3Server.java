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

import io.netty.incubator.codec.quic.QuicConnectionIdGenerator;
import io.netty.incubator.codec.quic.QuicTokenHandler;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.http.server.HttpServerState;
import reactor.netty.transport.AddressUtils;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;
import xyz.nyist.http.Http3ServerRequest;
import xyz.nyist.http.Http3ServerResponse;
import xyz.nyist.http.Http3Transport;
import xyz.nyist.quic.QuicConnection;

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
