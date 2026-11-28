package io.github.engrkarlo.orionbridge;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.UUID;

/** Resolve only supported authenticated identities. Never infer Bedrock from a prefix. */
final class AuthenticatedIdentity {
    private final OrionBridge plugin;
    private final String realm;
    AuthenticatedIdentity(OrionBridge plugin,String realm){this.plugin=plugin;this.realm=realm!=null&&realm.matches("[a-z0-9_-]{1,32}")?realm:"";}
    private record Cached(Player player,int tick,String identity){}
    private final java.util.Map<UUID,Cached> cache=new java.util.HashMap<>();
    private record Signature(String name,java.util.List<Class<?>> parameters){}
    private static final ClassValue<java.util.concurrent.ConcurrentMap<Signature,java.lang.reflect.Method>> METHODS=new ClassValue<>(){
        @Override protected java.util.concurrent.ConcurrentMap<Signature,java.lang.reflect.Method> computeValue(Class<?> c){return new java.util.concurrent.ConcurrentHashMap<>();}
    };
    private static java.lang.reflect.Method method(Class<?> c,String name,Class<?>... parameters)throws NoSuchMethodException{
        var key=new Signature(name,java.util.List.of(parameters));var methods=METHODS.get(c);
        var m=methods.get(key);if(m!=null)return m;
        m=c.getMethod(name,parameters);methods.putIfAbsent(key,m);return m;
    }
    String resolve(Player p){
        int tick=org.bukkit.Bukkit.getCurrentTick();
        var old=cache.get(p.getUniqueId());
        if(old!=null&&old.player()==p&&old.tick()==tick)return old.identity();
        return fresh(p);
    }
    String fresh(Player p){
        String id=lookup(p);
        if(cache.size()>=4096&&!cache.containsKey(p.getUniqueId()))cache.clear();
        cache.put(p.getUniqueId(),new Cached(p,org.bukkit.Bukkit.getCurrentTick(),id));return id;
    }
    void forget(UUID id){cache.remove(id);}
    private String lookup(Player p){
        Plugin flood=plugin.getServer().getPluginManager().getPlugin("floodgate");
        if(flood!=null&&flood.isEnabled()){
            try{
                Class<?> api=Class.forName("org.geysermc.floodgate.api.FloodgateApi",true,flood.getClass().getClassLoader());
                Object instance=method(api,"getInstance").invoke(null);
                if(Boolean.TRUE.equals(method(api,"isFloodgatePlayer",UUID.class).invoke(instance,p.getUniqueId()))){
                    Object account=method(api,"getPlayer",UUID.class).invoke(instance,p.getUniqueId());
                    if(account==null)return null;
                    Class<?> type=Class.forName("org.geysermc.floodgate.api.player.FloodgatePlayer",true,flood.getClass().getClassLoader());
                    // A linked Floodgate account uses its authenticated Java identity.
                    Object linked=method(type,"getLinkedPlayer").invoke(account);
                    if(linked!=null){
                        Object id=Class.forName("org.geysermc.floodgate.util.LinkedPlayer",true,flood.getClass().getClassLoader()).getMethod("getJavaUniqueId").invoke(linked);
                        return id instanceof UUID uuid&&uuid.version()==4&&uuid.variant()==2?javaId(uuid):null;
                    }
                    String identity="bedrock:"+method(type,"getXuid").invoke(account);
                    return SanctionState.validIdentity(identity)?identity:null;
                }
            }catch(ReflectiveOperationException|RuntimeException|LinkageError e){return null;}
        }
        if(plugin.getServer().getOnlineMode()&&p.getUniqueId().version()==4&&p.getUniqueId().variant()==2)return javaId(p.getUniqueId());
        if(realm.isEmpty()||!p.getName().matches("[A-Za-z0-9_]{1,16}"))return null;
        Plugin auth=plugin.getServer().getPluginManager().getPlugin("AuthMe");
        if(auth==null||!auth.isEnabled())return null;
        try{
            Class<?> api=Class.forName("fr.xephi.authme.api.v3.AuthMeApi",true,auth.getClass().getClassLoader());
            Object instance=method(api,"getInstance").invoke(null);
            if(instance!=null&&Boolean.TRUE.equals(method(api,"isAuthenticated",Player.class).invoke(instance,p)))return "authme:"+realm+":"+p.getName().toLowerCase(java.util.Locale.ROOT);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){}
        return null;
    }
    private static String javaId(UUID id){return "java:"+id.toString().replace("-","");}
}
