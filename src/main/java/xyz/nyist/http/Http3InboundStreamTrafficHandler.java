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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.ChannelOperations;
import xyz.nyist.core.Http3Exception;
import xyz.nyist.core.Http3HeadersFrame;
import xyz.nyist.http.server.Http3ServerOperations;

import java.net.SocketAddress;
import java.util.Queue;
import java.util.function.BiFunction;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Violeta Georgieva
 */
@Slf4j
public final class Http3InboundStreamTrafficHandler extends ChannelInboundHandlerAdapter {


    final BiFunction<ConnectionInfo, Http3HeadersFrame, ConnectionInfo> forwardedHeaderHandler;

    ConnectionObserver listener;

    ChannelHandlerContext ctx;


    Queue<Object> pipelined;

    SocketAddress remoteAddress;

    public Http3InboundStreamTrafficHandler(ConnectionObserver listener) {
        this.listener = listener;
        this.forwardedHeaderHandler = null;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        this.ctx = ctx;
        if (log.isDebugEnabled()) {
            log.debug(format(ctx.channel(), "New stream connection, requesting read"));
        }
        ctx.read();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Http3Exception {
        log.debug("{}收到消息:{}", ctx.channel(), msg.getClass().getSimpleName());
        if (remoteAddress == null) {
            remoteAddress = ctx.channel().remoteAddress();
//                    Optional.ofNullable(HAProxyMessageReader.resolveRemoteAddressFromProxyProtocol(ctx.channel()))
//                            .orElse(ctx.channel().remoteAddress());
        }
        // read message and track if it was keepAlive
        if (msg instanceof Http3HeadersFrame) {

            final Http3HeadersFrame request = (Http3HeadersFrame) msg;

            Connection conn = Connection.from(ctx.channel());


            Http3ServerOperations ops = new Http3ServerOperations(conn, listener, request,
                                                                  null,
                                                                  ConnectionInfo.from(ctx.channel(),
                                                                                      request,
                                                                                      forwardedHeaderHandler),
                                                                  Http3ServerFormDecoderProvider.DEFAULT_FORM_DECODER_SPEC,
                                                                  null,
                                                                  true
            );
            ops.bind();
            listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);

            ctx.fireChannelRead(msg);
            return;
        }

        ctx.fireChannelRead(msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
            Http3ServerOperations ops = (Http3ServerOperations) ChannelOperations.get(ctx.channel());
            if (ops != null) {
                if (log.isDebugEnabled()) {
                    log.debug(format(ops.channel(), "Remote peer sent WRITE_FIN."));
                }
                ctx.channel().config().setAutoRead(true);
                ops.onInboundComplete();
            }
        }
        ctx.fireUserEventTriggered(evt);
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error(cause.toString());
        super.exceptionCaught(ctx, cause);
    }

}
