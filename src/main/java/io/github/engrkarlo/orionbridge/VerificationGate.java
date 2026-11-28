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
    private final VerificationState state;
    VerificationState reloadState(){return state.forReload(System.currentTimeMillis());}
    private final AuthenticatedIdentity identities;
    private final VerificationMenus menus;
    private final VerificationLobby lobby;
    private final String invite;
    private final long lease;
    private final int reopenSeconds;
    private final BukkitTask refresh;
    private final Map<UUID,Long> lastShown=new HashMap<>();
    private final Map<UUID,Boolean> wasVerified=new HashMap<>();
    private volatile boolean active=true;
    private volatile CachedSnapshot cachedSnapshot;
    private record CachedSnapshot(long created,String json){}
    private record Trust(String profile,long until,TaskScope task){}
    record EditAuthorization(UUID session,String identity,String profile,TaskScope task){}
    TaskScope task(UUID player){var a=editAuthorization(player);return a==null?null:a.task();}
    private final java.util.concurrent.ConcurrentMap<UUID,Trust> trusted=new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<UUID,String> primaryGroups=new ConcurrentHashMap<>();
    EditAuthorization editAuthorization(UUID player){
        var s=state.get(player);var t=trusted.get(player);long now=System.currentTimeMillis();
        if(!active||s==null||t==null||t.until()<=now||!s.verified(now))return null;
        if(org.bukkit.Bukkit.isPrimaryThread()){
            Player p=plugin.getServer().getPlayer(player);
            if(p==null||!Objects.equals(s.identity(),identities.resolve(p)))return null;
        }
        if(state.get(player)!=s||trusted.get(player)!=t)return null;
        return new EditAuthorization(s.session(),s.identity(),t.profile(),t.task());
    }
    String profile(UUID player){var a=editAuthorization(player);return a==null?null:a.profile();}
    String linkedIdentity(UUID player){
        var s=state.get(player);
        if(!active||s==null||!s.verified(System.currentTimeMillis()))return null;
        if(org.bukkit.Bukkit.isPrimaryThread()){
            Player p=plugin.getServer().getPlayer(player);
            if(p==null||!Objects.equals(s.identity(),identities.resolve(p)))return null;
        }
        return s.identity();
    }
    VerificationState.Session session(UUID player){return active?state.get(player):null;}
    boolean authorized(UUID player){return linkedIdentity(player)!=null;}
    VerificationGate(OrionBridge plugin,YamlConfiguration cfg)throws Exception{this(plugin,cfg,null);}
    VerificationGate(OrionBridge plugin,YamlConfiguration cfg,VerificationState previous)throws Exception{
        this.state=previous==null?new VerificationState():previous.forReload(System.currentTimeMillis());
        this.plugin=plugin;identities=plugin.identities();
        invite=cfg.getString("verification.discord-invite","");lease=Math.max(15,Math.min(60,cfg.getInt("verification.lease-seconds",60)))*1000L;
        reopenSeconds=Math.max(0,Math.min(300,cfg.getInt("verification.reopen-seconds",15)));
        lobby=new VerificationLobby(plugin,cfg);
        menus=new VerificationMenus(plugin,cfg);
        for(Player p:plugin.getServer().getOnlinePlayers())state.join(p.getUniqueId(),identities.resolve(p));
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        refresh=plugin.getServer().getScheduler().runTaskTimer(plugin,()->plugin.metrics().measure("verification",this::sample),20L,20L);
    }
    private boolean restricted(Player p){
        if(!active)return false;
        var s=state.get(p.getUniqueId());
        if(s==null||!s.verified(System.currentTimeMillis()))return true;
        // AuthMe logout/re-authentication cannot use the previous lease between samples.
        return org.bukkit.Bukkit.isPrimaryThread()&&!Objects.equals(s.identity(),identities.resolve(p));
    }
    private boolean same(Player p,VerificationState.Session old){
        var current=state.get(p.getUniqueId());
        return active&&p.isOnline()&&current!=null&&Objects.equals(current.identity(),identities.fresh(p))&&current.session().equals(old.session())&&Objects.equals(current.request(),old.request())&&Objects.equals(current.claimant(),old.claimant());
    }
    void refreshAfterReload(){for(Player p:plugin.getServer().getOnlinePlayers())if(restricted(p))show(p);}
    private void show(Player p){
        if(!active||!p.isOnline())return;
        var s=state.expireChallenge(p.getUniqueId(),System.currentTimeMillis());if(s==null)return;
        long now=System.currentTimeMillis();
        // Do not open a modal/inventory over AuthMe's login flow.
        if(s.identity()==null){
            if(!lastShown.containsKey(p.getUniqueId()))plugin.messages().send(p,"verification.authenticate-first");
            lastShown.put(p.getUniqueId(),now);return;
        }
        if(s.verified(now)){
            if(!lobby.restore(p,s.identity()))plugin.messages().send(p,"verification.return-unavailable");
        }else lobby.enter(p,s.identity());
        lastShown.put(p.getUniqueId(),now);
        menus.show(p,s,invite,()->{if(same(p,s)){state.request(p.getUniqueId(),System.currentTimeMillis());show(p);}},()->{if(same(p,s))show(p);},()->{if(same(p,s)&&state.confirm(p.getUniqueId(),s.request(),s.claimant(),System.currentTimeMillis()))show(p);},()->{if(same(p,s))p.closeInventory();});
    }
    boolean playerCommand(Player p,String[] args){
        if(!active)return false;
        UUID id=p.getUniqueId();String identity=identities.fresh(p);
        var current=state.get(id);
        if(current==null||!Objects.equals(current.identity(),identity)){
            trusted.remove(id);if(plugin.roles()!=null)plugin.roles().clear(id);
            state.join(id,identity);lastShown.remove(id);
        }
        if(identity==null&&args.length==1&&args[0].equalsIgnoreCase("link")){
            plugin.messages().send(p,"verification.authenticate-link");return true;
        }
        if(args.length==1&&args[0].equalsIgnoreCase("return")){
            if(restricted(p)){plugin.messages().send(p,"verification.return-required");return true;}
            plugin.messages().send(p,lobby.restore(p,identity)?"verification.return-ok":"verification.return-unavailable");return true;
        }
        if(args.length==0||args.length==1&&Set.of("account","help").contains(args[0].toLowerCase(Locale.ROOT))){show(p);return true;}
        if(args.length==1&&args[0].equalsIgnoreCase("link")){
            long now=System.currentTimeMillis();var s=state.expireChallenge(p.getUniqueId(),now);if(s!=null&&s.request()==null)state.request(p.getUniqueId(),now);
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
        long nanos=System.nanoTime();var cached=cachedSnapshot;if(cached!=null&&nanos-cached.created()<250_000_000L)return cached.json();
        List<String> rows=new ArrayList<>();long now=System.currentTimeMillis();
        for(var s:state.snapshot()){
            if(s.identity()==null||rows.size()>=2000)continue;
            rows.add("{\"player\":"+q(s.player().toString())+",\"session\":"+q(s.session().toString())+",\"identity\":"+q(s.identity())+",\"request\":"+(s.request()==null?"null":q(s.request().toString()))+",\"regenerate\":"+s.regenerate()+",\"hasCode\":"+s.hasCode(now)+",\"claimant\":"+(s.claimant()==null?"null":q(s.claimant()))+",\"confirmed\":"+s.confirmed()+",\"leaseUntil\":"+s.leaseUntil()+",\"primaryGroup\":"+q(primaryGroups.getOrDefault(s.player(),""))+"}");
        }
        String json="{\"protocol\":2,\"batchVerification\":true,\"resumeChallenges\":true,\"taskScopes\":true,\"leaseSeconds\":"+(lease/1000)+",\"sessions\":["+String.join(",",rows)+"]}";
        cachedSnapshot=new CachedSnapshot(nanos,json);return json;
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
            case "RESUME"->{if(fields.length!=8)return "INVALID";result=state.resume(player,session,identity,fields[4].equals("-")?null:UUID.fromString(fields[4]),UUID.fromString(fields[5]),fields[6],Long.parseLong(fields[7]),now);}
            case "CLAIM"->{if(fields.length!=7)return "INVALID";result=state.claim(player,session,identity,UUID.fromString(fields[4]),fields[5],new String(Base64.getUrlDecoder().decode(fields[6]),StandardCharsets.UTF_8),now);}
            case "LEASE"->{
                if(fields.length<7||fields.length>11)return "INVALID";
                String profile=fields.length>=8?fields[7]:"";
                String groups=fields.length>=9?fields[8]:"";
                long trustUntil=fields.length>=10?Long.parseLong(fields[9]):Long.parseLong(fields[6]);
                trustUntil=Math.min(trustUntil,Long.parseLong(fields[6]));
                TaskScope task=fields.length==11?TaskScope.parse(fields[10],now):null;
                if(task!=null&&(profile.isEmpty()||trustUntil>task.expires()))return "INVALID";
                if(!groups.isEmpty()&&!groups.matches("[a-z0-9_-]{1,40}(,[a-z0-9_-]{1,40}){0,9}"))return "INVALID";
                if(!profile.isEmpty()&&!profile.matches("[a-z0-9_-]{1,40}"))return "INVALID";
                result=state.lease(player,session,identity,fields[4],Long.parseLong(fields[5]),Long.parseLong(fields[6]),now,lease);
                if(result.equals("APPLIED")){
                    if(profile.isEmpty())trusted.remove(player);else trusted.put(player,new Trust(profile,trustUntil,task));
                    if(plugin.roles()!=null)try{
                        Set<String> desired=groups.isEmpty()||plugin.moderation()!=null&&plugin.moderation().denied(player,SanctionState.Kind.ROLE_SUSPENSION)?Set.of():new HashSet<>(Arrays.asList(groups.split(",")));
                        plugin.roles().sync(p,desired,Long.parseLong(fields[6]));
                    }catch(RuntimeException e){plugin.getLogger().warning("Managed rank reconciliation failed; prior temporary grants expire.");}
                }
            }
            case "RESTRICT"->{if(fields.length!=5)return "INVALID";result=state.restrict(player,session,identity,Long.parseLong(fields[4]),now);if(result.equals("APPLIED")){trusted.remove(player);if(plugin.roles()!=null)plugin.roles().clear(player);}}
            default->{return "INVALID";}
        }
        if(result.equals("APPLIED")){
            cachedSnapshot=null;
            boolean verified=state.get(player).verified(now);
            if(!verified||!Boolean.TRUE.equals(wasVerified.get(player)))show(p);
            wasVerified.put(player,verified);
        }
        return result;
    }
    private void sample(){
        cachedSnapshot=null;
        long now=System.currentTimeMillis();
        state.pruneDetached(now);
        for(Player p:plugin.getServer().getOnlinePlayers()){
            UUID id=p.getUniqueId();String identity=identities.resolve(p);var s=state.get(id);
            if(plugin.roles()!=null)primaryGroups.put(id,plugin.roles().primary(id));
            if(s==null||!Objects.equals(s.identity(),identity)){trusted.remove(id);if(plugin.roles()!=null)plugin.roles().clear(id);s=state.join(id,identity,now);lastShown.remove(id);show(p);}
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
            return parts.length==1||parts.length==2&&Set.of("link","account","help").contains(parts[1].toLowerCase(Locale.ROOT))||parts.length==3&&parts[1].equalsIgnoreCase("help")||parts.length==3&&parts[1].equalsIgnoreCase("confirm")&&parts[2].matches("[a-fA-F0-9-]{36}");
        }
        var s=state.get(p.getUniqueId());
        return resolved!=null&&resolved.getPlugin().getName().equalsIgnoreCase("AuthMe")&&Set.of("login","register").contains(resolved.getName().toLowerCase(Locale.ROOT));
    }
    @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent e){Player p=e.getPlayer();trusted.remove(p.getUniqueId());state.join(p.getUniqueId(),identities.resolve(p));cachedSnapshot=null;plugin.getServer().getScheduler().runTask(plugin,()->show(p));}
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();state.quit(id);cachedSnapshot=null;lobby.forget(id);trusted.remove(id);primaryGroups.remove(id);if(plugin.roles()!=null)plugin.roles().clear(id);identities.forget(id);lastShown.remove(id);wasVerified.remove(id);}
    @EventHandler(priority=EventPriority.MONITOR) public void respawn(PlayerRespawnEvent e){if(restricted(e.getPlayer()))plugin.getServer().getScheduler().runTask(plugin,()->show(e.getPlayer()));}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent e){
        if(restricted(e.getPlayer())&&e.getTo()!=null&&(e.getFrom().getWorld()!=e.getTo().getWorld()||e.getFrom().getX()!=e.getTo().getX()||e.getFrom().getY()!=e.getTo().getY()||e.getFrom().getZ()!=e.getTo().getZ()))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e){if(restricted(e.getPlayer())&&!lobby.allowed(e))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void placeBlock(BlockPlaceEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void empty(PlayerBucketEmptyEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fill(PlayerBucketFillEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void armorStand(PlayerArmorStandManipulateEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entity(PlayerInteractEntityEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entityAt(PlayerInteractAtEntityEvent e){if(restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void hangingPlace(org.bukkit.event.hanging.HangingPlaceEvent e){if(e.getPlayer()!=null&&restricted(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void hangingBreak(org.bukkit.event.hanging.HangingBreakByEntityEvent e){if(e.getRemover() instanceof Player p&&restricted(p))e.setCancelled(true);}
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
