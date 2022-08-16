package xyz.nyist.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.incubator.codec.quic.QuicConnectionEvent;

/**
 * @author: fucong
 * @Date: 2022/8/16 21:12
 * @Description:
 */
public class ConnectionChangeHandler extends ChannelInboundHandlerAdapter {

    private QuicConnectionEvent connection;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof QuicConnectionEvent) {
            this.connection = (QuicConnectionEvent) evt;
        }
        super.userEventTriggered(ctx, evt);
    }

    public QuicConnectionEvent getConnection() {
        return connection;
    }

}
