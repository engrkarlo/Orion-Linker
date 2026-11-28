package io.github.engrkarlo.orionbridge;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
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
    }
    private void record(OrionGuard guard,String target,UUID uuid,String node,boolean value,String action){
        // Only node key/value, never command arguments or unrelated metadata.
        if(node.startsWith("prefix.")||node.startsWith("suffix.")||node.startsWith("meta."))return;
        String key=target+":"+node+":"+action;long now=System.currentTimeMillis();
        if(last.size()>=4096){last.entrySet().removeIf(e->e.getValue()<now-60000);if(last.size()>=4096)return;}
        Long old=last.put(key,now);if(old!=null&&now-old<5000)return;
        guard.external("PERMISSION_MUTATION",uuid==null?null:guard.identity(uuid),"Target "+target+"; node "+node+"="+value+" "+action+"; grant initiator unknown","LuckPerms");
    }
    @Override public void close(){for(var subscription:subscriptions)subscription.close();subscriptions.clear();}
}
