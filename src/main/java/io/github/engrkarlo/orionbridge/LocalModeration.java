package io.github.engrkarlo.orionbridge;

import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.projectiles.ProjectileSource;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit typed sanctions only. No remote command execution or permission mutation. */
final class LocalModeration implements Listener,AutoCloseable {
    private final OrionBridge plugin;
    final SanctionState state;
    private final AuthenticatedIdentity identities;
    private final Map<UUID,String> online=new ConcurrentHashMap<>();
    private final AtomicBoolean open=new AtomicBoolean(true);
    private final org.bukkit.scheduler.BukkitTask refresh;
    LocalModeration(OrionBridge plugin,YamlConfiguration cfg)throws Exception{
        this.plugin=plugin;identities=plugin.identities();
        state=new SanctionState(plugin.getDataFolder().toPath().resolve("sanctions").resolve(cfg.getString("audit.guild-id")).resolve(cfg.getString("audit.server-id")));
        for(Player p:plugin.getServer().getOnlinePlayers())track(p);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        refresh=plugin.getServer().getScheduler().runTaskTimer(plugin,()->{for(Player player:plugin.getServer().getOnlinePlayers()){String before=online.get(player.getUniqueId());track(player);if(!Objects.equals(before,online.get(player.getUniqueId())))notifyBan(player);}},20L,20L);
    }
    private void track(Player p){String id=identities.resolve(p);if(id==null)online.remove(p.getUniqueId());else online.put(p.getUniqueId(),id);}
    String apply(SanctionState.Case c)throws java.io.IOException{
        if(!open.get())return "CLOSED";
        String result=state.apply(c,System.currentTimeMillis());
        if(result.equals("PERSISTED"))try{plugin.getServer().getScheduler().runTask(plugin,()->{
            if(!open.get())return;
            for(Player p:plugin.getServer().getOnlinePlayers())if(c.identity().equals(online.get(p.getUniqueId())))notifyBan(p);
        });}catch(RuntimeException ignored){/* The persisted sanction is checked on the next event/login. */}
        return result;
    }
    boolean denied(UUID player,SanctionState.Kind... kinds){return open.get()&&state.has(online.get(player),System.currentTimeMillis(),kinds);}
    private boolean has(Player p,SanctionState.Kind... kinds){return open.get()&&state.has(online.get(p.getUniqueId()),System.currentTimeMillis(),kinds);}
    private boolean frozen(Player p){return has(p,SanctionState.Kind.FREEZE,SanctionState.Kind.QUARANTINE,SanctionState.Kind.TEMP_BAN,SanctionState.Kind.PERMANENT_BAN);}
    private boolean build(Player p){return frozen(p)||has(p,SanctionState.Kind.BUILD_RESTRICTION);}
    private void notifyBan(Player p){
        for(var c:state.active(online.get(p.getUniqueId()),System.currentTimeMillis()))if(c.kind()==SanctionState.Kind.TEMP_BAN||c.kind()==SanctionState.Kind.PERMANENT_BAN){
            p.kick(Component.text("Access restricted. "+c.reason()+" | Case "+c.id()));return;
        }
    }
    @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent e){track(e.getPlayer());notifyBan(e.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){online.remove(e.getPlayer().getUniqueId());identities.forget(e.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent e){
        if(frozen(e.getPlayer())&&e.getTo()!=null&&(e.getFrom().getWorld()!=e.getTo().getWorld()||e.getFrom().getX()!=e.getTo().getX()||e.getFrom().getY()!=e.getTo().getY()||e.getFrom().getZ()!=e.getTo().getZ()))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e){if(frozen(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(build(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void placeBlock(BlockPlaceEvent e){if(build(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void empty(PlayerBucketEmptyEvent e){if(build(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fill(PlayerBucketFillEvent e){if(build(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(frozen(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interactEntity(PlayerInteractEntityEvent e){if(frozen(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e){if(frozen(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p&&frozen(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p&&frozen(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p&&frozen(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p&&frozen(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void chat(AsyncChatEvent e){if(frozen(e.getPlayer())||has(e.getPlayer(),SanctionState.Kind.MUTE))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e){if(frozen(e.getPlayer())||has(e.getPlayer(),SanctionState.Kind.COMMAND_RESTRICTION))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e){
        if(e.getDamager() instanceof Player p&&frozen(p))e.setCancelled(true);
        if(e.getDamager() instanceof org.bukkit.entity.Projectile projectile&&projectile.getShooter() instanceof Player p&&frozen(p))e.setCancelled(true);
    }
    @Override public void close(){open.set(false);refresh.cancel();HandlerList.unregisterAll(this);online.clear();}
}
