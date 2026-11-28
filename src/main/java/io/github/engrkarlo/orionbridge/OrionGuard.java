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
import java.util.concurrent.atomic.AtomicInteger;

final class OrionGuard implements Listener,AutoCloseable {
    record Profile(Set<String> commands,int editLimit,int minuteLimit,int blocks,boolean staffChat,boolean lockdown,boolean entityTasks,EditScope scope){}
    private static final Profile ORDINARY=new Profile(Set.of(),0,0,0,false,false,false,EditScope.unrestricted());
    private final OrionBridge plugin;
    private final VerificationGate gate;
    private final LocalModeration moderation;
    private final SecurityAudit audit;
    private final Map<String,Profile> profiles;
    private final Set<String> protectedWorlds;
    private record ProtectedRegion(int minX,int minY,int minZ,int maxX,int maxY,int maxZ){boolean overlaps(int x,int y,int z,int endX,int endY,int endZ){return x<=maxX&&endX>=minX&&y<=maxY&&endY>=minY&&z<=maxZ&&endZ>=minZ;}}
    private final Map<String,List<ProtectedRegion>> protectedRegions;
    private final GuardBudget editBudget=new GuardBudget();
    private final TaskBudget taskBudget;
    private final DurableLockdown lockdownState;
    private record AlertKey(UUID player,String kind){}
    private final Map<AlertKey,Long> lastAlert=new ConcurrentHashMap<>();
    private final GuardContainment containment;
    private final Map<String,GuardContainment.Rule> rules;
    private final Map<UUID,Counter> blocks=new HashMap<>();
    private final Map<UUID,Counter> containers=new HashMap<>();

