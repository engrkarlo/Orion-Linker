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
    private final boolean offlineReconciliation;
    private final java.util.concurrent.Semaphore rankSlots=new java.util.concurrent.Semaphore(4);
    private final Map<UUID,Map<String,InheritanceNode>> owned=new HashMap<>();
    LuckPermsRoles(YamlConfiguration cfg){
        offlineReconciliation=cfg.getBoolean("roles.offline-reconciliation",true);
        groups=Set.copyOf(cfg.getStringList("roles.allowed-groups"));
        permissions=Set.copyOf(cfg.getStringList("roles.allowed-permissions"));
    }
    String primary(UUID uuid){
        User user=api.getUserManager().getUser(uuid);
        return user==null?"":user.getPrimaryGroup();
    }
    /** Signed, bounded queries for previously observed authenticated Java UUIDs.
     * Runs on the HTTP worker; LuckPerms storage loading is asynchronous. */
    String rankQuery(String body){
        if(!offlineReconciliation)return "{\"result\":\"DISABLED\"}";
        String[] requested=body.split("\\n",-1);
        if(requested.length<1||requested.length>4||new HashSet<>(Arrays.asList(requested)).size()!=requested.length)throw new IllegalArgumentException("Invalid rank query");
        for(String id:requested)if(!SanctionState.validIdentity(id)||!id.startsWith("java:"))throw new IllegalArgumentException("Java identity required");
        Map<String,java.util.concurrent.CompletableFuture<String>> queries=new LinkedHashMap<>();
        for(String id:requested){
            String hex=id.substring(5);
            UUID uuid=UUID.fromString(hex.substring(0,8)+"-"+hex.substring(8,12)+"-"+hex.substring(12,16)+"-"+hex.substring(16,20)+"-"+hex.substring(20));
            User cached=api.getUserManager().getUser(uuid);
            if(cached!=null){queries.put(id,java.util.concurrent.CompletableFuture.completedFuture(cached.getPrimaryGroup()));continue;}
            if(!rankSlots.tryAcquire())continue;
            try{
                queries.put(id,api.getUserManager().loadUser(uuid).handle((user,error)->{
                    try{return error==null&&user!=null?user.getPrimaryGroup():null;}
                    finally{
                        try{if(user!=null)api.getUserManager().cleanupUser(user);}finally{rankSlots.release();}
                    }
                }));
            }catch(RuntimeException e){rankSlots.release();}
        }
        try{java.util.concurrent.CompletableFuture.allOf(queries.values().toArray(java.util.concurrent.CompletableFuture[]::new)).get(2,java.util.concurrent.TimeUnit.SECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException ignored){}
        List<String> rows=new ArrayList<>();
        for(var e:queries.entrySet())if(e.getValue().isDone()&&!e.getValue().isCompletedExceptionally()){
            String group=e.getValue().getNow(null);
            if(group!=null&&group.matches("[a-z0-9_-]{1,40}"))rows.add(OrionBridge.quote(e.getKey())+":"+OrionBridge.quote(group));
        }
        return "{\"result\":\"OK\",\"groups\":{"+String.join(",",rows)+"}}";
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
