package io.github.engrkarlo.orionbridge;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Optional verification-only lobby. Never changes a player's inventory,
 * gamemode, health, permission state or spawn point. Bukkit main thread only. */
final class VerificationLobby {
    private final OrionBridge plugin;
    private final Path folder;
    private final String worldName;
    private final boolean enabled;
    private final Map<UUID,Location> allowed=new HashMap<>();
    private final Set<UUID> entered=new HashSet<>();
    VerificationLobby(OrionBridge plugin,YamlConfiguration cfg){
        this.plugin=plugin;folder=plugin.getDataFolder().toPath().resolve("verification-return");
        enabled=cfg.getBoolean("verification.lobby.enabled",false);
        worldName=cfg.getString("verification.lobby.world","");
        if(enabled&&(worldName.isBlank()||worldName.length()>100||Bukkit.getWorld(worldName)==null))throw new IllegalArgumentException("Invalid verification lobby world");
    }
    private Path path(UUID player){return folder.resolve(player+".yml");}
    boolean allowed(PlayerTeleportEvent event){
        Location target=allowed.get(event.getPlayer().getUniqueId());
        return target!=null&&event.getCause()==PlayerTeleportEvent.TeleportCause.PLUGIN&&target.equals(event.getTo());
    }
    private boolean move(Player p,Location to){
        if(to.getWorld()==null||!to.getWorld().isChunkLoaded(to.getBlockX()>>4,to.getBlockZ()>>4))return false;
        allowed.put(p.getUniqueId(),to.clone());
        try{return p.teleport(to,PlayerTeleportEvent.TeleportCause.PLUGIN);}finally{allowed.remove(p.getUniqueId());}
    }
    private boolean safe(Location loc){
        var w=loc.getWorld();
        if(w==null||!w.isChunkLoaded(loc.getBlockX()>>4,loc.getBlockZ()>>4)||loc.getY()<=w.getMinHeight()||loc.getY()>=w.getMaxHeight()-2)return false;
        return loc.getBlock().isPassable()&&loc.clone().add(0,1,0).getBlock().isPassable()&&loc.clone().add(0,-1,0).getBlock().getType().isSolid();
    }
    private YamlConfiguration read(UUID id)throws IOException,org.bukkit.configuration.InvalidConfigurationException{
        Path file=path(id);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>4096)throw new IOException("Invalid return record");
        var yaml=new YamlConfiguration();yaml.load(file.toFile());return yaml;
    }
    private void save(Player p,String identity)throws IOException{
        Path file=path(p.getUniqueId());if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))return;
        Files.createDirectories(folder);Location loc=p.getLocation();var yaml=new YamlConfiguration();
        yaml.set("identity",identity);yaml.set("world",loc.getWorld().getUID().toString());yaml.set("x",loc.getX());yaml.set("y",loc.getY());yaml.set("z",loc.getZ());yaml.set("yaw",(double)loc.getYaw());yaml.set("pitch",(double)loc.getPitch());
        Path tmp=Files.createTempFile(folder,"return-",".tmp");
        try{yaml.save(tmp.toFile());try(var channel=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.WRITE)){channel.force(true);}Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE);}
        finally{Files.deleteIfExists(tmp);}
    }
    void enter(Player p,String identity){
        UUID id=p.getUniqueId();if(!enabled||identity==null||entered.contains(id))return;
        World world=Bukkit.getWorld(worldName);if(world==null)return;
        Location to=world.getSpawnLocation();
        if(!safe(to)){entered.add(id);plugin.getLogger().warning("Verification lobby spawn unavailable or unsafe; retaining original position.");return;}
        try{
            if(Files.exists(path(id),LinkOption.NOFOLLOW_LINKS)&&!identity.equals(read(id).getString("identity")))return;
            if(p.getWorld()==world){entered.add(id);return;}
            save(p,identity);
            if(move(p,to))entered.add(id);
        }catch(Exception ex){entered.add(id);plugin.getLogger().warning("Verification lobby return storage unavailable; retaining original position.");}
    }
    boolean restore(Player p,String identity){
        UUID id=p.getUniqueId();if(identity==null||!Files.exists(path(id),LinkOption.NOFOLLOW_LINKS))return true;
        try{
            var y=read(id);if(!identity.equals(y.getString("identity")))return false;
            World w=Bukkit.getWorld(UUID.fromString(y.getString("world","")));
            if(w==null)return false;
            for(String k:List.of("x","y","z","yaw","pitch"))if(!y.isDouble(k)||!Double.isFinite(y.getDouble(k)))return false;
            Location loc=new Location(w,y.getDouble("x"),y.getDouble("y"),y.getDouble("z"),(float)y.getDouble("yaw"),(float)y.getDouble("pitch"));
            if(!safe(loc)||!move(p,loc))return false;
            Files.delete(path(id));entered.remove(id);return true;
        }catch(Exception ex){return false;}
    }
    void forget(UUID id){entered.remove(id);allowed.remove(id);}
}
