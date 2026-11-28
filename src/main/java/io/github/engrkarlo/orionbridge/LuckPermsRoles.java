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
    private final OrionBridge plugin;
    private final Set<String> groups,permissions;
    private final boolean offlineReconciliation,observedOffline;
    private final String authmeRealm;
    private final java.util.concurrent.Semaphore rankSlots=new java.util.concurrent.Semaphore(4),providerSlots=new java.util.concurrent.Semaphore(4);
    private final Map<UUID,Map<String,InheritanceNode>> owned=new HashMap<>();
    LuckPermsRoles(OrionBridge plugin,YamlConfiguration cfg){
        this.plugin=plugin;
        offlineReconciliation=cfg.getBoolean("roles.offline-reconciliation",true);
        observedOffline=cfg.getBoolean("roles.offline-observed-identities",false);
        authmeRealm=cfg.getString("verification.authme-realm","");
        groups=Set.copyOf(cfg.getStringList("roles.allowed-groups"));
        permissions=Set.copyOf(cfg.getStringList("roles.allowed-permissions"));
    }
    String primary(UUID uuid){
        User user=api.getUserManager().getUser(uuid);
        return user==null?"":user.getPrimaryGroup();
    }
    /** Signed, bounded queries for previously observed authenticated Java and unlinked Floodgate identities.
     * Runs on the HTTP worker; LuckPerms storage loading is asynchronous. */
    String rankQuery(String body){
        if(!offlineReconciliation)return "{\"result\":\"DISABLED\"}";
        String[] requested=body.split("\\n",-1);
        if(requested.length<1||requested.length>4||new HashSet<>(Arrays.asList(requested)).size()!=requested.length)throw new IllegalArgumentException("Invalid rank query");
        Map<String,UUID> ids=new LinkedHashMap<>();
        Set<String> seen=new HashSet<>();
        for(String line:requested){
            String[] fields=line.split("\\|",-1);
            String id=fields[0];
            if(!SanctionState.validIdentity(id)||!seen.add(id)||fields.length!=1&&fields.length!=3)throw new IllegalArgumentException("Invalid rank identity");
            UUID uuid;
            if(fields.length==3){
                uuid=observedRankUuid(id,fields[1],fields[2]);
            }else if(id.startsWith("java:")||id.startsWith("bedrock:"))uuid=rankUuid(id);
            else uuid=null;
            if(uuid!=null&&observedSessionMatches(id,uuid))ids.put(id,uuid);
        }
        Map<String,java.util.concurrent.CompletableFuture<String>> queries=new LinkedHashMap<>();
        for(var entry:ids.entrySet()){
            String id=entry.getKey();UUID uuid=entry.getValue();
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
            if(group!=null&&group.matches("[a-z0-9_-]{1,40}")&&observedSessionMatches(e.getKey(),ids.get(e.getKey())))rows.add(OrionBridge.quote(e.getKey())+":"+OrionBridge.quote(group));
        }
        return "{\"result\":\"OK\",\"groups\":{"+String.join(",",rows)+"}}";
    }
    private boolean observedSessionMatches(String identity,UUID uuid){
        var gate=plugin.verification();
        if(gate==null)return false;
        var s=gate.session(uuid);
        return s==null||identity.equals(s.identity());
    }
    private UUID observedRankUuid(String identity,String raw,String observedAt){
        if(!observedOffline||!raw.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")||!observedAt.matches("[0-9]{1,16}"))return null;
        long seen=Long.parseLong(observedAt),now=System.currentTimeMillis();
        if(seen>now+5000L||now-seen>86400000L)return null;
        UUID uuid=UUID.fromString(raw);
        if(uuid.getMostSignificantBits()==0&&uuid.getLeastSignificantBits()==0)return null;
        if(identity.startsWith("authme:")){
            if(authmeRealm.isBlank()||!identity.startsWith("authme:"+authmeRealm+":")||!plugin.getServer().getPluginManager().isPluginEnabled("AuthMe"))return null;
        }else if(identity.startsWith("bedrock:")){
            // A wrong canonical XUID UUID is never eligible as an observed mapping.
            if(uuid.getMostSignificantBits()==0||rankUuid(identity)==null)return null;
        }else return null;
        return observedSessionMatches(identity,uuid)?uuid:null;
    }
    private UUID rankUuid(String id){
        if(id.startsWith("java:")){
            String hex=id.substring(5);
            UUID uuid=UUID.fromString(hex.substring(0,8)+"-"+hex.substring(8,12)+"-"+hex.substring(12,16)+"-"+hex.substring(16,20)+"-"+hex.substring(20));
            return uuid.version()==4&&uuid.variant()==2?uuid:null;
        }
        // Never infer an authenticated identity from a name or prefix. The host
        // sends only previously verified matching identity/UUID observations.
        var flood=plugin.getServer().getPluginManager().getPlugin("floodgate");
        if(flood==null||!flood.isEnabled())return null;
        try{
            Class<?> type=Class.forName("org.geysermc.floodgate.api.FloodgateApi",true,flood.getClass().getClassLoader());
            Object api=type.getMethod("getInstance").invoke(null);
            Object value=type.getMethod("createJavaPlayerId",long.class).invoke(api,Long.parseUnsignedLong(id.substring(8)));
            if(!(value instanceof UUID uuid)||uuid.getMostSignificantBits()!=0||uuid.getLeastSignificantBits()!=Long.parseUnsignedLong(id.substring(8)))return null;
            return floodgateUnlinked(type,api,uuid,flood.getClass().getClassLoader())?uuid:null;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){return null;}
    }
    private boolean floodgateUnlinked(Class<?> apiType,Object api,UUID uuid,ClassLoader loader){
        // Only local linking providers can authoritatively answer offline. A
        // global-only provider or a failed lookup is UNKNOWN, never a rank change.
        try{
            Object link=apiType.getMethod("getPlayerLink").invoke(api);
            if(link==null)return false;
            Class<?> type=Class.forName("org.geysermc.floodgate.api.link.PlayerLink",true,loader);
            Object enabled=type.getMethod("isEnabled").invoke(link);
            if(Boolean.FALSE.equals(enabled))return true;
            if(!Boolean.TRUE.equals(enabled))return false;
            if(type.getMethod("getName").invoke(link)==null||!providerSlots.tryAcquire())return false;
            boolean handedOff=false;
            try{
                Object result=type.getMethod("getLinkedPlayer",UUID.class).invoke(link,uuid);
                if(!(result instanceof java.util.concurrent.CompletableFuture<?> future))return false;
                var bounded=future.whenComplete((v,e)->providerSlots.release());handedOff=true;
                return bounded.get(300,java.util.concurrent.TimeUnit.MILLISECONDS)==null;
            }finally{if(!handedOff)providerSlots.release();}
        }catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
        catch(ReflectiveOperationException|java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException|RuntimeException|LinkageError e){return false;}
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
        long now=System.currentTimeMillis();
        var active=owned.computeIfAbsent(id,k->new HashMap<>());
        // Validate group inheritance each renewal, but avoid replacing unchanged
        // transient nodes while sufficient authorized lifetime remains.
        Set<String> safeDesired=new HashSet<>();
        if(until>now)for(String name:desired)if(safe(name,new HashSet<>(),0))safeDesired.add(name);
        for(var it=active.entrySet().iterator();it.hasNext();){
            var entry=it.next();var node=entry.getValue();
            boolean stillOwned=user.transientData().toCollection().contains(node);
            long expiry=node.getExpiry()==null?0:node.getExpiry().toEpochMilli();
            if(stillOwned&&safeDesired.contains(entry.getKey())&&expiry<=until&&expiry-now>=Math.min(20000L,Math.max(1000L,(until-now)/2)))continue;
            if(stillOwned)user.transientData().remove(node);
            it.remove();
        }
        for(String name:safeDesired){
            if(active.containsKey(name))continue;
            var node=InheritanceNode.builder(name).expiry(Instant.ofEpochMilli(until)).build();
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
