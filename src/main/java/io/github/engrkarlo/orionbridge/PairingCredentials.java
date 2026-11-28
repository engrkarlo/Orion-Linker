package io.github.engrkarlo.orionbridge;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Single source of credentials. Legacy fields are imported once, not silently rotated. */
final class PairingCredentials {
    private PairingCredentials(){}
    private static final List<String> KEYS=List.of("token","audit.guild-id","audit.server-id","audit.signing-key","control.signing-key","correlation.guild-key");
    private static Path path(Path dir){return dir.resolve("internal/credentials.yml");}
    static YamlConfiguration read(Path dir)throws Exception{
        Path file=path(dir);
        if(Files.isSymbolicLink(file.getParent()))throw new IOException("Invalid private credential directory");
        var root=CategoryConfig.readRoot(dir);
        if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS)){
            if(root.getBoolean("credentials-managed",false))throw new IOException("Private credentials missing; restore internal/credentials.yml from backup");
            return new YamlConfiguration();
        }
        var y=CategoryConfig.read(file);
        for(String key:KEYS)if(y.contains(key)&&!y.isString(key))throw new IOException("Invalid private credential field "+key);
        return y;
    }
    private static Path publicFile(Path dir,String key){return key.equals("token")?dir.resolve("config.yml"):dir.resolve("config/"+key.substring(0,key.indexOf('.'))+".yml");}
    static void migrate(Path dir)throws Exception{
        var secret=read(dir);
        var files=new LinkedHashMap<Path,YamlConfiguration>();
        boolean changed=false;
        for(String key:KEYS){
            Path file=publicFile(dir,key);var publicYaml=files.get(file);
            if(publicYaml==null){publicYaml=CategoryConfig.read(file);files.put(file,publicYaml);}
            if(publicYaml.contains(key)&&!publicYaml.isString(key))throw new IOException("Invalid legacy credential field "+key);
            String legacy=publicYaml.getString(key,""),current=secret.getString(key,"");
            if(!legacy.isEmpty()){
                if(!current.isEmpty()&&!current.equals(legacy))throw new IOException("Legacy credential conflicts with the private store; restore the matching backup");
                if(current.isEmpty()){secret.set(key,legacy);changed=true;}
            }
        }
        if(changed)save(dir,secret);
        if(Files.notExists(path(dir),LinkOption.NOFOLLOW_LINKS))return;
        for(var entry:files.entrySet()){
            var y=entry.getValue();boolean dirty=false;
            for(String key:KEYS)if(publicFile(dir,key).equals(entry.getKey())&&!y.getString(key,"").isEmpty()){y.set(key,"");dirty=true;}
            if(entry.getKey().equals(dir.resolve("config.yml"))&&!y.getBoolean("credentials-managed",false)){y.set("credentials-managed",true);dirty=true;}
            if(dirty){CategoryConfig.backup(entry.getKey(),"credentials");CategoryConfig.save(entry.getKey(),y);}
        }
    }
    static void save(Path dir,YamlConfiguration value)throws IOException{
        Path file=path(dir);
        if(Files.isSymbolicLink(file.getParent()))throw new IOException("Invalid private credential directory");
        CategoryConfig.save(file,value);
    }
    static void putToken(Path dir,String token)throws Exception{
        var y=read(dir);
        if(!y.getString("token","").isEmpty()&&!y.getString("token").equals(token))throw new IOException("Refusing to replace an existing token");
        y.set("token",token);save(dir,y);migrate(dir);
    }
    static void overlay(Path dir,YamlConfiguration target)throws Exception{
        var y=read(dir);
        for(String key:KEYS)if(y.contains(key))target.set(key,y.getString(key));
    }
    static void match(YamlConfiguration y,String key,String value)throws IOException{
        String old=y.getString(key,"");
        if(!old.isEmpty()&&!old.equals(value))throw new IOException("Existing pairing differs; automatic setup will not replace it");
    }
    static void verify(YamlConfiguration y,SetupRequest.Credentials c)throws IOException{
        match(y,"audit.guild-id",c.guild());match(y,"audit.server-id",c.server());match(y,"audit.signing-key",c.audit());match(y,"control.signing-key",c.control());match(y,"correlation.guild-key",c.network());
    }
    static void provision(Path dir,SetupRequest.Credentials c)throws Exception{
        var y=read(dir);verify(y,c);
        if(c.guild().equals(y.getString("audit.guild-id"))&&c.server().equals(y.getString("audit.server-id"))&&c.audit().equals(y.getString("audit.signing-key"))&&c.control().equals(y.getString("control.signing-key"))&&c.network().equals(y.getString("correlation.guild-key")))return;
        y.set("audit.guild-id",c.guild());y.set("audit.server-id",c.server());y.set("audit.signing-key",c.audit());y.set("control.signing-key",c.control());y.set("correlation.guild-key",c.network());save(dir,y);
    }
}
