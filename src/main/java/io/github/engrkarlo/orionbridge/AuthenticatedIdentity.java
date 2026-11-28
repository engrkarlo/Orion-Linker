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
    private static final ClassValue<java.util.Map<String,java.lang.reflect.Method>> METHODS=new ClassValue<>(){
        @Override protected java.util.Map<String,java.lang.reflect.Method> computeValue(Class<?> c){
            var result=new java.util.HashMap<String,java.lang.reflect.Method>();
            for(var m:c.getMethods()){
                if(m.getParameterCount()==0)result.put(m.getName(),m);
                else if(m.getParameterCount()==1&&(m.getParameterTypes()[0]==UUID.class||m.getParameterTypes()[0]==Player.class))result.put(m.getName(),m);
            }
            return java.util.Map.copyOf(result);
        }
    };
    private static java.lang.reflect.Method method(Class<?> c,String name)throws NoSuchMethodException{
        var m=METHODS.get(c).get(name);if(m==null)throw new NoSuchMethodException(name);return m;
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
                if(Boolean.TRUE.equals(method(api,"isFloodgatePlayer").invoke(instance,p.getUniqueId()))){
                    Object account=method(api,"getPlayer").invoke(instance,p.getUniqueId());
                    if(account==null)return null;
                    Class<?> type=Class.forName("org.geysermc.floodgate.api.player.FloodgatePlayer",true,flood.getClass().getClassLoader());
                    // A linked Floodgate account uses its authenticated Java identity.
                    Object linked=method(type,"getLinkedPlayer").invoke(account);
                    if(linked!=null){
                        Object id=Class.forName("org.geysermc.floodgate.util.LinkedPlayer",true,flood.getClass().getClassLoader()).getMethod("getJavaUniqueId").invoke(linked);
                        return id instanceof UUID uuid&&uuid.version()==4?javaId(uuid):null;
                    }
                    String identity="bedrock:"+method(type,"getXuid").invoke(account);
                    return SanctionState.validIdentity(identity)?identity:null;
                }
            }catch(ReflectiveOperationException|RuntimeException|LinkageError e){return null;}
        }
        if(plugin.getServer().getOnlineMode()&&p.getUniqueId().version()==4)return javaId(p.getUniqueId());
        if(realm.isEmpty()||!p.getName().matches("[A-Za-z0-9_]{1,16}"))return null;
        Plugin auth=plugin.getServer().getPluginManager().getPlugin("AuthMe");
        if(auth==null||!auth.isEnabled())return null;
        try{
            Class<?> api=Class.forName("fr.xephi.authme.api.v3.AuthMeApi",true,auth.getClass().getClassLoader());
            Object instance=method(api,"getInstance").invoke(null);
            if(instance!=null&&Boolean.TRUE.equals(method(api,"isAuthenticated").invoke(instance,p)))return "authme:"+realm+":"+p.getName().toLowerCase(java.util.Locale.ROOT);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){}
        return null;
    }
    private static String javaId(UUID id){return "java:"+id.toString().replace("-","");}
}
