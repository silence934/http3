package xyz.nyist.test;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import reactor.core.publisher.Mono;
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

    public static void main(String[] args) {

        QuicSslContext context = QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();
        //curl 'https://quic.cloud/wp-includes/js/wp-emoji-release.min.js?ver=6.0.1' -I --http3
        Http3Client.create()
                .remoteAddress(() -> new InetSocketAddress("www.nyist.xyz", 443))
                .secure(context)
                .sendHandler(http3ClientRequest -> {
                    return http3ClientRequest.uri("/api").send(Mono.empty());
                })
                .response(http3ClientResponse -> {
                    Http3ClientOperations operations = (Http3ClientOperations) http3ClientResponse;
                    return operations.receive()
                            .asString()
                            .defaultIfEmpty("Hello Empty!")
                            .doOnNext(System.out::println)
                            .then();
                })
                .executeNow();


    }

}
