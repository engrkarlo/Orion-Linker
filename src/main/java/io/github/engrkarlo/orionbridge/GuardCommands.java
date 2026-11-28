package io.github.engrkarlo.orionbridge;

import java.util.Set;

/** Command-level policy for named, resolved plugin commands. This is not an
 * interception guarantee for direct Java API or console operations. */
final class GuardCommands {
    private static final Set<String> ADMIN=Set.of("op","deop","lp","luckperms","permissions","stop","restart","reload","plugman","plugmanx","execute","sudo","give","gamemode","ban","pardon","kill","summon","fill","clone","setblock","place","worldedit","fawe","we");
    private static final Set<String> ESSENTIALS=Set.of("sudo","give","item","i","gamemode","gmc","gms","gma","gmsp","ban","tempban","banip","unban","unbanip","kick","kickall","mute","jail","togglejail","tjail","invsee","enderchest","clearinventory","clearinventoryconfirm","ci","kill","killall","spawnmob","setspawn","setwarp","delwarp","tp","tpo","tpohere","tphere","tpall","tppos","tpaall","tptoggle","god","vanish","enchant","repair","eco","economy","setworth","essentials","reloadall");
    private static final Set<String> MODERATION=Set.of("ban","tempban","ipban","banip","unban","unbanip","mute","tempmute","unmute","warn","tempwarn","unwarn","kick","kickall","punish","unpunish","advancedban","litebans","ab","lb","banlist");
    private static final Set<String> REGIONS=Set.of("region","rg","worldguard","wg","flag","define","redefine","remove","delete","addowner","removeowner","addmember","removemember");
    private static final Set<String> COREPROTECT=Set.of("coreprotect","co","rollback","restore","purge","reload","consumer","lookup");
    private static final Set<String> DIRECT_EDIT=Set.of("butcher","remove","killall","regen","regenerate","restore","snapshot","restorechunk","delchunks","deletechunks","chunk","chunkinfo","listchunks","script","cs","craftscript","execute","sudo","reload","worldedit","we","fawe","schematic","schem");
    static boolean sensitive(String owner,String name,String raw){
        if(ADMIN.contains(name)||ADMIN.contains(raw))return true;
        return switch(owner){
            case "essentials","essentialsx","essentialsxspawn" -> ESSENTIALS.contains(name)||ESSENTIALS.contains(raw);
            case "advancedban","litebans" -> MODERATION.contains(name)||MODERATION.contains(raw);
            case "worldguard" -> REGIONS.contains(name)||REGIONS.contains(raw);
            case "coreprotect" -> COREPROTECT.contains(name)||COREPROTECT.contains(raw);
            case "luckperms","plugman","plugmanx" -> true;
            default -> false;
        };
    }
    static boolean directEdit(String name){return DIRECT_EDIT.contains(name);}
    static boolean taskCommand(EditScope scope,String owner,String name){
        return !directEdit(name)&&!scope.commands().isEmpty()&&scope.commands().contains(owner+":"+name);
    }
}
