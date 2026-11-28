package io.github.engrkarlo.orionbridge;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Debit durable chunks before edits. A crash loses unused reservations, never
 * refunds a consumed budget. A task is bound to its immutable scope and subject. */
final class TaskBudget {
    private record Bucket(String subject,TaskScope task,int debited,int available){}
    private final Path dir;
    private final Map<UUID,Bucket> active=new HashMap<>();
    private static final int MAX_FILES=4096,CHUNK=128;
    TaskBudget(Path dir){this.dir=dir;}
    private Path path(UUID id){return dir.resolve(id+".task");}
    private static String metadata(String subject,TaskScope task){
        return subject+"\n"+task.id()+"\n"+Base64.getUrlEncoder().withoutPadding().encodeToString(task.world().getBytes(StandardCharsets.UTF_8))+"\n"+task.operation()+"\n"+task.budget()+"\n"+task.expires()+"\n";
    }
    synchronized boolean reserve(String subject,TaskScope task,long now){
        if(subject==null||task==null||task.expires()<=now)return false;
        try{
            Bucket b=active.get(task.id());
            if(b==null){
                Files.createDirectories(dir);
                Path p=path(task.id());int spent=0;
                if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){
                    if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>1024)return false;
                    String data=Files.readString(p,StandardCharsets.UTF_8),prefix=metadata(subject,task);
                    if(!data.startsWith(prefix))return false;
                    spent=Integer.parseInt(data.substring(prefix.length()));if(spent<0||spent>task.budget())return false;
                }else{
                    int count=0;try(var files=Files.newDirectoryStream(dir,"*.task")){for(var ignored:files)if(++count>=MAX_FILES)return false;}
                }
                b=new Bucket(subject,task,spent,0);
            }
            if(!b.subject().equals(subject)||!b.task().equals(task))return false;
            if(b.available()==0){
                int n=Math.min(CHUNK,task.budget()-b.debited());if(n<=0)return false;
                int debit=b.debited()+n;Path tmp=Files.createTempFile(dir,"quota-",".tmp");
                try{
                    Files.writeString(tmp,metadata(subject,task)+debit,StandardCharsets.UTF_8);
                    try(var file=FileChannel.open(tmp,StandardOpenOption.WRITE)){file.force(true);}
                    Files.move(tmp,path(task.id()),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                }finally{Files.deleteIfExists(tmp);}
                b=new Bucket(subject,task,debit,n);
            }
            active.put(task.id(),new Bucket(subject,task,b.debited(),b.available()-1));return true;
        }catch(IOException|RuntimeException ex){return false;}
    }
    synchronized void prune(long now){
        active.values().removeIf(b->b.task().expires()+60000L<=now);
        if(!Files.isDirectory(dir))return;
        try(var files=Files.newDirectoryStream(dir,"*.task")){
            int removed=0;
            for(Path p:files){if(removed>=64)break;if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>1024)continue;
                String[] f=Files.readString(p,StandardCharsets.UTF_8).split("\n",-1);
                try{if(f.length==7&&p.getFileName().toString().equals(UUID.fromString(f[1])+".task")&&Long.parseLong(f[5])+60000L<=now){Files.deleteIfExists(p);removed++;}}catch(IllegalArgumentException ignored){}
            }
        }catch(IOException ignored){}
    }
}
