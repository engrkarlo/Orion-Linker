package io.github.engrkarlo.orionbridge;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** One server-local absolute deadline; never extend an expired lockdown on restart. */
final class DurableLockdown {
    private final Path file;
    private volatile long until;
    DurableLockdown(Path file,long now){
        this.file=file.toAbsolutePath();
        try{
            if(Files.exists(this.file)){
                if(Files.size(this.file)>32)throw new IOException("Invalid lockdown state size");
                String value=Files.readString(this.file,StandardCharsets.US_ASCII).strip();
                if(!value.matches("[0-9]{1,16}"))throw new IOException("Invalid lockdown state");
                long stored=Long.parseLong(value);
                if(stored>now+3600000L)throw new IOException("Lockdown state exceeds maximum; check system clock");
                until=stored>now?stored:0;
            }else if(!Files.notExists(this.file))throw new IOException("Cannot read lockdown state");
        }catch(IOException e){throw new UncheckedIOException("Cannot load guard lockdown state",e);}
    }
    long until(){return until;}
    synchronized void set(long value){
        if(value<0)throw new IllegalArgumentException("Negative lockdown deadline");
        // Never acknowledge an unsuccessful manual clear or set.
        persist(value);until=value;
    }
    synchronized void extend(long value){
        if(value<=until)return;
        // Enforce immediately even if storage fails. A later higher deadline retries.
        until=value;persist(value);
    }
    private void persist(long value){
        Path temp=null;
        try{
            Files.createDirectories(file.getParent());
            temp=Files.createTempFile(file.getParent(),"guard-lockdown-",".tmp");
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)){
                ByteBuffer buffer=StandardCharsets.US_ASCII.encode(Long.toString(value)+"\n");
                while(buffer.hasRemaining())channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(IOException e){throw new UncheckedIOException("Cannot persist guard lockdown state",e);}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
}
