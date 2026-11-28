package io.github.engrkarlo.orionbridge;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.event.node.NodeClearEvent;
import net.luckperms.api.event.log.LogPublishEvent;
import net.luckperms.api.model.user.User;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Permission mutation evidence, not a claim about the grant initiator. */
final class LuckPermsAudit implements AutoCloseable {
    private final List<EventSubscription<?>> subscriptions=new ArrayList<>();
    private final Map<String,Long> last=new ConcurrentHashMap<>();
    LuckPermsAudit(OrionBridge plugin,OrionGuard guard){
        LuckPerms api=LuckPermsProvider.get();
        subscriptions.add(api.getEventBus().subscribe(plugin,NodeAddEvent.class,e->record(guard,e.getTarget().getFriendlyName(),e.getTarget() instanceof User u?u.getUniqueId():null,e.getNode().getKey(),e.getNode().getValue(),"added")));
        subscriptions.add(api.getEventBus().subscribe(plugin,NodeRemoveEvent.class,e->record(guard,e.getTarget().getFriendlyName(),e.getTarget() instanceof User u?u.getUniqueId():null,e.getNode().getKey(),e.getNode().getValue(),"removed")));
        subscriptions.add(api.getEventBus().subscribe(plugin,NodeClearEvent.class,e->{
            String target=e.getTarget().getFriendlyName();
            UUID uuid=e.getTarget() instanceof User u?u.getUniqueId():null;
            if(!permit("clear:"+target))return;
            guard.external("PERMISSION_CLEAR",uuid==null?null:guard.identity(uuid),"Affected holder "+target+"; before="+e.getDataBefore().size()+"; after="+e.getDataAfter().size()+"; initiator unknown","LuckPerms");
        }));
        subscriptions.add(api.getEventBus().subscribe(plugin,LogPublishEvent.class,e->{
            if(e.isCancelled())return;
            var entry=e.getEntry();var source=entry.getSource();var target=entry.getTarget();
            Object raw=source.getUniqueId();
            UUID actor=raw instanceof UUID u?u:raw instanceof Optional<?> o&&o.orElse(null) instanceof UUID u?u:null;
            UUID affected=target.getUniqueId().orElse(null);
            String key="log:"+actor+":"+target.getName();
            if(!permit(key))return;
            // Never log raw action descriptions: those may contain arbitrary arguments.
            guard.attributed("PERMISSION_ACTION_REPORTED",actor==null?null:guard.identity(actor),affected==null?null:guard.identity(affected),
                "LuckPerms action log reports source "+source.getName()+" ("+Objects.toString(actor,"unresolved")+") and target "+target.getName()+"; published log is not independent proof of successful execution","LuckPerms");
        }));
    }
    private void record(OrionGuard guard,String target,UUID uuid,String node,boolean value,String action){
        // Only node key/value, never command arguments or unrelated metadata.
        if(node.startsWith("prefix.")||node.startsWith("suffix.")||node.startsWith("meta."))return;
        if(!permit(target+":"+node+":"+action))return;
        guard.external("PERMISSION_MUTATION",uuid==null?null:guard.identity(uuid),"Target "+target+"; node "+node+"="+value+" "+action+"; grant initiator unknown","LuckPerms");
    }
    private boolean permit(String key){
        long now=System.currentTimeMillis();
        if(last.size()>=4096&&!last.containsKey(key)){last.entrySet().removeIf(e->e.getValue()<now-60000);if(last.size()>=4096)return false;}
        Long old=last.putIfAbsent(key,now);return old==null||now-old>=5000&&last.replace(key,old,now);
    }
    @Override public void close(){for(var subscription:subscriptions)subscription.close();subscriptions.clear();}
}
