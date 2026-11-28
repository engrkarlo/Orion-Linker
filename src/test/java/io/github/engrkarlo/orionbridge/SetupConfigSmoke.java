package io.github.engrkarlo.orionbridge;
import java.nio.file.*;
import org.bukkit.configuration.file.YamlConfiguration;
public final class SetupConfigSmoke {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static void put(Path p,String key,Object value)throws Exception{var y=Files.exists(p)?CategoryConfig.read(p):new YamlConfiguration();y.set(key,value);CategoryConfig.save(p,y);}
    static boolean bool(Path p,String key)throws Exception{return CategoryConfig.read(p).getBoolean(key);}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("orion-setup-test"),root=dir.resolve("config.yml"),v=dir.resolve("config/verification.yml"),chat=dir.resolve("config/chat.yml");
        try{
            put(root,"config-version",15);put(root,"mode","pull");put(root,"token","f".repeat(64));
            for(String category:CategoryConfig.CATEGORIES)put(dir.resolve("config/"+category+".yml"),category+".enabled",false);
            String g="123456789012345678",s="0123456789abcdef",actor="123456789012345679",a="a".repeat(64),b="b".repeat(64),c="c".repeat(64);
            java.util.function.Function<String,SetupRequest.Credentials> credentials=feature->new SetupRequest.Credentials(g,s,actor,a,b,c,feature);
            SetupConfig.provision(dir,credentials.apply("CREDENTIALS"));
            check(bool(dir.resolve("config/control.yml"),"control.enabled"));check(!bool(v,"verification.enabled"));
            SetupConfig.provision(dir,credentials.apply("CHAT"));check(bool(chat,"chat.enabled"));check(!bool(chat,"chat.require-link"));
            SetupConfig.provision(dir,credentials.apply("LINK"));check(bool(v,"verification.enabled"));check(!bool(v,"verification.force"));
            SetupConfig.provision(dir,credentials.apply("FORCE"));check(bool(v,"verification.force"));
            SetupConfig.provision(dir,credentials.apply("LINK"));check(bool(v,"verification.force"));
            SetupConfig.provision(dir,credentials.apply("CHAT"));check(bool(v,"verification.force"));
            try{SetupConfig.provision(dir,new SetupRequest.Credentials(g,s,actor,"d".repeat(64),b,c,"CREDENTIALS"));throw new AssertionError("replaced credentials");}catch(java.io.IOException expected){}
            check(CategoryConfig.read(dir.resolve("config/audit.yml")).getString("audit.signing-key").equals(a));
            check(!Files.exists(dir.resolve("setup-pending.yml")));
            SetupConfig.recover(dir);
        }finally{try(var files=Files.walk(dir)){for(Path p:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("SetupConfigSmoke passed");
    }
}
