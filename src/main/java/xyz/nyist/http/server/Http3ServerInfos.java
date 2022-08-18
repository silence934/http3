package xyz.nyist.http.server;

import io.netty.handler.codec.http.cookie.Cookie;
import xyz.nyist.http.Http3StreamInfo;

import java.util.List;
import java.util.Map;

/**
 * @author: fucong
 * @Date: 2022/8/18 14:54
 * @Description:
 */
public interface Http3ServerInfos extends Http3StreamInfo {

    /**
     * Returns resolved HTTP cookies. As opposed to {@link #cookies()}, this
     * returns all cookies, even if they have the same name.
     *
     * @return Resolved HTTP cookies
     */
    Map<CharSequence, List<Cookie>> allCookies();

}
