/*
 * Copyright (c) 2018-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package xyz.nyist.http.temp;

import io.netty.channel.Channel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import reactor.util.annotation.Nullable;
import xyz.nyist.core.Http3HeadersFrame;
import xyz.nyist.http.ConnectionChangeHandler;

import java.net.InetSocketAddress;
import java.util.function.BiFunction;

import static java.util.Objects.requireNonNull;

/**
 * Resolve information about the current connection, including the
 * host (server) address, the remote (client) address and the scheme.
 *
 * <p>Depending on the chosen factory method, the information
 * can be retrieved directly from the channel or additionally
 * using the {@code "Forwarded"}, or {@code "X-Forwarded-*"}
 * HTTP request headers.
 *
 * @author Brian Clozel
 * @author Andrey Shlykov
 * @see <a href="https://tools.ietf.org/html/rfc7239">rfc7239</a>
 * @since 0.8
 */
public final class ConnectionInfo {

    final InetSocketAddress hostAddress;

    final InetSocketAddress remoteAddress;

    final String scheme;

    ConnectionInfo(InetSocketAddress hostAddress, InetSocketAddress remoteAddress, String scheme) {
        this.hostAddress = hostAddress;
        this.remoteAddress = remoteAddress;
        this.scheme = scheme;
    }

    @Nullable
    public static ConnectionInfo from(Channel channel, Http3HeadersFrame request,
                                      @Nullable BiFunction<ConnectionInfo, Http3HeadersFrame, ConnectionInfo> forwardedHeaderHandler) {
        if (!(channel instanceof QuicStreamChannel)) {
            return null;
        } else {
            ConnectionInfo connectionInfo = ConnectionInfo.newConnectionInfo(channel);
            if (forwardedHeaderHandler != null) {
                return forwardedHeaderHandler.apply(connectionInfo, request);
            }
            return connectionInfo;
        }
    }

    /**
     * Retrieve the connection information from the current connection directly
     *
     * @param c the current channel
     * @return the connection information
     */
    static ConnectionInfo newConnectionInfo(Channel c) {
        Channel parent = c.parent().parent();
        ConnectionChangeHandler handler = c.parent().pipeline().get(ConnectionChangeHandler.class);
        InetSocketAddress remoteAddress = null;
        if (handler != null) {
            remoteAddress = (InetSocketAddress) handler.getConnection().newAddress();
        }

        return new ConnectionInfo((InetSocketAddress) parent.localAddress(), remoteAddress, "https");
    }

    /**
     * Return the host address of the connection.
     *
     * @return the host address
     */
    public InetSocketAddress getHostAddress() {
        return hostAddress;
    }

    /**
     * Return the remote address of the connection.
     *
     * @return the remote address
     */
    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Return the connection scheme.
     *
     * @return the connection scheme
     */
    public String getScheme() {
        return scheme;
    }

    /**
     * Return a new {@link ConnectionInfo} with the updated host address.
     *
     * @param hostAddress the host address
     * @return a new {@link ConnectionInfo}
     */
    public ConnectionInfo withHostAddress(InetSocketAddress hostAddress) {
        requireNonNull(hostAddress, "hostAddress");
        return new ConnectionInfo(hostAddress, this.remoteAddress, this.scheme);
    }

    /**
     * Return a new {@link ConnectionInfo} with the updated remote address.
     *
     * @param remoteAddress the remote address
     * @return a new {@link ConnectionInfo}
     */
    public ConnectionInfo withRemoteAddress(InetSocketAddress remoteAddress) {
        requireNonNull(remoteAddress, "remoteAddress");
        return new ConnectionInfo(this.hostAddress, remoteAddress, this.scheme);
    }

    /**
     * Return a new {@link ConnectionInfo} with the updated scheme.
     *
     * @param scheme the connection scheme
     * @return a new {@link ConnectionInfo}
     */
    public ConnectionInfo withScheme(String scheme) {
        requireNonNull(scheme, "scheme");
        return new ConnectionInfo(this.hostAddress, this.remoteAddress, scheme);
    }

}
