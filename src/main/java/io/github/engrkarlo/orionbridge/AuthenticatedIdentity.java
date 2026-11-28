package io.github.engrkarlo.orionbridge;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.UUID;

/** Resolve only supported authenticated identities. Never infer Bedrock from a prefix. */
final class AuthenticatedIdentity {
    private final OrionBridge plugin;
    private final String realm;
    AuthenticatedIdentity(OrionBridge plugin,String realm){this.plugin=plugin;this.realm=realm!=null&&realm.matches("[a-z0-9_-]{1,32}")?realm:"";}
    String resolve(Player p){
        Plugin flood=plugin.getServer().getPluginManager().getPlugin("floodgate");
        if(flood!=null&&flood.isEnabled()){
            try{
                Class<?> api=Class.forName("org.geysermc.floodgate.api.FloodgateApi",true,flood.getClass().getClassLoader());
                Object instance=api.getMethod("getInstance").invoke(null);
                if(Boolean.TRUE.equals(api.getMethod("isFloodgatePlayer",UUID.class).invoke(instance,p.getUniqueId()))){
                    Object account=api.getMethod("getPlayer",UUID.class).invoke(instance,p.getUniqueId());
                    if(account==null)return null;
                    Class<?> type=Class.forName("org.geysermc.floodgate.api.player.FloodgatePlayer",true,flood.getClass().getClassLoader());
                    // A linked Floodgate account uses its authenticated Java identity.
                    Object linked=type.getMethod("getLinkedPlayer").invoke(account);
                    if(linked!=null){
                        Object id=Class.forName("org.geysermc.floodgate.util.LinkedPlayer",true,flood.getClass().getClassLoader()).getMethod("getJavaUniqueId").invoke(linked);
                        return id instanceof UUID uuid&&uuid.version()==4?javaId(uuid):null;
                    }
                    String identity="bedrock:"+type.getMethod("getXuid").invoke(account);
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
            Object instance=api.getMethod("getInstance").invoke(null);
            if(instance!=null&&Boolean.TRUE.equals(api.getMethod("isAuthenticated",Player.class).invoke(instance,p)))return "authme:"+realm+":"+p.getName().toLowerCase(java.util.Locale.ROOT);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){}
        return null;
    }
    private static String javaId(UUID id){return "java:"+id.toString().replace("-","");}
}
