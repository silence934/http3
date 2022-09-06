import io.netty.buffer.Unpooled;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import xyz.nyist.core.Http3;
import xyz.nyist.http.server.Http3Server;
import xyz.nyist.http.server.Http3ServerOperations;

import javax.net.ssl.KeyManagerFactory;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
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

        InputStream inputStream = Http3ServerTest.class.getClassLoader().getResourceAsStream("http3.nyist.xyz.pfx");
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(inputStream, "123456".toCharArray());
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "123456".toCharArray());

        QuicSslContext serverCtx = QuicSslContextBuilder.forServer(keyManagerFactory, "123456")
                .keylog(true)
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();


        File file = new File(Http3ServerTest.class.getClassLoader().getResource("logback.xml").getPath());

        Http3Server.create()
                .disableQpackDynamicTable(true)
//                .option(QuicChannelOption.SEGMENTED_DATAGRAM_PACKET_ALLOCATOR, new SegmentedDatagramPacketAllocator() {
//                    @Override
//                    public DatagramPacket newPacket(ByteBuf buffer, int segmentSize, InetSocketAddress remoteAddress) {
//                        return new io.netty.channel.unix.SegmentedDatagramPacket(buffer, segmentSize, remoteAddress);
//                    }
//                })
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handleStream((http3ServerRequest, http3ServerResponse) -> {
                                  Http3ServerOperations operations = ((Http3ServerOperations) http3ServerResponse);
                                  operations.outboundHttpMessage().add("content-type", "text/plain");
                                  //operations.outboundHttpMessage().add("content-length", 14);
                                  String path = http3ServerRequest.fullPath();
                                  if ("/api".equals(path)) {
                                      return http3ServerResponse.send(Mono.just(Unpooled.wrappedBuffer("api.toString()".getBytes(CharsetUtil.UTF_8))));
                                  } else if ("/file".equals(path)) {
                                      return http3ServerResponse.sendFile(file.toPath());
                                  }
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
