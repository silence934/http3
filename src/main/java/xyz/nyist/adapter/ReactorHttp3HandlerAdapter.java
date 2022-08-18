package xyz.nyist.adapter;

import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.HttpHeadResponseDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;
import xyz.nyist.core.Http3Exception;
import xyz.nyist.http.server.Http3ServerRequest;
import xyz.nyist.http.server.Http3ServerResponse;

import java.net.URISyntaxException;
import java.util.function.BiFunction;

/**
 * @author: fucong
 * @Date: 2022/8/2 18:38
 * @Description:
 */
@Slf4j
public class ReactorHttp3HandlerAdapter implements BiFunction<Http3ServerRequest, Http3ServerResponse, Mono<Void>> {


    private final HttpHandler httpHandler;


    public ReactorHttp3HandlerAdapter(HttpHandler httpHandler) {
        Assert.notNull(httpHandler, "HttpHandler must not be null");
        this.httpHandler = httpHandler;
    }


    @Override
    public Mono<Void> apply(Http3ServerRequest reactorRequest, Http3ServerResponse reactorResponse) {
        NettyDataBufferFactory bufferFactory = new NettyDataBufferFactory(reactorResponse.alloc());
        try {
            ReactorServerHttp3Request request = new ReactorServerHttp3Request(reactorRequest, bufferFactory);
            ServerHttpResponse response = new ReactorServerHttp3Response(reactorResponse, bufferFactory);

            if (request.getMethod() == HttpMethod.HEAD) {
                response = new HttpHeadResponseDecorator(response);
            }

            return this.httpHandler.handle(request, response)
                    .doOnError(ex -> log.error(request.getLogPrefix() + "Failed to complete: " + ex.getMessage()))
                    .doOnSuccess(aVoid -> log.debug(request.getLogPrefix() + "Handling completed"));
        } catch (URISyntaxException ex) {
            ex.printStackTrace();
            if (log.isDebugEnabled()) {
                log.warn("Failed to get request URI: " + ex.getMessage());
            }
            reactorResponse.status(HttpResponseStatus.BAD_REQUEST);
            return Mono.empty();
        } catch (Http3Exception e) {
            throw new RuntimeException(e);
        }
    }

}
