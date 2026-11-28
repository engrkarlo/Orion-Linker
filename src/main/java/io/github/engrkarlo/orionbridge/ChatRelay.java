package io.github.engrkarlo.orionbridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class ChatRelay implements Listener,AutoCloseable {
    private record Message(UUID id,long at,String identity,String name,String text,boolean staff){}
    private final OrionBridge plugin;
    private final VerificationGate gate;
    private final LocalModeration moderation;
    private final boolean outbound,inbound,staff;
    private final Deque<Message> queue=new ArrayDeque<>();
    private final Map<UUID,Long> delivered=new LinkedHashMap<>();
    private final Map<String,Long> rate=new HashMap<>();
    private final AtomicLong dropped=new AtomicLong();
    private volatile boolean open=true;
    ChatRelay(OrionBridge plugin,YamlConfiguration cfg,VerificationGate gate,LocalModeration moderation){
        this.plugin=plugin;this.gate=gate;this.moderation=moderation;
        outbound=cfg.getBoolean("chat.minecraft-to-discord",true);inbound=cfg.getBoolean("chat.discord-to-minecraft",true);staff=cfg.getBoolean("chat.staff-chat",false);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private static String clean(String value,int limit){
        String text=value.replaceAll("[\\p{Cntrl}]"," ").strip();
        return text.length()>limit?text.substring(0,limit):text;
    }
    private boolean muted(String identity){
        return moderation!=null&&moderation.state.has(identity,System.currentTimeMillis(),SanctionState.Kind.MUTE,SanctionState.Kind.FREEZE,SanctionState.Kind.QUARANTINE,SanctionState.Kind.TEMP_BAN,SanctionState.Kind.PERMANENT_BAN);
    }
    private synchronized boolean offer(String identity,String name,String text,boolean privateChat){
        if(!open||!outbound||identity==null||muted(identity)||privateChat&&!staff)return false;
        long now=System.currentTimeMillis();expire(now);
        String key=identity+":"+privateChat;
        if(now-rate.getOrDefault(key,0L)<500)return false;
        if(rate.size()>=4096){rate.entrySet().removeIf(e->now-e.getValue()>60000);if(rate.size()>=4096)return false;}
        rate.put(key,now);
        if(queue.size()>=512){queue.removeFirst();dropped.incrementAndGet();}
        String message=clean(text,500);if(message.isEmpty())return false;
        queue.addLast(new Message(UUID.randomUUID(),now,identity,clean(name,64),message,privateChat));
        return true;
    }
    private void expire(long now){
        while(!queue.isEmpty()&&now-queue.peekFirst().at()>30000)queue.removeFirst();
        delivered.entrySet().removeIf(e->now-e.getValue()>120000);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void chat(AsyncChatEvent e){
        String identity=gate==null?null:gate.linkedIdentity(e.getPlayer().getUniqueId());
        if(identity!=null)offer(identity,e.getPlayer().getName(),PlainTextComponentSerializer.plainText().serialize(e.message()),false);
    }
    boolean staff(Player p,String message){
        if(!open||!staff||gate==null||plugin.guard()==null||!plugin.guard().staffChat(p.getUniqueId()))return false;
        String identity=gate.linkedIdentity(p.getUniqueId());
        if(identity==null||muted(identity)||!offer(identity,p.getName(),message,true))return false;
        Component line=Component.text("[Staff] "+p.getName()+": "+clean(message,500));
        for(Player viewer:plugin.getServer().getOnlinePlayers())if(plugin.guard().staffChat(viewer.getUniqueId()))viewer.sendMessage(line);
        return true;
    }
    synchronized String snapshot(){
        expire(System.currentTimeMillis());List<String> rows=new ArrayList<>();
        for(Message m:queue){
            if(rows.size()>=50)break;
            rows.add("{\"id\":"+q(m.id().toString())+",\"at\":"+m.at()+",\"identity\":"+q(m.identity())+",\"name\":"+q(m.name())+",\"text\":"+q(m.text())+",\"staff\":"+m.staff()+"}");
        }
        return "{\"protocol\":2,\"queued\":"+queue.size()+",\"dropped\":"+dropped.get()+",\"messages\":["+String.join(",",rows)+"]}";
    }
    private static String q(String value){return OrionBridge.quote(value);}
    synchronized int acknowledge(Set<UUID> ids){int before=queue.size();queue.removeIf(m->ids.contains(m.id()));return before-queue.size();}
    String receive(String raw){
        if(!open||!inbound)return "DISABLED";
        String[] f=raw.split("\\n",-1);
        if(f.length!=8||!f[0].equals("SEND")||!SanctionState.validIdentity(f[3])||!Set.of("true","false").contains(f[4]))return "INVALID";
        UUID id=UUID.fromString(f[1]);long time=Long.parseLong(f[2]);boolean privateChat=Boolean.parseBoolean(f[4]);
        long now=System.currentTimeMillis();
        if(time<now-30000||time>now+5000||privateChat&&!staff||muted(f[3]))return "DENIED";
        String name=clean(new String(Base64.getUrlDecoder().decode(f[5]),java.nio.charset.StandardCharsets.UTF_8),64);
        String text=clean(new String(Base64.getUrlDecoder().decode(f[6]),java.nio.charset.StandardCharsets.UTF_8),500);
        if(text.isEmpty()||name.isEmpty())return "INVALID";
        if(privateChat&&(plugin.guard()==null||!plugin.guard().profileStaffChat(f[7])))return "DENIED";
        var result=new CompletableFuture<String>();
        try{
            plugin.getServer().getScheduler().runTask(plugin,()->{
                if(result.isDone()||!open)return;
                synchronized(this){
                    expire(System.currentTimeMillis());
                    if(delivered.containsKey(id)){result.complete("APPLIED");return;}
                    if(delivered.size()>=4096){result.complete("BUSY");return;}
                    if(muted(f[3])){result.complete("DENIED");return;}
                    String rateKey="in:"+f[3]+":"+privateChat;long t=System.currentTimeMillis();
                    if(t-rate.getOrDefault(rateKey,0L)<500){result.complete("RATE_LIMITED");return;}
                    if(rate.size()>=4096){rate.entrySet().removeIf(e->t-e.getValue()>60000);if(rate.size()>=4096){result.complete("BUSY");return;}}
                    rate.put(rateKey,t);delivered.put(id,t);
                }
                Component message=Component.text((privateChat?"[Discord Staff] ":"[Discord] ")+name+": "+text);
                for(Player player:plugin.getServer().getOnlinePlayers()){
                    if(gate==null||!gate.authorized(player.getUniqueId()))continue;
                    if(!privateChat||plugin.guard()!=null&&plugin.guard().staffChat(player.getUniqueId()))player.sendMessage(message);
                }
                result.complete("APPLIED");
            });
            return result.get(3,TimeUnit.SECONDS);
        }catch(InterruptedException e){Thread.currentThread().interrupt();result.cancel(false);return "BUSY";}
        catch(Exception e){result.cancel(false);return "BUSY";}
    }
    @Override public synchronized void close(){open=false;HandlerList.unregisterAll(this);queue.clear();delivered.clear();rate.clear();}
}
