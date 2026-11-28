package io.github.engrkarlo.orionbridge;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class ConfigMigration {
    static boolean migrate(Path path,YamlConfiguration defaults)throws Exception{
        var current=new YamlConfiguration();current.load(path.toFile());
        if(current.contains("config-version",true)&&!current.isInt("config-version"))throw new IllegalArgumentException("Invalid config version");
        int from=current.getInt("config-version",1),to=defaults.getInt("config-version",1);
        if(from>to)throw new IllegalArgumentException("Configuration is from a newer release");
        if(from==to)return false;
        // Legacy installations without mode used Push if an endpoint existed.
        if(!current.contains("mode",true))current.set("mode",current.getString("endpoint","").isBlank()?"pull":"push");
        merge(current,defaults);current.set("config-version",to);
        Path backup=Files.createTempFile(path.getParent(),"config.yml.v"+from+".",".bak");
        Files.copy(path,backup,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
        Path temp=Files.createTempFile(path.getParent(),"config.yml.",".tmp");
        try{
            current.save(temp.toFile());var check=new YamlConfiguration();check.load(temp.toFile());
            if(check.getInt("config-version")!=to)throw new java.io.IOException("Migration verification failed");
            if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(temp,Files.getPosixFilePermissions(path));
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            return true;
        }finally{Files.deleteIfExists(temp);}
    }
    private static void merge(ConfigurationSection current,ConfigurationSection defaults){
        for(String key:defaults.getKeys(false)){
            if(key.equals("config-version"))continue;
            if(defaults.isConfigurationSection(key)){
                if(!current.contains(key,true))current.createSection(key);
                var child=current.getConfigurationSection(key);
                if(child==null)throw new IllegalArgumentException("Invalid configuration section");
                merge(child,defaults.getConfigurationSection(key));
            }else if(!current.contains(key,true)){
                current.set(key,defaults.get(key));current.setComments(key,defaults.getComments(key));
            }
        }
    }
}
