package io.github.engrkarlo.orionbridge;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** One root connection file plus independent feature files.
 * The root version is the migration commit marker, written last. */
final class CategoryConfig {
    static final int VERSION=15;
    static final List<String> CATEGORIES=List.of("audit","control","verification","correlation","guard","roles","chat","recovery");
    static final List<String> MENUS=List.of("java-dialogs","java-inventory","bedrock-forms","dashboard","help");
    private CategoryConfig(){}
    static final class LayoutException extends IOException{LayoutException(String message){super(message);}}
    static YamlConfiguration resource(JavaPlugin plugin,String name)throws Exception{
        try(var input=plugin.getResource(name)){
            if(input==null)throw new IOException("Missing bundled resource "+name);
            var yaml=new YamlConfiguration();yaml.load(new InputStreamReader(input,StandardCharsets.UTF_8));return yaml;
        }
    }
    static YamlConfiguration read(Path file)throws Exception{
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new LayoutException("Missing or invalid configuration file: "+file.getFileName());
        var yaml=new YamlConfiguration();yaml.load(file.toFile());return yaml;
    }
    static YamlConfiguration readRoot(Path directory)throws Exception{
        var root=read(directory.resolve("config.yml"));
        if(!root.isInt("config-version")||root.getInt("config-version")!=VERSION)throw new LayoutException("Configuration layout requires restart/migration");
        for(String name:CATEGORIES)if(root.contains(name,true))throw new LayoutException("Move "+name+" settings to config/"+name+".yml; duplicate root category");
        return root;
    }
    static YamlConfiguration load(JavaPlugin plugin)throws Exception{
        Path directory=plugin.getDataFolder().toPath();
        var defaults=resource(plugin,"config.yml");
        Map<String,YamlConfiguration> categories=new LinkedHashMap<>();
        for(String name:CATEGORIES)categories.put(name,resource(plugin,"config/"+name+".yml"));
        boolean changed=migrate(directory,defaults,categories);
        if(changed)plugin.getLogger().info("Configuration v15: feature files are in config/. Existing values retained; previous root backed up.");
        PairingCredentials.migrate(directory);
        SetupConfig.recover(directory);
        var combined=load(directory,defaults,categories);
        PairingCredentials.overlay(directory,combined);
        if(Files.isSymbolicLink(directory.resolve("menus")))throw new LayoutException("Invalid menus directory");
        for(String name:MENUS)prepareMenu(directory,name,resource(plugin,"menus/"+name+".yml"));
        prepareTheme(directory.resolve("messages.yml"),"messages",resource(plugin,"messages.yml"));
        return combined;
    }
    static boolean migrate(Path directory,YamlConfiguration defaults,Map<String,YamlConfiguration> categories)throws Exception{
        Files.createDirectories(directory);
        Path rootFile=directory.resolve("config.yml"),featureDir=directory.resolve("config");
        if(Files.isSymbolicLink(featureDir)||Files.exists(featureDir)&&!Files.isDirectory(featureDir,LinkOption.NOFOLLOW_LINKS))throw new LayoutException("Invalid config directory");
        if(Files.notExists(rootFile,LinkOption.NOFOLLOW_LINKS)){
            // Establish a recoverable legacy marker before creating category files.
            // Never manufacture a new token if an existing root was lost.
            for(String name:CATEGORIES)if(Files.exists(featureDir.resolve(name+".yml"),LinkOption.NOFOLLOW_LINKS))throw new LayoutException("Missing config.yml; restore it from backup before starting");
            var fresh=new YamlConfiguration();merge(fresh,defaults,true);fresh.set("config-version",14);save(rootFile,fresh);
        }
        var legacy=read(rootFile);
        if(legacy.contains("config-version",true)&&!legacy.isInt("config-version"))throw new LayoutException("Invalid config version");
        int from=legacy.getInt("config-version",1);
        if(from>VERSION||from<1)throw new LayoutException("Unsupported config version");
        if(from==VERSION)return false;
        if(!legacy.contains("mode",true))legacy.set("mode",legacy.getString("endpoint","").isBlank()?"pull":"push");
        Files.createDirectories(featureDir);
        Map<String,YamlConfiguration> planned=new LinkedHashMap<>();
        // Validate every category before changing any file.
        for(String name:CATEGORIES){
            var template=Objects.requireNonNull(categories.get(name));
            var expected=new YamlConfiguration();
            if(legacy.contains(name,true)){
                var source=legacy.getConfigurationSection(name);
                if(source==null)throw new LayoutException("Invalid legacy category "+name);
                copy(source,expected.createSection(name));
            }
            merge(expected,template,true);
            Path file=featureDir.resolve(name+".yml");
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
                var existing=read(file);
                if(!same(existing,expected))throw new LayoutException("Migration conflict in config/"+name+".yml; retain both files and reconcile values");
            }else planned.put(name,expected);
        }
        backup(rootFile,"v"+from);
        for(var entry:planned.entrySet())save(featureDir.resolve(entry.getKey()+".yml"),entry.getValue());
        for(String name:CATEGORIES)legacy.set(name,null);
        merge(legacy,defaults,true);legacy.set("config-version",VERSION);
        save(rootFile,legacy);
        return true;
    }
    static YamlConfiguration load(Path directory,YamlConfiguration defaults,Map<String,YamlConfiguration> categories)throws Exception{
        var combined=readRoot(directory);merge(combined,defaults,false);
        for(String name:CATEGORIES){
            var file=read(directory.resolve("config").resolve(name+".yml"));
            if(!file.getKeys(false).equals(Set.of(name))||!file.isConfigurationSection(name))throw new LayoutException("Expected only the "+name+" section in config/"+name+".yml");
            merge(file,Objects.requireNonNull(categories.get(name)),false);
            copy(file.getConfigurationSection(name),combined.createSection(name));
        }
        return combined;
    }
    static void prepareMenu(Path directory,String name,YamlConfiguration defaults)throws Exception{
        prepareTheme(directory.resolve("menus").resolve(name+".yml"),name,defaults);
    }
    static void prepareTheme(Path file,String name,YamlConfiguration defaults)throws Exception{
        if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS)){save(file,defaults);return;}
        var current=read(file);
        if(current.contains("theme-version",true)&&!current.isInt("theme-version"))throw new LayoutException("Invalid theme version in "+name);
        int from=current.getInt("theme-version",1),to=defaults.getInt("theme-version",1);
        if(from>to)throw new LayoutException("Theme from a newer release: "+name);
        if(from==to)return;
        merge(current,defaults,true);current.set("theme-version",to);
        backup(file,"v"+from);save(file,current);
    }
    static boolean same(ConfigurationSection a,ConfigurationSection b){
        if(!a.getKeys(false).equals(b.getKeys(false)))return false;
        for(String key:a.getKeys(false)){
            if(a.isConfigurationSection(key)||b.isConfigurationSection(key)){
                if(!a.isConfigurationSection(key)||!b.isConfigurationSection(key)||!same(a.getConfigurationSection(key),b.getConfigurationSection(key)))return false;
            }else if(!Objects.equals(a.get(key),b.get(key)))return false;
        }
        return true;
    }
    private static void copy(ConfigurationSection from,ConfigurationSection to){
        for(String key:from.getKeys(false)){
            if(from.isConfigurationSection(key))copy(from.getConfigurationSection(key),to.createSection(key));else to.set(key,from.get(key));
            to.setComments(key,from.getComments(key));to.setInlineComments(key,from.getInlineComments(key));
        }
    }
    static void merge(ConfigurationSection current,ConfigurationSection defaults,boolean comments)throws IOException{
        for(String key:defaults.getKeys(false)){
            if(defaults.isConfigurationSection(key)){
                boolean existed=current.contains(key,true);
                if(!existed)current.createSection(key);
                var child=current.getConfigurationSection(key);
                if(child==null)throw new IOException("Expected configuration section "+key);
                if(key.equals("profiles")&&"guard".equals(current.getCurrentPath())&&existed){
                    var template=defaults.getConfigurationSection(key);
                    for(String profile:child.getKeys(false))if(child.isConfigurationSection(profile)&&template.isConfigurationSection(profile))merge(child.getConfigurationSection(profile),template.getConfigurationSection(profile),comments);
                }else merge(child,defaults.getConfigurationSection(key),comments);
            }else if(!current.contains(key,true))current.set(key,defaults.get(key));
            else if(current.isConfigurationSection(key))throw new LayoutException("Expected a value, not a section: "+current.getCurrentPath()+"."+key);
            else validateType(current,defaults,key);
            if(comments&&!defaults.getComments(key).isEmpty()){
                var notes=new ArrayList<>(defaults.getComments(key));for(String note:current.getComments(key))if(!notes.contains(note))notes.add(note);current.setComments(key,notes);
            }
        }
        if(comments&&current instanceof YamlConfiguration yaml&&defaults instanceof YamlConfiguration template){
            var header=new ArrayList<>(template.options().getHeader());for(String note:yaml.options().getHeader())if(!header.contains(note))header.add(note);yaml.options().setHeader(header);
        }
    }
    private static void validateType(ConfigurationSection current,ConfigurationSection defaults,String key)throws LayoutException{
        Object value=defaults.get(key);
        boolean valid=value instanceof Boolean?current.isBoolean(key):value instanceof Integer?current.isInt(key):value instanceof String?current.isString(key):value instanceof List<?>?current.isList(key):true;
        if(!valid)throw new LayoutException("Invalid value type: "+current.getCurrentPath()+"."+key);
    }
    static void backup(Path file,String version)throws IOException{
        Path backup=Files.createTempFile(file.getParent(),file.getFileName()+"."+version+".",".bak");
        Files.copy(file,backup,StandardCopyOption.REPLACE_EXISTING);
        privatePermissions(backup);
    }
    static void save(Path file,YamlConfiguration yaml)throws IOException{
        Files.createDirectories(file.getParent());
        if(Files.isSymbolicLink(file))throw new IOException("Refusing symlink configuration file "+file.getFileName());
        Path temp=Files.createTempFile(file.getParent(),file.getFileName()+".",".tmp");
        try{
            privatePermissions(temp);yaml.save(temp.toFile());
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
            var check=new YamlConfiguration();try{check.load(temp.toFile());}catch(Exception ex){throw new IOException("Cannot validate configuration output",ex);}
            if(!same(yaml,check))throw new IOException("Configuration output differs from source: "+file.getFileName());
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
    }
    private static void privatePermissions(Path path)throws IOException{
        if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
    }
}
