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
package xyz.nyist.quic;

import io.netty.incubator.codec.quic.QuicStreamType;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import xyz.nyist.http.client.Http3ClientRequest;
import xyz.nyist.http.client.Http3ClientResponse;

import java.util.function.BiFunction;

/**
 * API for creating and handling streams.
 *
 * @author Violeta Georgieva
 */
public interface QuicConnection extends Connection {

    /**
     * Creates a bidirectional stream. A {@link Mono} completing when the stream is created,
     * then the provided callback is invoked. If the stream creation is not
     * successful the returned {@link Mono} fails.
     *
     * @param streamHandler the I/O handler for the stream
     * @return a {@link Mono} completing when the stream is created, otherwise fails
     */
    default Mono<Connection> createStream(
            BiFunction<? super Http3ClientRequest, ? super Http3ClientResponse, ? extends Publisher<Void>> streamHandler) {
        return createStream(QuicStreamType.BIDIRECTIONAL, streamHandler);
    }

    /**
     * Creates a stream. A {@link Mono} completing when the stream is created,
     * then the provided callback is invoked. If the stream creation is not
     * successful the returned {@link Mono} fails.
     *
     * @param streamType    the {@link QuicStreamType}
     * @param streamHandler the I/O handler for the stream
     * @return a {@link Mono} completing when the stream is created, otherwise fails
     */
    Mono<Connection> createStream(
            QuicStreamType streamType,
            BiFunction<? super Http3ClientRequest, ? super Http3ClientResponse, ? extends Publisher<Void>> streamHandler);

}
