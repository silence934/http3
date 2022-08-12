package xyz.nyist.http;

import io.netty.handler.codec.http.HttpVersion;

/**
 * @author: fucong
 * @Date: 2022/8/12 17:08
 * @Description:
 */
public class Http3Version extends HttpVersion {


    public static final Http3Version INSTANCE = new Http3Version();


    private Http3Version() {
        super("http", 3, 0, false);
    }

}
