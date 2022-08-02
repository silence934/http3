package xyz.nyist.test;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.util.CharsetUtil;
import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.nyist.core.Http3;
import xyz.nyist.core.Http3ClientConnectionHandler;
import xyz.nyist.quic.QuicClient;
import xyz.nyist.quic.QuicConnection;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static reactor.netty.ConnectionObserver.State.CONNECTED;

/**
 * @author: fucong
 * @Date: 2022/7/6 18:10
 * @Description:
 */
@Slf4j
public class QuicClientTest {

    public static void main(String[] args) throws InterruptedException {

        QuicSslContext context = QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();

        QuicConnection client = QuicClient.create()
                .remoteAddress(() -> new InetSocketAddress(NetUtil.LOCALHOST4, 7777))
                .bindAddress(() -> new InetSocketAddress(0))
                .wiretap(true)
                .secure(context)
                .observe((conn, state) -> {
                    if (state == CONNECTED) {
                        conn.addHandlerLast(new Http3ClientConnectionHandler());
                    }
                })
                .streamObserve((conn, state) -> {
                    if (state == CONNECTED) {
                        //conn.addHandlerLast("xx", new ChannelOutboundHandlerAdapter());
                    }
                })
                .handleStream((quicInbound, quicOutbound) -> {
                    quicInbound.receive().subscribe();
                    return Mono.empty();
                })
                .idleTimeout(Duration.ofSeconds(5))
                .initialSettings(spec -> spec.maxData(10000000)
                        .maxStreamDataBidirectionalLocal(1000000)
                        .maxStreamDataUnidirectional(3)
                        .maxStreamsUnidirectional(1024)
                )
                .connectNow();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> response = new AtomicReference<>("");

        Flux.range(0, 1)
                .flatMap(i -> client.createStream((in, out) -> {
                             //ChannelOperations<QuicInbound, QuicOutbound> channelOperations = (ChannelOperations<QuicInbound, QuicOutbound>) in;
                             in.receive()
                                     .asString()
                                     .doOnNext(s -> {
                                         // ChannelUtil.printChannel(channelOperations.channel());
                                         response.set(s);
                                         latch.countDown();
                                     })
                                     .subscribe(s -> System.out.println(i + s));

                             DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello/world", Unpooled.wrappedBuffer("你好呀".getBytes(CharsetUtil.UTF_8)));
                             return out.sendObject(Mono.just(request));
                             //return out.sendString(Mono.just("request\r\n"));
                         })
                ).subscribe();

        boolean await = latch.await(30, TimeUnit.SECONDS);
        System.out.println("await:" + await);
        System.err.println(response.get());

    }

}
