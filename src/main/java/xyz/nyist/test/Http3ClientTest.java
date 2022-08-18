package xyz.nyist.test;

import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamType;
import xyz.nyist.core.DefaultHttp3HeadersFrame;
import xyz.nyist.core.Http3;
import xyz.nyist.core.Http3HeadersFrame;
import xyz.nyist.http.client.Http3Client;
import xyz.nyist.http.client.Http3ClientOperations;
import xyz.nyist.quic.QuicConnection;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Consumer;

import static reactor.netty.ConnectionObserver.State.CONNECTED;

/**
 * @author: fucong
 * @Date: 2022/8/18 13:45
 * @Description:
 */
public class Http3ClientTest {

    public static void main(String[] args) throws Exception {

        QuicSslContext context = QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();

        QuicConnection client = (QuicConnection) Http3Client.create()
                .remoteAddress(() -> new InetSocketAddress("www.nyist.xyz", 443))
                .bindAddress(() -> new InetSocketAddress(0))
                .wiretap(true)
                .secure(context)
                .observe((conn, state) -> {
                    if (state == CONNECTED) {
                        // conn.addHandlerLast(new Http3ClientConnectionHandler());
                    }
                })
                .streamObserve((conn, state) -> {
                    if (state == CONNECTED) {
                        //conn.addHandlerLast("xx", new ChannelOutboundHandlerAdapter());
                    }
                })
//                .handleStream((quicInbound, quicOutbound) -> {
//                    quicInbound.receive().subscribe();
//                    return Mono.empty();
//                })
                .idleTimeout(Duration.ofSeconds(5))
                .initialSettings(spec -> spec.maxData(10000000)
                        .maxStreamDataBidirectionalLocal(1000000)
                        .maxStreamDataUnidirectional(3)
                        .maxStreamsUnidirectional(1024)
                )
                .connectNow();


        client.createStream(
                        QuicStreamType.BIDIRECTIONAL,
                        (in, out) -> {
                            Http3ClientOperations operations = (Http3ClientOperations) out;

                            operations.receive()
                                    .asString()
                                    .defaultIfEmpty("Hello Empty!")
                                    .doOnNext(System.out::println)
                                    .subscribe();

                            Http3HeadersFrame frame = new DefaultHttp3HeadersFrame();
                            frame.headers().method("GET").path("/api")
                                    .authority("www.nyist.xyz:443")
                                    .scheme("https");
                            return operations.sendObject(Unpooled.EMPTY_BUFFER);
                            // return out.sendString(Mono.just("123345\r\n"));
                        })
                .doOnError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        throwable.printStackTrace();
                    }
                })
                .block(Duration.ofSeconds(15));

        Thread.sleep(10000);

    }

}
