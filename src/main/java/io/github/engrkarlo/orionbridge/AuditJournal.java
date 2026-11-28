package io.github.engrkarlo.orionbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded durable spool. Producers never perform disk I/O. ACK only after central persistence. */
final class AuditJournal implements AutoCloseable {
    private static final int MAX_RECORDS=4096, MAX_BYTES=4096;
    private final Path directory;
    private final Map<UUID,String> records=new LinkedHashMap<>();
    private final AtomicLong dropped=new AtomicLong();
    private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(512),r->{Thread t=new Thread(r,"OrionBridge-audit");t.setDaemon(true);return t;});
    AuditJournal(Path directory)throws IOException{
        this.directory=directory;Files.createDirectories(directory);
        try(var paths=Files.list(directory)){
            for(Path file:paths.filter(p->p.getFileName().toString().endsWith(".event")).sorted().toList()){
                if(records.size()>=MAX_RECORDS){dropped.incrementAndGet();continue;}
                try{
                    UUID id=UUID.fromString(file.getFileName().toString().replaceFirst("\\.event$",""));
                    if(Files.size(file)>MAX_BYTES||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)){dropped.incrementAndGet();continue;}
                    records.put(id,Files.readString(file,StandardCharsets.UTF_8));
                }catch(IllegalArgumentException|IOException e){dropped.incrementAndGet();}
            }
        }
    }
    void offer(UUID id,String json){
        if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES){dropped.incrementAndGet();return;}
        try{writer.execute(()->persist(id,json));}catch(RejectedExecutionException e){dropped.incrementAndGet();}
    }
    private synchronized void persist(UUID id,String json){
        if(records.containsKey(id))return;
        if(records.size()>=MAX_RECORDS){dropped.incrementAndGet();return;}
        Path temp=null;
        try{
            temp=Files.createTempFile(directory,"event-",".tmp");
            Files.writeString(temp,json,StandardCharsets.UTF_8);
            try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(temp,directory.resolve(id+".event"),StandardCopyOption.ATOMIC_MOVE);
            records.put(id,json);
        }catch(IOException e){dropped.incrementAndGet();}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    synchronized String batch(){
        return "{\"protocol\":2,\"queued\":"+records.size()+",\"droppedSinceStartup\":"+dropped.get()+",\"events\":["+String.join(",",records.values().stream().limit(100).toList())+"]}";
    }
    synchronized int acknowledge(List<UUID> ids)throws IOException{
        int count=0;
        for(UUID id:ids)if(records.containsKey(id)){Files.deleteIfExists(directory.resolve(id+".event"));records.remove(id);count++;}
        return count;
    }
    long dropped(){return dropped.get();}
    @Override public void close(){
        writer.shutdown();
        try{if(!writer.awaitTermination(2,TimeUnit.SECONDS)){dropped.addAndGet(writer.shutdownNow().size());}}catch(InterruptedException e){dropped.addAndGet(writer.shutdownNow().size());Thread.currentThread().interrupt();}
    }
}
