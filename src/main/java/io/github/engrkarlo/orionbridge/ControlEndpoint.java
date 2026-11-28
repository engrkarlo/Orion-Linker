package io.github.engrkarlo.orionbridge;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ControlEndpoint {
    private final AuditAuth auth;
    private final LocalModeration moderation;
    ControlEndpoint(AuditAuth auth,LocalModeration moderation){this.auth=auth;this.moderation=moderation;}
    void handle(HttpExchange ex)throws IOException{
        String path=ex.getRequestURI().getRawPath();
        if(!path.equals("/v2/actions")||ex.getRequestURI().getRawQuery()!=null){ex.sendResponseHeaders(404,-1);return;}
        if(!ex.getRequestMethod().equals("POST")){ex.sendResponseHeaders(405,-1);return;}
        var h=ex.getRequestHeaders();String g=h.getFirst("X-Orion-Guild"),s=h.getFirst("X-Orion-Server");
        if(!auth.scope(g,s)){ex.sendResponseHeaders(401,-1);return;}
        byte[] body=ex.getRequestBody().readNBytes(4097);
        if(body.length>4096){ex.sendResponseHeaders(413,-1);return;}
        if(!auth.verify("POST",path,g,s,h.getFirst("X-Orion-Time"),h.getFirst("X-Orion-Nonce"),h.getFirst("X-Orion-Signature"),body,System.currentTimeMillis())){ex.sendResponseHeaders(401,-1);return;}
        SanctionState.Case c;
        try{c=SanctionState.parse(new String(body,StandardCharsets.UTF_8));}catch(IllegalArgumentException e){ex.sendResponseHeaders(400,-1);return;}
        String result=moderation.apply(c);
        byte[] response=("{\"case\":"+OrionBridge.quote(c.id().toString())+",\"revision\":"+c.revision()+",\"result\":"+OrionBridge.quote(result)+"}").getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200,response.length);ex.getResponseBody().write(response);
    }
}
