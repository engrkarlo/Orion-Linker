package io.github.engrkarlo.orionbridge;
import java.nio.charset.StandardCharsets;
public final class BridgeWireSmoke {
    public static void main(String[] args)throws Exception{
        String id="1234567890abcdef",token="a".repeat(64);
        var request=BridgeWire.parse(String.join("\n",id,"GET","/v1/snapshot","","","","","",""),token);
        if(!request.id().equals(id)||!request.exchange().getRequestHeaders().getFirst("Authorization").equals("Bearer "+token))throw new AssertionError();
        request.exchange().sendResponseHeaders(200,2);request.exchange().getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
        if(!BridgeWire.response(id,200,request.exchange().body()).equals(id+"\n200\ne30"))throw new AssertionError();
        try{BridgeWire.parse(String.join("\n",id,"POST","/v3/pair","","","","","",""),token);throw new AssertionError("bootstrap over socket");}catch(IllegalArgumentException expected){}
        try{BridgeWire.parse("x".repeat(100000),token);throw new AssertionError("oversized");}catch(IllegalArgumentException expected){}
        try{request.exchange().getResponseBody().write(new byte[BridgeExchange.MAX_RESPONSE]);throw new AssertionError("oversized response");}catch(java.io.IOException expected){}
        System.out.println("BridgeWireSmoke passed");
    }
}
