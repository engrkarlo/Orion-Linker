package io.github.engrkarlo.orionbridge;

import java.io.IOException;
import java.nio.file.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** A private journal commits the approved provisioning intent before touching any category.
 * Recovery is idempotent; existing non-empty keys and scopes can never be replaced. */
final class SetupConfig {
    private SetupConfig(){}
    private static Path journal(Path dir){return dir.resolve("setup-pending.yml");}
    private static void match(YamlConfiguration config,String key,String value)throws IOException{
        if(config.contains(key)&&!config.isString(key))throw new IOException("Invalid credential field "+key);
        String old=config.getString(key,"");
        if(!old.isEmpty()&&!old.equals(value))throw new IOException("Existing pairing differs; automatic setup will not replace it");
    }
    private record Plan(YamlConfiguration audit,YamlConfiguration control,YamlConfiguration correlation){}
    private static Plan plan(Path dir,SetupRequest.Credentials c)throws Exception{
        var a=CategoryConfig.read(dir.resolve("config/audit.yml"));
        var b=CategoryConfig.read(dir.resolve("config/control.yml"));
        var n=CategoryConfig.read(dir.resolve("config/correlation.yml"));
        match(a,"audit.guild-id",c.guild());match(a,"audit.server-id",c.server());match(a,"audit.signing-key",c.audit());
        match(b,"control.signing-key",c.control());match(n,"correlation.guild-key",c.network());
        return new Plan(a,b,n);
    }
    static void provision(Path dir,SetupRequest.Credentials c)throws Exception{
        if(Files.exists(journal(dir),LinkOption.NOFOLLOW_LINKS))throw new IOException("An approved setup is already pending; reload to recover it");
        var root=CategoryConfig.readRoot(dir);
        if(!"pull".equals(root.getString("mode"))||!c.distinct(root.getString("token","")))throw new IOException("Setup requires Pull and distinct credentials");
        var p=plan(dir,c);
        var j=new YamlConfiguration();
        j.set("guild",c.guild());j.set("server",c.server());j.set("actor",c.actor());j.set("audit",c.audit());j.set("control",c.control());j.set("network",c.network());
        // Only new services are automatically activated. Existing settings remain exactly as chosen.
        j.set("enable-audit",p.audit().getString("audit.signing-key","").isEmpty());
        j.set("enable-control",p.control().getString("control.signing-key","").isEmpty());
        CategoryConfig.save(journal(dir),j);
        recover(dir);
    }
    static void recover(Path dir)throws Exception{
        if(Files.notExists(journal(dir),LinkOption.NOFOLLOW_LINKS))return;
        var j=CategoryConfig.read(journal(dir));
        var c=new SetupRequest.Credentials(j.getString("guild",""),j.getString("server",""),j.getString("actor",""),j.getString("audit",""),j.getString("control",""),j.getString("network",""));
        var root=CategoryConfig.readRoot(dir);
        if(!"pull".equals(root.getString("mode"))||!c.distinct(root.getString("token","")))throw new IOException("Pending setup no longer matches connection");
        if(!j.isBoolean("enable-audit")||!j.isBoolean("enable-control"))throw new IOException("Invalid setup journal");
        var p=plan(dir,c);
        p.audit().set("audit.guild-id",c.guild());p.audit().set("audit.server-id",c.server());p.audit().set("audit.signing-key",c.audit());
        p.control().set("control.signing-key",c.control());p.correlation().set("correlation.guild-key",c.network());
        if(j.getBoolean("enable-audit"))p.audit().set("audit.enabled",true);
        if(j.getBoolean("enable-control"))p.control().set("control.enabled",true);
        // Never enable correlation, verification, Guard or any enforcement here.
        for(var e:java.util.Map.of("audit",p.audit(),"control",p.control(),"correlation",p.correlation()).entrySet()){
            Path file=dir.resolve("config/"+e.getKey()+".yml");
            if(!CategoryConfig.same(CategoryConfig.read(file),e.getValue())){
                CategoryConfig.backup(file,"setup");CategoryConfig.save(file,e.getValue());
            }
        }
        Files.delete(journal(dir));
    }
}
