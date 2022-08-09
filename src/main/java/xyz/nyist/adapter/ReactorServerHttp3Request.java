/*
 * Copyright 2002-2022 the original author or authors.
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

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.ssl.SslHandler;
import org.apache.commons.logging.Log;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpLogging;
import org.springframework.http.server.reactive.AbstractServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.netty.Connection;
import reactor.netty.http.server.HttpServerRequest;
import xyz.nyist.http.Http3ServerRequest;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adapt {@link ServerHttpRequest} to the Reactor {@link HttpServerRequest}.
 *
 * @author Stephane Maldini
 * @author Rossen Stoyanchev
 * @since 5.0
 */
class ReactorServerHttp3Request extends AbstractServerHttpRequest {

    /**
     * Reactor Netty 1.0.5+.
     */
    static final boolean reactorNettyRequestChannelOperationsIdPresent = ClassUtils.isPresent(
            "reactor.netty.ChannelOperationsId", ReactorServerHttp3Request.class.getClassLoader());

    private static final Log logger = HttpLogging.forLogName(ReactorServerHttp3Request.class);


    private static final AtomicLong logPrefixIndex = new AtomicLong();


    private final Http3ServerRequest request;

    private final NettyDataBufferFactory bufferFactory;


    public ReactorServerHttp3Request(Http3ServerRequest request, NettyDataBufferFactory bufferFactory)
            throws URISyntaxException {

        super(initUri(request), "", new Netty3HeadersAdapter(request.requestHeaders()));
        Assert.notNull(bufferFactory, "DataBufferFactory must not be null");
        this.request = request;
        this.bufferFactory = bufferFactory;
    }

    private static URI initUri(Http3ServerRequest request) throws URISyntaxException {
        Assert.notNull(request, "HttpServerRequest must not be null");
        return new URI(resolveBaseUrl(request) + resolveRequestUri(request));
    }

    private static URI resolveBaseUrl(Http3ServerRequest request) throws URISyntaxException {
        String scheme = getScheme(request);
        String header = request.requestHeaders().get(HttpHeaderNames.HOST);
        if (header != null) {
            final int portIndex;
            if (header.startsWith("[")) {
                portIndex = header.indexOf(':', header.indexOf(']'));
            } else {
                portIndex = header.indexOf(':');
            }
            if (portIndex != -1) {
                try {
                    return new URI(scheme, null, header.substring(0, portIndex),
                                   Integer.parseInt(header.substring(portIndex + 1)), null, null, null);
                } catch (NumberFormatException ex) {
                    throw new URISyntaxException(header, "Unable to parse port", portIndex);
                }
            } else {
                return new URI(scheme, header, null, null);
            }
        } else {
            InetSocketAddress localAddress = request.hostAddress();
            Assert.state(localAddress != null, "No host address available");
            return new URI(scheme, null, localAddress.getHostString(),
                           localAddress.getPort(), null, null, null);
        }
    }

    private static String getScheme(Http3ServerRequest request) {
        return request.scheme();
    }

    private static String resolveRequestUri(Http3ServerRequest request) {
        String uri = request.uri();
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                break;
            }
            if (c == ':' && (i + 2 < uri.length())) {
                if (uri.charAt(i + 1) == '/' && uri.charAt(i + 2) == '/') {
                    for (int j = i + 3; j < uri.length(); j++) {
                        c = uri.charAt(j);
                        if (c == '/' || c == '?' || c == '#') {
                            return uri.substring(j);
                        }
                    }
                    return "";
                }
            }
        }
        return uri;
    }


    @Override
    public String getMethodValue() {
        return this.request.method().name();
    }

    @Override
    protected MultiValueMap<String, HttpCookie> initCookies() {
        MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
        for (CharSequence name : this.request.cookies().keySet()) {
            for (Cookie cookie : this.request.cookies().get(name)) {
                HttpCookie httpCookie = new HttpCookie(name.toString(), cookie.value());
                cookies.add(name.toString(), httpCookie);
            }
        }
        return cookies;
    }

    @Override
    @Nullable
    public InetSocketAddress getLocalAddress() {
        return this.request.hostAddress();
    }

    @Override
    @Nullable
    public InetSocketAddress getRemoteAddress() {
        return this.request.remoteAddress();
    }

    @Override
    @Nullable
    protected SslInfo initSslInfo() {
        Channel channel = ((Connection) this.request).channel();
        SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
        if (sslHandler == null && channel.parent() != null) { // HTTP/2
            sslHandler = channel.parent().pipeline().get(SslHandler.class);
        }
        if (sslHandler != null) {
            SSLSession session = sslHandler.engine().getSession();
            //return new DefaultSslInfo(session);
        }
        return null;
    }

    @Override
    public Flux<DataBuffer> getBody() {
        return this.request.receive().retain().map(this.bufferFactory::wrap);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getNativeRequest() {
        return (T) this.request;
    }

    @Override
    @Nullable
    protected String initId() {
        if (this.request instanceof Connection) {
            return ((Connection) this.request).channel().id().asShortText() +
                    "-" + logPrefixIndex.incrementAndGet();
        }
        return null;
    }

    @Override
    protected String initLogPrefix() {
        if (reactorNettyRequestChannelOperationsIdPresent) {
            String id = (ChannelOperationsIdHelper.getId(this.request));
            if (id != null) {
                return id;
            }
        }
        if (this.request instanceof Connection) {
            return ((Connection) this.request).channel().id().asShortText() +
                    "-" + logPrefixIndex.incrementAndGet();
        }
        return getId();
    }

    private static class ChannelOperationsIdHelper {

        @Nullable
        public static String getId(Http3ServerRequest request) {
            if (request instanceof reactor.netty.ChannelOperationsId) {
                return (logger.isDebugEnabled() ?
                        ((reactor.netty.ChannelOperationsId) request).asLongText() :
                        ((reactor.netty.ChannelOperationsId) request).asShortText());
            }
            return null;
        }

    }

}
