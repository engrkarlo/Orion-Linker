package io.github.engrkarlo.orionbridge;
import java.util.*;
public final class EditScopeSmoke {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        var scope=new EditScope(Set.of("build"),List.of(new EditScope.Region("build",0,0,0,7,7,7)),Set.of(EditScope.Operation.BLOCK,EditScope.Operation.BIOME),Set.of("worldedit:set"));
        check(scope.permits("build",7,7,7,EditScope.Operation.BLOCK));
        check(scope.permits("build",7,7,7,EditScope.Operation.BIOME));
        check(!scope.permits("build",8,7,7,EditScope.Operation.BLOCK));
        check(!scope.permits("build",0,0,0,EditScope.Operation.ENTITY));
        check(!scope.permits("other",0,0,0,EditScope.Operation.BLOCK));
        check(scope.permitsCommand("worldedit","set"));
        check(!scope.permitsCommand("worldedit","replace"));
        var narrow=new EditScope(Set.of(),List.of(new EditScope.Region("build",1,0,0,7,7,7)),Set.of(EditScope.Operation.BIOME),Set.of());
        check(!narrow.permits("build",1,1,1,EditScope.Operation.BIOME));
    }
}
