package xyz.nyist.test;

import java.io.File;
import java.io.InputStream;

/**
 * @author: fucong
 * @Date: 2022/8/12 18:08
 * @Description:
 */
public class Test {

    public static void main(String[] args) throws Exception {
        File file = new File("/Users/fucong/IdeaProjects/netty-incubator-codec-http3/src/test/resources/www.nyist.xyz.pfx");
        System.out.println(file.length());
        InputStream inputStream = Test.class.getClassLoader().getResourceAsStream("www.nyist.xyz.pfx");
        System.out.println(inputStream.available());

    }

}
