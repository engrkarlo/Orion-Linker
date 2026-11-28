package io.github.engrkarlo.orionbridge;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serves immutable snapshots only. Never touches Bukkit from HTTP threads. */
final class PullHttp implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final byte[] authorization;
    private volatile Snapshot latest;
    private final RequestAdmission admission=new RequestAdmission();
    boolean quiesce(){return admission.quiesce();}
    void resume(){admission.resume();}
    private record Snapshot(byte[] json,long sentAt){}
    PullHttp(String bind,int port,String token,AuditEndpoint audit,ControlEndpoint control,SetupRequest setup) throws IOException {
        authorization=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
        server=HttpServer.create(new InetSocketAddress(bind,port),32);
        workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"OrionBridge-HTTP");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers);
        server.createContext("/v1/snapshot",this::handle);
        // Same HTTPS origin and port. Bootstrap knows only a temporary console code;
        // an independently generated claim capability is required to obtain the token.
        server.createContext("/v3/setup",ex->handleSetup(ex,setup,token,false));
        server.createContext("/v3/pair",ex->handleSetup(ex,setup,token,true));
        server.createContext("/v3/claim",ex->{
            try{
                ex.getResponseHeaders().set("Cache-Control","no-store");ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                if(!ex.getRequestURI().getRawPath().equals("/v3/claim")||ex.getRequestURI().getRawQuery()!=null){ex.sendResponseHeaders(404,-1);return;}
                if(!ex.getRequestMethod().equals("POST")){ex.sendResponseHeaders(405,-1);return;}
                if(!admission.enter()){ex.sendResponseHeaders(503,-1);return;}
                try{
                    String secret=ex.getRequestHeaders().getFirst("Authorization");
                    if(secret==null||!secret.matches("Bearer [a-f0-9]{64}")||!"16".equals(ex.getRequestHeaders().getFirst("Content-Length"))){ex.sendResponseHeaders(403,-1);return;}
                    String id=new String(ex.getRequestBody().readNBytes(16),StandardCharsets.US_ASCII);
                    if(!setup.claim(id,secret.substring(7),token,System.currentTimeMillis())){ex.sendResponseHeaders(403,-1);return;}
                    byte[] reply=("{\"token\":\""+token+"\"}").getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200,reply.length);ex.getResponseBody().write(reply);
                }finally{admission.leave();}
            }finally{ex.close();}
        });
        if(audit!=null||control!=null)server.createContext("/v2/",ex->{
            if(!admission.enter()){try{ex.getResponseHeaders().set("Retry-After","2");ex.sendResponseHeaders(503,-1);}finally{ex.close();}return;}
            try{
                ex.getResponseHeaders().set("Cache-Control","no-store");
                ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                if(!authorized(ex)){ex.sendResponseHeaders(401,-1);return;}
                if(java.util.Set.of("/v2/actions","/v2/verification","/v2/verification/batch","/v2/policy","/v2/chat","/v2/recovery","/v2/health","/v2/ranks").contains(ex.getRequestURI().getRawPath())&&control!=null)control.handle(ex);
                else if(audit!=null)audit.handle(ex);
                else ex.sendResponseHeaders(404,-1);
            }finally{try{ex.close();}finally{admission.leave();}}
        });
        try{server.start();}catch(RuntimeException e){server.stop(0);workers.shutdownNow();throw e;}
    }
    private void handleSetup(HttpExchange ex,SetupRequest setup,String token,boolean bootstrap)throws IOException{
        try{
            ex.getResponseHeaders().set("Cache-Control","no-store");ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            if(!ex.getRequestURI().getRawPath().equals(bootstrap?"/v3/pair":"/v3/setup")||ex.getRequestURI().getRawQuery()!=null){ex.sendResponseHeaders(404,-1);return;}
            if(!ex.getRequestMethod().equals("POST")){ex.sendResponseHeaders(405,-1);return;}
            String supplied=ex.getRequestHeaders().getFirst("Authorization");
            if(bootstrap?(supplied==null||!supplied.matches("Bearer [a-f0-9]{32}")):!authorized(ex)){ex.sendResponseHeaders(401,-1);return;}
            if(!admission.enter()){ex.sendResponseHeaders(503,-1);return;}
            try{
                String length=ex.getRequestHeaders().getFirst("Content-Length");
                if(length==null||!length.matches("[0-9]{1,3}")||Integer.parseInt(length)>512){ex.sendResponseHeaders(413,-1);return;}
                byte[] body=ex.getRequestBody().readNBytes(Integer.parseInt(length));
                if(body.length!=Integer.parseInt(length)){ex.sendResponseHeaders(400,-1);return;}
                String raw=new String(body,StandardCharsets.US_ASCII);
                if(bootstrap&&!raw.startsWith(supplied.substring(7)+"\n")){ex.sendResponseHeaders(403,-1);return;}
                var staged=setup.stage(raw,token,System.currentTimeMillis(),bootstrap);
                if(staged==null){ex.sendResponseHeaders(403,-1);return;}
                byte[] reply=("{\"state\":\"PENDING\",\"id\":\""+staged.id()+"\"}").getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200,reply.length);ex.getResponseBody().write(reply);
            }finally{admission.leave();}
        }finally{ex.close();}
    }
    int port(){return server.getAddress().getPort();}
    void publish(String json,long sentAt){
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
        if(bytes.length<=1024*1024)latest=new Snapshot(bytes,sentAt);
    }
    private boolean authorized(HttpExchange ex){
        String auth=ex.getRequestHeaders().getFirst("Authorization");
        return auth!=null&&auth.length()==authorization.length&&MessageDigest.isEqual(authorization,auth.getBytes(StandardCharsets.UTF_8));
    }
    private void handle(HttpExchange ex) throws IOException {
        try{
            ex.getResponseHeaders().set("Cache-Control","no-store");
            ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            if(!ex.getRequestURI().getPath().equals("/v1/snapshot")||ex.getRequestURI().getRawQuery()!=null){ex.sendResponseHeaders(404,-1);return;}
            if(!ex.getRequestMethod().equals("GET")){ex.getResponseHeaders().set("Allow","GET");ex.sendResponseHeaders(405,-1);return;}
            if(!authorized(ex)){ex.sendResponseHeaders(401,-1);return;}
            Snapshot value=latest;
            if(value==null||System.currentTimeMillis()-value.sentAt()>90000){ex.sendResponseHeaders(503,-1);return;}
            ex.sendResponseHeaders(200,value.json().length);
            ex.getResponseBody().write(value.json());
        }finally{ex.close();}
    }
    @Override public void close(){latest=null;server.stop(0);workers.shutdownNow();}
}
