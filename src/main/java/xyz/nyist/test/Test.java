package xyz.nyist.test;

import reactor.core.publisher.Mono;
import xyz.nyist.core.Http3Exception;

import java.util.function.Consumer;

/**
 * @author: fucong
 * @Date: 2022/8/12 18:08
 * @Description:
 */
public class Test {

    public static void main(String[] args) throws Http3Exception {
        Mono.just("123").subscribe(new Consumer<String>() {
            @Override
            public void accept(String s) {
                System.out.println(s);
            }
        });
    }

}
