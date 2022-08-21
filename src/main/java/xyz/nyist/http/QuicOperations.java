/*
 * Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannelBootstrap;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.netty.ChannelOperationsId;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import xyz.nyist.http.client.Http3ClientOperations;
import xyz.nyist.http.client.Http3ClientRequest;
import xyz.nyist.http.client.Http3ClientResponse;
import xyz.nyist.quic.QuicConnection;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import static reactor.netty.ConnectionObserver.State.CONFIGURED;
import static reactor.netty.ReactorNetty.format;

/**
 * @author Violeta Georgieva
 */
public final class QuicOperations implements ChannelOperationsId, QuicConnection {

    static final char CHANNEL_ID_PREFIX = '[';

    static final Logger log = Loggers.getLogger(QuicOperations.class);

    static final String ORIGINAL_CHANNEL_ID_PREFIX = "[id: 0x";

    static final int ORIGINAL_CHANNEL_ID_PREFIX_LENGTH = ORIGINAL_CHANNEL_ID_PREFIX.length();

    final ChannelHandler loggingHandler;

    final QuicChannel quicChannel;

    final String shortId;

    final Map<AttributeKey<?>, ?> streamAttrs;

    final ConnectionObserver streamListener;

    final Map<ChannelOption<?>, ?> streamOptions;

    public QuicOperations(
            QuicChannel quicChannel,
            @Nullable ChannelHandler loggingHandler,
            ConnectionObserver streamListener,
            Map<AttributeKey<?>, ?> streamAttrs,
            Map<ChannelOption<?>, ?> streamOptions) {
        this.loggingHandler = loggingHandler;
        this.quicChannel = quicChannel;
        this.shortId = channel().id().asShortText();
        this.streamAttrs = streamAttrs;
        this.streamListener = streamListener;
        this.streamOptions = streamOptions;
    }

    @SuppressWarnings("unchecked")
    static void setAttributes(QuicStreamChannelBootstrap bootstrap, Map<AttributeKey<?>, ?> attrs) {
        for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
            bootstrap.attr((AttributeKey<Object>) e.getKey(), e.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    static void setChannelOptions(QuicStreamChannelBootstrap bootstrap, Map<ChannelOption<?>, ?> options) {
        for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
            bootstrap.option((ChannelOption<Object>) e.getKey(), e.getValue());
        }
    }

    @Override
    public String asLongText() {
        String channelStr = channel().toString();
        int ind = channelStr.indexOf(ORIGINAL_CHANNEL_ID_PREFIX);
        return channelStr.substring(0, ind) +
                CHANNEL_ID_PREFIX +
                channelStr.substring(ind + ORIGINAL_CHANNEL_ID_PREFIX_LENGTH);
    }

    @Override
    public String asShortText() {
        return shortId;
    }

    @Override
    public Channel channel() {
        return quicChannel;
    }

    @Override
    public Mono<Connection> createStream(
            QuicStreamType streamType,
            BiFunction<? super Http3ClientRequest, ? super Http3ClientResponse, ? extends Publisher<Void>> streamHandler) {
        Objects.requireNonNull(streamType, "streamType");
        Objects.requireNonNull(streamHandler, "streamHandler");

        return Mono.create(sink -> {
            QuicStreamChannelBootstrap bootstrap = quicChannel.newStreamBootstrap();
            bootstrap.type(streamType)
                    .handler(Http3TransportConfig.streamChannelInitializer(loggingHandler,
                                                                           streamListener.then(new QuicStreamChannelObserver(sink, streamHandler)), false));

            setAttributes(bootstrap, streamAttrs);
            setChannelOptions(bootstrap, streamOptions);

            bootstrap.create()
                    .addListener(f -> {
                        // We don't need to handle success case, we've already configured QuicStreamChannelObserver
                        if (!f.isSuccess()) {
                            if (f.cause() != null) {
                                sink.error(f.cause());
                            } else {
                                sink.error(new IOException("Cannot create stream"));
                            }
                        }
                    });
        });
    }

    static final class QuicStreamChannelObserver implements ConnectionObserver {

        final Context currentContext;

        final MonoSink<Connection> sink;

        final BiFunction<? super Http3ClientRequest, ? super Http3ClientResponse, ? extends Publisher<Void>> streamHandler;

        QuicStreamChannelObserver(
                MonoSink<Connection> sink,
                BiFunction<? super Http3ClientRequest, ? super Http3ClientResponse, ? extends Publisher<Void>> streamHandler) {
            this.currentContext = Context.of(sink.contextView());
            this.sink = sink;
            this.streamHandler = streamHandler;
        }

        @Override
        public Context currentContext() {
            return currentContext;
        }

        @Override
        @SuppressWarnings("FutureReturnValueIgnored")
        public void onStateChange(Connection connection, State newState) {
            if (newState == CONFIGURED) {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug(format(connection.channel(), "Handler is being applied: {}"), streamHandler);
                    }
                    Http3ClientOperations ops = (Http3ClientOperations) connection;
                    Mono.fromDirect(streamHandler.apply(ops, ops))
                            .subscribe(ops.disposeSubscriber());
                    sink.success(ops);
                } catch (Throwable t) {
                    log.error(format(connection.channel(), ""), t);

                    //"FutureReturnValueIgnored" this is deliberate
                    connection.channel().close();
                }
            }
        }

        @Override
        public void onUncaughtException(Connection connection, Throwable error) {
            sink.error(error);
        }

    }

}
