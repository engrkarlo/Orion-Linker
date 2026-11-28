package io.github.engrkarlo.orionbridge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts attempted mutations conservatively, before any delegate can write. */
final class GuardBudget {
    private static final long MINUTE=60_000_000_000L;
    private final Map<UUID,Window> windows=new ConcurrentHashMap<>();
    private static final class Window {long started;long count;Window(long now){started=now;}}
    boolean reserve(UUID player,long now,long maximum){
        if(maximum<=0)return false;
        Window window=windows.computeIfAbsent(player,id->new Window(now));
        synchronized(window){
            if(now-window.started>=MINUTE){window.started=now;window.count=0;}
            if(window.count>=maximum)return false;
            window.count++;return true;
        }
    }
    void prune(long now){windows.entrySet().removeIf(e->{Window w=e.getValue();synchronized(w){return now-w.started>2*MINUTE;}});}
    static boolean operation(AtomicInteger used,int maximum){
        if(maximum<=0)return false;
        for(;;){int old=used.get();if(old>=maximum)return false;if(used.compareAndSet(old,old+1))return true;}
    }
}
