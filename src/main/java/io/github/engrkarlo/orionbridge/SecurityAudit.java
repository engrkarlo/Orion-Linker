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
    private final int threshold;
    private final Map<UUID,Window> windows=new HashMap<>();
    private final Map<UUID,Boolean> ops=new HashMap<>();
    private final Map<UUID,Long> commandAlerts=new HashMap<>();
    private final BukkitTask task;
    private long reportedDrops;
    private record Window(long started,int count,boolean alerted){}
    SecurityAudit(OrionBridge plugin,YamlConfiguration cfg)throws Exception{
        this.plugin=plugin;identities=new AuthenticatedIdentity(plugin,cfg.getString("verification.authme-realm",""));String guild=cfg.getString("audit.guild-id",""),server=cfg.getString("audit.server-id",""),key=cfg.getString("audit.signing-key","");
        AuditAuth auth=new AuditAuth(guild,server,key);
        threshold=Math.max(10,Math.min(100000,cfg.getInt("audit.block-changes-per-minute",500)));
        journal=new AuditJournal(plugin.getDataFolder().toPath().resolve("audit").resolve(guild).resolve(server));
        endpoint=new AuditEndpoint(journal,auth);
        for(Player p:plugin.getServer().getOnlinePlayers())ops.put(p.getUniqueId(),p.isOp());
        BukkitTask started=null;
        try{
            plugin.getServer().getPluginManager().registerEvents(this,plugin);
            started=plugin.getServer().getScheduler().runTaskTimer(plugin,this::sample,20L,20L);
        }catch(RuntimeException e){HandlerList.unregisterAll(this);journal.close();throw e;}
        task=started;
    }
    private String identity(Player p){
        String id=identities.resolve(p);
        return id==null?"null":OrionBridge.quote(id);
    }
    private void emit(Player p,String kind,String detail){
        UUID id=UUID.randomUUID();
        String safe=detail.replaceAll("[\\p{Cntrl}]"," ");if(safe.length()>1000)safe=safe.substring(0,1000);
        journal.offer(id,"{\"id\":"+OrionBridge.quote(id.toString())+",\"at\":"+System.currentTimeMillis()+",\"kind\":"+OrionBridge.quote(kind)+",\"identity\":"+identity(p)+",\"detail\":"+OrionBridge.quote(safe)+",\"source\":\"Paper\"}");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){Player p=e.getPlayer();ops.put(p.getUniqueId(),p.isOp());emit(p,"JOIN","Player joined: "+p.getName()+"; operator="+p.isOp());}
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){Player p=e.getPlayer();emit(p,"QUIT","Player left: "+p.getName());ops.remove(p.getUniqueId());windows.remove(p.getUniqueId());commandAlerts.remove(p.getUniqueId());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void broken(BlockBreakEvent e){count(e.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void placed(BlockPlaceEvent e){count(e.getPlayer());}
    private void count(Player p){
        long now=System.nanoTime();UUID id=p.getUniqueId();Window old=windows.get(id);
        if(old==null||now-old.started()>=60_000_000_000L)old=new Window(now,0,false);
        int count=old.count()+1;boolean alert=!old.alerted()&&count>=threshold;
        windows.put(id,new Window(old.started(),count,old.alerted()||alert));
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
            Boolean old=ops.put(p.getUniqueId(),p.isOp());
            if(old!=null&&old!=p.isOp())emit(p,"OP_STATUS_CHANGED","Affected account "+p.getName()+"; operator "+old+" -> "+p.isOp()+"; initiator unknown");
        }
        long drops=journal.dropped();if(drops!=reportedDrops){plugin.getLogger().warning("Audit spool dropped "+drops+" records since startup; check storage and consumer.");reportedDrops=drops;}
    }
    @Override public void close(){task.cancel();HandlerList.unregisterAll(this);journal.close();}
}
