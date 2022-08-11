package xyz.nyist.http.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.http.*;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.FutureMono;
import reactor.netty.channel.ChannelOperations;
import xyz.nyist.core.DefaultHttp3Headers;
import xyz.nyist.core.Http3;
import xyz.nyist.core.Http3Headers;
import xyz.nyist.core.HttpConversionUtil;
import xyz.nyist.http.server.Http3Server;

import javax.net.ssl.KeyManagerFactory;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.time.Duration;

/**
 * @author: fucong
 * @Date: 2022/7/29 13:56
 * @Description:
 */
@Slf4j
public class Http3ServerTest {

    public static void main(String[] args) throws Exception {

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(Files.newInputStream(Paths.get("/Users/fucong/IdeaProjects/netty-incubator-codec-http3/src/test/resources/www.nyist.xyz.pfx")), "fc2998820...".toCharArray());
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "fc2998820...".toCharArray());

        QuicSslContext serverCtx = QuicSslContextBuilder.forServer(keyManagerFactory, "fc2998820...")
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();

        DisposableServer connection = Http3Server.create()
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handleStream((quicInbound, quicOutbound) -> {

                    //System.err.println(operations.getClass());
                    //  ChannelUtil.printChannel(operations.channel());

//                    return Mono.empty();
                    return quicInbound.receiveObject().flatMap(s -> {
                        ChannelOperations operations = (ChannelOperations) quicInbound;
                        System.out.println("receive: " + s);
                        HttpContent request = (HttpContent) s;
                        ByteBuf content = request.content();
                        System.out.println("content:" + content.toString(CharsetUtil.UTF_8));

                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 3; i++) {
                            sb.append("我是回应消息!");
                        }
                        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(sb.toString().getBytes(CharsetUtil.UTF_8)));

                        Http3Headers headers = HttpConversionUtil.toHttp3Headers(response, false);

                        Http3Headers http3Headers = new DefaultHttp3Headers();
                        http3Headers.status("ok");

                        HttpContent httpContent = new DefaultHttpContent(Unpooled.wrappedBuffer(sb.toString().getBytes(CharsetUtil.UTF_8)));
                        //HttpContent httpContent = new DefaultLastHttpContent(Unpooled.wrappedBuffer(sb.toString().getBytes(CharsetUtil.UTF_8)));

                        Channel channel = operations.channel();


                        Mono<Void> a = Mono.create(sink -> {
                            channel.writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK), new DefaultChannelPromise(channel))
                                    .addListener(t -> {
                                        if (t.isSuccess()) {
                                            System.out.println("发送消息成功");
                                            sink.success();
                                        } else {
                                            sink.error(new Exception("发送消息失败"));
                                        }
                                    });
                        });
                        Mono<Void> voidMono = FutureMono.deferFuture(() -> {
                            System.out.println("voidMono");
                            return channel.writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK), new DefaultChannelPromise(channel));
                        });

                        Mono<Void> sendObject = FutureMono.deferFuture(() -> {
                            System.out.println("sendObject");
                            return channel
                                    .writeAndFlush(httpContent);
                        });
                        //return a.thenEmpty(sendObject);
                        //return quicOutbound.sendObject(Unpooled.wrappedBuffer(sb.toString().getBytes(CharsetUtil.UTF_8)));
                        return quicOutbound.sendObject(httpContent);
                    });
                })
                .bindAddress(() -> new InetSocketAddress("0.0.0.0", 443))
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
