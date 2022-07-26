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

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.Cookie;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.NettyOutbound;
import reactor.netty.http.server.WebsocketServerSpec;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import xyz.nyist.core.Http3Headers;
import xyz.nyist.core.Http3HeadersFrame;

import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * An outbound-traffic API delegating to an underlying {@link Channel}.
 *
 * @author silenct
 */
public interface Http3ServerResponse extends NettyOutbound, Http3ServerInfos {

    /**
     * Adds an outbound HTTP header, appending the value if the header already exist.
     *
     * @param name  header name
     * @param value header value
     * @return this {@link Http3ServerResponse}
     */
    Http3ServerResponse addHeader(CharSequence name, CharSequence value);

    /**
     * Sets an outbound HTTP header, replacing any pre-existing value.
     *
     * @param name  headers key
     * @param value header value
     * @return this {@link Http3ServerResponse}
     */
    Http3ServerResponse header(CharSequence name, CharSequence value);

    /**
     * Sets outbound HTTP headers, replacing any pre-existing value for these headers.
     *
     * @param headers netty headers map
     * @return this {@link Http3ServerResponse}
     */
    Http3ServerResponse headers(Http3Headers headers);

    Http3ServerResponse addCookie(Cookie cookie);


    @Override
    Http3ServerResponse withConnection(Consumer<? super Connection> withConnection);

    /**
     * Enables/Disables compression handling (gzip/deflate) for the underlying response
     *
     * @param compress should handle compression
     * @return this {@link Http3ServerResponse}
     */
    Http3ServerResponse compression(boolean compress);

    /**
     * Returns true if headers and status have been sent to the client
     *
     * @return true if headers and status have been sent to the client
     */
    boolean hasSentHeaders();


    /**
     * Returns the outbound HTTP headers, sent back to the clients
     *
     * @return headers sent back to the clients
     */
    Http3HeadersFrame outboundHttpMessage();

    /**
     * Sends the HTTP headers and empty content thus delimiting a full empty body http response.
     *
     * @return a {@link Mono} successful on committed response
     * @see #send(Publisher)
     */
    Mono<Void> send();

    /**
     * Returns a {@link NettyOutbound} successful on committed response
     *
     * @return a {@link NettyOutbound} successful on committed response
     */
    NettyOutbound sendHeaders();

    /**
     * Sends 404 status {@link HttpResponseStatus#NOT_FOUND}.
     *
     * @return a {@link Mono} successful on flush confirmation
     */
    Mono<Void> sendNotFound();

    /**
     * Sends redirect status {@link HttpResponseStatus#FOUND} along with a location
     * header to the remote client.
     *
     * @param location the location to redirect to
     * @return a {@link Mono} successful on flush confirmation
     */
    Mono<Void> sendRedirect(String location);

    /**
     * Upgrades the connection to websocket. A {@link Mono} completing when the upgrade
     * is confirmed, then the provided callback is invoked, if the upgrade is not
     * successful the returned {@link Mono} fails.
     *
     * @param websocketHandler the I/O handler for websocket transport
     * @return a {@link Mono} completing when upgrade is confirmed, otherwise fails
     */
    default Mono<Void> sendWebsocket(BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
        return sendWebsocket(websocketHandler, WebsocketServerSpec.builder().build());
    }

    /**
     * Upgrades the connection to websocket. A {@link Mono} completing when the upgrade
     * is confirmed, then the provided callback is invoked, if the upgrade is not
     * successful the returned {@link Mono} fails.
     *
     * @param websocketHandler    the I/O handler for websocket transport
     * @param websocketServerSpec {@link WebsocketServerSpec} for websocket configuration
     * @return a {@link Mono} completing when upgrade is confirmed, otherwise fails
     * @since 0.9.5
     */
    Mono<Void> sendWebsocket(
            BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler,
            WebsocketServerSpec websocketServerSpec);

    /**
     * Adds "text/event-stream" content-type for Server-Sent Events
     *
     * @return this {@link Http3ServerResponse}
     */
    Http3ServerResponse sse();

    /**
     * Returns the assigned HTTP status
     *
     * @return the assigned HTTP status
     */
    CharSequence status();

    /**
     * Sets an HTTP status to be sent along with the headers
     *
     * @param status an HTTP status to be sent along with the headers
     * @return this {@link Http3ServerResponse}
     */
    Http3ServerResponse status(HttpResponseStatus status);

    /**
     * Sets an HTTP status to be sent along with the headers
     *
     * @param status an HTTP status to be sent along with the headers
     * @return this {@link Http3ServerResponse}
     */
    default Http3ServerResponse status(int status) {
        return status(HttpResponseStatus.valueOf(status));
    }

    /**
     * Callback for setting outbound trailer headers.
     * The callback is invoked when the response is about to be completed.
     * <p><strong>Note:</strong>Only headers names declared with {@link HttpHeaderNames#TRAILER} are accepted.
     * <p><strong>Note:</strong>Trailer headers are sent only when a message body is encoded with the chunked transfer coding
     * <p><strong>Note:</strong>The headers below cannot be sent as trailer headers:
     * <ul>
     *     <li>Age</li>
     *     <li>Cache-Control</li>
     *     <li>Content-Encoding</li>
     *     <li>Content-Length</li>
     *     <li>Content-Range</li>
     *     <li>Content-Type</li>
     *     <li>Date</li>
     *     <li>Expires</li>
     *     <li>Location</li>
     *     <li>Retry-After</li>
     *     <li>Trailer</li>
     *     <li>Transfer-Encoding</li>
     *     <li>Vary</li>
     *     <li>Warning</li>
     * </ul>
     *
     * @param trailerHeaders netty headers map
     * @return this {@link Http3ServerResponse}
     * @since 1.0.12
     */
    Http3ServerResponse trailerHeaders(Consumer<? super HttpHeaders> trailerHeaders);

}
