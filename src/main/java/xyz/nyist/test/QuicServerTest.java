package xyz.nyist.test;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.CharsetUtil;
import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.Connection;
import xyz.nyist.http3.Http3;
import xyz.nyist.http3.Http3FrameToHttpObjectCodec;
import xyz.nyist.http3.Http3ServerConnectionHandler;
import xyz.nyist.quic.QuicServer;

import java.net.InetSocketAddress;
import java.time.Duration;

import static reactor.netty.ConnectionObserver.State.CONNECTED;

/**
 * @author: fucong
 * @Date: 2022/7/6 17:50
 * @Description:
 */
@Slf4j
public class QuicServerTest {

    public static void main(String[] args) throws Exception {

        SelfSignedCertificate cert = new SelfSignedCertificate();

        QuicSslContext serverCtx = QuicSslContextBuilder.forServer(cert.key(), null, cert.cert())
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();


        Connection connection = QuicServer.create()
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .observe((conn, state) -> {
                    if (state == CONNECTED) {
                        conn.addHandlerLast(new Http3ServerConnectionHandler(
                                new ChannelInitializer<QuicStreamChannel>() {
                                    @Override
                                    protected void initChannel(QuicStreamChannel ch) {
                                        ch.pipeline()
                                                .addLast(new Http3FrameToHttpObjectCodec(true, false))
                                                .addLast("aggregator", new HttpObjectAggregator(512 * 1024))
                                                .remove(this);
                                    }
                                }));
                        //  ChannelUtil.printChannel(conn.channel());
                    }
                })
//                .streamObserve((conn, state) -> {
//                    System.err.println(state);
//                    if (state == CONNECTED) {
//                        conn.addHandlerLast("123handler", new ChannelInboundHandlerAdapter() {
//                            @Override
//                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//                                //ctx.pipeline().fireChannelRead();
//                                System.out.println("收到消息");
//                                super.channelRead(ctx, msg);
//                            }
//                        });
//                        conn.addHandlerLast(new Http3ServerConnectionHandler(
//                                new ChannelInitializer<QuicStreamChannel>() {
//                                    @Override
//                                    protected void initChannel(QuicStreamChannel ch) {
//                                        ch.pipeline()
//                                                .addLast(new Http3FrameToHttpObjectCodec(true, false))
//                                                .addLast("aggregator", new HttpObjectAggregator(512 * 1024));
//                                    }
//                                }));
//                        // conn.addHandlerLast(new LineBasedFrameDecoder(1024));
//                        conn.addHandlerLast("456handler", new ChannelInboundHandlerAdapter() {
//                            @Override
//                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//                                System.out.println("msg:" + msg);
//                                super.channelRead(ctx, msg);
//                            }
//                        });
//                        System.out.println("yyyy");
//                        ChannelUtil.printChannel(conn.channel());
//                        // conn.addHandlerLast(new Http3FrameToHttpObjectCodec(true, false)).addHandlerLast("aggregator", new HttpObjectAggregator(512 * 1024));
//                    }
//                })
                .handleStream((quicInbound, quicOutbound) -> {
                    quicInbound.receive()
                            .asString()
                            .subscribe(s -> System.out.println("receive: " + s));

                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 3; i++) {
                        sb.append("我是回应消息!");
                    }

                    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                                                            Unpooled.wrappedBuffer(sb.toString().getBytes(CharsetUtil.UTF_8)));

                    return quicOutbound.sendObject(response);
                })
                .bindAddress(() -> new InetSocketAddress(NetUtil.LOCALHOST4, 7777))
                .wiretap(true)
                .secure(serverCtx)
                .idleTimeout(Duration.ofSeconds(5))
                .initialSettings(spec ->
                                         spec.maxData(10000000)
                                                 .maxStreamDataBidirectionalLocal(1000000)
                                                 .maxStreamDataBidirectionalRemote(1000000)
                                                 .maxStreamsBidirectional(100)
                                                 .maxStreamDataUnidirectional(3)
                                                 .maxStreamsUnidirectional(1024)
                )

                .bindNow();
        connection.onDispose().block();
    }

}
