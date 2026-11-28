package io.github.engrkarlo.orionbridge;
import java.util.*;
import java.nio.file.*;
public final class SanctionSmoke {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("orion-sanctions");
        try{
            long now=1000000;String identity="java:"+"a".repeat(32);
            var a=new SanctionState.Case(UUID.randomUUID(),1,identity,SanctionState.Kind.MUTE,now+60000,false,"test");
            var b=new SanctionState.Case(UUID.randomUUID(),1,identity,SanctionState.Kind.MUTE,now+120000,false,"test two");
            var state=new SanctionState(dir);
            check(SanctionState.parse(SanctionState.encode(a)).equals(a));
            check(state.apply(a,now).equals("PERSISTED"));check(state.apply(b,now).equals("PERSISTED"));
            var revoke=new SanctionState.Case(a.id(),2,identity,a.kind(),a.expiry(),true,a.reason());
            check(state.apply(revoke,now).equals("PERSISTED"));check(state.has(identity,now,SanctionState.Kind.MUTE));
            check(state.apply(a,now).equals("STALE"));
            state=new SanctionState(dir);check(state.apply(a,now).equals("STALE"));check(state.active(identity,now).size()==1);
            check(!state.has(identity,now+121000,SanctionState.Kind.MUTE));
            check(state.apply(b,now+121000).equals("PERSISTED")); // idempotent receipt, not a new active restriction
            check(!state.has(identity,now+121000,SanctionState.Kind.MUTE));
        }finally{try(var files=Files.walk(dir)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
