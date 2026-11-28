package io.github.engrkarlo.orionbridge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Short, reversible local policy restrictions, independent of durable moderator cases. */
final class GuardContainment {
    enum Type { ALERT, BUILD_RESTRICTION, WORLDEDIT_RESTRICTION, COMMAND_RESTRICTION, FREEZE, QUARANTINE, LOCKDOWN }
    record Rule(boolean enabled,Type type,int seconds,String severity,int cooldownSeconds){}
    private record Entry(long build,long edit,long command,long freeze,long quarantine){
        boolean expired(long now){return build<=now&&edit<=now&&command<=now&&freeze<=now&&quarantine<=now;}
    }
    private final ConcurrentHashMap<UUID,Entry> entries=new ConcurrentHashMap<>();
    void apply(UUID id,Type type,long until){
        if(id==null||type==Type.ALERT||type==Type.LOCKDOWN)return;
        entries.compute(id,(key,old)->{
            Entry e=old==null?new Entry(0,0,0,0,0):old;
            return switch(type){
                case BUILD_RESTRICTION -> new Entry(Math.max(e.build(),until),e.edit(),e.command(),e.freeze(),e.quarantine());
                case WORLDEDIT_RESTRICTION -> new Entry(e.build(),Math.max(e.edit(),until),e.command(),e.freeze(),e.quarantine());
                case COMMAND_RESTRICTION -> new Entry(e.build(),e.edit(),Math.max(e.command(),until),e.freeze(),e.quarantine());
                case FREEZE -> new Entry(e.build(),e.edit(),e.command(),Math.max(e.freeze(),until),e.quarantine());
                case QUARANTINE -> new Entry(e.build(),e.edit(),e.command(),e.freeze(),Math.max(e.quarantine(),until));
                default -> e;
            };
        });
    }
    boolean denied(UUID id,Type type,long now){
        Entry e=entries.get(id);if(e==null)return false;
        if(e.quarantine()>now||e.freeze()>now)return true;
        return switch(type){
            case BUILD_RESTRICTION -> e.build()>now;
            case WORLDEDIT_RESTRICTION -> e.edit()>now||e.build()>now;
            case COMMAND_RESTRICTION -> e.command()>now;
            default -> false;
        };
    }
    void prune(long now){entries.forEach((id,e)->{if(e.expired(now))entries.remove(id,e);});}
}
