package xyz.nyist.test;

import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import xyz.nyist.core.Http3;
import xyz.nyist.http.client.Http3Client;
import xyz.nyist.http.client.Http3ClientOperations;

import java.net.InetSocketAddress;

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

        //QuicConnection client = (QuicConnection)
        Http3Client.create()
                .remoteAddress(() -> new InetSocketAddress("www.nyist.xyz", 443))
                .secure(context)
//                .handleStream((in, out) -> {
//                    Http3ClientOperations operations = (Http3ClientOperations) out;
//
//                    operations.receive()
//                            .asString()
//                            .defaultIfEmpty("Hello Empty!")
//                            .subscribe(System.out::println);
//
//                    return operations.sendObject(Unpooled.EMPTY_BUFFER);
//                })
                .sendHandler(http3ClientRequest -> {
                    Http3ClientOperations operations = (Http3ClientOperations) http3ClientRequest;
                    return operations.sendObject(Unpooled.EMPTY_BUFFER);
                })
                .response(http3ClientResponse -> {
                    System.out.println("response 执行");
                    Http3ClientOperations operations = (Http3ClientOperations) http3ClientResponse;
                    return operations.receive()
                            .asString()
                            .defaultIfEmpty("Hello Empty!")
                            .doOnNext(i -> System.out.println("33333" + i))
                            .then();
//                    operations.receive()
//                            .asString()
//                            .defaultIfEmpty("Hello Empty!")
//                            .subscribe(System.out::println);
//                    return null;
                })
                .executeNow();

//        client.createStream(
//                        (in, out) -> {
//                            Http3ClientOperations operations = (Http3ClientOperations) out;
//
//                            operations.receive()
//                                    .asString()
//                                    .defaultIfEmpty("Hello Empty!")
//                                    .doOnNext(System.out::println)
//                                    .subscribe();
//
//
//                            return operations.sendObject(Unpooled.EMPTY_BUFFER);
//                            // return out.sendString(Mono.just("123345\r\n"));
//                        })
//                .block(Duration.ofSeconds(15));
//
//        Thread.sleep(10000);

    }

}
