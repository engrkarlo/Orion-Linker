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
    private final Map<UUID,Long> lastAlert=new ConcurrentHashMap<>();
    private final Map<UUID,Counter> blocks=new HashMap<>();
    private final Map<UUID,Counter> containers=new HashMap<>();
    private final Map<UUID,Long> restrictedUntil=new HashMap<>();
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
        allowNonPlayerEdits=config.getBoolean("guard.allow-non-player-edits",false);
        blockCommands=config.getBoolean("guard.block-sensitive-commands",true);
        blockEdits=config.getBoolean("guard.enforce-worldedit",true);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        cleanup=plugin.getServer().getScheduler().runTaskTimer(plugin,()->{
            long now=System.currentTimeMillis();
            restrictedUntil.entrySet().removeIf(e->e.getValue()<=now);
            lastAlert.entrySet().removeIf(e->e.getValue()<now-120000);
            editBudget.prune(System.nanoTime());
        },200L,200L);
    }
    private static int clamp(int n,int low,int high){return Math.max(low,Math.min(high,n));}
    boolean open(){return open;}
    Profile profile(UUID id){return gate==null?ORDINARY:profiles.getOrDefault(Objects.toString(gate.profile(id),""),ORDINARY);}
    String identity(UUID id){return gate==null?null:gate.linkedIdentity(id);}
    boolean staffChat(UUID id){return profile(id).staffChat();}
    Set<String> profiles(){return profiles.keySet();}
    boolean lockdown(){return lockdownUntil.get()>System.currentTimeMillis();}
    long lockdownUntil(){return lockdownUntil.get();}
    void lockdown(long until,String source){
        if(until<0||until>System.currentTimeMillis()+3600000)throw new IllegalArgumentException("Lockdown duration out of bounds");
        lockdownUntil.set(until);evidence("LOCKDOWN",null,"Lockdown until "+until+"; source "+source,"OrionGuard");
    }
    private void evidence(String kind,String id,String detail,String source){if(audit!=null)audit.external(kind,id,detail,source);}
    void external(String kind,String identity,String detail,String source){evidence(kind,identity,detail,source);}
    private void alert(Player p,String kind,String detail){
        long now=System.currentTimeMillis();UUID id=p.getUniqueId();
        Long previous=lastAlert.putIfAbsent(id,now);
        if(previous!=null&&(now-previous<5000||!lastAlert.replace(id,previous,now)))return;
        evidence(kind,plugin.identities().resolve(p),"Player "+p.getName()+"; "+detail,"OrionGuard");
    }
    private boolean blocked(Player p){
        return restrictedUntil.getOrDefault(p.getUniqueId(),0L)>System.currentTimeMillis()||lockdown()&&!profile(p.getUniqueId()).lockdown();
    }
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
        if(blocked(player)){e.setCancelled(true);return;}
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
        if(blocked(player))return false;
        UUID id=player.getUniqueId();long now=System.currentTimeMillis();
        int count=blocks.computeIfAbsent(id,k->new Counter()).add(now,1,60);
        int limit=Math.max(ordinaryBlocks,profile(id).blocks());
        if(count<=limit)return true;
        if(count==limit+1){restrictedUntil.put(id,now+containSeconds*1000L);alert(player,"GUARD_BLOCK_LIMIT","Block-operation budget "+limit+" exceeded; further operations restricted for "+containSeconds+" seconds");}
        return false;
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void inventory(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p)||VerificationMenus.owns(e.getView().getTopInventory()))return;
        if(blocked(p)){e.setCancelled(true);return;}
        if(e.getRawSlot()<0||e.getRawSlot()>=e.getView().getTopInventory().getSize()||e.getCurrentItem()==null)return;
        int count=containers.computeIfAbsent(p.getUniqueId(),k->new Counter()).add(System.currentTimeMillis(),e.getCurrentItem().getAmount(),60);
        if(count>=containerLimit&&count-e.getCurrentItem().getAmount()<containerLimit)alert(p,"CONTAINER_ACTIVITY","High volume of container item interaction attempts; item count is not a confirmed withdrawal");
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void move(PlayerMoveEvent e){
        if(lockdown()&&!profile(e.getPlayer().getUniqueId()).lockdown()&&e.getTo()!=null&&(e.getFrom().getX()!=e.getTo().getX()||e.getFrom().getY()!=e.getTo().getY()||e.getFrom().getZ()!=e.getTo().getZ()))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){
        int count=joins.add(System.currentTimeMillis(),1,10);
        if(count==joinLimit){evidence("JOIN_SPIKE",null,"Join threshold reached: "+count+" in 10 seconds; not proof of a raid","OrionGuard");if(lockSeconds>0)lockdown(System.currentTimeMillis()+lockSeconds*1000L,"join-spike policy");}
    }
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();blocks.remove(id);containers.remove(id);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void spawn(CreatureSpawnEvent e){
        if(entities.add(System.currentTimeMillis(),1,10)==entityLimit)evidence("ENTITY_SPIKE",null,"Creature-spawn threshold reached; source="+e.getSpawnReason()+"; actor unknown","OrionGuard");
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void explosion(EntityExplodeEvent e){
        if(e.blockList().size()<ordinaryBlocks)return;
        String actor=null;
        if(e.getEntity() instanceof org.bukkit.entity.TNTPrimed t&&t.getSource() instanceof Player p)actor=plugin.identities().resolve(p);
        evidence("EXPLOSION_ACTIVITY",actor,"Explosion affected "+e.blockList().size()+" candidate blocks; world="+e.getLocation().getWorld().getName()+"; actual result not confirmed","OrionGuard");
    }
    int operationLimit(UUID player,String world){
        if(!open||protectedWorlds.contains(world)||lockdown()&&!profile(player).lockdown()||moderation!=null&&moderation.denied(player,SanctionState.Kind.WORLDEDIT_RESTRICTION,SanctionState.Kind.BUILD_RESTRICTION,SanctionState.Kind.QUARANTINE,SanctionState.Kind.FREEZE,SanctionState.Kind.TEMP_BAN,SanctionState.Kind.PERMANENT_BAN))return 0;
        return profile(player).editLimit();
    }
    boolean enforceEdits(){return blockEdits;}
    boolean allowNonPlayerEdits(){return allowNonPlayerEdits;}
    boolean reserveEdit(UUID player,String world,AtomicInteger used){
        int max=operationLimit(player,world);
        return GuardBudget.operation(used,max)&&editBudget.reserve(player,System.nanoTime(),profile(player).minuteLimit());
    }
    @Override public void close(){open=false;cleanup.cancel();HandlerList.unregisterAll(this);lastAlert.clear();}
}
