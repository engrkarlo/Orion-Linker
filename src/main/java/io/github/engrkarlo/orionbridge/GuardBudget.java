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
        var accepted=new java.util.concurrent.atomic.AtomicBoolean();
        windows.compute(player,(id,window)->{
            if(window==null||now-window.started>=MINUTE)window=new Window(now);
            if(window.count<maximum){window.count++;accepted.set(true);}
            return window;
        });return accepted.get();
    }
    void prune(long now){for(UUID id:windows.keySet())windows.computeIfPresent(id,(k,w)->now-w.started>2*MINUTE?null:w);}
    static boolean operation(AtomicInteger used,int maximum){
        if(maximum<=0)return false;
        for(;;){int old=used.get();if(old>=maximum)return false;if(used.compareAndSet(old,old+1))return true;}
    }
}