    private final Counter joins=new Counter(),entities=new Counter();
    private final int ordinaryBlocks,joinLimit,entityLimit,containerLimit,containSeconds,lockSeconds,nonPlayerOperation,nonPlayerMinute;
    private static final UUID NON_PLAYER=new UUID(0L,0L);
    private final boolean allowNonPlayerEdits,blockCommands,blockEdits,strictCommands;
    private final Set<String> ordinaryCommands;
    private final org.bukkit.scheduler.BukkitTask cleanup;
    private volatile boolean open=true,worldEditReady=false;
    private static final class Counter {
        long start;int count;
        int add(long now,int amount,int seconds){if(now-start>=seconds*1000L||now<start){start=now;count=0;}count=Math.min(Integer.MAX_VALUE-amount,count)+amount;return count;}
    }
    OrionGuard(OrionBridge plugin,YamlConfiguration config,VerificationGate gate,LocalModeration moderation,SecurityAudit audit){
        this.plugin=plugin;this.gate=gate;this.moderation=moderation;this.audit=audit;
        taskBudget=new TaskBudget(plugin.getDataFolder().toPath().resolve("guard-task-budgets"));
        lockdownState=new DurableLockdown(plugin.getDataFolder().toPath().resolve("guard-lockdown.state"),System.currentTimeMillis());
        containment=new GuardContainment(plugin.getDataFolder().toPath().resolve("guard-containment"),System.currentTimeMillis());
        Map<String,Profile> loaded=new HashMap<>();
        var section=config.getConfigurationSection("guard.profiles");
        if(section!=null)for(String name:section.getKeys(false)){
            if(!name.matches("[a-z0-9_-]{1,40}"))continue;
            String base="guard.profiles."+name+".";
            Set<String> commands=new HashSet<>();
            for(String command:config.getStringList(base+"commands")){
                String c=command.toLowerCase(Locale.ROOT);if(c.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))commands.add(c);
            }
            loaded.put(name,new Profile(Set.copyOf(commands),clamp(config.getInt(base+"worldedit-per-operation"),0,2_000_000),clamp(config.getInt(base+"worldedit-per-minute"),0,4_000_000),clamp(config.getInt(base+"blocks-per-minute"),0,100_000),config.getBoolean(base+"staff-chat"),config.getBoolean(base+"lockdown-bypass"),config.getBoolean(base+"entity-tasks"),readScope(config,base+"worldedit-scope.")));
        }
        profiles=Map.copyOf(loaded);protectedWorlds=Set.copyOf(config.getStringList("guard.protected-worlds"));
        var regions=config.getMapList("guard.protected-regions");if(regions.size()>32)throw new IllegalArgumentException("Too many protected regions");
        Map<String,List<ProtectedRegion>> area=new HashMap<>();
        for(var region:regions){
            Object w=region.get("world");if(!(w instanceof String world)||world.isBlank()||world.length()>100)throw new IllegalArgumentException("Invalid protected world");
            int x1=coordinate(region,"x1"),y1=coordinate(region,"y1"),z1=coordinate(region,"z1"),x2=coordinate(region,"x2"),y2=coordinate(region,"y2"),z2=coordinate(region,"z2");
            area.computeIfAbsent(world,k->new ArrayList<>()).add(new ProtectedRegion(Math.min(x1,x2),Math.min(y1,y2),Math.min(z1,z2),Math.max(x1,x2),Math.max(y1,y2),Math.max(z1,z2)));
        }
        area.replaceAll((world,list)->List.copyOf(list));protectedRegions=Map.copyOf(area);
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
        nonPlayerOperation=clamp(config.getInt("guard.non-player-per-operation",10000),0,100000);
        nonPlayerMinute=clamp(config.getInt("guard.non-player-per-minute",20000),0,200000);
        blockCommands=config.getBoolean("guard.block-sensitive-commands",true);
        strictCommands=config.getBoolean("guard.strict-command-allowlist",false);
        ordinaryCommands=Set.copyOf(config.getStringList("guard.ordinary-commands"));if(ordinaryCommands.stream().anyMatch(c->!c.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")))throw new IllegalArgumentException("Invalid ordinary command allowlist");
        blockEdits=config.getBoolean("guard.enforce-worldedit",true);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        cleanup=plugin.getServer().getScheduler().runTaskTimer(plugin,()->{
            long now=System.currentTimeMillis();
            containment.prune(now);
            taskBudget.prune(now);
            blocks.entrySet().removeIf(e->now-e.getValue().start>=60000);
            containers.entrySet().removeIf(e->now-e.getValue().start>=60000);
            lastAlert.entrySet().removeIf(e->e.getValue()<now-3600000);
            editBudget.prune(System.nanoTime());
        },200L,200L);
    }
    private static EditScope readScope(YamlConfiguration cfg,String base){
        for(String k:List.of("worlds","commands","operations","regions"))if(cfg.contains(base+k)&&!cfg.isList(base+k))throw new IllegalArgumentException("Invalid task scope list: "+k);
        Set<String> worlds=Set.copyOf(cfg.getStringList(base+"worlds")),commands=Set.copyOf(cfg.getStringList(base+"commands"));
        if(worlds.size()>32||worlds.stream().anyMatch(w->w.isBlank()||w.length()>100)||commands.size()>100||commands.stream().anyMatch(c->!c.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")))throw new IllegalArgumentException("Invalid task profile scope");
        var operations=EnumSet.noneOf(EditScope.Operation.class);
        if(!cfg.contains(base+"operations"))operations=EnumSet.allOf(EditScope.Operation.class);
        else for(String op:cfg.getStringList(base+"operations"))operations.add(EditScope.Operation.valueOf(op.toUpperCase(Locale.ROOT)));
        var rows=cfg.getMapList(base+"regions");if(cfg.contains(base+"regions")&&rows.size()!=cfg.getList(base+"regions").size())throw new IllegalArgumentException("Invalid task region entries");if(rows.size()>32)throw new IllegalArgumentException("Too many task regions");
        List<EditScope.Region> regions=new ArrayList<>();
        for(var region:rows){
            Object w=region.get("world");if(!(w instanceof String world)||world.isBlank()||world.length()>100)throw new IllegalArgumentException("Invalid task region world");
            int x1=coordinate(region,"x1"),y1=coordinate(region,"y1"),z1=coordinate(region,"z1"),x2=coordinate(region,"x2"),y2=coordinate(region,"y2"),z2=coordinate(region,"z2");
            regions.add(new EditScope.Region(world,Math.min(x1,x2),Math.min(y1,y2),Math.min(z1,z2),Math.max(x1,x2),Math.max(y1,y2),Math.max(z1,z2)));
        }
        return new EditScope(worlds,regions,operations,commands);
    }
    private static int coordinate(Map<?,?> region,String key){
        Object raw=region.get(key);if(!(raw instanceof Integer n)||Math.abs((long)n)>30000000)throw new IllegalArgumentException("Invalid protected region coordinate "+key);return n;
    }
    boolean protectedEdit(String world,int x,int y,int z,boolean biome){
        if(world.equals("unknown")||protectedWorlds.contains(world))return true;
        int ex=x,ey=y,ez=z;
        if(biome){x=Math.floorDiv(x,4)*4;y=Math.floorDiv(y,4)*4;z=Math.floorDiv(z,4)*4;ex=x+3;ey=y+3;ez=z+3;}
        for(var region:protectedRegions.getOrDefault(world,List.of()))if(region.overlaps(x,y,z,ex,ey,ez))return true;
        return false;
    }
    private static int clamp(int n,int low,int high){return Math.max(low,Math.min(high,n));}
    boolean open(){return open;}
    void worldEditReady(){worldEditReady=true;}
    @EventHandler public void pluginDisabled(org.bukkit.event.server.PluginDisableEvent e){if(Set.of("WorldEdit","FastAsyncWorldEdit").contains(e.getPlugin().getName()))worldEditReady=false;}
    private VerificationGate.EditAuthorization editAuth(UUID id){
        return gate==null||moderation!=null&&moderation.denied(id,SanctionState.Kind.ROLE_SUSPENSION,SanctionState.Kind.FREEZE,SanctionState.Kind.QUARANTINE,SanctionState.Kind.TEMP_BAN,SanctionState.Kind.PERMANENT_BAN)?null:gate.editAuthorization(id);
    }
    Profile profile(UUID id){var a=editAuth(id);return a==null||a.task()!=null?ORDINARY:profiles.getOrDefault(a.profile(),ORDINARY);}
    private Profile editProfile(UUID id){var a=editAuth(id);return a==null?ORDINARY:profiles.getOrDefault(a.profile(),ORDINARY);}
    VerificationGate.EditAuthorization beginEdit(UUID id){return editAuth(id);}
    String identity(UUID id){return gate==null?null:gate.linkedIdentity(id);}
    boolean staffChat(UUID id){return !frozen(id)&&profile(id).staffChat();}
    boolean profileStaffChat(String name){return profiles.getOrDefault(name,ORDINARY).staffChat();}
    Set<String> profiles(){return profiles.keySet();}
    boolean lockdown(){return lockdownState.until()>System.currentTimeMillis();}
    long lockdownUntil(){return lockdownState.until();}
    void lockdown(long until,String source){
        if(until<0||until>System.currentTimeMillis()+3600000)throw new IllegalArgumentException("Lockdown duration out of bounds");
        lockdownState.set(until);evidence("LOCKDOWN",null,"Lockdown until "+until+"; source "+source,"OrionGuard");
    }
    private void evidence(String kind,String id,String detail,String source){if(audit!=null)audit.external(kind,id,detail,source);}
    void external(String kind,String identity,String detail,String source){evidence(kind,identity,detail,source);}
    void attributed(String kind,String actor,String target,String detail,String source){if(audit!=null)audit.attributed(kind,actor,actor==null?"UNKNOWN":"REPORTED_ACTOR",target,detail,source);}
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
            try{lockdownState.extend(now+policy.seconds()*1000L);}catch(java.io.UncheckedIOException e){plugin.getLogger().severe("Automatic lockdown active in memory but could not be persisted; check guard state storage.");}
        }else if(player!=null&&identity!=null){try{containment.apply(identity,type,now+policy.seconds()*1000L);}catch(java.io.UncheckedIOException|IllegalStateException e){plugin.getLogger().severe("Automatic containment storage/capacity failure; inspect Guard state.");}}
        else type=GuardContainment.Type.ALERT;
        evidence(kind,identity,"Severity="+policy.severity()+"; response="+type+"; duration="+(type==GuardContainment.Type.ALERT?0:policy.seconds())+"s; "+detail,source);
    }
    private void alert(Player p,String kind,String detail){
        if(rules.containsKey(kind))rule(p.getUniqueId(),plugin.identities().resolve(p),kind,"Player "+p.getName()+"; "+detail,"OrionGuard");
        else{
            long now=System.currentTimeMillis();var key=new AlertKey(p.getUniqueId(),kind);if(lastAlert.size()>=20000&&!lastAlert.containsKey(key))return;Long prior=lastAlert.putIfAbsent(key,now);
            if(prior==null||now-prior>=5000&&lastAlert.replace(key,prior,now))evidence(kind,plugin.identities().resolve(p),"Player "+p.getName()+"; "+detail,"OrionGuard");
        }
    }
    private String subject(UUID id){
        if(id==null)return null;
        if(org.bukkit.Bukkit.isPrimaryThread()){
            Player p=plugin.getServer().getPlayer(id);return p==null?null:plugin.identities().resolve(p);
        }
        var s=gate==null?null:gate.session(id);return s!=null?s.identity():moderation==null?null:moderation.identity(id);
    }
    private boolean frozen(UUID id){return containment.denied(subject(id),GuardContainment.Type.FREEZE,System.currentTimeMillis())||lockdown()&&!profile(id).lockdown();}
    private boolean blocked(Player p){return frozen(p.getUniqueId());}
    private boolean denied(Player p,GuardContainment.Type type){return blocked(p)||containment.denied(subject(p.getUniqueId()),type,System.currentTimeMillis());}
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
        boolean sensitive=edit||GuardCommands.sensitive(owner,name,raw.substring(raw.lastIndexOf(':')+1).replaceFirst("^/+",""));
        if(strictCommands&&!sensitive&&!ordinaryCommands.contains(owner+":"+name))sensitive=true;
        if(edit&&blockEdits&&!worldEditReady){e.setCancelled(true);alert(player,"WORLDEDIT_BLOCKED","Guard edit hook unavailable; refusing an edit command until restart");player.sendMessage(Component.text("OrionGuard: edit protection unavailable. Contact staff."));return;}
        // These direct entity operations may bypass the guarded extent. Deny
        // them while WE protection is on instead of claiming complete coverage.
        if(edit&&blockEdits&&GuardCommands.directEdit(name)){e.setCancelled(true);alert(player,"WORLDEDIT_BLOCKED","Direct or administrative edit command denied; no guarded extent guarantee");return;}
        if((!strictCommands&&!blockCommands&&(!edit||!blockEdits))||!sensitive){if(edit&&gate!=null&&gate.task(player.getUniqueId())!=null&&!blockEdits)e.setCancelled(true);return;}
        Profile profile=edit?editProfile(player.getUniqueId()):profile(player.getUniqueId());
        var task=gate==null?null:gate.task(player.getUniqueId());
        boolean allowed=resolved!=null&&(edit?profile.editLimit()>0&&profile.scope().permitsCommand(owner,name)&&(task==null||blockEdits&&worldEditReady&&GuardCommands.taskCommand(profile.scope(),owner,name)):profile.commands().contains(owner+":"+name));
        if(!allowed){e.setCancelled(true);alert(player,"GUARD_COMMAND_BLOCKED","Blocked sensitive command category "+name+"; resolved owner="+owner);player.sendMessage(Component.text("OrionGuard: this operation requires an approved staff profile."));}
        else alert(player,"TRUSTED_OPERATION","Authorized command category "+name+"; owner="+owner);
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(!block(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void placeBlock(BlockPlaceEvent e){int count=e instanceof BlockMultiPlaceEvent multi?multi.getReplacedBlockStates().size():1;if(!block(e.getPlayer(),Math.max(1,count)))e.setCancelled(true);}
    private boolean block(Player player){return block(player,1);}
    private boolean block(Player player,int amount){
        if(denied(player,GuardContainment.Type.BUILD_RESTRICTION))return false;
        UUID id=player.getUniqueId();long now=System.currentTimeMillis();
        if(!blocks.containsKey(id)&&blocks.size()>=10000)return false;
        int count=blocks.computeIfAbsent(id,k->new Counter()).add(now,amount,60);
        int limit=Math.max(ordinaryBlocks,profile(id).blocks());
        if(count<=limit)return true;
        if(count-amount<=limit)alert(player,"GUARD_BLOCK_LIMIT","Block-operation budget "+limit+" exceeded; further block attempts denied in this window");
        return false;
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void inventory(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p)||VerificationMenus.owns(e.getView().getTopInventory())||DashboardMenus.owns(e.getView().getTopInventory()))return;
        if(blocked(p)){e.setCancelled(true);return;}
        if(e.getRawSlot()<0||e.getRawSlot()>=e.getView().getTopInventory().getSize()||e.getCurrentItem()==null)return;
        if(!containers.containsKey(p.getUniqueId())&&containers.size()>=10000)return;
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
        if(!open||!worldEditReady||protectedWorlds.contains(world)||frozen(player)||containment.denied(subject(player),GuardContainment.Type.WORLDEDIT_RESTRICTION,System.currentTimeMillis())||moderation!=null&&moderation.denied(player,SanctionState.Kind.WORLDEDIT_RESTRICTION,SanctionState.Kind.BUILD_RESTRICTION,SanctionState.Kind.QUARANTINE,SanctionState.Kind.FREEZE,SanctionState.Kind.TEMP_BAN,SanctionState.Kind.PERMANENT_BAN))return 0;
        var task=gate==null?null:gate.task(player);return task!=null&&!task.world().equals(world)?0:Math.min(editProfile(player).editLimit(),task==null?Integer.MAX_VALUE:task.budget());
    }
    boolean enforceEdits(){return blockEdits;}
    boolean allowNonPlayerEdits(){return allowNonPlayerEdits;}
    boolean reserveNonPlayerEdit(AtomicInteger used){return open&&worldEditReady&&allowNonPlayerEdits&&!lockdown()&&GuardBudget.operation(used,nonPlayerOperation)&&editBudget.reserve(NON_PLAYER,System.nanoTime(),nonPlayerMinute);}
    boolean reserveScopedEdit(UUID player,VerificationGate.EditAuthorization initial,String world,int x,int y,int z,boolean biome,boolean entity,AtomicInteger used){
        if(initial==null||!initial.equals(editAuth(player))||operationLimit(player,world)<=0)return false;
        var profile=profiles.getOrDefault(initial.profile(),ORDINARY);
        var task=initial.task();long now=System.currentTimeMillis();
        var op=entity?EditScope.Operation.ENTITY:biome?EditScope.Operation.BIOME:EditScope.Operation.BLOCK;
        if(entity&&!profile.entityTasks()||!profile.scope().permits(world,x,y,z,op)||task!=null&&!task.permits(world,op,now))return false;
        int limit=Math.min(profile.editLimit(),task==null?Integer.MAX_VALUE:task.budget());
        if(!GuardBudget.operation(used,limit)||!editBudget.reserve(player,System.nanoTime(),profile.minuteLimit()))return false;
        if(task!=null&&!taskBudget.reserve(initial.identity(),task,now))return false;
        return initial.equals(editAuth(player))&&operationLimit(player,world)>0;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void bucket(PlayerBucketEmptyEvent e){if(!block(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void bucketFill(PlayerBucketFillEvent e){if(!block(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void openInventory(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p&&blocked(p)&&!VerificationMenus.owns(e.getInventory())&&!DashboardMenus.owns(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void armorStand(PlayerArmorStandManipulateEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void consume(PlayerItemConsumeEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void swap(PlayerSwapHandItemsEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void fish(PlayerFishEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void vehicle(org.bukkit.event.vehicle.VehicleEnterEvent e){if(e.getEntered() instanceof Player p&&blocked(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entity(PlayerInteractEntityEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void entityAt(PlayerInteractAtEntityEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void hangingPlace(org.bukkit.event.hanging.HangingPlaceEvent e){if(e.getPlayer()!=null&&!block(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void hangingBreak(org.bukkit.event.hanging.HangingBreakByEntityEvent e){if(e.getRemover() instanceof Player p&&!block(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p&&blocked(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p&&blocked(p)&&!VerificationMenus.owns(e.getView().getTopInventory())&&!DashboardMenus.owns(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void chat(io.papermc.paper.event.player.AsyncChatEvent e){if(frozen(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e){if(e.getDamager() instanceof Player p&&blocked(p)||e.getDamager() instanceof org.bukkit.entity.Projectile projectile&&projectile.getShooter() instanceof Player shooter&&blocked(shooter))e.setCancelled(true);}
    @Override public void close(){open=false;cleanup.cancel();HandlerList.unregisterAll(this);lastAlert.clear();}
}
