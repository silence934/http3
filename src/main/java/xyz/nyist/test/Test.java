package xyz.nyist.test;

import xyz.nyist.core.Http3Exception;

/**
 * @author: fucong
 * @Date: 2022/8/12 18:08
 * @Description:
 */
public class Test {

    public static void main(String[] args) throws Http3Exception {
        long a = 2879572594025284834L;
        System.out.println(0x21);
        System.out.println(2879572594025284834L & 0x21);
        System.out.println(Long.toBinaryString(512520635826812275L));


        System.out.println(Integer.toBinaryString(0x21));
        System.out.println(Integer.toBinaryString(0x1f));
        System.out.println(Integer.toBinaryString(0x1f * 3));
        System.out.println(Integer.toBinaryString(0x1f * 4));
        System.out.println(Integer.toBinaryString(0x1f * 5));

        System.out.println(512520635826812275L - 33);
        System.out.println(16532923736348782L * 31);
    }

}
