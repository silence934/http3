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
package xyz.nyist.http.server;

import io.netty.util.NetUtil;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import xyz.nyist.quic.QuicServer;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Objects;

/**
 * Provides the actual {@link QuicServer} instance.
 *
 * @author Violeta Georgieva
 */
final class Http3ServerBind extends Http3Server {

    static final Http3ServerBind INSTANCE = new Http3ServerBind();

    final Http3ServerConfig config;

    Http3ServerBind() {
        this.config = new Http3ServerConfig(
                Collections.emptyMap(),
                Collections.emptyMap(),
                //Collections.singletonMap(ChannelOption.AUTO_READ, false),
                () -> new InetSocketAddress(NetUtil.LOCALHOST, 0));
    }

    Http3ServerBind(Http3ServerConfig config) {
        this.config = config;
    }

    void validate(Http3ServerConfig config) {
        Objects.requireNonNull(config.bindAddress(), "bindAddress");
        Objects.requireNonNull(config.sslEngineProvider(), "sslEngineProvider");
        Objects.requireNonNull(config.tokenHandler, "tokenHandler");
    }

    @Override
    public Mono<? extends DisposableServer> bind() {
        Http3ServerConfig config = configuration();
        validate(config);
        return super.bind();
    }

    @Override
    public Http3ServerConfig configuration() {
        return config;
    }

    @Override
    protected Http3Server duplicate() {
        return new Http3ServerBind(new Http3ServerConfig(config));
    }


}
