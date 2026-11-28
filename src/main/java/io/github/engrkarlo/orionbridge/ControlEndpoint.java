package io.github.engrkarlo.orionbridge;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ControlEndpoint {
    private final AuditAuth auth;
    private final LocalModeration moderation;
    private final VerificationGate verification;
    private final OrionBridge plugin;
    ControlEndpoint(AuditAuth auth,LocalModeration moderation,VerificationGate verification,OrionBridge plugin){this.auth=auth;this.moderation=moderation;this.verification=verification;this.plugin=plugin;}
    void handle(HttpExchange ex)throws IOException{
        String path=ex.getRequestURI().getRawPath(),method=ex.getRequestMethod();
        boolean batch=path.equals("/v2/verification/batch");
        boolean linking=batch||path.equals("/v2/verification");
        boolean policy=path.equals("/v2/policy");
        boolean chat=path.equals("/v2/chat");
        boolean recovery=path.equals("/v2/recovery"),health=path.equals("/v2/health"),ranks=path.equals("/v2/ranks");
        if(ex.getRequestURI().getRawQuery()!=null||!(linking||policy||chat||recovery||health||ranks||path.equals("/v2/actions"))||linking&&verification==null||!linking&&!policy&&!chat&&!recovery&&!health&&!ranks&&moderation==null||policy&&plugin.guard()==null||chat&&plugin.chat()==null||recovery&&plugin.recovery()==null||ranks&&plugin.roles()==null){ex.sendResponseHeaders(404,-1);return;}
        if(!method.equals("POST")&&!((linking&&!batch||policy||chat||health)&&method.equals("GET"))){ex.sendResponseHeaders(405,-1);return;}
        var h=ex.getRequestHeaders();String g=h.getFirst("X-Orion-Guild"),s=h.getFirst("X-Orion-Server");
        if(!auth.scope(g,s)){ex.sendResponseHeaders(401,-1);return;}
        int limit=batch?16384:4096;
        byte[] body=ex.getRequestBody().readNBytes(limit+1);
        if(body.length>limit){ex.sendResponseHeaders(413,-1);return;}
        if(!auth.verify(method,path,g,s,h.getFirst("X-Orion-Time"),h.getFirst("X-Orion-Nonce"),h.getFirst("X-Orion-Signature"),body,System.currentTimeMillis())){ex.sendResponseHeaders(401,-1);return;}
        if(ranks){
            try{respond(ex,plugin.roles().rankQuery(new String(body,StandardCharsets.UTF_8)));}
            catch(IllegalArgumentException e){ex.sendResponseHeaders(400,-1);}
            return;
        }
        if(health){
            if(!method.equals("GET")||body.length!=0){ex.sendResponseHeaders(405,-1);return;}
            respond(ex,plugin.metrics().json());return;
        }
        if(recovery){
            try{respond(ex,plugin.recovery().request(new String(body,StandardCharsets.UTF_8)));}
            catch(IllegalArgumentException e){ex.sendResponseHeaders(400,-1);}
            catch(Exception e){respond(ex,"{\"result\":\"UNAVAILABLE_OR_UNKNOWN\"}");}
            return;
        }
        if(chat){
            ChatRelay relay=plugin.chat();
            if(method.equals("GET")){if(body.length!=0){ex.sendResponseHeaders(400,-1);return;}respond(ex,relay.snapshot());return;}
            String raw=new String(body,StandardCharsets.UTF_8);
            try{
                if(raw.startsWith("ACK\n")){
                    var ids=new java.util.HashSet<java.util.UUID>();String[] fields=raw.substring(4).split("\n");
                    if(fields.length>50)throw new IllegalArgumentException();
                    for(String id:fields)ids.add(java.util.UUID.fromString(id));
                    respond(ex,"{\"acknowledged\":"+relay.acknowledge(ids)+"}");return;
                }
                respond(ex,"{\"result\":"+OrionBridge.quote(relay.receive(raw))+"}");
            }catch(IllegalArgumentException e){ex.sendResponseHeaders(400,-1);}
            return;
        }
        if(policy){
            OrionGuard guard=plugin.guard();
            if(method.equals("GET")){
                if(body.length!=0){ex.sendResponseHeaders(400,-1);return;}
                respond(ex,"{\"lockdownUntil\":"+guard.lockdownUntil()+",\"profiles\":["+String.join(",",guard.profiles().stream().sorted().map(OrionBridge::quote).toList())+"]}");return;
            }
            String raw=new String(body,StandardCharsets.UTF_8);
            try{
                if(!raw.matches("LOCKDOWN\\n[0-9]{1,16}"))throw new IllegalArgumentException();
                guard.lockdown(Long.parseLong(raw.substring(9)),"Discord control");
                respond(ex,"{\"result\":\"APPLIED\"}");
            }catch(IllegalArgumentException e){ex.sendResponseHeaders(400,-1);}
            return;
        }
        if(batch){respond(ex,verification.batch(new String(body,StandardCharsets.UTF_8)));return;}
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
