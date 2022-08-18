package xyz.nyist.http.client;

import io.netty.channel.Channel;
import io.netty.resolver.AddressResolverGroup;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.TransportConfig;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import java.io.IOException;
import java.net.BindException;
import java.net.SocketAddress;
import java.util.function.Supplier;

import static reactor.netty.ReactorNetty.format;

/**
 * @author: fucong
 * @Date: 2022/8/17 17:49
 * @Description:
 */
@Slf4j
class Http3ConnectionProvider implements ConnectionProvider {

    //todo
    @Override
    public Mono<? extends Connection> acquire(TransportConfig config, ConnectionObserver connectionObserver, Supplier<? extends SocketAddress> remoteAddress, AddressResolverGroup<?> resolverGroup) {

        Mono<? extends Connection> mono = Mono.create(sink -> {

//            DisposableConnect disposableConnect = new DisposableConnect(sink, config.bindAddress());
//            TransportConnector.bind(config, config.parentChannelInitializer(), local, false)
//                    .subscribe(disposableConnect);
        });

//        if (config.doOnConnect() != null) {
//            mono = mono.doOnSubscribe(s -> config.doOnConnect.accept(config));
//        }

        return mono;
    }

    static final class DisposableConnect implements CoreSubscriber<Channel>, Disposable {

        final MonoSink<Connection> sink;

        final Context currentContext;

        final Supplier<? extends SocketAddress> bindAddress;

        Subscription subscription;

        DisposableConnect(MonoSink<Connection> sink, @Nullable Supplier<? extends SocketAddress> bindAddress) {
            this.sink = sink;
            this.currentContext = Context.of(sink.contextView());
            this.bindAddress = bindAddress;
        }

        @Override
        public Context currentContext() {
            return currentContext;
        }

        @Override
        public void dispose() {
            subscription.cancel();
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable t) {
            if (bindAddress != null && (t instanceof BindException ||
                    // With epoll/kqueue transport it is
                    // io.netty.channel.unix.Errors$NativeIoException: bind(..) failed: Address already in use
                    (t instanceof IOException && t.getMessage() != null &&
                            t.getMessage().contains("bind(..)")))) {
                sink.error(ChannelBindException.fail(bindAddress.get(), null));
            } else {
                sink.error(t);
            }
        }

        @Override
        public void onNext(Channel channel) {
            if (log.isDebugEnabled()) {
                log.debug(format(channel, "Connected new channel"));
            }
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (Operators.validate(subscription, s)) {
                this.subscription = s;
                sink.onCancel(this);
                s.request(Long.MAX_VALUE);
            }
        }

    }


}
