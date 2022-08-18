package xyz.nyist.http.client;

import io.netty.handler.codec.http.cookie.Cookie;
import reactor.netty.http.client.HttpClient;
import xyz.nyist.core.Http3Headers;

import java.time.Duration;

/**
 * @author: fucong
 * @Date: 2022/8/18 14:56
 * @Description:
 */
public interface Http3ClientRequest extends Http3ClientInfos {

    /**
     * Add an outbound cookie
     *
     * @return this outbound
     */
    Http3ClientRequest addCookie(Cookie cookie);

    /**
     * Add an outbound http header, appending the value if the header is already set.
     *
     * @param name  header name
     * @param value header value
     * @return this outbound
     */
    Http3ClientRequest addHeader(CharSequence name, CharSequence value);

    /**
     * Set an outbound header, replacing any pre-existing value.
     *
     * @param name  headers key
     * @param value header value
     * @return this outbound
     */
    Http3ClientRequest header(CharSequence name, CharSequence value);

    /**
     * Set outbound headers from the passed headers. It will however ignore {@code
     * HOST} header key. Any pre-existing value for the passed headers will be replaced.
     *
     * @param headers a netty headers map
     * @return this outbound
     */
    Http3ClientRequest headers(Http3Headers headers);

    /**
     * Return true if redirected will be followed
     *
     * @return true if redirected will be followed
     */
    boolean isFollowRedirect();

    /**
     * Specifies the maximum duration allowed between each network-level read operation while reading a given response
     * (resolution: ms). In other words, {@link io.netty.handler.timeout.ReadTimeoutHandler} is added to the channel
     * pipeline after sending the request and is removed when the response is fully received.
     * If the {@code maxReadOperationInterval} is {@code null}, any previous setting will be removed and no
     * {@code maxReadOperationInterval} will be applied.
     * If the {@code maxReadOperationInterval} is less than {@code 1ms}, then {@code 1ms} will be the
     * {@code maxReadOperationInterval}.
     * The {@code maxReadOperationInterval} setting on {@link Http3ClientRequest} level overrides any
     * {@code maxReadOperationInterval} setting on {@link HttpClient} level.
     *
     * @param maxReadOperationInterval the maximum duration allowed between each network-level read operations
     *                                 (resolution: ms).
     * @return this outbound
     * @see io.netty.handler.timeout.ReadTimeoutHandler
     * @since 0.9.11
     */
    Http3ClientRequest responseTimeout(Duration maxReadOperationInterval);

}
