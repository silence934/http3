package xyz.nyist.test;

import io.netty.buffer.Unpooled;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import xyz.nyist.core.Http3;
import xyz.nyist.http.server.Http3Server;
import xyz.nyist.http.server.Http3ServerOperations;

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
public class Http3ServerTest3 {

    public static void main(String[] args) throws Exception {

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(Files.newInputStream(Paths.get("/Users/fucong/IdeaProjects/netty-incubator-codec-http3/src/test/resources/www.nyist.xyz.pfx")), "fc2998820...".toCharArray());
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "fc2998820...".toCharArray());

        QuicSslContext serverCtx = QuicSslContextBuilder.forServer(keyManagerFactory, "fc2998820...")
                .keylog(true)
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();


        Http3Server.create()
                .disableQpackDynamicTable(true)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handleStream((http3ServerRequest, http3ServerResponse) -> {
                                  Http3ServerOperations operations = ((Http3ServerOperations) http3ServerResponse);
                                  operations.outboundHttpMessage().add("content-type", "text/plain");
                                  operations.outboundHttpMessage().add(new AsciiString("qwer"), new AsciiString("123"));
                                  operations.outboundHttpMessage().add("content-length", 14);
                                  String path = http3ServerRequest.fullPath();
                                  System.out.println(path);
                                  if ("/api".equals(path)) {
                                      return http3ServerResponse.send(Mono.just(Unpooled.wrappedBuffer("api.toString()".getBytes(CharsetUtil.UTF_8))));
                                  }
                                  // http3ServerRequest.requestHeaders().add("123", "456");
                                  return http3ServerResponse.send(Mono.empty());
                              }
                )
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
                ).bindNow().onDispose().block();
    }

}
