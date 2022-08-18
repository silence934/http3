package xyz.nyist.http.client;

import reactor.core.publisher.Mono;
import xyz.nyist.core.Http3HeadersFrame;

/**
 * @author: fucong
 * @Date: 2022/8/18 14:57
 * @Description:
 */
public interface Http3ClientResponse extends Http3ClientInfos {

    /**
     * Return response HTTP headers.
     *
     * @return response HTTP headers.
     */
    Http3HeadersFrame responseHeaders();

    /**
     * Return the resolved HTTP Response Status.
     *
     * @return the resolved HTTP Response Status
     */
    CharSequence status();

    /**
     * Return response trailer headers.
     *
     * @return response trailer headers.
     * @since 1.0.12
     */
    Mono<Http3HeadersFrame> trailerHeaders();

}
