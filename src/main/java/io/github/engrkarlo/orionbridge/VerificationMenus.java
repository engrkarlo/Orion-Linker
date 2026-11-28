package io.github.engrkarlo.orionbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class VerificationMenus implements Listener,AutoCloseable {
    record Action(String label,Runnable run){}
    private record Screen(Component title,Component body,List<Action> actions){}
    private final OrionBridge plugin;
    private final YamlConfiguration dialogs,inventory,bedrock;
    private final boolean inventoryFirst;
    private final MiniMessage mm=MiniMessage.miniMessage();
    VerificationMenus(OrionBridge plugin,YamlConfiguration cfg)throws Exception{
        this.plugin=plugin;inventoryFirst=cfg.getString("verification.java-interface","DIALOG").equalsIgnoreCase("INVENTORY");
        dialogs=load("java-dialogs.yml");inventory=load("java-inventory.yml");bedrock=load("bedrock-forms.yml");
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private YamlConfiguration load(String name)throws Exception{
        String path="menus/"+name;File file=new File(plugin.getDataFolder(),path);if(!file.exists())plugin.saveResource(path,false);
        var theme=new YamlConfiguration();theme.load(file);return theme;
    }
    private Component formatted(YamlConfiguration theme,String key,String fallback,VerificationState.Session s,String invite){
        String value=theme.getString(key,fallback);
        try{return mm.deserialize(value,Placeholder.unparsed("code",s.code()==null?"":s.code()),Placeholder.unparsed("discord",s.display()==null?"":s.display()),Placeholder.unparsed("discord_id",s.claimant()==null?(s.linked()==null?"":s.linked()):s.claimant()),Placeholder.unparsed("invite",invite));}
        catch(RuntimeException ex){return Component.text(value);}
    }
    private Screen screen(YamlConfiguration theme,VerificationState.Session s,String invite,Runnable request,Runnable refresh,Runnable confirm,Runnable close){
        long now=System.currentTimeMillis();String state;
        List<Action> actions=new ArrayList<>();
        if(s.verified(now)){state="verified";actions.add(new Action("close",close));}
        else if(s.identity()==null){state="authenticate";actions.add(new Action("refresh",refresh));}
        else if(s.confirmed()){state="pending";actions.add(new Action("refresh",refresh));}
        else if(s.claimant()!=null&&s.hasCode(now)){state="confirm";actions.add(new Action("confirm",confirm));actions.add(new Action("regenerate",request));}
        else if(s.hasCode(now)){state="code";actions.add(new Action("refresh",refresh));actions.add(new Action("regenerate",request));}
        else if(s.request()!=null){state="waiting";actions.add(new Action("refresh",refresh));}
        else{state="welcome";actions.add(new Action("link",request));}
        return new Screen(formatted(theme,"title","OrionBridge | Account",s,invite),formatted(theme,"screens."+state,"Open /ob link for verification.",s,invite),List.copyOf(actions));
    }
    private Component label(YamlConfiguration theme,Action a,VerificationState.Session s,String invite){return formatted(theme,"buttons."+a.label()+".name",a.label(),s,invite);}
    void show(Player p,VerificationState.Session s,String invite,Runnable request,Runnable refresh,Runnable confirm,Runnable close){
        var edition=Bedrock.detect(p);
        if(edition==Bedrock.Detection.BEDROCK){
            Screen sc=screen(bedrock,s,invite,request,refresh,confirm,close);var legacy=LegacyComponentSerializer.legacySection();
            List<Bedrock.Button> buttons=sc.actions().stream().map(a->new Bedrock.Button(legacy.serialize(label(bedrock,a,s,invite)),bedrock.getString("buttons."+a.label()+".image",""))).toList();
            if(Bedrock.show(p,plugin,legacy.serialize(sc.title()),legacy.serialize(sc.body()),buttons,i->{if(i>=0&&i<sc.actions().size())sc.actions().get(i).run();},()->{}))return;
        }else if(edition==Bedrock.Detection.JAVA){
            if(!inventoryFirst){
                Screen sc=screen(dialogs,s,invite,request,refresh,confirm,close);
                if(JavaDialogs.show(p,plugin,sc.title(),sc.body(),null,sc.actions().stream().map(a->new JavaDialogs.Button(label(dialogs,a,s,invite),null)).toList(),Math.max(1,Math.min(2,dialogs.getInt("columns",1))),Math.max(50,Math.min(300,dialogs.getInt("button-width",160))),-1,i->{if(i>=0&&i<sc.actions().size())sc.actions().get(i).run();}))return;
            }
            try{showInventory(p,s,invite,request,refresh,confirm,close);return;}catch(RuntimeException|LinkageError ignored){}
        }
        Screen sc=screen(dialogs,s,invite,request,refresh,confirm,close);p.sendMessage(sc.title());p.sendMessage(sc.body());
        if(s.hasCode(System.currentTimeMillis()))p.sendMessage(Component.text(s.code()).clickEvent(ClickEvent.copyToClipboard(s.code())));
        if(s.claimant()!=null&&s.request()!=null&&!s.confirmed())p.sendMessage(Component.text("/ob confirm "+s.request()).clickEvent(ClickEvent.suggestCommand("/ob confirm "+s.request())));
        else if(!s.verified(System.currentTimeMillis()))p.sendMessage(Component.text("/ob link").clickEvent(ClickEvent.suggestCommand("/ob link")));
    }
    private void showInventory(Player p,VerificationState.Session s,String invite,Runnable request,Runnable refresh,Runnable confirm,Runnable close){
        Screen sc=screen(inventory,s,invite,request,refresh,confirm,close);
        Holder holder=new Holder(p.getUniqueId());int size=Math.max(27,Math.min(54,inventory.getInt("size",27)/9*9));
        holder.inventory=Bukkit.createInventory(holder,size,sc.title());
        Material filler=material(inventory.getString("filler-material","BLACK_STAINED_GLASS_PANE"),Material.BLACK_STAINED_GLASS_PANE);
        ItemStack fill=new ItemStack(filler);var fm=fill.getItemMeta();fm.displayName(Component.text(" "));fill.setItemMeta(fm);
        for(int slot=0;slot<size;slot++)if(slot<9||slot>=size-9||slot%9==0||slot%9==8)holder.inventory.setItem(slot,fill.clone());
        ItemStack info=new ItemStack(material(inventory.getString("information-material","BOOK"),Material.BOOK));var im=info.getItemMeta();
        im.displayName(sc.title().decoration(TextDecoration.ITALIC,false));im.lore(List.of(sc.body().decoration(TextDecoration.ITALIC,false)));info.setItemMeta(im);
        int infoSlot=Math.max(9,Math.min(size-10,inventory.getInt("information-slot",13)));holder.inventory.setItem(infoSlot,info);
        for(int i=0;i<sc.actions().size();i++){
            Action a=sc.actions().get(i);int slot=Math.max(9,Math.min(size-10,inventory.getInt(i==0?"primary-slot":"secondary-slot",i==0?11:15)));
            if(slot==infoSlot||holder.actions.containsKey(slot))slot=java.util.stream.IntStream.range(9,size-9).filter(n->n!=infoSlot&&!holder.actions.containsKey(n)).findFirst().orElseThrow();
            ItemStack item=new ItemStack(material(inventory.getString("buttons."+a.label()+".material","PAPER"),Material.PAPER));
            var meta=item.getItemMeta();meta.displayName(label(inventory,a,s,invite).decoration(TextDecoration.ITALIC,false));item.setItemMeta(meta);holder.inventory.setItem(slot,item);holder.actions.put(slot,a.run());
        }
        p.openInventory(holder.inventory);
        if(s.hasCode(System.currentTimeMillis()))p.sendMessage(Component.text(s.code()).clickEvent(ClickEvent.copyToClipboard(s.code())));
    }
    private Material material(String name,Material fallback){Material m=Material.matchMaterial(name);return m!=null&&m.isItem()&&!m.isAir()?m:fallback;}
    private static final class Holder implements InventoryHolder{
        final UUID player;final Map<Integer,Runnable> actions=new HashMap<>();final AtomicBoolean used=new AtomicBoolean();Inventory inventory;
        Holder(UUID player){this.player=player;}
        @Override public Inventory getInventory(){return inventory;}
    }
    static boolean owns(Inventory inventory){return inventory.getHolder() instanceof Holder;}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof Holder h))return;
        boolean prior=e.isCancelled();e.setCancelled(true);
        if(prior||!(e.getWhoClicked() instanceof Player p)||!h.player.equals(p.getUniqueId())||e.getRawSlot()<0||e.getRawSlot()>=h.inventory.getSize()||e.getClick()!=ClickType.LEFT&&e.getClick()!=ClickType.RIGHT)return;
        Runnable action=h.actions.get(e.getRawSlot());if(action!=null&&h.used.compareAndSet(false,true))plugin.getServer().getScheduler().runTask(plugin,()->{if(p.isOnline()&&p.getOpenInventory().getTopInventory().getHolder()==h)action.run();});
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(owns(e.getView().getTopInventory()))e.setCancelled(true);}
    @Override public void close(){HandlerList.unregisterAll(this);}
}
