package io.github.engrkarlo.orionbridge;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Each immutable case is independently enforced. Revisions prevent stale apply after revoke. */
final class SanctionState {
    enum Kind {MUTE,FREEZE,BUILD_RESTRICTION,QUARANTINE,TEMP_BAN,PERMANENT_BAN}
    record Case(UUID id,long revision,String identity,Kind kind,long expiry,boolean revoked,String reason) {
        boolean active(long now){return !revoked&&(expiry==0||expiry>now);}
    }
    private static final int MAX_CASES=10000;
    private final Path directory;
    private final Map<UUID,Case> cases=new HashMap<>();
    private volatile Map<String,List<Case>> byIdentity=Map.of();
    SanctionState(Path directory)throws IOException{
        this.directory=directory;Files.createDirectories(directory);
        try(var paths=Files.list(directory)){
            for(Path path:paths.filter(p->p.getFileName().toString().endsWith(".case")).toList()){
                if(cases.size()>=MAX_CASES||Files.size(path)>4096||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid or oversized sanction store");
                Case c;
                try{c=parse(Files.readString(path,StandardCharsets.UTF_8));}
                catch(RuntimeException e){throw new IOException("Invalid sanction store",e);}
                if(!path.getFileName().toString().equals(c.id()+".case")||cases.putIfAbsent(c.id(),c)!=null)throw new IOException("Invalid sanction identity");
            }
        }
        rebuild();
    }
    static Case parse(String body){
        String[] fields=body.split("\n",-1);
        if(fields.length!=7||!fields[0].matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")||!fields[1].matches("[1-9][0-9]{0,8}")||!validIdentity(fields[2])||!fields[4].matches("[0-9]{1,16}")||!Set.of("APPLY","REVOKE").contains(fields[5])||!fields[6].matches("[A-Za-z0-9_-]{1,2048}"))throw new IllegalArgumentException("Invalid sanction");
        Kind kind=Kind.valueOf(fields[3]);long expiry=Long.parseLong(fields[4]);boolean revoked=fields[5].equals("REVOKE");
        if(kind==Kind.TEMP_BAN&&expiry==0||kind==Kind.PERMANENT_BAN&&expiry!=0)throw new IllegalArgumentException("Invalid ban duration");
        String reason=new String(Base64.getUrlDecoder().decode(fields[6]),StandardCharsets.UTF_8);
        if(reason.isBlank()||reason.length()>500||reason.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid reason");
        return new Case(UUID.fromString(fields[0]),Long.parseLong(fields[1]),fields[2],kind,expiry,revoked,reason);
    }
    static boolean validIdentity(String i){
        if(i==null)return false;
        if(i.matches("java:[a-f0-9]{32}"))return !i.equals("java:"+"0".repeat(32));
        if(i.matches("bedrock:[1-9][0-9]{0,19}"))try{return Long.parseUnsignedLong(i.substring(8))!=0;}catch(NumberFormatException ignored){return false;}
        return false;
    }
    static String encode(Case c){
        return String.join("\n",c.id().toString(),Long.toString(c.revision()),c.identity(),c.kind().name(),Long.toString(c.expiry()),c.revoked()?"REVOKE":"APPLY",Base64.getUrlEncoder().withoutPadding().encodeToString(c.reason().getBytes(StandardCharsets.UTF_8)));
    }
    synchronized String apply(Case next,long now)throws IOException{
        Case old=cases.get(next.id());
        if(old!=null){
            if(!old.identity().equals(next.identity())||old.kind()!=next.kind())return "CONFLICT";
            if(next.revision()<old.revision())return "STALE";
            if(next.revision()==old.revision())return old.equals(next)?"PERSISTED":"CONFLICT";
        }else if(cases.size()>=MAX_CASES)return "CAPACITY";
        if(!next.revoked()&&next.expiry()!=0&&(next.expiry()<=now||next.expiry()>now+365L*86400000))return "EXPIRED_OR_INVALID";
        Path tmp=Files.createTempFile(directory,"case-",".tmp");
        try{
            Files.writeString(tmp,encode(next),StandardCharsets.UTF_8);
            try(var channel=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(tmp,directory.resolve(next.id()+".case"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            cases.put(next.id(),next);rebuild();return "PERSISTED";
        }finally{Files.deleteIfExists(tmp);}
    }
    private void rebuild(){
        Map<String,List<Case>> next=new HashMap<>();
        for(Case c:cases.values())if(!c.revoked())next.computeIfAbsent(c.identity(),k->new ArrayList<>()).add(c);
        next.replaceAll((k,v)->List.copyOf(v));byIdentity=Map.copyOf(next);
    }
    List<Case> active(String identity,long now){
        if(identity==null)return List.of();
        return byIdentity.getOrDefault(identity,List.of()).stream().filter(c->c.active(now)).toList();
    }
    boolean has(String identity,long now,Kind... kinds){
        if(identity==null)return false;
        for(Case c:byIdentity.getOrDefault(identity,List.of()))if(c.active(now))for(Kind kind:kinds)if(c.kind()==kind)return true;
        return false;
    }
}
