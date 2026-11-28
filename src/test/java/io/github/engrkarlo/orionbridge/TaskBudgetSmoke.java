package io.github.engrkarlo.orionbridge;
import java.nio.file.*;
import java.util.*;
public final class TaskBudgetSmoke{
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("orion-task");
        try{
            long now=1000;String owner="java:"+"a".repeat(32);
            var t=new TaskScope(UUID.randomUUID(),"build",EditScope.Operation.BLOCK,130,60000);
            var b=new TaskBudget(dir);
            check(t.permits("build",EditScope.Operation.BLOCK,now));check(!t.permits("other",EditScope.Operation.BLOCK,now));
            check(!t.permits("build",EditScope.Operation.ENTITY,now));
            check(b.reserve(owner,t,now));check(!b.reserve("other",t,now));
            var restart=new TaskBudget(dir);
            check(restart.reserve(owner,t,now));check(restart.reserve(owner,t,now));check(!restart.reserve(owner,t,now));
            check(!restart.reserve(owner,new TaskScope(t.id(),"other",t.operation(),t.budget(),t.expires()),now));
            check(!restart.reserve(owner,t,t.expires()));
            restart.prune(t.expires()+60000);
            check(!Files.exists(dir.resolve(t.id()+".task")));
        }finally{try(var files=Files.walk(dir)){for(Path f:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);}}
    }
}
