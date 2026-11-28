package io.github.engrkarlo.orionbridge;

import com.sun.net.httpserver.Headers;
import java.util.Base64;
import java.util.Set;

/** Private protocol v3. Socket authentication never replaces per-request signatures. */
final class BridgeWire {
    static final int MAX_FRAME=96*1024;
    private static final Set<String> ROUTES=Set.of("/v1/snapshot","/v2/actions","/v2/verification","/v2/verification/batch","/v2/policy","/v2/chat","/v2/recovery","/v2/health","/v2/ranks","/v2/events","/v2/acknowledge");
    record Request(String id,BridgeExchange exchange){}
    static Request parse(String text,String token){
        if(text.length()>MAX_FRAME)throw new IllegalArgumentException("frame limit");
        String[] p=text.split("\\n",-1);
        if(p.length!=9||!p[0].matches("[a-f0-9]{16}")||!Set.of("GET","POST").contains(p[1])||!ROUTES.contains(p[2]))throw new IllegalArgumentException("invalid request");
        Headers h=new Headers();h.set("Authorization","Bearer "+token);
        if(p[2].equals("/v1/snapshot")){
            if(!p[1].equals("GET")||!p[3].isEmpty()||!p[4].isEmpty()||!p[5].isEmpty()||!p[6].isEmpty()||!p[7].isEmpty()||!p[8].isEmpty())throw new IllegalArgumentException("invalid snapshot");
        }else{
            if(!p[3].matches("[0-9]{17,20}")||!p[4].matches("[a-z0-9-]{1,64}")||!p[5].matches("[0-9]{1,16}")||!p[6].matches("[a-f0-9]{16,128}")||!p[7].matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid signature envelope");
            String[] names={"X-Orion-Guild","X-Orion-Server","X-Orion-Time","X-Orion-Nonce","X-Orion-Signature"};
            for(int i=0;i<names.length;i++)h.set(names[i],p[i+3]);
        }
        byte[] body=Base64.getUrlDecoder().decode(p[8]);
        if(body.length>65536)throw new IllegalArgumentException("body limit");
        h.set("Content-Length",Integer.toString(body.length));
        return new Request(p[0],new BridgeExchange(p[1],p[2],h,body));
    }
    static String response(String id,int status,byte[] body){return id+"\n"+status+"\n"+Base64.getUrlEncoder().withoutPadding().encodeToString(body);}
}
