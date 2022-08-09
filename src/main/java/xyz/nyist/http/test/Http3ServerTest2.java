package xyz.nyist.http.test;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.util.NetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import xyz.nyist.adapter.ReactorHttp3HandlerAdapter;
import xyz.nyist.core.Http3;
import xyz.nyist.http.server.Http3Server;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * @author: fucong
 * @Date: 2022/7/29 13:56
 * @Description:
 */
@Slf4j
public class Http3ServerTest2 {

    public static void main(String[] args) throws Exception {

        SelfSignedCertificate cert = new SelfSignedCertificate();

        QuicSslContext serverCtx = QuicSslContextBuilder.forServer(cert.key(), null, cert.cert())
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();

        DisposableServer connection = Http3Server.create()
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handleStream(new ReactorHttp3HandlerAdapter(new HttpHandler() {
                    @Override
                    public Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response) {
                        return null;
                    }
                }))
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
