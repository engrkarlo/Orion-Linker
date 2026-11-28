package io.github.engrkarlo.orionbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import java.util.*;

final class TextTheme {
    private final YamlConfiguration config;
    private static final MiniMessage MM=MiniMessage.miniMessage();
    TextTheme(YamlConfiguration config){this.config=Objects.requireNonNull(config);}
    static TextTheme empty(){return new TextTheme(new YamlConfiguration());}
    String raw(String key){return config.getString(key,key);}
    boolean flag(String key,boolean fallback){return config.getBoolean(key,fallback);}
    Component render(String key,String... args){return format(raw(key),args);}
    static Component format(String template,String... args){
        var tags=new ArrayList<TagResolver>();
        for(int i=0;i+1<args.length;i+=2)tags.add(Placeholder.unparsed(args[i],Objects.toString(args[i+1],"")));
        try{return MM.deserialize(template,tags.toArray(TagResolver[]::new));}
        catch(RuntimeException invalid){return Component.text(template);}
    }
    void send(CommandSender sender,String key,String... args){sender.sendMessage(render(key,args));}
}
