/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.nyist.adapter;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelId;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ZeroCopyHttpOutputMessage;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ChannelOperationsId;
import reactor.netty.http.server.HttpServerResponse;
import xyz.nyist.core.Http3Exception;
import xyz.nyist.http.Http3ServerResponse;

import java.nio.file.Path;
import java.util.List;

/**
 * Adapt {@link ServerHttpResponse} to the {@link HttpServerResponse}.
 *
 * @author Stephane Maldini
 * @author Rossen Stoyanchev
 * @since 5.0
 */
public class ReactorServerHttp3Response extends AbstractServerHttpResponse implements ZeroCopyHttpOutputMessage {

    private static final Log logger = LogFactory.getLog(ReactorServerHttp3Response.class);

    private final Http3ServerResponse response;

    private final HttpHeaders headers;

    @Nullable
    private HttpHeaders readOnlyHeaders;


    public ReactorServerHttp3Response(Http3ServerResponse response, DataBufferFactory bufferFactory) throws Http3Exception {
        super(bufferFactory, HttpHeaders.EMPTY);
        Assert.notNull(response, "HttpServerResponse must not be null");
        this.response = response;
        this.headers = new Http3HeadersAdapter(new Netty3HeadersAdapter(response.outboundHttpMessage(), false));
    }


    @Override
    public HttpHeaders getHeaders() {
        if (this.readOnlyHeaders != null) {
            return this.readOnlyHeaders;
        } else if (super.getHeaders().getClass().getName().contains("ReadOnlyHttpHeaders")) {
            this.readOnlyHeaders = Http3HeadersAdapter.readOnlyHttpHeaders(this.headers);
            return this.readOnlyHeaders;
        } else {
            return this.headers;
        }
    }


    @SuppressWarnings("unchecked")
    @Override
    public <T> T getNativeResponse() {
        return (T) this.response;
    }

    @Override
    public HttpStatus getStatusCode() {
        HttpStatus status = super.getStatusCode();
        return (status != null ? status : HttpStatus.resolve(Integer.parseInt(this.response.status().toString())));
    }

    @Override
    public Integer getRawStatusCode() {
        Integer status = super.getRawStatusCode();
        return (status != null ? status : Integer.parseInt(this.response.status().toString()));
    }

    @Override
    protected void applyStatusCode() {
        Integer status = super.getRawStatusCode();
        if (status != null) {
            this.response.status(status);
        }
    }

    @Override
    protected Mono<Void> writeWithInternal(Publisher<? extends DataBuffer> publisher) {
        return this.response.send(toByteBufs(publisher)).then();
    }

    @Override
    protected Mono<Void> writeAndFlushWithInternal(Publisher<? extends Publisher<? extends DataBuffer>> publisher) {
        return this.response.sendGroups(Flux.from(publisher).map(this::toByteBufs)).then();
    }

    @Override
    protected void applyHeaders() {
    }

    @Override
    protected void applyCookies() {
        // Netty Cookie doesn't support sameSite. When this is resolved, we can adapt to it again:
        // https://github.com/netty/netty/issues/8161
        for (List<ResponseCookie> cookies : getCookies().values()) {
            for (ResponseCookie cookie : cookies) {
                this.response.addHeader(Http3HeadersAdapter.SET_COOKIE, cookie.toString());
            }
        }
    }

    @Override
    public Mono<Void> writeWith(Path file, long position, long count) {
        return doCommit(() -> this.response.sendFile(file, position, count).then());
    }

    private Publisher<ByteBuf> toByteBufs(Publisher<? extends DataBuffer> dataBuffers) {
        return dataBuffers instanceof Mono ?
                Mono.from(dataBuffers).map(NettyDataBufferFactory::toByteBuf) :
                Flux.from(dataBuffers).map(NettyDataBufferFactory::toByteBuf);
    }

    @Override
    protected void touchDataBuffer(DataBuffer buffer) {
        if (logger.isDebugEnabled()) {
            if (ReactorServerHttp3Request.REACTOR_NETTY_REQUEST_CHANNEL_OPERATIONS_ID_PRESENT) {
                if (ChannelOperationsIdHelper.touch(buffer, this.response)) {
                    return;
                }
            }
            this.response.withConnection(connection -> {
                ChannelId id = connection.channel().id();
                DataBufferUtils.touch(buffer, "Channel id: " + id.asShortText());
            });
        }
    }


    private static class ChannelOperationsIdHelper {

        public static boolean touch(DataBuffer dataBuffer, Http3ServerResponse response) {
            if (response instanceof ChannelOperationsId) {
                String id = ((ChannelOperationsId) response).asLongText();
                DataBufferUtils.touch(dataBuffer, "Channel id: " + id);
                return true;
            }
            return false;
        }

    }


}
