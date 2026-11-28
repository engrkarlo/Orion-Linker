package io.github.engrkarlo.orionbridge;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Bounded, single-use console-authorized provisioning. No Bukkit access from HTTP workers. */
final class SetupRequest {
    private static final long TTL=300_000L;
    private final SecureRandom random=new SecureRandom();
    private String code;
    private long expires;
    private int attempts;
    private Pending pending;
    record Credentials(String guild,String server,String actor,String audit,String control,String network) {
        Credentials {
            if(!guild.matches("[0-9]{17,20}")||!server.matches("[a-f0-9]{16}")||!actor.matches("[0-9]{17,20}")
                ||!audit.matches("[a-f0-9]{64}")||!control.matches("[a-f0-9]{64}")||!network.matches("[a-f0-9]{64}")
                ||audit.equals(control)||audit.equals(network)||control.equals(network))
                throw new IllegalArgumentException("Invalid setup credentials");
        }
        @Override public String toString(){return "Credentials[redacted]";}
        boolean distinct(String token){return !audit.equals(token)&&!control.equals(token)&&!network.equals(token);}
    }
    record Pending(String id,Credentials credentials,long expires){}
    private String secret(int bytes){byte[] value=new byte[bytes];random.nextBytes(value);return HexFormat.of().formatHex(value);}
    synchronized String open(long now){code=secret(16);expires=now+TTL;attempts=0;pending=null;return code;}
    synchronized void cancel(){code=null;expires=0;pending=null;}
    synchronized Pending stage(String raw,String token,long now){
        if(code==null||now>=expires||raw.length()>512)return null;
        if(++attempts>8){cancel();return null;}
        String[] parts=raw.split("\\n",-1);
        if(parts.length!=7||parts[0].length()!=32||!MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII),parts[0].getBytes(StandardCharsets.US_ASCII)))return null;
        Credentials creds;
        try{creds=new Credentials(parts[1],parts[2],parts[3],parts[4],parts[5],parts[6]);}catch(IllegalArgumentException e){return null;}
        if(!creds.distinct(token))return null;
        code=null;
        pending=new Pending(secret(8),creds,expires);
        return pending;
    }
    synchronized Pending peek(long now){if(now>=expires){cancel();return null;}return pending;}
    synchronized Pending consume(String id,long now){
        var value=peek(now);
        if(value==null||!value.id().equals(id))return null;
        cancel();return value;
    }
}
