package xyz.nyist.adapter;

import org.springframework.http.HttpHeaders;
import xyz.nyist.core.Http3HeadersFrame;

/**
 * @author: fucong
 * @Date: 2022/8/15 14:25
 * @Description:
 */
public class Http3HeadersAdapter extends HttpHeaders {


    public Http3HeadersAdapter(Http3HeadersFrame headersFrame) {
        super();
    }

}
