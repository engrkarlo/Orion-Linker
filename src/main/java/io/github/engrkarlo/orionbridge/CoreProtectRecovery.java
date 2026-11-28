package io.github.engrkarlo.orionbridge;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.file.YamlConfiguration;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded legacy CoreProtect API adapter. Never queries on Paper's main thread. */
final class CoreProtectRecovery {
    private final OrionBridge plugin;
    private final Object api;
    private final Method lookup,rollback;
    private final boolean execution;
    private final int maxRows,maxRadius,maxSeconds;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final Map<UUID,Preview> previews=new ConcurrentHashMap<>();
    private record Query(String name,String world,int x,int z,int radius,int seconds,long started){}
    private record Preview(Query query,int count,String hash,long expires){}
    CoreProtectRecovery(OrionBridge plugin,YamlConfiguration cfg)throws ReflectiveOperationException{
        this.plugin=plugin;Plugin cp=plugin.getServer().getPluginManager().getPlugin("CoreProtect");
        if(cp==null||!cp.isEnabled())throw new IllegalArgumentException("CoreProtect unavailable");
        api=cp.getClass().getMethod("getAPI").invoke(cp);
        if(api==null||!Boolean.TRUE.equals(api.getClass().getMethod("isEnabled").invoke(api)))throw new IllegalArgumentException("CoreProtect API unavailable");
        lookup=api.getClass().getMethod("performPartialLookup",int.class,List.class,List.class,List.class,List.class,List.class,int.class,Location.class,int.class,int.class);
        rollback=api.getClass().getMethod("performRollback",int.class,List.class,List.class,List.class,List.class,List.class,int.class,Location.class);
        execution=cfg.getBoolean("recovery.allow-rollback",false);
        maxRows=Math.max(10,Math.min(1000,cfg.getInt("recovery.maximum-records",500)));
        maxRadius=Math.max(1,Math.min(64,cfg.getInt("recovery.maximum-radius",32)));
        maxSeconds=Math.max(60,Math.min(3600,cfg.getInt("recovery.maximum-seconds",900)));
    }
    String request(String body)throws Exception{
        if(!busy.compareAndSet(false,true))return "{\"result\":\"BUSY\"}";
        try{
            previews.entrySet().removeIf(e->e.getValue().expires()<System.currentTimeMillis());
            String[] f=body.split("\\n",-1);
            if(f.length==7&&f[0].equals("PREVIEW")){
                long now=System.currentTimeMillis();
                if(!f[1].matches("[A-Za-z0-9_.]{1,32}")||!f[2].matches("[A-Za-z0-9_.-]{1,100}"))throw new IllegalArgumentException("Invalid target or world");
                Query q=new Query(f[1],f[2],Integer.parseInt(f[3]),Integer.parseInt(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6]),now);
                if(Math.abs((long)q.x())>30000000||Math.abs((long)q.z())>30000000||q.radius()<1||q.radius()>maxRadius||q.seconds()<1||q.seconds()>maxSeconds)throw new IllegalArgumentException("Query exceeds local bounds");
                List<String[]> rows=rows(q,location(q,false));
                if(rows==null)return "{\"result\":\"UNAVAILABLE\"}";
                if(rows.size()>maxRows)return "{\"result\":\"TOO_LARGE\"}";
                if(previews.size()>=32)return "{\"result\":\"BUSY\"}";
                UUID id=UUID.randomUUID();long expires=now+90000;
                previews.put(id,new Preview(q,rows.size(),digest(rows),expires));
                return "{\"result\":\"PREVIEW\",\"id\":"+OrionBridge.quote(id.toString())+",\"count\":"+rows.size()+",\"expires\":"+expires+",\"executionEnabled\":"+execution+"}";
            }
            if(f.length==2&&f[0].equals("EXECUTE")){
                UUID id=UUID.fromString(f[1]);Preview preview=previews.remove(id); // one-shot, never retry an uncertain rollback
                if(preview==null||!execution||preview.expires()<System.currentTimeMillis()||preview.count()==0)return "{\"result\":\"DENIED\"}";
                Query q=preview.query();Location loc=location(q,true);
                List<String[]> current=rows(q,loc);
                if(current==null||current.size()!=preview.count()||!digest(current).equals(preview.hash()))return "{\"result\":\"CHANGED_REPREVIEW\"}";
                // The name must remain offline and lockdown active. The external
                // API has no atomic preview token; concurrent external edits remain a limitation.
                location(q,true);
                Object result=rollback.invoke(api,seconds(q),List.of(q.name()),null,null,null,List.of(0,1),q.radius(),loc);
                int returned=result instanceof List<?> list?list.size():0;
                if(plugin.guard()!=null)plugin.guard().external("RECOVERY_RESULT",null,"CoreProtect rollback request "+id+"; target="+q.name()+"; world="+q.world()+"; radius="+q.radius()+"; returned rows="+returned+"; inspect world to confirm","CoreProtect");
                return "{\"result\":"+OrionBridge.quote(returned>0?"SUBMITTED":"UNKNOWN")+",\"returnedRows\":"+returned+"}";
            }
            throw new IllegalArgumentException("Invalid recovery action");
        }finally{busy.set(false);}
    }
    private int seconds(Query q){
        return Math.min(maxSeconds+90,q.seconds()+(int)Math.max(0,(System.currentTimeMillis()-q.started())/1000));
    }
    @SuppressWarnings("unchecked") private List<String[]> rows(Query q,Location loc)throws ReflectiveOperationException{
        return (List<String[]>)lookup.invoke(api,seconds(q),List.of(q.name()),null,null,null,List.of(0,1),q.radius(),loc,0,maxRows+1);
    }
    private Location location(Query q,boolean executing)throws Exception{
        var result=new CompletableFuture<Location>();
        plugin.getServer().getScheduler().runTask(plugin,()->{
            if(result.isDone())return;
            var world=plugin.getServer().getWorld(q.world());
            if(world==null||executing&&(plugin.guard()==null||!plugin.guard().lockdown()||plugin.getServer().getPlayerExact(q.name())!=null)){
                result.completeExceptionally(new IllegalArgumentException("World unavailable, lockdown inactive or target online"));return;
            }
            result.complete(new Location(world,q.x(),world.getMinHeight(),q.z()));
        });
        try{return result.get(3,TimeUnit.SECONDS);}finally{result.cancel(false);}
    }
    private static String digest(List<String[]> rows)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        var normalized=new ArrayList<String>();
        for(String[] row:rows)normalized.add(Arrays.toString(row));
        Collections.sort(normalized);for(String row:normalized){digest.update(row.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
