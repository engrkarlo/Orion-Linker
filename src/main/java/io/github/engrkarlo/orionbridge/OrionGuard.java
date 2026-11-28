package io.github.engrkarlo.orionbridge;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

final class OrionGuard implements Listener,AutoCloseable {
    record Profile(Set<String> commands,int editLimit,int minuteLimit,int blocks,boolean staffChat,boolean lockdown,boolean entityTasks){}
    private static final Profile ORDINARY=new Profile(Set.of(),0,0,0,false,false,false);
    private static final Set<String> SENSITIVE=Set.of("op","deop","lp","luckperms","permissions","stop","restart","reload","plugman","plugmanx","execute","sudo","give","gamemode","ban","pardon","kill","summon","fill","clone","setblock","place","worldedit","fawe","we");
    private final OrionBridge plugin;
    private final VerificationGate gate;
    private final LocalModeration moderation;
    private final SecurityAudit audit;
    private final Map<String,Profile> profiles;
    private final Set<String> protectedWorlds;
    private final GuardBudget editBudget=new GuardBudget();
    private final AtomicLong lockdownUntil=new AtomicLong();
    private record AlertKey(UUID player,String kind){}
    private final Map<AlertKey,Long> lastAlert=new ConcurrentHashMap<>();
    private final GuardContainment containment=new GuardContainment();
    private final Map<String,GuardContainment.Rule> rules;
    private final Map<UUID,Counter> blocks=new HashMap<>();
    private final Map<UUID,Counter> containers=new HashMap<>();

