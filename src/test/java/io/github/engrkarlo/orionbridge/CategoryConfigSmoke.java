package io.github.engrkarlo.orionbridge;

import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class CategoryConfigSmoke {
    static void check(boolean yes){if(!yes)throw new AssertionError();}
    static YamlConfiguration resource(String name)throws Exception{
        try(var in=CategoryConfigSmoke.class.getClassLoader().getResourceAsStream(name)){
            if(in==null)throw new IOException("Missing "+name);
            var y=new YamlConfiguration();y.load(new InputStreamReader(in,StandardCharsets.UTF_8));return y;
        }
    }
    static void remove(Path dir)throws Exception{try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    public static void main(String[] args)throws Exception{
        var defaults=resource("config.yml");var categories=new LinkedHashMap<String,YamlConfiguration>();
        for(String name:CategoryConfig.CATEGORIES)categories.put(name,resource("config/"+name+".yml"));
        Path dir=Files.createTempDirectory("orion-categories"),fresh=Files.createTempDirectory("orion-fresh"),conflict=Files.createTempDirectory("orion-conflict"),retry=Files.createTempDirectory("orion-retry");
        try{
            var legacy=new YamlConfiguration();legacy.set("config-version",14);legacy.set("mode","pull");legacy.set("token","a".repeat(64));
            legacy.set("port",30001);legacy.set("custom", "keep");legacy.set("chat.enabled",true);legacy.set("chat.discord-to-minecraft",false);
            legacy.set("audit.signing-key","b".repeat(64));legacy.set("control.signing-key","c".repeat(64));
            legacy.set("verification.enabled",true);legacy.set("guard.profiles.special.blocks-per-minute",1234);
            legacy.set("roles.allowed-groups",List.of("vip"));legacy.save(dir.resolve("config.yml").toFile());
            String original=Files.readString(dir.resolve("config.yml"));
            check(CategoryConfig.migrate(dir,defaults,categories));
            var root=CategoryConfig.readRoot(dir);check(root.getInt("config-version")==15);check(root.getString("token").equals("a".repeat(64)));check(root.getInt("port")==30001);check(root.getString("custom").equals("keep"));
            for(String name:CategoryConfig.CATEGORIES)check(!root.contains(name,true));
            var all=CategoryConfig.load(dir,defaults,categories);
            check(all.getBoolean("chat.enabled"));check(!all.getBoolean("chat.discord-to-minecraft"));check(all.getBoolean("chat.minecraft-to-discord"));
            check(all.getString("audit.signing-key").equals("b".repeat(64)));check(all.getString("control.signing-key").equals("c".repeat(64)));
            check(all.getInt("guard.profiles.special.blocks-per-minute")==1234);check(!all.contains("guard.profiles.administrator"));
            check(all.getStringList("roles.allowed-groups").equals(List.of("vip")));
            check(Files.readString(dir.resolve("config/chat.yml")).contains("Cross-platform chat"));
            try(var files=Files.list(dir)){check(Files.readString(files.filter(p->p.getFileName().toString().endsWith(".bak")).findFirst().orElseThrow()).equals(original));}
            check(!CategoryConfig.migrate(dir,defaults,categories));
            Files.delete(dir.resolve("config/chat.yml"));
            try{CategoryConfig.load(dir,defaults,categories);throw new AssertionError("Missing category accepted");}catch(IOException expected){}
            check(CategoryConfig.migrate(fresh,defaults,categories));var clean=CategoryConfig.load(fresh,defaults,categories);check(!clean.getBoolean("chat.enabled"));check(clean.isConfigurationSection("guard.profiles.administrator.worldedit-scope"));check(clean.isList("guard.profiles.administrator.worldedit-scope.commands"));
            Files.createDirectories(fresh.resolve("menus"));Files.writeString(fresh.resolve("menus/dashboard.yml"),"theme-version: 1\nlabels:\n  back: My custom back\n");
            CategoryConfig.prepareMenu(fresh,"dashboard",resource("menus/dashboard.yml"));
            var menu=CategoryConfig.read(fresh.resolve("menus/dashboard.yml"));check(menu.getInt("theme-version")==2);check(menu.getString("labels.back").equals("My custom back"));check(menu.contains("bodies.account"));
            // Interrupted migration: an identical category can be reused.
            Files.writeString(retry.resolve("config.yml"),original);Files.createDirectories(retry.resolve("config"));
            Files.copy(dir.resolve("config/audit.yml"),retry.resolve("config/audit.yml"));
            check(CategoryConfig.migrate(retry,defaults,categories));
            // Conflict must not rewrite either original or pre-existing category.
            Files.writeString(conflict.resolve("config.yml"),original);Files.createDirectories(conflict.resolve("config"));
            String different="chat:\n  enabled: false\n";Files.writeString(conflict.resolve("config/chat.yml"),different);
            try{CategoryConfig.migrate(conflict,defaults,categories);throw new AssertionError("Conflict accepted");}catch(IOException expected){}
            check(Files.readString(conflict.resolve("config.yml")).equals(original));check(Files.readString(conflict.resolve("config/chat.yml")).equals(different));
        }finally{remove(dir);remove(fresh);remove(conflict);remove(retry);}
    }
}
