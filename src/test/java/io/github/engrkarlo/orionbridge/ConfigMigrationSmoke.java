package io.github.engrkarlo.orionbridge;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;
public final class ConfigMigrationSmoke {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("orion-config");
        try{
            Path file=dir.resolve("config.yml");String original="endpoint: 'https://bridge.example.com/v1/snapshot/111111111111111111/0123456789abcdef'\ntoken: '"+"a".repeat(64)+"'\nhide-vanished: false\ncustom: keep\n";
            Files.writeString(file,original);var defaults=new YamlConfiguration();
            defaults.loadFromString("config-version: 2\nmode: pull\ntoken: ''\nhide-vanished: true\naudit:\n  enabled: false\n");
            check(ConfigMigration.migrate(file,defaults));var result=YamlConfiguration.loadConfiguration(file.toFile());
            check(result.getString("mode").equals("push"));check(result.getString("token").equals("a".repeat(64)));check(!result.getBoolean("hide-vanished"));check(result.getString("custom").equals("keep"));check(!result.getBoolean("audit.enabled"));
            try(var files=Files.list(dir)){check(Files.readString(files.filter(x->x.toString().endsWith(".bak")).findFirst().orElseThrow()).equals(original));}
            check(!ConfigMigration.migrate(file,defaults));
        }finally{try(var files=Files.walk(dir)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
