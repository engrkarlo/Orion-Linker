package io.github.engrkarlo.orionbridge;
import java.util.*;
public final class GuardCommandsSmoke {
    static void check(boolean result){if(!result)throw new AssertionError();}
    public static void main(String[] args){
        check(GuardCommands.sensitive("essentials","invsee","invsee"));
        check(GuardCommands.sensitive("worldguard","region","rg"));
        check(GuardCommands.sensitive("coreprotect","coreprotect","co"));
        check(GuardCommands.sensitive("advancedban","tempban","tempban"));
        check(!GuardCommands.sensitive("essentials","home","home"));
        check(GuardCommands.directEdit("regen"));
        check(!GuardCommands.taskCommand(EditScope.unrestricted(),"worldedit","set"));
        var scope=new EditScope(Set.of(),List.of(),EnumSet.allOf(EditScope.Operation.class),Set.of("worldedit:set","worldedit:regen"));
        check(GuardCommands.taskCommand(scope,"worldedit","set"));
        check(!GuardCommands.taskCommand(scope,"worldedit","regen"));
        check(!GuardCommands.taskCommand(scope,"fastasyncworldedit","set"));
    }
}
