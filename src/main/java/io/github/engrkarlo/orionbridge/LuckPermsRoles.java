package io.github.engrkarlo.orionbridge;

import net.luckperms.api.*;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;
import java.time.Instant;

/** Only explicitly allowed, temporary, transient inheritance nodes are managed.
 * Manual persistent and pre-existing transient memberships are untouched. */
final class LuckPermsRoles implements AutoCloseable {
    private final LuckPerms api=LuckPermsProvider.get();
    private final Set<String> groups,permissions;
    private final Map<UUID,Map<String,InheritanceNode>> owned=new HashMap<>();
    LuckPermsRoles(YamlConfiguration cfg){
        groups=Set.copyOf(cfg.getStringList("roles.allowed-groups"));
        permissions=Set.copyOf(cfg.getStringList("roles.allowed-permissions"));
    }
    String primary(UUID uuid){
        User user=api.getUserManager().getUser(uuid);
        return user==null?"":user.getPrimaryGroup();
    }
    private boolean safe(String name,Set<String> seen,int depth){
        if(depth>12||!seen.add(name)||!groups.contains(name)||Set.of("admin","administrator","owner","op","operator","staff","moderator","developer").contains(name.toLowerCase(Locale.ROOT)))return false;
        Group group=api.getGroupManager().getGroup(name);
        if(group==null)return false;
        try{
            for(Node node:group.getNodes()){
                if(!node.getValue())continue;
                if(node instanceof InheritanceNode inheritance){if(!safe(inheritance.getGroupName(),seen,depth+1))return false;continue;}
                String key=node.getKey().toLowerCase(Locale.ROOT);
                if(key.startsWith("prefix.")||key.startsWith("suffix.")||key.startsWith("meta.")||key.startsWith("weight."))continue;
                if(key.contains("*")||key.startsWith("luckperms.")||key.startsWith("minecraft.command.")||key.startsWith("bukkit.command.")||key.startsWith("worldedit.")||key.startsWith("fawe.")||key.startsWith("orionbridge.")||!permissions.contains(key))return false;
            }
            return true;
        }finally{seen.remove(name);}
    }
    void sync(Player player,Set<String> desired,long until){
        UUID id=player.getUniqueId();User user=api.getUserManager().getUser(id);if(user==null)return;
        var active=owned.computeIfAbsent(id,k->new HashMap<>());
        for(var it=active.entrySet().iterator();it.hasNext();){
            var entry=it.next();
            // Renew by replacing only the exact transient node this bridge owns.
            user.transientData().remove(entry.getValue());it.remove();
        }
        if(until<=System.currentTimeMillis()){owned.remove(id);return;}
        for(String name:desired){
            if(!safe(name,new HashSet<>(),0))continue;
            var node=InheritanceNode.builder(name).expiry(Instant.ofEpochMilli(until)).build();
            // Do not take ownership of any existing transient/persistent membership.
            boolean existing=user.getNodes().stream().anyMatch(n->n instanceof InheritanceNode i&&i.getGroupName().equals(name)&&i.getValue()&&!i.hasExpired());
            if(!existing&&user.transientData().add(node).wasSuccessful())active.put(name,node);
        }
        if(active.isEmpty())owned.remove(id);
    }
    void clear(UUID id){
        var active=owned.remove(id);User user=api.getUserManager().getUser(id);
        if(active!=null&&user!=null)for(var node:active.values())user.transientData().remove(node);
    }
    @Override public void close(){for(UUID id:List.copyOf(owned.keySet()))clear(id);}
}
