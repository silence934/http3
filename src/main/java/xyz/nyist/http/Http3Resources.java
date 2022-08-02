package xyz.nyist.http;

import reactor.netty.resources.LoopResources;
import xyz.nyist.quic.QuicResources;

/**
 * @author: fucong
 * @Date: 2022/7/29 11:14
 * @Description:
 */
public class Http3Resources extends QuicResources {

    protected Http3Resources(LoopResources defaultLoops) {
        super(defaultLoops);
    }

}
