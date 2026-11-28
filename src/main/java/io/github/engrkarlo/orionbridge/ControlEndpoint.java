package io.github.engrkarlo.orionbridge;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ControlEndpoint {
    private final AuditAuth auth;
    private final LocalModeration moderation;
    private final VerificationGate verification;
    ControlEndpoint(AuditAuth auth,LocalModeration moderation,VerificationGate verification){this.auth=auth;this.moderation=moderation;this.verification=verification;}
    void handle(HttpExchange ex)throws IOException{
        String path=ex.getRequestURI().getRawPath(),method=ex.getRequestMethod();
        boolean linking=path.equals("/v2/verification");
        if(ex.getRequestURI().getRawQuery()!=null||!(linking||path.equals("/v2/actions"))||linking&&verification==null||!linking&&moderation==null){ex.sendResponseHeaders(404,-1);return;}
        if(!method.equals("POST")&&!(linking&&method.equals("GET"))){ex.sendResponseHeaders(405,-1);return;}
        var h=ex.getRequestHeaders();String g=h.getFirst("X-Orion-Guild"),s=h.getFirst("X-Orion-Server");
        if(!auth.scope(g,s)){ex.sendResponseHeaders(401,-1);return;}
        byte[] body=ex.getRequestBody().readNBytes(4097);
        if(body.length>4096){ex.sendResponseHeaders(413,-1);return;}
        if(!auth.verify(method,path,g,s,h.getFirst("X-Orion-Time"),h.getFirst("X-Orion-Nonce"),h.getFirst("X-Orion-Signature"),body,System.currentTimeMillis())){ex.sendResponseHeaders(401,-1);return;}
        if(linking){
            if(method.equals("GET")){if(body.length!=0){ex.sendResponseHeaders(400,-1);return;}respond(ex,verification.snapshot());}
            else respond(ex,"{\"result\":"+OrionBridge.quote(verification.action(new String(body,StandardCharsets.UTF_8)))+"}");
            return;
        }
        SanctionState.Case c;
        try{c=SanctionState.parse(new String(body,StandardCharsets.UTF_8));}catch(IllegalArgumentException e){ex.sendResponseHeaders(400,-1);return;}
        String result=moderation.apply(c);
        respond(ex,"{\"case\":"+OrionBridge.quote(c.id().toString())+",\"revision\":"+c.revision()+",\"result\":"+OrionBridge.quote(result)+"}");
    }
    private static void respond(HttpExchange ex,String json)throws IOException{
        byte[] response=json.getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,response.length);ex.getResponseBody().write(response);
    }
}