    private final Counter joins=new Counter(),entities=new Counter();
    private final int ordinaryBlocks,joinLimit,entityLimit,containerLimit,containSeconds,lockSeconds;
    private final boolean allowNonPlayerEdits,blockCommands,blockEdits;
    private final org.bukkit.scheduler.BukkitTask cleanup;
    private volatile boolean open=true;
    private static final class Counter {
        long start;int count;
        int add(long now,int amount,int seconds){if(now-start>=seconds*1000L||now<start){start=now;count=0;}count=Math.min(Integer.MAX_VALUE-amount,count)+amount;return count;}
    }
    OrionGuard(OrionBridge plugin,YamlConfiguration config,VerificationGate gate,LocalModeration moderation,SecurityAudit audit){
        this.plugin=plugin;this.gate=gate;this.moderation=moderation;this.audit=audit;
        Map<String,Profile> loaded=new HashMap<>();
        var section=config.getConfigurationSection("guard.profiles");
        if(section!=null)for(String name:section.getKeys(false)){
            if(!name.matches("[a-z0-9_-]{1,40}"))continue;
            String base="guard.profiles."+name+".";
            Set<String> commands=new HashSet<>();
            for(String command:config.getStringList(base+"commands")){
                String c=command.toLowerCase(Locale.ROOT);if(c.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))commands.add(c);
            }
            loaded.put(name,new Profile(Set.copyOf(commands),clamp(config.getInt(base+"worldedit-per-operation"),0,2_000_000),clamp(config.getInt(base+"worldedit-per-minute"),0,4_000_000),clamp(config.getInt(base+"blocks-per-minute"),0,100_000),config.getBoolean(base+"staff-chat"),config.getBoolean(base+"lockdown-bypass"),config.getBoolean(base+"entity-tasks")));
        }
        profiles=Map.copyOf(loaded);protectedWorlds=Set.copyOf(config.getStringList("guard.protected-worlds"));
        ordinaryBlocks=clamp(config.getInt("guard.block-changes-per-minute",500),10,100000);
        joinLimit=clamp(config.getInt("guard.joins-per-10-seconds",20),3,1000);
        entityLimit=clamp(config.getInt("guard.entities-per-10-seconds",500),10,10000);
        containerLimit=clamp(config.getInt("guard.container-items-per-minute",4096),64,100000);
        containSeconds=clamp(config.getInt("guard.containment-seconds",60),5,3600);
        lockSeconds=clamp(config.getInt("guard.join-lockdown-seconds",0),0,3600);
        Map<String,GuardContainment.Rule> configured=new HashMap<>();
        for(String kind:List.of("GUARD_COMMAND_BLOCKED","WORLDEDIT_BLOCKED","GUARD_BLOCK_LIMIT","CONTAINER_ACTIVITY","JOIN_SPIKE","ENTITY_SPIKE","EXPLOSION_ACTIVITY")){
            String base="guard.rules."+kind+".";
            var fallback=kind.equals("GUARD_BLOCK_LIMIT")?GuardContainment.Type.QUARANTINE:kind.equals("JOIN_SPIKE")&&lockSeconds>0?GuardContainment.Type.LOCKDOWN:GuardContainment.Type.ALERT;
            var type=GuardContainment.Type.valueOf(config.getString(base+"response",fallback.name()).toUpperCase(Locale.ROOT));
            if(type==GuardContainment.Type.LOCKDOWN&&!kind.equals("JOIN_SPIKE"))throw new IllegalArgumentException("Lockdown response only supported for JOIN_SPIKE");
            if(Set.of("JOIN_SPIKE","ENTITY_SPIKE").contains(kind)&&type!=GuardContainment.Type.ALERT&&type!=GuardContainment.Type.LOCKDOWN)throw new IllegalArgumentException("Unattributed rule cannot restrict a player");
            String severity=config.getString(base+"severity",Set.of("GUARD_COMMAND_BLOCKED","WORLDEDIT_BLOCKED").contains(kind)?"CRITICAL":"MEDIUM").toUpperCase(Locale.ROOT);
            if(!Set.of("LOW","MEDIUM","HIGH","CRITICAL").contains(severity))throw new IllegalArgumentException("Invalid rule severity");
            configured.put(kind,new GuardContainment.Rule(config.getBoolean(base+"enabled",true),type,clamp(config.getInt(base+"duration-seconds",kind.equals("JOIN_SPIKE")?Math.max(5,lockSeconds):containSeconds),5,3600),severity,clamp(config.getInt(base+"cooldown-seconds",5),1,3600)));
        }
        rules=Map.copyOf(configured);
        allowNonPlayerEdits=config.getBoolean("guard.allow-non-player-edits",false);
        blockCommands=config.getBoolean("guard.block-sensitive-commands",true);
        blockEdits=config.getBoolean("guard.enforce-worldedit",true);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        cleanup=plugin.getServer().getScheduler().runTaskTimer(plugin,()->{
            long now=System.currentTimeMillis();
            containment.prune(now);
            lastAlert.entrySet().removeIf(e->e.getValue()<now-3600000);
            editBudget.prune(System.nanoTime());
        },200L,200L);
    }
    private static int clamp(int n,int low,int high){return Math.max(low,Math.min(high,n));}
    boolean open(){return open;}
    Profile profile(UUID id){return gate==null||moderation!=null&&moderation.denied(id,SanctionState.Kind.ROLE_SUSPENSION,SanctionState.Kind.FREEZE,SanctionState.Kind.QUARANTINE,SanctionState.Kind.TEMP_BAN,SanctionState.Kind.PERMANENT_BAN)?ORDINARY:profiles.getOrDefault(Objects.toString(gate.profile(id),""),ORDINARY);}
    String identity(UUID id){return gate==null?null:gate.linkedIdentity(id);}
    boolean staffChat(UUID id){return !frozen(id)&&profile(id).staffChat();}
    boolean profileStaffChat(String name){return profiles.getOrDefault(name,ORDINARY).staffChat();}
    Set<String> profiles(){return profiles.keySet();}
    boolean lockdown(){return lockdownUntil.get()>System.currentTimeMillis();}
    long lockdownUntil(){return lockdownUntil.get();}
    void lockdown(long until,String source){
        if(until<0||until>System.currentTimeMillis()+3600000)throw new IllegalArgumentException("Lockdown duration out of bounds");
        lockdownUntil.set(until);evidence("LOCKDOWN",null,"Lockdown until "+until+"; source "+source,"OrionGuard");
    }
    private void evidence(String kind,String id,String detail,String source){if(audit!=null)audit.external(kind,id,detail,source);}
    void external(String kind,String identity,String detail,String source){evidence(kind,identity,detail,source);}
    void rule(UUID player,String identity,String kind,String detail,String source){
        var policy=rules.get(kind);
        if(policy==null||!policy.enabled())return;
        long now=System.currentTimeMillis();
        var key=new AlertKey(player,kind);
        if(lastAlert.size()>=20000&&!lastAlert.containsKey(key)){lastAlert.entrySet().removeIf(e->e.getValue()<now-120000);if(lastAlert.size()>=20000)return;}
        Long prior=lastAlert.putIfAbsent(key,now);
        if(prior!=null&&(now-prior<policy.cooldownSeconds()*1000L||!lastAlert.replace(key,prior,now)))return;
        var type=policy.type();
        if(type==GuardContainment.Type.LOCKDOWN){
            lockdownUntil.accumulateAndGet(now+policy.seconds()*1000L,Math::max);
        }else if(player!=null&&identity!=null)containment.apply(player,type,now+policy.seconds()*1000L);
        else type=GuardContainment.Type.ALERT;
        evidence(kind,identity,"Severity="+policy.severity()+"; response="+type+"; duration="+(type==GuardContainment.Type.ALERT?0:policy.seconds())+"s; "+detail,source);
    }
    private void alert(Player p,String kind,String detail){
        if(rules.containsKey(kind))rule(p.getUniqueId(),plugin.identities().resolve(p),kind,"Player "+p.getName()+"; "+detail,"OrionGuard");
        else{
            long now=System.currentTimeMillis();var key=new AlertKey(p.getUniqueId(),kind);Long prior=lastAlert.putIfAbsent(key,now);
            if(prior==null||now-prior>=5000&&lastAlert.replace(key,prior,now))evidence(kind,plugin.identities().resolve(p),"Player "+p.getName()+"; "+detail,"OrionGuard");
        }
    }
    private boolean frozen(UUID id){return containment.denied(id,GuardContainment.Type.FREEZE,System.currentTimeMillis())||lockdown()&&!profile(id).lockdown();}
    private boolean blocked(Player p){return frozen(p.getUniqueId());}
    private boolean denied(Player p,GuardContainment.Type type){return blocked(p)||containment.denied(p.getUniqueId(),type,System.currentTimeMillis());}
    private boolean safeCommand(String raw,Command command){
        if(!(command instanceof PluginIdentifiableCommand owned))return false;
        return owned.getPlugin()==plugin&&command.getName().equals("ob")
            ||owned.getPlugin().getName().equalsIgnoreCase("AuthMe")&&Set.of("login","register").contains(command.getName().toLowerCase(Locale.ROOT));
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e){
        Player player=e.getPlayer();String input=e.getMessage();
        String raw=input.substring(1).stripLeading().split("\\s+",2)[0].toLowerCase(Locale.ROOT);
        Command resolved=plugin.getServer().getCommandMap().getCommand(raw);
        if(safeCommand(raw,resolved))return;
        if(denied(player,GuardContainment.Type.COMMAND_RESTRICTION)){e.setCancelled(true);return;}
        String owner=resolved instanceof PluginIdentifiableCommand pc?pc.getPlugin().getName().toLowerCase(Locale.ROOT):"minecraft";
        String canonical=resolved==null?raw:resolved.getName().toLowerCase(Locale.ROOT);
        String name=canonical.substring(canonical.lastIndexOf(':')+1).replaceFirst("^/+","");
        boolean edit=owner.equals("worldedit")||owner.equals("fastasyncworldedit")||raw.startsWith("/")||raw.startsWith("worldedit:")||raw.startsWith("fawe:");
        boolean sensitive=edit||SENSITIVE.contains(name)||SENSITIVE.contains(raw.substring(raw.lastIndexOf(':')+1));
        if(!blockCommands||!sensitive)return;
        Profile profile=profile(player.getUniqueId());
        boolean allowed=resolved!=null&&(edit?profile.editLimit()>0:profile.commands().contains(owner+":"+name));
        if(!allowed){e.setCancelled(true);alert(player,"GUARD_COMMAND_BLOCKED","Blocked sensitive command category "+name+"; resolved owner="+owner);player.sendMessage(Component.text("OrionGuard: this operation requires an approved staff profile."));}
        else alert(player,"TRUSTED_OPERATION","Authorized command category "+name+"; owner="+owner);
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(!block(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void placeBlock(BlockPlaceEvent e){if(!block(e.getPlayer()))e.setCancelled(true);}
    private boolean block(Player player){
        if(denied(player,GuardContainment.Type.BUILD_RESTRICTION))return false;
        UUID id=player.getUniqueId();long now=System.currentTimeMillis();
        int count=blocks.computeIfAbsent(id,k->new Counter()).add(now,1,60);
        int limit=Math.max(ordinaryBlocks,profile(id).blocks());
        if(count<=limit)return true;
        if(count==limit+1)alert(player,"GUARD_BLOCK_LIMIT","Block-operation budget "+limit+" exceeded; further block attempts denied in this window");
        return false;
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void inventory(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p)||VerificationMenus.owns(e.getView().getTopInventory())||DashboardMenus.owns(e.getView().getTopInventory()))return;
        if(blocked(p)){e.setCancelled(true);return;}
        if(e.getRawSlot()<0||e.getRawSlot()>=e.getView().getTopInventory().getSize()||e.getCurrentItem()==null)return;
        int count=containers.computeIfAbsent(p.getUniqueId(),k->new Counter()).add(System.currentTimeMillis(),e.getCurrentItem().getAmount(),60);
        if(count>=containerLimit&&count-e.getCurrentItem().getAmount()<containerLimit)alert(p,"CONTAINER_ACTIVITY","High volume of container item interaction attempts; item count is not a confirmed withdrawal");
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void move(PlayerMoveEvent e){
        if(blocked(e.getPlayer())&&e.getTo()!=null&&(e.getFrom().getX()!=e.getTo().getX()||e.getFrom().getY()!=e.getTo().getY()||e.getFrom().getZ()!=e.getTo().getZ()))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){
        int count=joins.add(System.currentTimeMillis(),1,10);
        if(count==joinLimit)rule(null,null,"JOIN_SPIKE","Join threshold reached: "+count+" in 10 seconds; not proof of a raid","OrionGuard");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();blocks.remove(id);containers.remove(id);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void spawn(CreatureSpawnEvent e){
        if(entities.add(System.currentTimeMillis(),1,10)==entityLimit)rule(null,null,"ENTITY_SPIKE","Creature-spawn threshold reached; source="+e.getSpawnReason()+"; actor unknown","OrionGuard");
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void explosion(EntityExplodeEvent e){
        if(e.blockList().size()<ordinaryBlocks)return;
        String actor=null;UUID player=null;
        if(e.getEntity() instanceof org.bukkit.entity.TNTPrimed t&&t.getSource() instanceof Player p){actor=plugin.identities().resolve(p);player=p.getUniqueId();}
        rule(player,actor,"EXPLOSION_ACTIVITY","Explosion affected "+e.blockList().size()+" candidate blocks; world="+e.getLocation().getWorld().getName()+"; actual result not confirmed","OrionGuard");
    }
    int operationLimit(UUID player,String world){
        if(!open||protectedWorlds.contains(world)||frozen(player)||containment.denied(player,GuardContainment.Type.WORLDEDIT_RESTRICTION,System.currentTimeMillis())||moderation!=null&&moderation.denied(player,SanctionState.Kind.WORLDEDIT_RESTRICTION,SanctionState.Kind.BUILD_RESTRICTION,SanctionState.Kind.QUARANTINE,SanctionState.Kind.FREEZE,SanctionState.Kind.TEMP_BAN,SanctionState.Kind.PERMANENT_BAN))return 0;
        return profile(player).editLimit();
    }
    boolean enforceEdits(){return blockEdits;}
    boolean allowNonPlayerEdits(){return allowNonPlayerEdits;}
    boolean reserveEdit(UUID player,String world,AtomicInteger used){
        int max=operationLimit(player,world);
        return GuardBudget.operation(used,max)&&editBudget.reserve(player,System.nanoTime(),profile(player).minuteLimit());
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void bucket(PlayerBucketEmptyEvent e){if(denied(e.getPlayer(),GuardContainment.Type.BUILD_RESTRICTION))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entity(PlayerInteractEntityEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p&&blocked(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p&&blocked(p)&&!VerificationMenus.owns(e.getView().getTopInventory())&&!DashboardMenus.owns(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void chat(io.papermc.paper.event.player.AsyncChatEvent e){if(frozen(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e){if(e.getDamager() instanceof Player p&&blocked(p)||e.getDamager() instanceof org.bukkit.entity.Projectile projectile&&projectile.getShooter() instanceof Player shooter&&blocked(shooter))e.setCancelled(true);}
    @Override public void close(){open=false;cleanup.cancel();HandlerList.unregisterAll(this);lastAlert.clear();}
}
