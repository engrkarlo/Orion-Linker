package io.github.engrkarlo.orionbridge;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class AuditAuth {
    private final String guild,server;
    private final byte[] key;
    private final NonceLedger ledger;
    static final class NonceLedger {
        private final int capacity;
        private final Map<String,Long> nonces=new HashMap<>();
        private final PriorityQueue<Map.Entry<String,Long>> expiry=new PriorityQueue<>(Comparator.comparingLong(Map.Entry::getValue));
        NonceLedger(int capacity){this.capacity=capacity;}
        synchronized boolean claim(String nonce,long until,long now){
            while(!expiry.isEmpty()&&expiry.peek().getValue()<=now){var old=expiry.poll();nonces.remove(old.getKey(),old.getValue());}
            if(nonces.containsKey(nonce)||nonces.size()>=capacity)return false;
            nonces.put(nonce,until);expiry.add(Map.entry(nonce,until));return true;
        }
    }
    AuditAuth(String guild,String server,String key){this(guild,server,key,new NonceLedger(4096));}
    AuditAuth(String guild,String server,String key,NonceLedger ledger){
        this.ledger=Objects.requireNonNull(ledger);
        if(!guild.matches("[0-9]{17,20}")||!server.matches("[a-f0-9]{16}")||!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid audit scope or signing key");
        this.guild=guild;this.server=server;this.key=HexFormat.of().parseHex(key);
    }
    boolean scope(String g,String s){return guild.equals(g)&&server.equals(s);}
    static String signature(byte[] key,String method,String path,String guild,String server,String time,String nonce,byte[] body){
        try{
            var digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(String.join("\n",method,path,guild,server,time,nonce,digest).getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    synchronized boolean verify(String method,String path,String g,String s,String timestamp,String nonce,String supplied,byte[] body,long now){
        if(!scope(g,s)||timestamp==null||!timestamp.matches("[0-9]{1,16}")||nonce==null||!nonce.matches("[a-f0-9]{32}")||supplied==null||!supplied.matches("[a-f0-9]{64}"))return false;
        long time=Long.parseLong(timestamp);
        if(time<now-60000||time>now+60000)return false;
        var expected=signature(key,method,path,g,s,timestamp,nonce,body);
        if(!MessageDigest.isEqual(HexFormat.of().parseHex(expected),HexFormat.of().parseHex(supplied)))return false;
        return ledger.claim(nonce,time+60001,now);
    }
}
