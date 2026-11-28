package io.github.engrkarlo.orionbridge;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class AuditEndpoint {
    private final AuditJournal journal;
    private final AuditAuth auth;
    AuditEndpoint(AuditJournal journal,AuditAuth auth){this.journal=journal;this.auth=auth;}
    void handle(HttpExchange ex)throws IOException{
        String path=ex.getRequestURI().getRawPath(),method=ex.getRequestMethod();
        if(ex.getRequestURI().getRawQuery()!=null||!(path.equals("/v2/events")||path.equals("/v2/acknowledge"))){ex.sendResponseHeaders(404,-1);return;}
        if(!(path.equals("/v2/events")?method.equals("GET"):method.equals("POST"))){ex.sendResponseHeaders(405,-1);return;}
        var h=ex.getRequestHeaders();String g=h.getFirst("X-Orion-Guild"),s=h.getFirst("X-Orion-Server");
        if(!auth.scope(g,s)){ex.sendResponseHeaders(401,-1);return;}
        byte[] body=ex.getRequestBody().readNBytes(4097);
        if(body.length>4096){ex.sendResponseHeaders(413,-1);return;}
        if(!auth.verify(method,path,g,s,h.getFirst("X-Orion-Time"),h.getFirst("X-Orion-Nonce"),h.getFirst("X-Orion-Signature"),body,System.currentTimeMillis())){ex.sendResponseHeaders(401,-1);return;}
        if(path.equals("/v2/events")){
            if(body.length!=0){ex.sendResponseHeaders(400,-1);return;}
            respond(ex,journal.batch());return;
        }
        List<UUID> ids=new ArrayList<>();
        try{
            for(String line:new String(body,StandardCharsets.UTF_8).split("\n")){
                if(!line.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")||ids.size()>=100)throw new IllegalArgumentException();
                ids.add(UUID.fromString(line));
            }
        }catch(IllegalArgumentException e){ex.sendResponseHeaders(400,-1);return;}
        respond(ex,"{\"acknowledged\":"+journal.acknowledge(ids)+"}");
    }
    private static void respond(HttpExchange ex,String json)throws IOException{
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);
    }
}
