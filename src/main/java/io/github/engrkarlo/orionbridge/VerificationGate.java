package io.github.engrkarlo.orionbridge;

import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.command.PluginCommand;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

final class VerificationGate implements Listener,AutoCloseable {
    private final OrionBridge plugin;
    private final VerificationState state=new VerificationState();
    private final AuthenticatedIdentity identities;
    private final VerificationMenus menus;
    private final String invite;
    private final long lease;
    private final int reopenSeconds;
    private final BukkitTask refresh;
    private final Map<UUID,Long> lastShown=new HashMap<>();
    private final Map<UUID,Boolean> wasVerified=new HashMap<>();
    private volatile boolean active=true;
    private record Trust(String profile,long until){}
    private final java.util.concurrent.ConcurrentMap<UUID,Trust> trusted=new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<UUID,String> primaryGroups=new ConcurrentHashMap<>();
    String profile(UUID player){
        var s=state.get(player);
        var t=trusted.get(player);long now=System.currentTimeMillis();
        return active&&s!=null&&s.verified(now)&&t!=null&&t.until()>now?t.profile():null;
    }
    String linkedIdentity(UUID player){
        var s=state.get(player);
        return active&&s!=null&&s.verified(System.currentTimeMillis())?s.identity():null;
    }
    VerificationState.Session session(UUID player){return active?state.get(player):null;}
    boolean authorized(UUID player){return linkedIdentity(player)!=null;}
    VerificationGate(OrionBridge plugin,YamlConfiguration cfg)throws Exception{
        this.plugin=plugin;identities=plugin.identities();
        invite=cfg.getString("verification.discord-invite","");lease=Math.max(15,Math.min(60,cfg.getInt("verification.lease-seconds",60)))*1000L;
        reopenSeconds=Math.max(0,Math.min(300,cfg.getInt("verification.reopen-seconds",15)));
        menus=new VerificationMenus(plugin,cfg);
        for(Player p:plugin.getServer().getOnlinePlayers())state.join(p.getUniqueId(),identities.resolve(p));
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        refresh=plugin.getServer().getScheduler().runTaskTimer(plugin,this::sample,20L,20L);
    }
    private boolean restricted(Player p){return active&&state.restricted(p.getUniqueId(),System.currentTimeMillis());}
    private boolean same(Player p,VerificationState.Session old){
        var current=state.get(p.getUniqueId());
        return active&&p.isOnline()&&current!=null&&current.session().equals(old.session())&&Objects.equals(current.request(),old.request())&&Objects.equals(current.claimant(),old.claimant());
    }
    private void show(Player p){
        if(!active||!p.isOnline())return;
        var s=state.expireChallenge(p.getUniqueId(),System.currentTimeMillis());if(s==null)return;
        lastShown.put(p.getUniqueId(),System.currentTimeMillis());
        menus.show(p,s,invite,()->{if(same(p,s)){state.request(p.getUniqueId(),System.currentTimeMillis());show(p);}},()->{if(same(p,s))show(p);},()->{if(same(p,s)&&state.confirm(p.getUniqueId(),s.request(),s.claimant(),System.currentTimeMillis()))show(p);},()->{if(same(p,s))p.closeInventory();});
    }
    boolean playerCommand(Player p,String[] args){
        if(!active)return false;
        if(args.length==0||args.length==1&&Set.of("account","help").contains(args[0].toLowerCase(Locale.ROOT))){show(p);return true;}
        if(args.length==1&&args[0].equalsIgnoreCase("link")){
            var s=state.get(p.getUniqueId());if(s!=null&&s.request()==null)state.request(p.getUniqueId(),System.currentTimeMillis());
            show(p);return true;
        }
        if(args.length==2&&args[0].equalsIgnoreCase("confirm")){
            var s=state.get(p.getUniqueId());
            try{if(s!=null&&state.confirm(p.getUniqueId(),UUID.fromString(args[1]),s.claimant(),System.currentTimeMillis()))show(p);}catch(IllegalArgumentException ignored){}
            return true;
        }
        return false;
    }
    String snapshot(){
        if(!active)return "{\"protocol\":2,\"sessions\":[]}";
        List<String> rows=new ArrayList<>();long now=System.currentTimeMillis();
        for(var s:state.snapshot()){
            if(s.identity()==null||rows.size()>=2000)continue;
            rows.add("{\"player\":"+q(s.player().toString())+",\"session\":"+q(s.session().toString())+",\"identity\":"+q(s.identity())+",\"request\":"+(s.request()==null?"null":q(s.request().toString()))+",\"hasCode\":"+s.hasCode(now)+",\"claimant\":"+(s.claimant()==null?"null":q(s.claimant()))+",\"confirmed\":"+s.confirmed()+",\"leaseUntil\":"+s.leaseUntil()+",\"primaryGroup\":"+q(primaryGroups.getOrDefault(s.player(),""))+"}");
        }
        return "{\"protocol\":2,\"batchVerification\":true,\"leaseSeconds\":"+(lease/1000)+",\"sessions\":["+String.join(",",rows)+"]}";
    }
    private static String q(String v){return OrionBridge.quote(v);}
    String action(String body){
        if(!active)return "CLOSED";
        CompletableFuture<String> result=new CompletableFuture<>();
        try{plugin.getServer().getScheduler().runTask(plugin,()->{
            if(result.isDone())return;
            try{result.complete(execute(body));}catch(RuntimeException e){result.complete("INVALID");}
        });return result.get(3,TimeUnit.SECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();result.cancel(false);return "BUSY";}
        catch(ExecutionException|TimeoutException|RuntimeException e){result.cancel(false);return "BUSY";}
    }
    String batch(String body){
        if(!active)return "{\"results\":[]}";
        String[] rows=body.split("\\n\\n",-1);
        if(rows.length>64||rows.length==0)return "{\"results\":[]}";
        // One scheduled task, only lease/restriction changes; interactive linking
        // remains on the individual route to keep GUI work out of bulk renewals.
        CompletableFuture<String> result=new CompletableFuture<>();
        try{
            plugin.getServer().getScheduler().runTask(plugin,()->{
                if(result.isDone())return;
                List<String> receipts=new ArrayList<>();
                for(String row:rows){
                    if(result.isDone())return;
                    String value="INVALID";
                    if(row.startsWith("LEASE\n")||row.startsWith("RESTRICT\n"))try{value=execute(row);}catch(RuntimeException ignored){}
                    receipts.add(q(value));
                }
                result.complete("{\"results\":["+String.join(",",receipts)+"]}");
            });
            return result.get(3,TimeUnit.SECONDS);
        }catch(InterruptedException e){Thread.currentThread().interrupt();result.cancel(false);return "{\"results\":[]}";}
        catch(ExecutionException|TimeoutException|RuntimeException e){result.cancel(false);return "{\"results\":[]}";}
    }
    private String execute(String body){
        if(!active)return "CLOSED";
        String[] fields=body.split("\n",-1);if(fields.length<5)return "INVALID";
        UUID player=UUID.fromString(fields[1]),session=UUID.fromString(fields[2]);String identity=fields[3];
        if(!SanctionState.validIdentity(identity))return "INVALID";
        Player p=plugin.getServer().getPlayer(player);
        if(p==null||!p.isOnline()||!identity.equals(identities.fresh(p)))return "STALE";
        long now=System.currentTimeMillis();String result;
        switch(fields[0]){
            case "OFFER"->{if(fields.length!=7)return "INVALID";result=state.offer(player,session,identity,UUID.fromString(fields[4]),fields[5],Long.parseLong(fields[6]),now);}
            case "CLAIM"->{if(fields.length!=7)return "INVALID";result=state.claim(player,session,identity,UUID.fromString(fields[4]),fields[5],new String(Base64.getUrlDecoder().decode(fields[6]),StandardCharsets.UTF_8),now);}
            case "LEASE"->{
                if(fields.length<7||fields.length>10)return "INVALID";
                String profile=fields.length>=8?fields[7]:"";
                String groups=fields.length>=9?fields[8]:"";
                long trustUntil=fields.length==10?Long.parseLong(fields[9]):Long.parseLong(fields[6]);
                trustUntil=Math.min(trustUntil,Long.parseLong(fields[6]));
                if(!groups.isEmpty()&&!groups.matches("[a-z0-9_-]{1,40}(,[a-z0-9_-]{1,40}){0,9}"))return "INVALID";
                if(!profile.isEmpty()&&!profile.matches("[a-z0-9_-]{1,40}"))return "INVALID";
                result=state.lease(player,session,identity,fields[4],Long.parseLong(fields[5]),Long.parseLong(fields[6]),now,lease);
                if(result.equals("APPLIED")){
                    if(profile.isEmpty())trusted.remove(player);else trusted.put(player,new Trust(profile,trustUntil));
                    if(plugin.roles()!=null)try{
                        Set<String> desired=groups.isEmpty()?Set.of():new HashSet<>(Arrays.asList(groups.split(",")));
                        plugin.roles().sync(p,desired,Long.parseLong(fields[6]));
                    }catch(RuntimeException e){plugin.getLogger().warning("Managed rank reconciliation failed; prior temporary grants expire.");}
                }
            }
            case "RESTRICT"->{if(fields.length!=5)return "INVALID";result=state.restrict(player,session,identity,Long.parseLong(fields[4]),now);if(result.equals("APPLIED")){trusted.remove(player);if(plugin.roles()!=null)plugin.roles().clear(player);}}
            default->{return "INVALID";}
        }
        if(result.equals("APPLIED")){
            boolean verified=state.get(player).verified(now);
            if(!verified||!Boolean.TRUE.equals(wasVerified.get(player)))show(p);
            wasVerified.put(player,verified);
        }
        return result;
    }
    private void sample(){
        long now=System.currentTimeMillis();
        for(Player p:plugin.getServer().getOnlinePlayers()){
            UUID id=p.getUniqueId();String identity=identities.resolve(p);var s=state.get(id);
            if(plugin.roles()!=null)primaryGroups.put(id,plugin.roles().primary(id));
            if(s==null||!Objects.equals(s.identity(),identity)){trusted.remove(id);s=state.join(id,identity);show(p);}
            s=state.expireChallenge(id,now);
            boolean verified=s.verified(now),before=Boolean.TRUE.equals(wasVerified.put(id,verified));
            if(!verified&&(before||reopenSeconds>0&&now-lastShown.getOrDefault(id,0L)>=reopenSeconds*1000L))show(p);
        }
    }
    private boolean allowedCommand(Player p,String message){
        String[] parts=message.substring(1).trim().split("\\s+");
        String cmd=parts[0].toLowerCase(Locale.ROOT);
        PluginCommand resolved=plugin.getServer().getPluginCommand(cmd);
        if(resolved!=null&&resolved.getPlugin()==plugin&&resolved.getName().equals("ob")){
            return parts.length==1||parts.length==2&&Set.of("link","account","help").contains(parts[1].toLowerCase(Locale.ROOT))||parts.length==3&&parts[1].equalsIgnoreCase("confirm")&&parts[2].matches("[a-fA-F0-9-]{36}");
        }
        var s=state.get(p.getUniqueId());
        return (s==null||s.identity()==null)&&resolved!=null&&resolved.getPlugin().getName().equalsIgnoreCase("AuthMe")&&Set.of("login","register").contains(resolved.getName().toLowerCase(Locale.ROOT));
    }
    @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent e){Player p=e.getPlayer();trusted.remove(p.getUniqueId());state.join(p.getUniqueId(),identities.resolve(p));plugin.getServer().getScheduler().runTask(plugin,()->show(p));}
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();state.quit(id);trusted.remove(id);primaryGroups.remove(id);if(plugin.roles()!=null)plugin.roles().clear(id);identities.forget(id);lastShown.remove(id);wasVerified.remove(id);}
    @EventHandler(priority=EventPriority.MONITOR) public void respawn(PlayerRespawnEvent e){if(restricted(e.getPlayer()))plugin.getServer().getScheduler().runTask(plugin,()->show(e.getPlayer()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent e){
        if(restricted(e.getPlayer())&&e.getTo()!=null&&(e.getFrom().getWorld()!=e.getTo().getWorld()||e.getFrom().getX()!=e.getTo().getX()||e.getFrom().getY()!=e.getTo().getY()||e.getFrom().getZ()!=e.getTo().getZ()))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void placeBlock(BlockPlaceEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void empty(PlayerBucketEmptyEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fill(PlayerBucketFillEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entity(PlayerInteractEntityEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entityAt(PlayerInteractAtEntityEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p&&restricted(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void swap(PlayerSwapHandItemsEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void consume(PlayerItemConsumeEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fish(PlayerFishEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void enter(VehicleEnterEvent e){if(e.getEntered() instanceof Player p&&restricted(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p&&restricted(p)&&!VerificationMenus.owns(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p&&restricted(p)&&!VerificationMenus.owns(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p&&restricted(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void chat(AsyncChatEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e){if(restricted(e.getPlayer())&&!allowedCommand(e.getPlayer(),e.getMessage()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e){
        if(e.getDamager() instanceof Player p&&restricted(p))e.setCancelled(true);
        if(e.getDamager() instanceof Projectile projectile&&projectile.getShooter() instanceof Player p&&restricted(p))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void hurt(EntityDamageEvent e){if(e.getEntity() instanceof Player p&&restricted(p))e.setCancelled(true);}
    @Override public void close(){active=false;refresh.cancel();HandlerList.unregisterAll(this);menus.close();state.clear();trusted.clear();primaryGroups.clear();}
}
