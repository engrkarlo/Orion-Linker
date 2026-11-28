package io.github.engrkarlo.orionbridge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.io.*;

/** Absolute, independent containment deadlines keyed by authenticated identity. */
final class GuardContainment {
    enum Type { ALERT, BUILD_RESTRICTION, WORLDEDIT_RESTRICTION, COMMAND_RESTRICTION, FREEZE, QUARANTINE, LOCKDOWN }
    record Rule(boolean enabled,Type type,int seconds,String severity,int cooldownSeconds){}
    private record Entry(long build,long edit,long command,long freeze,long quarantine){
        boolean expired(long now){return build<=now&&edit<=now&&command<=now&&freeze<=now&&quarantine<=now;}
        String encode(){return String.join("\n",Long.toString(build),Long.toString(edit),Long.toString(command),Long.toString(freeze),Long.toString(quarantine));}
    }
    private static final int MAX=10000;
    private final ConcurrentHashMap<String,Entry> entries=new ConcurrentHashMap<>();
    private final Path directory;
    GuardContainment(){directory=null;}
    GuardContainment(Path directory,long now){
        this.directory=directory;
        try{
            Files.createDirectories(directory);
            int count=0;
            try(var paths=Files.newDirectoryStream(directory,"*.guard")){
                for(Path p:paths){
                    if(++count>MAX||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>512)throw new IOException("Invalid containment store");
                    String[] fields=Files.readString(p,StandardCharsets.UTF_8).split("\n",-1);
                    if(fields.length!=6||!valid(fields[0])||!p.getFileName().equals(path(fields[0]).getFileName()))throw new IOException("Invalid containment identity");
                    long[] v=new long[5];
                    for(int i=0;i<5;i++){if(!fields[i+1].matches("[0-9]{1,16}"))throw new IOException("Invalid deadline");v[i]=Long.parseLong(fields[i+1]);if(v[i]>now+3600000L)throw new IOException("Containment exceeds maximum; check system clock");}
                    var e=new Entry(v[0],v[1],v[2],v[3],v[4]);
                    if(e.expired(now))Files.delete(p);else entries.put(fields[0],e);
                }
            }
        }catch(IOException e){throw new UncheckedIOException("Cannot load Guard containment",e);}
    }
    private static boolean valid(String id){return id!=null&&id.matches("(?:java:[a-f0-9]{32}|bedrock:[1-9][0-9]{0,19}|authme:[a-z0-9_-]{1,32}:[a-z0-9_]{1,16})");}
    private Path path(String id){
        return directory.resolve(HexFormat.of().formatHex(id.getBytes(StandardCharsets.UTF_8))+".guard");
    }
    synchronized void apply(String id,Type type,long until){
        if(!valid(id)||type==Type.ALERT||type==Type.LOCKDOWN)return;
        if(!entries.containsKey(id)&&entries.size()>=MAX)throw new IllegalStateException("Containment capacity reached");
        Entry e=entries.getOrDefault(id,new Entry(0,0,0,0,0));
        Entry next=switch(type){
            case BUILD_RESTRICTION -> new Entry(Math.max(e.build(),until),e.edit(),e.command(),e.freeze(),e.quarantine());
            case WORLDEDIT_RESTRICTION -> new Entry(e.build(),Math.max(e.edit(),until),e.command(),e.freeze(),e.quarantine());
            case COMMAND_RESTRICTION -> new Entry(e.build(),e.edit(),Math.max(e.command(),until),e.freeze(),e.quarantine());
            case FREEZE -> new Entry(e.build(),e.edit(),e.command(),Math.max(e.freeze(),until),e.quarantine());
            case QUARANTINE -> new Entry(e.build(),e.edit(),e.command(),e.freeze(),Math.max(e.quarantine(),until));
            default -> e;
        };
        entries.put(id,next);
        if(directory==null)return;
        Path tmp=null;
        try{
            tmp=Files.createTempFile(directory,"guard-",".tmp");
            Files.writeString(tmp,id+"\n"+next.encode(),StandardCharsets.UTF_8);
            try(var channel=FileChannel.open(tmp,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(tmp,path(id),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(IOException ex){throw new UncheckedIOException("Cannot persist containment",ex);}
        finally{if(tmp!=null)try{Files.deleteIfExists(tmp);}catch(IOException ignored){}}
    }
    boolean denied(String id,Type type,long now){
        if(id==null)return false;
        Entry e=entries.get(id);if(e==null)return false;
        if(e.quarantine()>now||e.freeze()>now)return true;
        return switch(type){
            case BUILD_RESTRICTION -> e.build()>now;
            case WORLDEDIT_RESTRICTION -> e.edit()>now||e.build()>now;
            case COMMAND_RESTRICTION -> e.command()>now;
            default -> false;
        };
    }
    synchronized void prune(long now){
        int removed=0;
        for(var e:entries.entrySet()){
            if(removed>=64)break;
            if(!e.getValue().expired(now))continue;
            if(directory!=null)try{Files.deleteIfExists(path(e.getKey()));}catch(IOException ex){continue;}
            entries.remove(e.getKey(),e.getValue());removed++;
        }
    }
}
