package io.github.engrkarlo.orionbridge;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;
import java.util.*;
import java.util.function.Consumer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local read-only dashboards and audited staff requests; Discord owns approval. */
final class DashboardMenus implements Listener,AutoCloseable {
    private record Entry(String name,Material material,Runnable action,UUID head,String image){Entry(String name,Material material,Runnable action,UUID head){this(name,material,action,head,"");}}
    private final OrionBridge plugin;
    private final YamlConfiguration theme;
    private final MiniMessage mm=MiniMessage.miniMessage();
    private final String interfaceType,invite;
    private final Map<UUID,UUID> screens=new HashMap<>();
    private boolean open=true;
    DashboardMenus(OrionBridge plugin,YamlConfiguration cfg)throws Exception{
        this.plugin=plugin;interfaceType=cfg.getString("verification.java-interface","DIALOG");invite=cfg.getString("verification.discord-invite","");
        String resource="menus/dashboard.yml";Path file=plugin.getDataFolder().toPath().resolve(resource);
        if(!java.nio.file.Files.exists(file))plugin.saveResource(resource,false);
        theme=new YamlConfiguration();theme.load(file.toFile());
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private boolean verified(Player p){return open&&p.isOnline()&&plugin.verification()!=null&&plugin.verification().authorized(p.getUniqueId());}
    private boolean admin(Player p){return verified(p)&&p.hasPermission("orionbridge.admin.gui")&&plugin.guard()!=null&&plugin.guard().profile(p.getUniqueId()).lockdown();}
    private String label(String key,String fallback){return theme.getString("labels."+key,fallback);}
    private Component component(String value){try{return mm.deserialize(value);}catch(RuntimeException e){return Component.text(value);}}
    private Component title(String title){String key=title.toLowerCase(Locale.ROOT).replace(' ','-');return component(theme.getString("title-prefix","<light_purple>OrionBridge</light_purple> <gray>·</gray> <white>")).append(component(theme.getString("headings."+key,title)));}
    private Entry button(String key,String fallback,Material material,Runnable run){String value=theme.getString("materials."+key);Material selected=value==null?null:Material.matchMaterial(value);return new Entry(label(key,fallback),selected!=null&&selected.isItem()&&!selected.isAir()?selected:material,run,null,theme.getString("images."+key,""));}
    void account(Player p){
        if(!verified(p)){p.sendMessage(Component.text("Verify first using /ob link."));return;}
        var s=plugin.verification().session(p.getUniqueId());
        String group=plugin.roles()==null?"Unavailable":plugin.roles().primary(p.getUniqueId());
        List<Entry> entries=new ArrayList<>();
        entries.add(button("punishments","Punishments",Material.BOOK,()->punishments(p,s.identity(),false)));
        entries.add(button("report","Report",Material.PAPER,()->report(p)));
        entries.add(button("appeal","Appeal a case",Material.WRITABLE_BOOK,()->appeal(p)));
        entries.add(button("refresh","Refresh account",Material.CLOCK,()->account(p)));
        if(admin(p))entries.add(button("admin","Administration",Material.NETHER_STAR,()->adminMenu(p)));
        show(p,false,"Account","Linked Discord: "+s.linked()+"\nMinecraft identity: "+s.identity()+"\nPrimary group: "+group+"\nTrusted profile: "+Objects.toString(plugin.verification().profile(p.getUniqueId()),"None"),entries);
    }
    void appeal(Player p){
        if(!verified(p))return;
        String details="Open the Discord verification panel and choose Appeal a case. Enter your case ID and explanation. Use My appeals to track the decision. An appeal does not automatically remove restrictions.";
        if(!invite.isBlank())details+="\nDiscord: "+invite;
        show(p,false,"Appeal a case",details,List.of(button("punishments","View case IDs",Material.BOOK,()->punishments(p,plugin.verification().linkedIdentity(p.getUniqueId()),false)),button("back","Back",Material.ARROW,()->account(p))));
    }
    void report(Player p){
        if(!verified(p))return;
        input(p,false,"Report an issue","Describe the issue or case ID",message->{
            if(!verified(p)||plugin.audit()==null){p.sendMessage(Component.text("Reporting unavailable; contact staff in Discord."));return;}
            String text=message.replaceAll("[\\p{Cntrl}]"," ").strip();
            if(text.isEmpty()||text.length()>500)return;
            plugin.audit().external("PLAYER_REPORT",plugin.verification().linkedIdentity(p.getUniqueId()),text,"Player report");
            p.sendMessage(Component.text("Report queued for staff review. For a formal appeal use the Discord verification panel."));
        });
    }
    void punishments(Player p,String identity,boolean staff){
        if(staff?!admin(p):!verified(p))return;
        var list=plugin.moderation()==null?List.<SanctionState.Case>of():plugin.moderation().active(identity);
        StringBuilder details=new StringBuilder("Active local sanctions: "+list.size());
        for(var c:list.stream().limit(12).toList())details.append("\n").append(c.kind()).append(" · ").append(c.id()).append("\n").append(c.expiry()==0?"Indefinite":java.time.Instant.ofEpochMilli(c.expiry())).append(" · ").append(c.reason());
        show(p,staff,"Punishments",details.toString(),List.of(button("back","Back",Material.ARROW,()->{if(staff)adminMenu(p);else account(p);})));
    }
    void adminMenu(Player p){
        if(!admin(p)){p.sendMessage(Component.text("A verified trusted administrator and orionbridge.admin.gui are required."));return;}
        String status="Online: "+plugin.getServer().getOnlinePlayers().size()+"\nAudit: "+(plugin.audit()!=null)+"\nModeration: "+(plugin.moderation()!=null)+"\nLockdown: "+(plugin.guard()!=null&&plugin.guard().lockdown())+"\nDiscord dashboard owns policy changes and approvals.";
        show(p,true,"Control Center",status,List.of(button("players","Online players",Material.PLAYER_HEAD,()->players(p,0)),button("search","Search identity",Material.COMPASS,()->input(p,true,"Inspect account","Canonical Minecraft identity",id->{if(SanctionState.validIdentity(id.strip()))inspect(p,id.strip(),id.strip());})),button("performance","Performance",Material.CLOCK,()->show(p,true,"Diagnostics",plugin.metrics().json(),List.of(button("back","Back",Material.ARROW,()->adminMenu(p))))),button("account","My account",Material.BOOK,()->account(p))));
    }
    private void players(Player p,int page){
        if(!admin(p))return;
        var online=plugin.getServer().getOnlinePlayers().stream().sorted(Comparator.comparing(Player::getName)).toList();
        int max=Math.max(0,(online.size()-1)/20),current=Math.max(0,Math.min(page,max));
        List<Entry> entries=new ArrayList<>();
        for(Player player:online.stream().skip(current*20L).limit(20).toList()){
            UUID id=player.getUniqueId();
            entries.add(new Entry(player.getName(),Material.PLAYER_HEAD,()->{
                if(!admin(p))return;Player target=plugin.getServer().getPlayer(id);
                String identity=target==null?null:plugin.identities().fresh(target);
                if(identity!=null)inspect(p,identity,target.getName());
                else p.sendMessage(Component.text("No authenticated identity available."));
            },id));
        }
        if(current>0)entries.add(button("previous","Previous page",Material.ARROW,()->players(p,current-1)));
        if(current<max)entries.add(button("next","Next page",Material.ARROW,()->players(p,current+1)));
        entries.add(button("back","Back",Material.BARRIER,()->adminMenu(p)));
        show(p,true,"Players","Page "+(current+1)+"/"+(max+1),entries);
    }
    private void inspect(Player p,String identity,String name){
        if(!admin(p)||!SanctionState.validIdentity(identity))return;
        show(p,true,"Player profile",name+"\n"+identity+"\nUse Discord for historical identity, evidence and policy editing.",List.of(button("punishments","Active punishments",Material.BOOK,()->punishments(p,identity,true)),button("request","Request punishment",Material.IRON_AXE,()->request(p,identity)),button("back","Back",Material.ARROW,()->adminMenu(p))));
    }
    private void request(Player p,String identity){
        if(!admin(p)||plugin.audit()==null)return;
        var types=List.of("WARNING","MUTE","FREEZE","QUARANTINE","BUILD_RESTRICTION","WORLDEDIT_RESTRICTION","TEMP_BAN");
        show(p,true,"Punishment request","Select a type; an independent Discord administrator must approve.",types.stream().map(type->new Entry(type,Material.PAPER,()->input(p,true,"Request "+type,"Duration and reason (e.g. 1h griefing)",raw->{
            if(!admin(p)||plugin.audit()==null)return;
            String text=raw.replaceAll("[\\p{Cntrl}]"," ").strip();
            if(text.isEmpty()||text.length()>450)return;
            plugin.audit().external("ADMIN_PUNISH_REQUEST",identity,"Requested type "+type+"; "+text+"; staff identity "+plugin.verification().linkedIdentity(p.getUniqueId()),"In-game staff request");
            p.sendMessage(Component.text("Request queued. No punishment applied before Discord approval."));
        }),null)).toList());
    }
    private void input(Player p,boolean staff,String title,String hint,Consumer<String> action){
        if(staff?!admin(p):!verified(p))return;
        UUID token=UUID.randomUUID();screens.put(p.getUniqueId(),token);
        Consumer<String> safe=text->{if(usable(p,staff,token))action.accept(text);};
        if(Bedrock.detect(p)==Bedrock.Detection.BEDROCK&&Bedrock.input(p,plugin,title,hint,"",safe,()->{}))return;
        if(JavaDialogs.input(p,plugin,Component.text(title),Component.text(hint),Component.text("Submit"),Component.text("Cancel"),240,safe))return;
        p.sendMessage(Component.text("Input interface unavailable. Use the Discord dashboard."));
    }
    private boolean usable(Player p,boolean staff,UUID token){return open&&Objects.equals(screens.get(p.getUniqueId()),token)&&(staff?admin(p):verified(p));}
    private void show(Player p,boolean staff,String heading,String details,List<Entry> entries){
        if(staff?!admin(p):!verified(p))return;
        if(entries.isEmpty()||entries.size()>45)return;
        UUID token=UUID.randomUUID();screens.put(p.getUniqueId(),token);
        Consumer<Integer> callback=index->{if(index>=0&&index<entries.size()&&usable(p,staff,token)){screens.remove(p.getUniqueId(),token);entries.get(index).action().run();}};
        var detection=Bedrock.detect(p);
        var legacy=LegacyComponentSerializer.legacySection();
        if(detection==Bedrock.Detection.BEDROCK&&Bedrock.show(p,plugin,legacy.serialize(title(heading)),details,entries.stream().map(e->new Bedrock.Button(legacy.serialize(component(e.name())),e.image())).toList(),callback,()->{}))return;
        if(detection==Bedrock.Detection.JAVA&&!interfaceType.equalsIgnoreCase("INVENTORY")&&JavaDialogs.show(p,plugin,title(heading),Component.text(details),null,entries.stream().map(e->new JavaDialogs.Button(component(e.name()),null)).toList(),Math.max(1,Math.min(2,theme.getInt("layout.dialog-columns",2))),Math.max(50,Math.min(300,theme.getInt("layout.dialog-button-width",150))),-1,callback))return;
        if(detection!=Bedrock.Detection.JAVA){p.sendMessage(title(heading));p.sendMessage(Component.text(details));return;}
        Holder holder=new Holder(p.getUniqueId(),token,staff,callback);
        int size=Math.max(Math.max(27,Math.min(54,theme.getInt("layout.inventory-rows",3)*9)),Math.min(54,((entries.size()+1+8)/9)*9));holder.inventory=Bukkit.createInventory(holder,size,title(heading));
        int infoSlot=Math.max(0,Math.min(size-1,theme.getInt("layout.information-slot",0)));
        List<Integer> positions=DashboardLayout.slots(size,infoSlot,entries.size(),theme.getIntegerList("layout.button-slots"));
        var info=new ItemStack(Material.BOOK);var infoMeta=info.getItemMeta();infoMeta.displayName(title(heading));infoMeta.lore(Arrays.stream(details.split("\n")).limit(20).map(Component::text).map(c->c.decoration(TextDecoration.ITALIC,false)).toList());info.setItemMeta(infoMeta);holder.inventory.setItem(infoSlot,info);
        for(int i=0;i<entries.size();i++){
            Entry e=entries.get(i);ItemStack item=new ItemStack(e.material());var meta=item.getItemMeta();
            if(meta instanceof SkullMeta skull&&e.head()!=null){Player target=plugin.getServer().getPlayer(e.head());if(target!=null)skull.setPlayerProfile(target.getPlayerProfile());}
            meta.displayName(component(e.name()).decoration(TextDecoration.ITALIC,false));item.setItemMeta(meta);int slot=positions.get(i);holder.inventory.setItem(slot,item);holder.positions.put(slot,i);
        }
        p.openInventory(holder.inventory);
    }
    private static final class Holder implements InventoryHolder {
        final UUID owner,token;final boolean staff;final Consumer<Integer> callback;final AtomicBoolean used=new AtomicBoolean();final Map<Integer,Integer> positions=new HashMap<>();Inventory inventory;
        Holder(UUID owner,UUID token,boolean staff,Consumer<Integer> callback){this.owner=owner;this.token=token;this.staff=staff;this.callback=callback;}
        @Override public Inventory getInventory(){return inventory;}
    }
    static boolean owns(Inventory inv){return inv.getHolder() instanceof Holder;}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof Holder holder))return;
        boolean prior=e.isCancelled();e.setCancelled(true);
        if(prior||!(e.getWhoClicked() instanceof Player p)||!holder.owner.equals(p.getUniqueId())||!holder.positions.containsKey(e.getRawSlot())||e.getClick()!=ClickType.LEFT&&e.getClick()!=ClickType.RIGHT)return;
        int index=holder.positions.get(e.getRawSlot());if(holder.used.compareAndSet(false,true))plugin.getServer().getScheduler().runTask(plugin,()->{if(usable(p,holder.staff,holder.token))holder.callback.accept(index);});
    }
    @EventHandler public void drag(InventoryDragEvent e){if(owns(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler public void quit(PlayerQuitEvent e){screens.remove(e.getPlayer().getUniqueId());}
    @Override public void close(){open=false;screens.clear();HandlerList.unregisterAll(this);}
}
