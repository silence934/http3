package xyz.nyist.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.channel.ChannelOperations;
import xyz.nyist.core.Http3Exception;
import xyz.nyist.http.client.Http3ClientOperations;

import static reactor.netty.ReactorNetty.format;

/**
 * @author: fucong
 * @Date: 2022/8/18 17:38
 * @Description:
 */
@Slf4j
public class Http3OutboundStreamTrafficHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Http3Exception {
        log.debug("{}收到消息:{}", ctx.channel(), msg.getClass().getSimpleName());
        ctx.fireChannelRead(msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
            Http3ClientOperations ops = (Http3ClientOperations) ChannelOperations.get(ctx.channel());
            if (ops != null) {
                if (log.isDebugEnabled()) {
                    log.debug(format(ctx.channel(), "Remote peer sent WRITE_FIN."));
                }
                ctx.channel().config().setAutoRead(true);
                ops.onInboundComplete();
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

}
