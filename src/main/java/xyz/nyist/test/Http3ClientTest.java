package xyz.nyist.test;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.util.CharsetUtil;
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
                .keylog(true)
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();
        //curl 'https://quic.cloud/wp-includes/js/wp-emoji-release.min.js?ver=6.0.1' -I --http3
        //https://www.cloudflare.com/favicon.ico
        //https://quic.tech:8443/quic.ico
        Http3Client.create()
                .remoteAddress(() -> new InetSocketAddress("quic.nginx.org", 443))
                .secure(context)
                .sendHandler(http3ClientRequest -> {
                    //Host: www.cloudflare.com
                    //> user-agent: curl/7.85.0-DEV
                    //> accept: */*
                    return http3ClientRequest.addHeader("host", "quic.nginx.org")
                            .addHeader("user-agent", "curl/7.85.0-DEV")
                            .addHeader("accept", "*/*")
                            .method(HttpMethod.GET)
                            .uri("/test").send(Mono.just(Unpooled.wrappedBuffer("api.toString()".getBytes(CharsetUtil.UTF_8))));
                    //.uri("/test").send(Mono.empty());
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
