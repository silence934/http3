package xyz.nyist.http;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.CookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import xyz.nyist.core.Http3HeadersFrame;

import java.util.*;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * @author: fucong
 * @Date: 2022/8/12 16:30
 * @Description:
 */
public class Cookies {

    final static int NOT_READ = 0;

    final static int READING = 1;

    final static int READ = 2;

    static final AtomicIntegerFieldUpdater<Cookies> STATE =
            AtomicIntegerFieldUpdater.newUpdater(Cookies.class, "state");

    final Http3HeadersFrame nettyHeaders;

    final CharSequence cookiesHeaderName;

    final boolean isClientChannel;

    final CookieDecoder decoder;

    protected Map<CharSequence, Set<Cookie>> cachedCookies;

    volatile int state;

    protected Cookies(Http3HeadersFrame nettyHeaders, CharSequence cookiesHeaderName, boolean isClientChannel,
                      CookieDecoder decoder) {
        this.nettyHeaders = Objects.requireNonNull(nettyHeaders, "nettyHeaders");
        this.cookiesHeaderName = cookiesHeaderName;
        this.isClientChannel = isClientChannel;
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        cachedCookies = Collections.emptyMap();
    }

    /**
     * Return a new cookies holder from client response headers.
     *
     * @param headers client response headers
     * @return a new cookies holder from client response headers
     */
    public static Cookies newClientResponseHolder(Http3HeadersFrame headers, ClientCookieDecoder decoder) {
        return new Cookies(headers, HttpHeaderNames.SET_COOKIE, true, decoder);
    }

    /**
     * Return a new cookies holder from server request headers.
     *
     * @param headers server request headers
     * @return a new cookies holder from server request headers
     * @deprecated as of 1.0.8.
     * Prefer {@link ServerCookies#newServerRequestHolder(Http3HeadersFrame, ServerCookieDecoder)}.
     * This method will be removed in version 1.2.0.
     */
    @Deprecated
    public static Cookies newServerRequestHolder(Http3HeadersFrame headers, ServerCookieDecoder decoder) {
        return new Cookies(headers, HttpHeaderNames.COOKIE, false, decoder);
    }

    /**
     * Wait for the cookies to become available, cache them and subsequently return the cached map of cookies.
     *
     * @return the cached map of cookies
     */
    public Map<CharSequence, Set<Cookie>> getCachedCookies() {
        if (!markReadingCookies()) {
            for (; ; ) {
                if (hasReadCookies()) {
                    return cachedCookies;
                }
            }
        }

        List<CharSequence> allCookieHeaders = allCookieHeaders();
        Map<String, Set<Cookie>> cookies = new HashMap<>();
        for (CharSequence aCookieHeader : allCookieHeaders) {
            Set<Cookie> decode;
            if (isClientChannel) {
                final Cookie c = ((ClientCookieDecoder) decoder).decode(aCookieHeader.toString());
                if (c == null) {
                    continue;
                }
                Set<Cookie> existingCookiesOfName = cookies.computeIfAbsent(c.name(), k -> new HashSet<>());
                existingCookiesOfName.add(c);
            } else {
                decode = ((ServerCookieDecoder) decoder).decode(aCookieHeader.toString());
                for (Cookie cookie : decode) {
                    Set<Cookie> existingCookiesOfName = cookies.computeIfAbsent(cookie.name(), k -> new HashSet<>());
                    existingCookiesOfName.add(cookie);
                }
            }
        }
        cachedCookies = Collections.unmodifiableMap(cookies);
        markReadCookies();
        return cachedCookies;
    }

    protected List<CharSequence> allCookieHeaders() {
        return nettyHeaders.headers().getAll(cookiesHeaderName);
    }

    protected final boolean hasReadCookies() {
        return state == READ;
    }

    protected final boolean markReadCookies() {
        return STATE.compareAndSet(this, READING, READ);
    }

    protected final boolean markReadingCookies() {
        return STATE.compareAndSet(this, NOT_READ, READING);
    }

}
