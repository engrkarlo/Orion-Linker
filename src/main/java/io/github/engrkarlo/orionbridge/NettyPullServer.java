package io.github.engrkarlo.orionbridge;

import com.sun.net.httpserver.Headers;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.IdleStateEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Same allocation for HTTPS proxy requests and WSS upgrades. No Bukkit calls on IO threads. */
final class NettyPullServer implements AutoCloseable {
    @FunctionalInterface interface Dispatch{void handle(com.sun.net.httpserver.HttpExchange exchange)throws IOException;}
    private final EventLoopGroup loop;
    private final Channel listener;
    private final AtomicReference<Channel> socket=new AtomicReference<>();
    private final Set<Channel> children=ConcurrentHashMap.newKeySet();
    private final AtomicInteger connections=new AtomicInteger();
    private final AtomicLong lastSignal=new AtomicLong();
    private final String token;private final byte[] authorization;
    private final Executor workers;private final Dispatch dispatch;
    NettyPullServer(String bind,int port,String token,Executor workers,Dispatch dispatch)throws IOException{
        this.token=token;this.authorization=("Bearer "+token).getBytes(StandardCharsets.UTF_8);this.workers=workers;this.dispatch=dispatch;
        loop=new NioEventLoopGroup(1,(java.util.concurrent.ThreadFactory)r->{Thread t=new Thread(r,"OrionBridge-Network");t.setDaemon(true);return t;});
        Channel bound=null;
        try{
            ChannelFuture f=new ServerBootstrap().group(loop).channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG,32).childOption(ChannelOption.TCP_NODELAY,true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,new WriteBufferWaterMark(262144,2097152))
                .childHandler(new ChannelInitializer<SocketChannel>(){ @Override protected void initChannel(SocketChannel ch){
                    ch.pipeline().addLast(new IdleStateHandler(50,0,0),new HttpServerCodec(4096,8192,8192),new HttpObjectAggregator(65536),new Handler());
                }}).bind(new InetSocketAddress(bind,port)).awaitUninterruptibly();
            if(!f.isSuccess())throw new IOException("Listener bind failed",f.cause());
            bound=f.channel();
        }catch(IOException|RuntimeException|LinkageError e){loop.shutdownGracefully(0,1,TimeUnit.SECONDS);throw e;}
        listener=bound;
    }
    int port(){return ((InetSocketAddress)listener.localAddress()).getPort();}
    void signal(String lane){
        if(!Set.of("chat","verification","audit").contains(lane))return;
        Channel ch=socket.get();if(ch==null||!ch.isActive()||!ch.isWritable())return;
        long now=System.nanoTime(),previous=lastSignal.get();
        if(now-previous<100_000_000L||!lastSignal.compareAndSet(previous,now))return;
        ch.eventLoop().execute(()->{if(ch.isActive()&&ch.isWritable()&&socket.get()==ch)ch.writeAndFlush(new TextWebSocketFrame("EVENT\n"+lane));});
    }
    private boolean authorized(String value){return value!=null&&MessageDigest.isEqual(authorization,value.getBytes(StandardCharsets.UTF_8));}
    private final class Handler extends SimpleChannelInboundHandler<Object>{
        private boolean upgraded,busy,counted;
        private int pending;
        @Override public void channelActive(ChannelHandlerContext ctx)throws Exception{
            if(connections.incrementAndGet()>32){connections.decrementAndGet();ctx.close();return;}
            counted=true;children.add(ctx.channel());super.channelActive(ctx);
        }
        @Override public void channelInactive(ChannelHandlerContext ctx)throws Exception{
            if(counted){connections.decrementAndGet();children.remove(ctx.channel());}
            socket.compareAndSet(ctx.channel(),null);super.channelInactive(ctx);
        }
        @Override public void userEventTriggered(ChannelHandlerContext ctx,Object event)throws Exception{
            if(event instanceof IdleStateEvent){ctx.close();return;}super.userEventTriggered(ctx,event);
        }
        @Override protected void channelRead0(ChannelHandlerContext ctx,Object message){
            if(message instanceof FullHttpRequest request&&!upgraded){
                if(busy){ctx.close();return;}
                busy=true;
                if(!request.decoderResult().isSuccess()){http(ctx,400,new byte[0],null);return;}
                if(request.uri().equals("/v3/socket")){
                    if(!request.method().equals(HttpMethod.GET)||!authorized(request.headers().get(HttpHeaderNames.AUTHORIZATION))){http(ctx,401,new byte[0],null);return;}
                    if(!"orionbridge.v3".equals(request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL))||!socket.compareAndSet(null,ctx.channel())){http(ctx,409,new byte[0],null);return;}
                    var handshaker=new WebSocketServerHandshakerFactory("ws://localhost/v3/socket","orionbridge.v3",false,BridgeWire.MAX_FRAME).newHandshaker(request);
                    if(handshaker==null){socket.compareAndSet(ctx.channel(),null);http(ctx,426,new byte[0],null);return;}
                    handshaker.handshake(ctx.channel(),request).addListener(f->{if(f.isSuccess())upgraded=true;else ctx.close();});
                    return;
                }
                try{
                    Headers headers=new Headers();
                    for(var entry:request.headers())headers.add(entry.getKey(),entry.getValue());
                    byte[] body=new byte[request.content().readableBytes()];request.content().getBytes(request.content().readerIndex(),body);
                    BridgeExchange exchange=new BridgeExchange(request.method().name(),request.uri(),headers,body);
                    workers.execute(()->{
                        try{dispatch.handle(exchange);ctx.executor().execute(()->http(ctx,exchange.getResponseCode(),exchange.body(),exchange.getResponseHeaders()));}
                        catch(Exception e){ctx.executor().execute(()->http(ctx,500,new byte[0],null));}
                    });
                }catch(RejectedExecutionException e){http(ctx,503,new byte[0],null);}catch(IllegalArgumentException e){http(ctx,400,new byte[0],null);}
                return;
            }
            if(!upgraded){ctx.close();return;}
            if(message instanceof PingWebSocketFrame ping){ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));return;}
            if(message instanceof PongWebSocketFrame)return;
            if(message instanceof CloseWebSocketFrame){ctx.close();return;}
            if(!(message instanceof TextWebSocketFrame frame)||!frame.isFinalFragment()||pending>=8||!ctx.channel().isWritable()){ctx.close();return;}
            BridgeWire.Request request;
            try{request=BridgeWire.parse(frame.text(),token);}catch(IllegalArgumentException e){ctx.close();return;}
            pending++;
            try{workers.execute(()->{
                int status;byte[] body;
                try{dispatch.handle(request.exchange());status=request.exchange().getResponseCode();body=request.exchange().body();}
                catch(Exception e){status=500;body=new byte[0];}
                String response=BridgeWire.response(request.id(),status,body);
                ctx.executor().execute(()->{pending--;if(!ctx.channel().isActive())return;if(!ctx.channel().isWritable()){ctx.close();return;}ctx.writeAndFlush(new TextWebSocketFrame(response));});
            });}catch(RejectedExecutionException e){pending--;ctx.writeAndFlush(new TextWebSocketFrame(BridgeWire.response(request.id(),503,new byte[0])));}
        }
        @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable cause){ctx.close();}
    }
    private static void http(ChannelHandlerContext ctx,int status,byte[] body,Headers headers){
        if(!ctx.channel().isActive())return;
        FullHttpResponse response=new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.valueOf(status),Unpooled.wrappedBuffer(body));
        if(headers!=null)headers.forEach((name,values)->{if(!name.equalsIgnoreCase("Content-Length")&&!name.equalsIgnoreCase("Connection"))response.headers().set(name,values);});
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH,body.length).set(HttpHeaderNames.CONNECTION,HttpHeaderValues.CLOSE).set(HttpHeaderNames.CACHE_CONTROL,"no-store");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
    @Override public void close(){listener.close().awaitUninterruptibly();for(Channel child:children)child.close();loop.shutdownGracefully(0,1,TimeUnit.SECONDS);}
}
