package io.github.engrkarlo.orionbridge;
import java.nio.file.*;
import java.io.UncheckedIOException;
public final class DurableLockdownSmoke {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("orion-lockdown");Path file=dir.resolve("state");
        try{
            long now=100000L;
            var state=new DurableLockdown(file,now);
            check(state.until()==0);
            state.extend(now+1000);state.extend(now+500);
            check(new DurableLockdown(file,now).until()==now+1000);
            check(new DurableLockdown(file,now+1000).until()==0);
            state.set(0);check(new DurableLockdown(file,now).until()==0);
            Files.writeString(file,"bad");boolean rejected=false;
            try{new DurableLockdown(file,now);}catch(UncheckedIOException e){rejected=true;}
            check(rejected);
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(dir);}
    }
}
