package io.github.engrkarlo.orionbridge;

import java.util.*;

/** Additional local limits for named trusted task profiles. Global protected
 * worlds/regions and sanctions always take precedence. */
record EditScope(Set<String> worlds,List<Region> regions,Set<Operation> operations,Set<String> commands){
    enum Operation { BLOCK, BIOME, ENTITY }
    record Region(String world,int minX,int minY,int minZ,int maxX,int maxY,int maxZ){
        boolean contains(String w,int x,int y,int z,int ex,int ey,int ez){
            return world.equals(w)&&x>=minX&&y>=minY&&z>=minZ&&ex<=maxX&&ey<=maxY&&ez<=maxZ;
        }
    }
    EditScope{worlds=Set.copyOf(worlds);regions=List.copyOf(regions);operations=Set.copyOf(operations);commands=Set.copyOf(commands);}
    static EditScope unrestricted(){return new EditScope(Set.of(),List.of(),EnumSet.allOf(Operation.class),Set.of());}
    boolean permits(String world,int x,int y,int z,Operation op){
        if(world==null||world.equals("unknown")||!operations.contains(op)||!worlds.isEmpty()&&!worlds.contains(world))return false;
        int ex=x,ey=y,ez=z;
        if(op==Operation.BIOME){x=Math.floorDiv(x,4)*4;y=Math.floorDiv(y,4)*4;z=Math.floorDiv(z,4)*4;ex=x+3;ey=y+3;ez=z+3;}
        if(regions.isEmpty())return true;
        for(var r:regions)if(r.contains(world,x,y,z,ex,ey,ez))return true;
        return false;
    }
    boolean permitsCommand(String owner,String name){return commands.isEmpty()||commands.contains(owner+":"+name);}
}
