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
import io.netty.handler.codec.http.HttpHeaders;
import reactor.netty.Connection;
import reactor.netty.NettyInbound;
import reactor.util.annotation.Nullable;
import xyz.nyist.core.Http3HeadersFrame;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * An inbound-traffic API delegating to an underlying {@link Channel}.
 *
 * @author silence
 */
public interface Http3ServerRequest extends NettyInbound, Http3StreamInfo {

    @Override
    Http3ServerRequest withConnection(Consumer<? super Connection> withConnection);


    /**
     * Returns the address of the host peer or {@code null} in case of Unix Domain Sockets.
     *
     * @return the host's address
     */
    @Nullable
    InetSocketAddress hostAddress();

    /**
     * Returns the address of the remote peer or {@code null} in case of Unix Domain Sockets.
     *
     * @return the peer's address
     */
    @Nullable
    InetSocketAddress remoteAddress();

    /**
     * Returns inbound {@link HttpHeaders}
     *
     * @return inbound {@link HttpHeaders}
     */
    Http3HeadersFrame requestHeaders();

    /**
     * Returns the current protocol scheme
     *
     * @return the protocol scheme
     */
    String scheme();

}
