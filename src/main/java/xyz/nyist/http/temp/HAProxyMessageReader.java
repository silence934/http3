package xyz.nyist.http.temp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.util.AttributeKey;
import reactor.netty.transport.AddressUtils;
import reactor.util.annotation.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * @author: fucong
 * @Date: 2022/7/31 22:40
 * @Description:
 */
public final class HAProxyMessageReader extends ChannelInboundHandlerAdapter {

    private static final AttributeKey<InetSocketAddress> REMOTE_ADDRESS_FROM_PROXY_PROTOCOL =
            AttributeKey.valueOf("remoteAddressFromProxyProtocol");

    private static final boolean hasProxyProtocol;

    static {
        boolean proxyProtocolCheck = true;
        try {
            Class.forName("io.netty.handler.codec.haproxy.HAProxyMessageDecoder");
        } catch (ClassNotFoundException cnfe) {
            proxyProtocolCheck = false;
        }
        hasProxyProtocol = proxyProtocolCheck;
    }

    static boolean hasProxyProtocol() {
        return hasProxyProtocol;
    }

    @Nullable
    public static SocketAddress resolveRemoteAddressFromProxyProtocol(Channel channel) {
        if (HAProxyMessageReader.hasProxyProtocol()) {
            return channel.attr(REMOTE_ADDRESS_FROM_PROXY_PROTOCOL).getAndSet(null);
        }

        return null;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HAProxyMessage) {
            HAProxyMessage proxyMessage = (HAProxyMessage) msg;
            if (proxyMessage.sourceAddress() != null && proxyMessage.sourcePort() != 0) {
                InetSocketAddress remoteAddress = AddressUtils
                        .createUnresolved(proxyMessage.sourceAddress(), proxyMessage.sourcePort());
                ctx.channel()
                        .attr(REMOTE_ADDRESS_FROM_PROXY_PROTOCOL)
                        .set(remoteAddress);
            }

            proxyMessage.release();

            ctx.channel()
                    .pipeline()
                    .remove(this);

            ctx.read();
        } else {
            super.channelRead(ctx, msg);
        }
    }

}
