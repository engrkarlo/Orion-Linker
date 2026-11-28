package io.github.engrkarlo.orionbridge;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
public final class SocketTransportSmoke {
    public static void main(String[] args)throws Exception{
        String token="a".repeat(64),id="1234567890abcdef";
        try(var http=new PullHttp("127.0.0.1",0,token,null,null,new SetupRequest(),"WEBSOCKET")){
            http.publish("{}",System.currentTimeMillis());
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var received=new CompletableFuture<String>();
            var ws=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+token).subprotocols("orionbridge.v3")
                .buildAsync(URI.create("ws://127.0.0.1:"+http.port()+"/v3/socket"),new WebSocket.Listener(){
                    StringBuilder buffer=new StringBuilder();
                    @Override public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last){buffer.append(data);if(last)received.complete(buffer.toString());socket.request(1);return null;}
                }).get(5,TimeUnit.SECONDS);
            ws.sendText(String.join("\n",id,"GET","/v1/snapshot","","","","","",""),true).get(3,TimeUnit.SECONDS);
            if(!received.get(5,TimeUnit.SECONDS).equals(id+"\n200\ne30"))throw new AssertionError("socket response");
            ws.abort();
            var reply=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+http.port()+"/v1/snapshot")).header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString());
            if(reply.statusCode()!=200||!reply.body().equals("{}"))throw new AssertionError("same port HTTP");
        }
        System.out.println("SocketTransportSmoke passed");
    }
}
