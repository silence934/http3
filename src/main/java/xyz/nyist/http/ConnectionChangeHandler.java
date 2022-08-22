package xyz.nyist.http;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.incubator.codec.quic.QuicConnectionEvent;

import java.net.SocketAddress;

/**
 * @author: fucong
 * @Date: 2022/8/16 21:12
 * @Description:
 */
public class ConnectionChangeHandler extends ChannelDuplexHandler {

    private SocketAddress remoteAddress;


    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof QuicConnectionEvent) {
            QuicConnectionEvent connection = (QuicConnectionEvent) evt;
            remoteAddress = connection.newAddress();
        }
        super.userEventTriggered(ctx, evt);
    }

//    @Override
//    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
//        this.remoteAddress = remoteAddress;
//        super.connect(ctx, remoteAddress, localAddress, promise);
//    }

    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

}
