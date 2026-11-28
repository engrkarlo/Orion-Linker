package io.github.engrkarlo.orionbridge;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
public final class AuditSmoke {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        String guild="111111111111111111",server="0123456789abcdef",key="a".repeat(64);
        var auth=new AuditAuth(guild,server,key);long now=System.currentTimeMillis();
        String ts=Long.toString(now),nonce="b".repeat(32);
        byte[] empty=new byte[0];
        String sig=AuditAuth.signature(HexFormat.of().parseHex(key),"GET","/v2/events",guild,server,ts,nonce,empty);
        check(!auth.verify("GET","/v2/events","222222222222222222",server,ts,nonce,sig,empty,now));
        check(auth.verify("GET","/v2/events",guild,server,ts,nonce,sig,empty,now));
        check(!auth.verify("GET","/v2/events",guild,server,ts,nonce,sig,empty,now));
        Path dir=Files.createTempDirectory("orion-audit-test");
        UUID id=UUID.randomUUID();String payload="{\"id\":\""+id+"\"}";
        try{
            try(var spool=new AuditJournal(dir)){spool.offer(id,payload);}
            try(var spool=new AuditJournal(dir)){
                check(spool.batch().contains(id.toString()));check(spool.acknowledge(List.of(id))==1);check(spool.acknowledge(List.of(id))==0);
            }
            try(var spool=new AuditJournal(dir)){check(!spool.batch().contains(id.toString()));}
        }finally{try(var files=Files.walk(dir)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
