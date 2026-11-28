package io.github.engrkarlo.orionbridge;
import java.util.HexFormat;
final class ReplayExpirySmoke {
    public static void main(String[] args){
        String g="1".repeat(18),s="2".repeat(16),key="a".repeat(64),nonce="b".repeat(32),p="/v2/health";
        byte[] body=new byte[0],raw=HexFormat.of().parseHex(key);long now=100000;
        AuditAuth auth=new AuditAuth(g,s,key);
        String first=AuditAuth.signature(raw,"GET",p,g,s,Long.toString(now),nonce,body);
        if(!auth.verify("GET",p,g,s,Long.toString(now),nonce,first,body,now))throw new AssertionError("initial");
        if(auth.verify("GET",p,g,s,Long.toString(now),nonce,first,body,now))throw new AssertionError("replay");
        long next=now+60001;String second=AuditAuth.signature(raw,"GET",p,g,s,Long.toString(next),nonce,body);
        if(!auth.verify("GET",p,g,s,Long.toString(next),nonce,second,body,next))throw new AssertionError("expiry");
    }
}
