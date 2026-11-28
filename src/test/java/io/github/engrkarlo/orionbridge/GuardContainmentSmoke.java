package io.github.engrkarlo.orionbridge;
import java.nio.file.*;
import java.io.UncheckedIOException;
public final class GuardContainmentSmoke {
    static void check(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        var c=new GuardContainment();var a="java:"+"a".repeat(32);var b="bedrock:123";
        c.apply(a,GuardContainment.Type.BUILD_RESTRICTION,200);
        check(c.denied(a,GuardContainment.Type.WORLDEDIT_RESTRICTION,100));
        check(!c.denied(a,GuardContainment.Type.COMMAND_RESTRICTION,100));
        c.apply(a,GuardContainment.Type.FREEZE,150);c.apply(a,GuardContainment.Type.FREEZE,120);
        check(c.denied(a,GuardContainment.Type.COMMAND_RESTRICTION,149));
        check(!c.denied(a,GuardContainment.Type.COMMAND_RESTRICTION,150));
        check(!c.denied(b,GuardContainment.Type.BUILD_RESTRICTION,100));
        c.prune(201);check(!c.denied(a,GuardContainment.Type.BUILD_RESTRICTION,201));
        Path dir=Files.createTempDirectory("orion-containment");
        try{
            var saved=new GuardContainment(dir,100);
            saved.apply(a,GuardContainment.Type.BUILD_RESTRICTION,200);
            saved.apply(a,GuardContainment.Type.FREEZE,150);
            var restored=new GuardContainment(dir,120);
            check(restored.denied(a,GuardContainment.Type.COMMAND_RESTRICTION,149));
            check(!restored.denied(a,GuardContainment.Type.COMMAND_RESTRICTION,150));
            check(restored.denied(a,GuardContainment.Type.BUILD_RESTRICTION,199));
            check(!restored.denied(b,GuardContainment.Type.BUILD_RESTRICTION,199));
            check(!new GuardContainment(dir,200).denied(a,GuardContainment.Type.BUILD_RESTRICTION,200));
        }finally{try(var files=Files.list(dir)){for(Path path:files.toList())Files.deleteIfExists(path);}Files.deleteIfExists(dir);}
    }
}
