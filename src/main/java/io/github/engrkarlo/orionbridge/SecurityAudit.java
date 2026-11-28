package io.github.engrkarlo.orionbridge;

import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.Path;
import java.util.*;

/** Observation only: no permission changes or punishments in the development foundation. */
final class SecurityAudit implements Listener,AutoCloseable {
    final AuditEndpoint endpoint;
    private final AuditJournal journal;
    private final OrionBridge plugin;
    private final AuthenticatedIdentity identities;
    private final NetworkFingerprint networks;
    private final Map<UUID,String> networkSeen=new HashMap<>();
    private final int threshold;
    private final boolean hideVanished,respectListing;
    private final Map<UUID,Window> windows=new HashMap<>();
    private final Map<UUID,Boolean> ops=new HashMap<>();
    private final Map<UUID,Long> commandAlerts=new HashMap<>();
    private final BukkitTask task;
    private long reportedDrops;
    private static final class Window {long started;int count;boolean alerted;Window(long started){this.started=started;}}
    SecurityAudit(OrionBridge plugin,YamlConfiguration cfg)throws Exception{
        this.plugin=plugin;identities=plugin.identities();String guild=cfg.getString("audit.guild-id",""),server=cfg.getString("audit.server-id",""),key=cfg.getString("audit.signing-key","");
        AuditAuth auth=plugin.auditAuthorization(guild,server,key);
        networks=cfg.getBoolean("correlation.enabled",false)&&cfg.getBoolean("correlation.confirm-real-client-addresses",false)?new NetworkFingerprint(guild,cfg.getString("correlation.guild-key","")):null;
        hideVanished=cfg.getBoolean("hide-vanished",true);respectListing=cfg.getBoolean("respect-listing-preference",true);
        threshold=Math.max(10,Math.min(100000,cfg.getInt("audit.block-changes-per-minute",500)));
        journal=new AuditJournal(plugin.getDataFolder().toPath().resolve("audit").resolve(guild).resolve(server));
        endpoint=new AuditEndpoint(journal,auth);
        for(Player p:plugin.getServer().getOnlinePlayers())ops.put(p.getUniqueId(),p.isOp());
        BukkitTask started=null;
        try{
            plugin.getServer().getPluginManager().registerEvents(this,plugin);
            started=plugin.getServer().getScheduler().runTaskTimer(plugin,()->plugin.metrics().measure("audit",this::sample),20L,20L);
        }catch(RuntimeException e){HandlerList.unregisterAll(this);journal.close();throw e;}
        task=started;
    }
    private String identity(Player p){
        String id=identities.resolve(p);
        return id==null?"null":OrionBridge.quote(id);
    }
    private void emit(Player p,String kind,String detail){
        if(Set.of("JOIN","QUIT","DEATH","ADVANCEMENT").contains(kind)&&(p.hasPermission("orionbridge.hide")||(hideVanished&&p.getMetadata("vanished").stream().anyMatch(v->v.asBoolean()))||(respectListing&&!p.isAllowingServerListings())))return;
        external(kind,identities.resolve(p),detail,"Paper");
    }
    void external(String kind,String identity,String detail,String source){
        String attribution=switch(kind){
            case "OP_STATUS_CHANGED","PERMISSION_MUTATION","PERMISSION_CLEAR" -> "AFFECTED";
            case "ADMIN_PUNISH_REQUEST" -> "REQUEST_TARGET";
            case "JOIN","QUIT","DEATH","ADVANCEMENT","PLAYER_REPORT","AUTHENTICATED_NETWORK","NETWORK_MATCH" -> "SUBJECT";
            default -> identity==null?"UNKNOWN":"OBSERVED_ACTOR";
        };
        attributed(kind,identity,attribution,null,detail,source);
    }
    void attributed(String kind,String identity,String attribution,String affected,String detail,String source){
        UUID id=UUID.randomUUID();
        String safe=detail.replaceAll("[\\p{Cntrl}]"," ");if(safe.length()>1000)safe=safe.substring(0,1000);
        journal.offer(id,"{\"id\":"+OrionBridge.quote(id.toString())+",\"at\":"+System.currentTimeMillis()+",\"kind\":"+OrionBridge.quote(kind)+",\"identity\":"+(identity==null?"null":OrionBridge.quote(identity))+",\"attribution\":"+OrionBridge.quote(attribution)+",\"targetIdentity\":"+(affected==null?"null":OrionBridge.quote(affected))+",\"detail\":"+OrionBridge.quote(safe)+",\"source\":"+OrionBridge.quote(source)+"}");
        plugin.signal("audit");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){Player p=e.getPlayer();ops.put(p.getUniqueId(),p.isOp());emit(p,"JOIN","Player joined: "+p.getName());}
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){Player p=e.getPlayer();emit(p,"QUIT","Player left: "+p.getName());ops.remove(p.getUniqueId());windows.remove(p.getUniqueId());commandAlerts.remove(p.getUniqueId());networkSeen.remove(p.getUniqueId());identities.forget(p.getUniqueId());}
    @EventHandler(priority=EventPriority.MONITOR) public void death(org.bukkit.event.entity.PlayerDeathEvent e){emit(e.getEntity(),"DEATH","Player died: "+e.getEntity().getName());}
    @EventHandler(priority=EventPriority.MONITOR) public void advancement(PlayerAdvancementDoneEvent e){emit(e.getPlayer(),"ADVANCEMENT","Player "+e.getPlayer().getName()+" completed "+e.getAdvancement().getKey());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void broken(BlockBreakEvent e){count(e.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void placed(BlockPlaceEvent e){count(e.getPlayer());}
    private void count(Player p){
        long now=System.nanoTime();UUID id=p.getUniqueId();Window old=windows.get(id);
        if(old==null){old=new Window(now);windows.put(id,old);}
        else if(now-old.started>=60_000_000_000L){old.started=now;old.count=0;old.alerted=false;}
        int count=++old.count;boolean alert=!old.alerted&&count>=threshold;
        if(alert)old.alerted=true;
        if(alert)emit(p,"BLOCK_ACTIVITY_THRESHOLD","Player "+p.getName()+" reached "+count+" uncancelled break/place events within one minute; world="+p.getWorld().getName()+"; not proof of griefing");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void command(PlayerCommandPreprocessEvent e){
        String first=e.getMessage().split("\\s+",2)[0].toLowerCase(Locale.ROOT);String name=first.substring(Math.max(first.lastIndexOf(':')+1,1));
        if(!Set.of("op","deop","lp","luckperms","stop","reload","plugman","worldedit","fawe").contains(name))return;
        Player p=e.getPlayer();long now=System.nanoTime();Long last=commandAlerts.get(p.getUniqueId());
        if(last!=null&&now-last<10_000_000_000L)return;
        commandAlerts.put(p.getUniqueId(),now);emit(p,"SENSITIVE_COMMAND_ATTEMPT","Player "+p.getName()+" attempted command category "+name+"; event cancelled="+e.isCancelled()+"; execution outcome unknown");
    }
    private void sample(){
        for(Player p:plugin.getServer().getOnlinePlayers()){
            if(networks!=null){
                String identity=identities.resolve(p);
                if(identity==null)networkSeen.remove(p.getUniqueId());
                else if(!identity.equals(networkSeen.get(p.getUniqueId()))){
                    networkSeen.put(p.getUniqueId(),identity);
                    var socket=p.getAddress();String fingerprint=socket==null?null:networks.fingerprint(socket.getAddress());
                    if(fingerprint!=null){
                        UUID event=UUID.randomUUID();
                        journal.offer(event,"{\"id\":"+OrionBridge.quote(event.toString())+",\"at\":"+System.currentTimeMillis()+",\"kind\":\"AUTHENTICATED_NETWORK\",\"identity\":"+OrionBridge.quote(identity)+",\"detail\":\"Authenticated Minecraft connection fingerprint\",\"source\":\"Paper\",\"fingerprint\":"+OrionBridge.quote(fingerprint)+"}");
        plugin.signal("audit");
                    }
                }
            }
            Boolean old=ops.put(p.getUniqueId(),p.isOp());
            if(old!=null&&old!=p.isOp())emit(p,"OP_STATUS_CHANGED","Affected account "+p.getName()+"; operator "+old+" -> "+p.isOp()+"; initiator unknown");
        }
        long drops=journal.dropped();if(drops!=reportedDrops){plugin.getLogger().warning("Audit spool dropped "+drops+" records since startup; check storage and consumer.");reportedDrops=drops;}
    }
    @Override public void close(){task.cancel();HandlerList.unregisterAll(this);journal.close();}
}
