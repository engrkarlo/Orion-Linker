package io.github.engrkarlo.orionbridge;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import java.util.*;

final class OrionCommands implements TabExecutor {
    private static final List<String> ORDER=List.of("help","account","link","confirm","return","roles","punishments","report","appeal","staffchat","admin","status","reload","lockdown","verification-off","setup");
    private static final Set<String> ACCOUNT=Set.of("account","roles","punishments","report","appeal");
    private final OrionBridge plugin;
    OrionCommands(OrionBridge plugin){this.plugin=plugin;}
    private TextTheme msg(){return plugin.messages();}
    private boolean verified(Player p){return plugin.verification()!=null&&plugin.verification().authorized(p.getUniqueId());}
    private boolean allowed(CommandSender sender,String sub){
        if(sub.equals("help"))return true;
        if(sub.equals("setup"))return sender instanceof ConsoleCommandSender;
        if(sub.equals("lockdown"))return sender instanceof ConsoleCommandSender&&plugin.guard()!=null;
        if(sub.equals("verification-off"))return sender instanceof ConsoleCommandSender;
        if(sender instanceof Player p&&plugin.moderation()!=null&&plugin.moderation().denied(p.getUniqueId(),SanctionState.Kind.FREEZE,SanctionState.Kind.QUARANTINE,SanctionState.Kind.COMMAND_RESTRICTION,SanctionState.Kind.TEMP_BAN,SanctionState.Kind.PERMANENT_BAN)&&!ACCOUNT.contains(sub)&&!Set.of("link","confirm").contains(sub))return false;
        if(sub.equals("status"))return sender.hasPermission("orionbridge.admin")&&(!(sender instanceof Player p)||plugin.verification()==null||verified(p));
        if(sub.equals("reload"))return sender.hasPermission("orionbridge.reload")&&(!(sender instanceof Player p)||(plugin.verification()==null||verified(p))&&(plugin.guard()==null||plugin.guard().profile(p.getUniqueId()).commands().contains("orionbridge:ob-reload")));
        if(!(sender instanceof Player p))return false;
        if(sub.equals("link")||sub.equals("confirm"))return plugin.verification()!=null;
        if(sub.equals("account"))return plugin.verification()!=null;
        if(sub.equals("return"))return verified(p);
        if(ACCOUNT.contains(sub))return verified(p)&&plugin.dashboards()!=null;
        if(sub.equals("admin"))return verified(p)&&p.hasPermission("orionbridge.admin.gui")&&plugin.guard()!=null&&plugin.guard().profile(p.getUniqueId()).lockdown();
        if(sub.equals("staffchat"))return verified(p)&&plugin.chat()!=null&&plugin.chat().staffEnabled()&&plugin.guard()!=null&&plugin.guard().staffChat(p.getUniqueId());
        return false;
    }
    private List<String> available(CommandSender sender){return ORDER.stream().filter(s->allowed(sender,s)).toList();}
    private static List<String> match(Collection<String> choices,String typed){String prefix=typed.toLowerCase(Locale.ROOT);return choices.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();}
    private void help(CommandSender sender,String selected){
        TextTheme theme;
        try{theme=new TextTheme(plugin.theme("help"));}catch(RuntimeException unavailable){msg().send(sender,"commands.help-unavailable");return;}
        List<String> entries=available(sender);
        if(selected!=null){
            if(!entries.contains(selected)){msg().send(sender,"commands.unknown");return;}
            entries=List.of(selected);
        }
        sender.sendMessage(theme.render("header"));
        for(String sub:entries){
            String usage=theme.raw("commands."+sub+".usage"),description=theme.raw("commands."+sub+".description");
            Component line=theme.render("entry","usage",usage,"description",description);
            if(theme.flag("click-to-suggest",true)&&sender instanceof Player){
                line=line.clickEvent(ClickEvent.suggestCommand("/ob "+sub+" "));
                if(theme.flag("hover-hint",true))line=line.hoverEvent(HoverEvent.showText(theme.render("hover","command","/ob "+sub)));
            }
            sender.sendMessage(line);
        }
        sender.sendMessage(theme.render("footer"));
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(args.length==1)return match(available(sender),args[0]);
        if(args.length==2){
            String sub=args[0].toLowerCase(Locale.ROOT);if(!allowed(sender,sub))return List.of();
            if(sub.equals("help"))return match(available(sender),args[1]);
            if(sub.equals("setup"))return match(List.of("status","approve","cancel"),args[1]);
            if(sub.equals("lockdown"))return match(List.of("off","1","5","10","15","30","60"),args[1]);
            if(sub.equals("confirm")&&sender instanceof Player p&&plugin.verification()!=null){
                var session=plugin.verification().session(p.getUniqueId());
                if(session!=null&&session.request()!=null&&session.claimant()!=null&&session.hasCode(System.currentTimeMillis())&&!session.confirmed())return match(List.of(session.request().toString()),args[1]);
            }
        }
        return List.of(); // never suggest other players or private codes
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        String sub=args.length==0?(sender instanceof Player&&allowed(sender,"account")?"account":"help"):args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("help")){
            if(args.length>2){help(sender,null);return true;}
            help(sender,args.length==2?args[1].toLowerCase(Locale.ROOT):null);return true;
        }
        if(!ORDER.contains(sub)){msg().send(sender,"commands.unknown");help(sender,null);return true;}
        if(!allowed(sender,sub)){msg().send(sender,"commands.denied");return true;}
        int n=args.length;
        if(sub.equals("setup")){plugin.setupCommand(sender,args);return true;}
        if(sub.equals("verification-off")&&n==1){msg().send(sender,plugin.releaseVerification()?"commands.verification-off":"commands.verification-inactive");return true;}
        if(sub.equals("reload")&&n==1){
            msg().send(sender,"commands.reload-start");
            var result=plugin.reloadAll();
            msg().send(sender,"commands.reload-"+result.name().toLowerCase(Locale.ROOT));return true;
        }
        if(sub.equals("status")&&n==1){
            msg().send(sender,"commands.status-header");
            status(sender,"telemetry",plugin.transportActive());status(sender,"audit",plugin.audit()!=null);status(sender,"verification",plugin.verification()!=null);status(sender,"moderation",plugin.moderation()!=null);status(sender,"guard",plugin.guard()!=null);status(sender,"roles",plugin.roles()!=null);status(sender,"chat",plugin.chat()!=null);status(sender,"worldedit",plugin.editHookActive());status(sender,"luckperms",plugin.permissionHookActive());status(sender,"recovery",plugin.recovery()!=null);
            msg().send(sender,"commands.status-footer");return true;
        }
        if(sub.equals("lockdown")&&n==2){
            try{long until=0;if(!args[1].equalsIgnoreCase("off")){int minutes=Integer.parseInt(args[1]);if(minutes<1||minutes>60)throw new IllegalArgumentException();until=System.currentTimeMillis()+minutes*60000L;}plugin.guard().lockdown(until,"console");msg().send(sender,"commands.lockdown-updated");}
            catch(java.io.UncheckedIOException e){msg().send(sender,"commands.lockdown-storage");}
            catch(RuntimeException e){help(sender,"lockdown");}return true;
        }
        if(sender instanceof Player p){
            if(sub.equals("staffchat")&&n>=2){if(!plugin.chat().staff(p,String.join(" ",Arrays.copyOfRange(args,1,n))))msg().send(sender,"commands.staffchat-unavailable");return true;}
            if(n<=1&&plugin.dashboards()!=null){
                if(sub.equals("admin")){plugin.dashboards().adminMenu(p);return true;}
                if(verified(p)&&ACCOUNT.contains(sub)){
                    switch(sub){case "report"->plugin.dashboards().report(p);case "appeal"->plugin.dashboards().appeal(p);case "punishments"->plugin.dashboards().punishments(p,plugin.verification().linkedIdentity(p.getUniqueId()),false);default->plugin.dashboards().account(p);}return true;
                }
            }
            if(plugin.verification()!=null&&plugin.verification().playerCommand(p,args))return true;
        }
        help(sender,sub);return true;
    }
    private void status(CommandSender sender,String key,boolean active){msg().send(sender,active?"commands.status-on":"commands.status-off","service",msg().raw("services."+key));}
}
