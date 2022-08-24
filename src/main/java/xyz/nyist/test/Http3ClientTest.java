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
        //https://www.cloudflare.com/favicon.ico
        //https://quic.tech:8443/quic.ico
        Http3Client.create()
                .remoteAddress(() -> new InetSocketAddress("www.cloudflare.com", 8443))
                .secure(context)
                .sendHandler(http3ClientRequest -> {
                    //Host: www.cloudflare.com
                    //> user-agent: curl/7.85.0-DEV
                    //> accept: */*
                    return http3ClientRequest.addHeader("host", "www.cloudflare.com")
                            .addHeader("user-agent", "curl/7.85.0-DEV")
                            .addHeader("accept", "*/*")
                            .uri("/favicon.ico").send(Mono.empty());
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
