package io.github.engrkarlo.orionbridge;

import com.sk89q.worldedit.*;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.*;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import java.util.UUID;
import java.util.concurrent.atomic.*;

/** Optional WE-compatible extent hook. Does not assume Bukkit thread affinity. */
final class WorldEditGuard implements AutoCloseable {
    private final OrionGuard guard;
    WorldEditGuard(OrionGuard guard){this.guard=guard;WorldEdit.getInstance().getEventBus().register(this);}
    @Subscribe public void edit(EditSessionEvent event){
        if(event.getStage()!=EditSession.Stage.BEFORE_CHANGE||!guard.enforceEdits())return;
        var actor=event.getActor();
        UUID player=actor instanceof com.sk89q.worldedit.entity.Player?actor.getUniqueId():null;
        String world=event.getWorld()==null?"unknown":event.getWorld().getName();
        event.setExtent(new AbstractDelegateExtent(event.getExtent()){
            private final AtomicInteger attempted=new AtomicInteger();
            private final AtomicBoolean reported=new AtomicBoolean(),authorized=new AtomicBoolean();
            private boolean permitted(BlockVector3 location,boolean biome,boolean entity){
                boolean accepted=!guard.protectedEdit(world,location.x(),location.y(),location.z(),biome)
                    &&(player==null?guard.reserveNonPlayerEdit(attempted):(!entity||guard.entityEdit(player))&&guard.scopedEdit(player,world,location.x(),location.y(),location.z(),biome,entity)&&guard.reserveEdit(player,world,attempted));
                if(!accepted){
                    if(reported.compareAndSet(false,true))guard.rule(player,player==null?null:guard.identity(player),"WORLDEDIT_BLOCKED","WorldEdit "+(entity?"entity":biome?"biome":"block")+" mutation blocked; world="+world+"; position="+location+"; reserved attempts="+attempted.get()+"; prior changes may already exist","WorldEdit/FAWE");
                    return false;
                }
                if(authorized.compareAndSet(false,true))guard.external("TRUSTED_OPERATION",player==null?null:guard.identity(player),"Authorized WorldEdit session; world="+world+"; actor="+(player==null?"configured non-player source":"verified staff profile"),"WorldEdit/FAWE");
                return true;
            }
            @Override public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 location,T block)throws WorldEditException{
                if(!permitted(location,false,false))throw new MaxChangedBlocksException(player==null?0:guard.operationLimit(player,world));
                return super.setBlock(location,block);
            }
            @Override public boolean setBiome(BlockVector3 location,BiomeType biome){
                return permitted(location,true,false)&&super.setBiome(location,biome);
            }
            @Override public Entity createEntity(Location location,BaseEntity entity){
                return permitted(location.toVector().toBlockPoint(),false,true)?super.createEntity(location,entity):null;
            }
        });
    }
    @Override public void close(){WorldEdit.getInstance().getEventBus().unregister(this);}
}
