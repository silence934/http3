package xyz.nyist.http.client;

import reactor.netty.http.client.HttpClient;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import reactor.util.context.ContextView;
import xyz.nyist.core.Http3HeadersFrame;
import xyz.nyist.http.Http3StreamInfo;

/**
 * @author: fucong
 * @Date: 2022/8/18 14:55
 * @Description:
 */
public interface Http3ClientInfos extends Http3StreamInfo {

    /**
     * Return the current {@link Context} associated with the Mono/Flux exposed
     * via {@link HttpClient.ResponseReceiver#response()} or related terminating API.
     *
     * @return the current user {@link Context}
     * @deprecated Use {@link #currentContextView()}. This method
     * will be removed in version 1.1.0.
     */
    @Deprecated
    Context currentContext();

    /**
     * Return the current {@link ContextView} associated with the Mono/Flux exposed
     * via {@link HttpClient.ResponseReceiver#response()} or related terminating API.
     *
     * @return the current user {@link ContextView}
     * @since 1.0.0
     */
    ContextView currentContextView();

    /**
     * Return the previous redirections or empty array
     *
     * @return the previous redirections or empty array
     */
    String[] redirectedFrom();

    /**
     * Return outbound headers to be sent
     *
     * @return outbound headers to be sent
     */
    Http3HeadersFrame requestHeaders();

    /**
     * Return the fully qualified URL of the requested resource. In case of redirects, return the URL the last
     * redirect led to.
     *
     * @return The URL of the retrieved resource. This method can return null in case there was an error before the
     * client could create the URL
     */
    @Nullable
    String resourceUrl();

}
