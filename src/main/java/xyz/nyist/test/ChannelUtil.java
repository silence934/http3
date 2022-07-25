package xyz.nyist.test;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultChannelPipeline;

import java.lang.reflect.Field;

/**
 * @author: fucong
 * @Date: 2022/7/22 14:24
 * @Description:
 */
public class ChannelUtil {

    private static final Class<?> CHANNEL_PIPELINE_CLASS = DefaultChannelPipeline.class;

    private static final Class<?> HANDLER_CONTEXT_CLASS;

    static {
        try {
            HANDLER_CONTEXT_CLASS = Class.forName("io.netty.channel.AbstractChannelHandlerContext");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }


    public static String printChannel(Channel channel) {
        try {
            System.out.println("=========" + channel + "==========");
            DefaultChannelPipeline pipeline = (DefaultChannelPipeline) channel.pipeline();
            Field head = CHANNEL_PIPELINE_CLASS.getDeclaredField("head");
            head.setAccessible(true);
            ChannelHandlerContext context = (ChannelHandlerContext) head.get(pipeline);
            print(context, 1);
            System.out.println("===================");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "ok";
    }


    private static void print(ChannelHandlerContext context, int i) throws Exception {
        if (context == null) {
            return;
        }
        System.out.println(i + ". " + context.name() + "  " + context.handler().getClass().getSimpleName() + " " + context.handler().hashCode());
        if (HANDLER_CONTEXT_CLASS.isAssignableFrom(context.getClass())) {
            Field next = HANDLER_CONTEXT_CLASS.getDeclaredField("next");
            next.setAccessible(true);
            print((ChannelHandlerContext) next.get(context), i + 1);
        }

    }

}
