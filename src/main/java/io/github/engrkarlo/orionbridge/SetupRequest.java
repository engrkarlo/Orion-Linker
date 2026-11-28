package io.github.engrkarlo.orionbridge;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Bounded, single-use console-authorized provisioning. No Bukkit access from HTTP workers. */
final class SetupRequest {
    private static final long TTL=300_000L;
    private final SecureRandom random=new SecureRandom();
    private String codeHash;
    private String tokenHash;
    private final java.nio.file.Path file;
    private long expires;
    private int attempts;
    private Pending pending;
    private Receipt receipt;
    private record Receipt(String id,String claimHash,String tokenHash,long expires){}
    record Credentials(String guild,String server,String actor,String audit,String control,String network,String feature) {
        Credentials(String guild,String server,String actor,String audit,String control,String network){this(guild,server,actor,audit,control,network,"CREDENTIALS");}
        Credentials {
            if(!guild.matches("[0-9]{17,20}")||!server.matches("[a-f0-9]{16}")||!actor.matches("[0-9]{17,20}")
                ||!audit.matches("[a-f0-9]{64}")||!control.matches("[a-f0-9]{64}")||!network.matches("[a-f0-9]{64}")
                ||audit.equals(control)||audit.equals(network)||control.equals(network)
                ||!java.util.Set.of("CREDENTIALS","CHAT","LINK","FORCE","AUDIT","ROLES","GUARD","RECOVERY").contains(feature))
                throw new IllegalArgumentException("Invalid setup credentials");
        }
        @Override public String toString(){return "Credentials[redacted]";}
        boolean distinct(String token){return !audit.equals(token)&&!control.equals(token)&&!network.equals(token);}
    }
    record Pending(String id,Credentials credentials,long expires,String claimHash){}
    SetupRequest(){file=null;}
    SetupRequest(java.nio.file.Path file)throws java.io.IOException{
        this.file=file;
        if(java.nio.file.Files.notExists(file,java.nio.file.LinkOption.NOFOLLOW_LINKS))return;
        if(!java.nio.file.Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)||java.nio.file.Files.size(file)>8192)throw new java.io.IOException("Invalid private setup state");
        var p=new java.util.Properties();
        try(var input=java.nio.file.Files.newInputStream(file)){p.load(input);}
        try{
            expires=Long.parseLong(p.getProperty("expires","0"));attempts=Integer.parseInt(p.getProperty("attempts","0"));
            codeHash=p.getProperty("code");tokenHash=p.getProperty("token");
            if(p.containsKey("pending")){
                var c=new Credentials(p.getProperty("guild"),p.getProperty("server"),p.getProperty("actor"),p.getProperty("audit"),p.getProperty("control"),p.getProperty("network"),p.getProperty("feature"));
                pending=new Pending(p.getProperty("pending"),c,expires,p.getProperty("claim"));
            }
            if(p.containsKey("receipt"))receipt=new Receipt(p.getProperty("receipt"),p.getProperty("receiptClaim"),p.getProperty("receiptToken"),Long.parseLong(p.getProperty("receiptExpires")));
            if(expires<=System.currentTimeMillis())clearPending();
            if(receipt!=null&&receipt.expires()<=System.currentTimeMillis())receipt=null;
            persist();
        }catch(RuntimeException e){throw new java.io.IOException("Invalid private setup state",e);}
    }
    private void persist(){
        if(file==null)return;
        try{
            java.nio.file.Files.createDirectories(file.getParent());
            if(java.nio.file.Files.isSymbolicLink(file)||java.nio.file.Files.isSymbolicLink(file.getParent()))throw new java.io.IOException("Invalid setup state path");
            var p=new java.util.Properties();
            p.setProperty("expires",Long.toString(expires));p.setProperty("attempts",Integer.toString(attempts));
            if(codeHash!=null)p.setProperty("code",codeHash);
            if(tokenHash!=null)p.setProperty("token",tokenHash);
            if(pending!=null){
                var c=pending.credentials();
                p.setProperty("pending",pending.id());p.setProperty("guild",c.guild());p.setProperty("server",c.server());p.setProperty("actor",c.actor());
                p.setProperty("audit",c.audit());p.setProperty("control",c.control());p.setProperty("network",c.network());p.setProperty("feature",c.feature());
                if(pending.claimHash()!=null)p.setProperty("claim",pending.claimHash());
            }
            if(receipt!=null){p.setProperty("receipt",receipt.id());p.setProperty("receiptClaim",receipt.claimHash());p.setProperty("receiptToken",receipt.tokenHash());p.setProperty("receiptExpires",Long.toString(receipt.expires()));}
            var temp=java.nio.file.Files.createTempFile(file.getParent(),"setup-",".tmp");
            try{
                if(java.nio.file.Files.getFileStore(temp).supportsFileAttributeView("posix"))java.nio.file.Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                try(var out=java.nio.file.Files.newOutputStream(temp)){p.store(out,"OrionBridge private setup state");}
                try(var channel=java.nio.channels.FileChannel.open(temp,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
                java.nio.file.Files.move(temp,file,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }finally{java.nio.file.Files.deleteIfExists(temp);}
        }catch(java.io.IOException e){clearPending();receipt=null;throw new java.io.UncheckedIOException("Could not persist setup state",e);}
    }
    private String secret(int bytes){byte[] value=new byte[bytes];random.nextBytes(value);return HexFormat.of().formatHex(value);}
    synchronized String open(long now){return open(now,null);}
    synchronized String open(long now,String token){String code=secret(16);codeHash=hash(code);tokenHash=token==null?null:hash(token);expires=now+TTL;attempts=0;pending=null;receipt=null;persist();return code;}
    private void clearPending(){codeHash=null;tokenHash=null;expires=0;pending=null;}
    synchronized void cancelPending(){clearPending();persist();}
    synchronized void cancel(){clearPending();receipt=null;persist();}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    synchronized Pending stage(String raw,String token,long now){return stage(raw,token,now,false);}
    synchronized Pending stage(String raw,String token,long now,boolean bootstrap){
        if(codeHash==null||now>=expires||raw.length()>512||tokenHash!=null&&!tokenHash.equals(hash(token)))return null;
        if(pending!=null&&!bootstrap)return null;
        if(!bootstrap&&++attempts>8){cancelPending();return null;}
        String[] parts=raw.split("\\n",-1);
        if((bootstrap?parts.length!=9:parts.length!=7&&parts.length!=8)||parts[0].length()!=32||!MessageDigest.isEqual(codeHash.getBytes(StandardCharsets.US_ASCII),hash(parts[0]).getBytes(StandardCharsets.US_ASCII)))return null;
        Credentials creds;
        try{creds=new Credentials(parts[1],parts[2],parts[3],parts[4],parts[5],parts[6],parts.length>=8?parts[7]:"CREDENTIALS");}catch(IllegalArgumentException e){return null;}
        if(!creds.distinct(token)||bootstrap&&(!creds.feature().equals("CREDENTIALS")||!parts[8].matches("[a-f0-9]{64}")||parts[8].equals(token)))return null;
        if(pending!=null){return bootstrap&&pending.claimHash()!=null&&pending.credentials().equals(creds)&&pending.claimHash().equals(hash(parts[8]))?pending:null;}
        if(!bootstrap)codeHash=null;tokenHash=hash(token);
        pending=new Pending(secret(8),creds,expires,bootstrap?hash(parts[8]):null);
        persist();return pending;
    }
    synchronized Pending peek(long now){if(now>=expires){if(expires!=0)cancelPending();return null;}return pending;}
    synchronized Pending pending(String id,String token,long now){var p=peek(now);return p!=null&&p.id().equals(id)&&tokenHash!=null&&tokenHash.equals(hash(token))?p:null;}
    synchronized Pending consume(String id,long now){
        var value=peek(now);
        if(value==null||!value.id().equals(id))return null;
        cancelPending();return value;
    }
    synchronized void approve(Pending value,String token,long now){
        if(value.claimHash()!=null&&value.expires()>now)receipt=new Receipt(value.id(),value.claimHash(),hash(token),value.expires());
        if(pending!=null&&pending.id().equals(value.id()))clearPending();persist();
    }
    synchronized boolean claim(String id,String secret,String token,long now){
        if(receipt==null||now>=receipt.expires()){if(receipt!=null){receipt=null;persist();}return false;}
        return receipt.id().equals(id)&&receipt.tokenHash().equals(hash(token))&&secret!=null&&secret.matches("[a-f0-9]{64}")&&MessageDigest.isEqual(receipt.claimHash().getBytes(StandardCharsets.US_ASCII),hash(secret).getBytes(StandardCharsets.US_ASCII));
    }
}
