package xyz.nyist.http.test;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.util.CharsetUtil;
import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.DisposableServer;
import reactor.netty.channel.ChannelOperations;
import xyz.nyist.core.DefaultHttp3Headers;
import xyz.nyist.core.Http3;
import xyz.nyist.core.Http3Headers;
import xyz.nyist.core.HttpConversionUtil;
import xyz.nyist.http.server.Http3Server;
import xyz.nyist.test.ChannelUtil;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * @author: fucong
 * @Date: 2022/7/29 13:56
 * @Description:
 */
@Slf4j
public class Http3ServerTest {

    public static void main(String[] args) throws Exception {

        SelfSignedCertificate cert = new SelfSignedCertificate();

        QuicSslContext serverCtx = QuicSslContextBuilder.forServer(cert.key(), null, cert.cert())
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();

        DisposableServer connection = Http3Server.create()
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handleStream((quicInbound, quicOutbound) -> {
                    ChannelOperations operations = (ChannelOperations) quicInbound;
                    //System.err.println(operations.getClass());
                    ChannelUtil.printChannel(operations.channel());

//                    return Mono.empty();
                    // return quicInbound.receive().flatMap(s -> {
                    // System.out.println("receive: " + s);
                    //HttpContent request = (HttpContent) s;
                    //ByteBuf content = request.content();
                    //System.out.println("content:" + content.toString(CharsetUtil.UTF_8));

                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 3; i++) {
                        sb.append("我是回应消息!");
                    }
                    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(sb.toString().getBytes(CharsetUtil.UTF_8)));

                    Http3Headers headers = HttpConversionUtil.toHttp3Headers(response, false);

                    Http3Headers http3Headers = new DefaultHttp3Headers();
                    http3Headers.status("ok");

//                        System.out.println(quicOutbound.getClass());
//                        NettyOutbound nettyOutbound = quicOutbound.sendObject(new DefaultHttp3HeadersFrame(headers));
//                        System.out.println(nettyOutbound.getClass());
//
//                        NettyOutbound object = nettyOutbound.sendObject(new DefaultHttp3DataFrame(response.content()));
//                        System.out.println(object.getClass());
//                        return object;
                    //return quicOutbound.sendObject(response);
                    return quicOutbound.sendObject(Unpooled.wrappedBuffer(sb.toString().getBytes(CharsetUtil.UTF_8)));
                    //});
                })
                .bindAddress(() -> new InetSocketAddress(NetUtil.LOCALHOST4, 8080))
                .wiretap(true)
                .secure(serverCtx)
                .idleTimeout(Duration.ofSeconds(5))
                .initialSettings(spec ->
                                         spec.maxData(10000000)
                                                 .maxStreamDataBidirectionalLocal(1000000)
                                                 .maxStreamDataBidirectionalRemote(1000000)
                                                 .maxStreamsBidirectional(100)
                                                 .maxStreamDataUnidirectional(1024)
                                                 .maxStreamsUnidirectional(3)
                ).bindNow();

        connection.onDispose().block();
    }

}
