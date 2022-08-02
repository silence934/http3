/*
 * Copyright 2021 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package xyz.nyist.core;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.UnsupportedMessageTypeException;
import io.netty.handler.codec.http.*;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;

/**
 * This handler converts from {@link Http3RequestStreamFrame} to {@link HttpObject},
 * and back. It can be used as an adapter in conjunction with {@link
 * Http3ServerConnectionHandler} or {@link Http3ClientConnectionHandler} to make http/3 connections
 * backward-compatible with {@link ChannelHandler}s expecting {@link HttpObject}.
 * <p>
 * For simplicity, it converts to chunked encoding unless the entire stream
 * is a single header.
 */
@Slf4j
public final class Http3FrameToHttpObjectCodec extends Http3RequestStreamInboundHandler implements ChannelOutboundHandler {

    private final boolean isServer;

    private final boolean validateHeaders;

    public Http3FrameToHttpObjectCodec(final boolean isServer,
                                       final boolean validateHeaders) {
        this.isServer = isServer;
        this.validateHeaders = validateHeaders;
    }

    public Http3FrameToHttpObjectCodec(final boolean isServer) {
        this(isServer, true);
    }

    @Override
    protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame, boolean isLast) throws Exception {
        Http3Headers headers = frame.headers();
        long id = ((QuicStreamChannel) ctx.channel()).streamId();

        final CharSequence status = headers.status();

        // 100-continue response is a special case where we should not send a fin,
        // but we need to decode it as a FullHttpResponse to play nice with HttpObjectAggregator.
        if (null != status && HttpResponseStatus.CONTINUE.codeAsText().contentEquals(status)) {
            final FullHttpMessage fullMsg = newFullMessage(id, headers, ctx.alloc());
            ctx.fireChannelRead(fullMsg);
            return;
        }

        if (isLast) {
            if (headers.method() == null && status == null) {
                LastHttpContent last = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, validateHeaders);
                HttpConversionUtil.addHttp3ToHttpHeaders(id, headers, last.trailingHeaders(),
                                                         HttpVersion.HTTP_1_1, true, true);
                ctx.fireChannelRead(last);
            } else {
                FullHttpMessage full = newFullMessage(id, headers, ctx.alloc());
                ctx.fireChannelRead(full);
            }
        } else {
            HttpMessage req = newMessage(id, headers);
            if (!HttpUtil.isContentLengthSet(req)) {
                req.headers().add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            }
            ctx.fireChannelRead(req);
        }
    }

    @Override
    protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame, boolean isLast) throws Exception {
        if (isLast) {
            ctx.fireChannelRead(new DefaultLastHttpContent(frame.content()));
        } else {
            ctx.fireChannelRead(new DefaultHttpContent(frame.content()));
        }
    }

    /**
     * Encode from an {@link HttpObject} to an {@link Http3RequestStreamFrame}. This method will
     * be called for each written message that can be handled by this encoder.
     * <p>
     * NOTE: 100-Continue responses that are NOT {@link FullHttpResponse} will be rejected.
     *
     * @param ctx the {@link ChannelHandlerContext} which this handler belongs to
     * @param msg the {@link HttpObject} message to encode
     * @throws Exception is thrown if an error occurs
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        log.debug("Http3FrameToHttpObjectCodec write:" + msg.getClass());
        if (!(msg instanceof HttpObject)) {
            throw new UnsupportedMessageTypeException();
        }
        // 100-continue is typically a FullHttpResponse, but the decoded
        // Http3HeadersFrame should not handles as a end of stream.
        if (msg instanceof HttpResponse) {
            final HttpResponse res = (HttpResponse) msg;
            if (res.status().equals(HttpResponseStatus.CONTINUE)) {
                if (res instanceof FullHttpResponse) {
                    final Http3Headers headers = toHttp3Headers(res);
                    ctx.write(new DefaultHttp3HeadersFrame(headers));
                    ((FullHttpResponse) res).release();
                    return;
                } else {
                    throw new EncoderException(
                            HttpResponseStatus.CONTINUE.toString() + " must be a FullHttpResponse");
                }
            }
        }

        if (msg instanceof HttpMessage) {
            Http3Headers headers = toHttp3Headers((HttpMessage) msg);
            ctx.write(new DefaultHttp3HeadersFrame(headers));
        }

        if (msg instanceof LastHttpContent) {
            LastHttpContent last = (LastHttpContent) msg;
            boolean readable = last.content().isReadable();
            boolean hasTrailers = !last.trailingHeaders().isEmpty();

            ChannelFuture future = null;
            if (readable) {
                future = ctx.write(new DefaultHttp3DataFrame(last.content()));
            }
            if (hasTrailers) {
                Http3Headers headers = HttpConversionUtil.toHttp3Headers(last.trailingHeaders(), validateHeaders);
                future = ctx.write(new DefaultHttp3HeadersFrame(headers));
            }
            if (!readable) {
                last.release();
            }
            if (future != null) {
                future.addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            }
        } else if (msg instanceof HttpContent) {
            ctx.write(new DefaultHttp3DataFrame(((HttpContent) msg).content()));
        }
    }

    private Http3Headers toHttp3Headers(HttpMessage msg) {
        if (msg instanceof HttpRequest) {
            msg.headers().set(
                    HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), HttpScheme.HTTPS);
        }

        return HttpConversionUtil.toHttp3Headers(msg, validateHeaders);
    }

    private HttpMessage newMessage(final long id,
                                   final Http3Headers headers) throws Http3Exception {
        return isServer ?
                HttpConversionUtil.toHttpRequest(id, headers, validateHeaders) :
                HttpConversionUtil.toHttpResponse(id, headers, validateHeaders);
    }

    private FullHttpMessage newFullMessage(final long id,
                                           final Http3Headers headers,
                                           final ByteBufAllocator alloc) throws Http3Exception {
        return isServer ?
                HttpConversionUtil.toFullHttpRequest(id, headers, alloc, validateHeaders) :
                HttpConversionUtil.toFullHttpResponse(id, headers, alloc, validateHeaders);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) {
        ctx.bind(localAddress, promise);
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
                        SocketAddress localAddress, ChannelPromise promise) {
        ctx.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) {
        ctx.disconnect(promise);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        ctx.close(promise);
    }

    @Override
    public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) {
        ctx.deregister(promise);
    }

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        ctx.read();
    }

}
