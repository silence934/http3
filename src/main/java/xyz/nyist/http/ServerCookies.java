package xyz.nyist.http;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import xyz.nyist.core.Http3HeadersFrame;

import java.util.*;

/**
 * @author: fucong
 * @Date: 2022/8/12 16:29
 * @Description:
 */
public class ServerCookies extends Cookies {

    final ServerCookieDecoder serverCookieDecoder;

    Map<CharSequence, List<Cookie>> allCachedCookies;

    ServerCookies(Http3HeadersFrame headersFrame, CharSequence cookiesHeaderName, boolean isClientChannel, ServerCookieDecoder decoder) {
        super(headersFrame, cookiesHeaderName, isClientChannel, decoder);
        this.serverCookieDecoder = decoder;
        allCachedCookies = Collections.emptyMap();
    }

    /**
     * Return a new cookies holder from server request headers.
     *
     * @param headersFrame server request
     * @return a new cookies holder from server request headers
     */
    public static ServerCookies newServerRequestHolder(Http3HeadersFrame headersFrame, ServerCookieDecoder decoder) {
        return new ServerCookies(headersFrame, HttpHeaderNames.COOKIE, false, decoder);
    }

    @Override
    public Map<CharSequence, Set<Cookie>> getCachedCookies() {
        getAllCachedCookies();
        return cachedCookies;
    }

    /**
     * Wait for the cookies to become available, cache them and subsequently return the cached map of cookies.
     * As opposed to {@link #getCachedCookies()}, this returns all cookies, even if they have the same name.
     *
     * @return the cached map of cookies
     */
    public Map<CharSequence, List<Cookie>> getAllCachedCookies() {
        if (!markReadingCookies()) {
            for (; ; ) {
                if (hasReadCookies()) {
                    return allCachedCookies;
                }
            }
        }

        List<CharSequence> allCookieHeaders = allCookieHeaders();
        Map<String, Set<Cookie>> cookies = new HashMap<>();
        Map<String, List<Cookie>> allCookies = new HashMap<>();
        for (CharSequence aCookieHeader : allCookieHeaders) {
            List<Cookie> decode = serverCookieDecoder.decodeAll(aCookieHeader.toString());
            for (Cookie cookie : decode) {
                Set<Cookie> existingCookiesOfNameSet = cookies.computeIfAbsent(cookie.name(), k -> new HashSet<>());
                existingCookiesOfNameSet.add(cookie);
                List<Cookie> existingCookiesOfNameList = allCookies.computeIfAbsent(cookie.name(), k -> new ArrayList<>());
                existingCookiesOfNameList.add(cookie);
            }
        }
        cachedCookies = Collections.unmodifiableMap(cookies);
        allCachedCookies = Collections.unmodifiableMap(allCookies);
        markReadCookies();
        return allCachedCookies;
    }

}
