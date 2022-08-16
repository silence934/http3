package xyz.nyist.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.DefaultChannelPromise;

/**
 * @author: fucong
 * @Date: 2022/8/12 14:42
 * @Description:
 */
public class CombinationChannelFuture extends DefaultChannelPromise {


    private CombinationChannelFuture(Channel channel) {
        super(channel);
    }

    public static CombinationChannelFuture create(ChannelFuture future1, ChannelFuture future2) {
        if (future1.channel() == future2.channel()) {
            return new SingleThreadCombinationChannelFuture(future1, future2);
        }
        //todo 两个future不是一个channel
        return null;
    }


    private static class SingleThreadCombinationChannelFuture extends CombinationChannelFuture {

        private final ChannelFuture future1;

        private final ChannelFuture future2;

        private SingleThreadCombinationChannelFuture(ChannelFuture future1, ChannelFuture future2) {
            super(future1.channel());
            this.future1 = future1;
            this.future2 = future2;


            future1.addListener((ChannelFutureListener) this::operationComplete);
            future2.addListener((ChannelFutureListener) this::operationComplete);
        }


        public void operationComplete(ChannelFuture future) {
            if (this.isDone()) {
                return;
            }
            if (future1.isSuccess() && future2.isSuccess()) {
                this.setSuccess();
            }

            if (future1.isDone()) {
                if (future1.isCancelled()) {
                    if (!future2.isDone() && future2.isCancellable()) {
                        future2.cancel(true);
                    }
                    this.cancel(true);
                } else if (!future1.isSuccess()) {
                    if (!future2.isDone() && future2.isCancellable()) {
                        future2.cancel(true);
                    }
                    this.setFailure(future1.cause());
                }
            } else if (future2.isDone()) {
                if (future2.isCancelled()) {
                    if (!future1.isDone() && future1.isCancellable()) {
                        future1.cancel(true);
                    }
                    this.cancel(true);
                } else if (!future2.isSuccess()) {
                    if (!future1.isDone() && future1.isCancellable()) {
                        future1.cancel(true);
                    }
                    this.setFailure(future2.cause());
                }
            }
        }

    }

}
