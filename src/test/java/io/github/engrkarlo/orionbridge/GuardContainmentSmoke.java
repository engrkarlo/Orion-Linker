package io.github.engrkarlo.orionbridge;
import java.util.UUID;
public final class GuardContainmentSmoke {
    static void check(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args){
        var c=new GuardContainment();var a=UUID.randomUUID();var b=UUID.randomUUID();
        c.apply(a,GuardContainment.Type.BUILD_RESTRICTION,200);
        check(c.denied(a,GuardContainment.Type.WORLDEDIT_RESTRICTION,100));
        check(!c.denied(a,GuardContainment.Type.COMMAND_RESTRICTION,100));
        c.apply(a,GuardContainment.Type.FREEZE,150);c.apply(a,GuardContainment.Type.FREEZE,120);
        check(c.denied(a,GuardContainment.Type.COMMAND_RESTRICTION,149));
        check(!c.denied(a,GuardContainment.Type.COMMAND_RESTRICTION,150));
        check(!c.denied(b,GuardContainment.Type.BUILD_RESTRICTION,100));
        c.prune(201);check(!c.denied(a,GuardContainment.Type.BUILD_RESTRICTION,201));
    }
}
