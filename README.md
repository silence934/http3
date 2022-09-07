# Netty http3 codec

这是一个基于 [netty-incubator-codec-quic](https://github.com/netty/netty-incubator-codec-quic)
和[netty-incubator-codec-http3](https://github.com/netty/netty-incubator-codec-http3)
的响应式、高性能 WEB 容器.

## 如何使用它?

在test下有一些测试类以供参考<br/>
可以添加域名 **http3.nyist.xyz** 到**127.0.0.1**

## 如何校验是否成功使用了http3

### 1. chrome浏览器

在浏览器地址栏输入 chrome://flags 回车，搜索 “quic” 可以看到 “Experimental QUIC Protocol” 点击下拉框选择 “Enabled”。<br/>

mac os使用命令行增加如下启动参数强制访问使用http3：

```
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --quic-version=h3-29 --origin-to-force-quic-on=http3.nyist.xyz:443
```

关闭代理，打开chrome network，访问服务，观察Protocol

### 2. curl

**ubuntu 20.04**

系统自带的curl虽然有--http3选项，但是未启用，需要我们自己编译安装
https://github.com/curl/curl/blob/master/docs/HTTP3.md#quiche-version

#### 安装必要工具:

sudo -i

apt install cmake autoconf libtool golang cargo -y

#### Build quiche and BoringSSL:

```angular2html
% git clone  --recursive https://github.com/cloudflare/quiche
% cd quiche
% cargo build --package quiche --release --features ffi,pkg-config-meta,qlog
% mkdir quiche/deps/boringssl/src/lib
% ln -vnf $(find target/release -name libcrypto.a -o -name libssl.a) quiche/deps/boringssl/src/lib/
```

#### Build curl:

```angular2html
 % cd ..
% git clone  https://github.com/curl/curl -b curl-7_85_0
% cd curl
% autoreconf -fi
% ./configure LDFLAGS="-Wl,-rpath,$PWD/../quiche/target/release" --with-openssl=$PWD/../quiche/quiche/deps/boringssl/src --with-quiche=$PWD/../quiche/target/release --prxfix=/root/curl
% make
% make install
```

安装位置在 /root/curl/bin

## 在spring boot中使用

参考：[spring-boot-http3](https://github.com/silence934/spring-boot-http3)
