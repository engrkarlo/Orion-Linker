package io.github.engrkarlo.orionbridge;

import com.sk89q.worldedit.*;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.*;
import com.sk89q.worldedit.math.BlockVector3;
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
        if(player==null&&guard.allowNonPlayerEdits())return;
        String world=event.getWorld()==null?"unknown":event.getWorld().getName();
        event.setExtent(new AbstractDelegateExtent(event.getExtent()){
            private final AtomicInteger attempted=new AtomicInteger();
            private final AtomicBoolean reported=new AtomicBoolean(),authorized=new AtomicBoolean();
            @Override public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 location,T block)throws WorldEditException{
                if(player==null||!guard.reserveEdit(player,world,attempted)){
                    if(reported.compareAndSet(false,true))guard.external("WORLDEDIT_BLOCKED",player==null?null:guard.identity(player),"WorldEdit mutation blocked; world="+world+"; position="+location+"; reserved attempts="+attempted.get()+"; prior changes may already exist","WorldEdit/FAWE");
                    throw new MaxChangedBlocksException(player==null?0:guard.operationLimit(player,world));
                }
                if(authorized.compareAndSet(false,true))guard.external("TRUSTED_OPERATION",guard.identity(player),"Authorized WorldEdit session; world="+world+"; operation limit="+guard.operationLimit(player,world),"WorldEdit/FAWE");
                return super.setBlock(location,block);
            }
        });
    }
    @Override public void close(){WorldEdit.getInstance().getEventBus().unregister(this);}
}
