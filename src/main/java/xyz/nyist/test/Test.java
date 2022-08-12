package xyz.nyist.test;

import io.netty.handler.codec.http.HttpHeaders;
import xyz.nyist.core.DefaultHttp3Headers;
import xyz.nyist.core.Http3Exception;
import xyz.nyist.core.Http3Headers;
import xyz.nyist.core.Http3Util;

/**
 * @author: fucong
 * @Date: 2022/8/12 18:08
 * @Description:
 */
public class Test {

    public static void main(String[] args) throws Http3Exception {
        Http3Headers headers = new DefaultHttp3Headers();
        HttpHeaders entries = Http3Util.addHttp3ToHttpHeaders(1, headers);
        System.out.println(entries);
    }

}
