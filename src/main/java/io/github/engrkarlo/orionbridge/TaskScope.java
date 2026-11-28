package io.github.engrkarlo.orionbridge;

import java.nio.charset.StandardCharsets;
import java.util.*;

record TaskScope(UUID id,String world,EditScope.Operation operation,int budget,long expires){
    static TaskScope parse(String raw,long now){
        String[] f=raw.split("\\|",-1);if(f.length!=5)throw new IllegalArgumentException("Invalid task");
        UUID id=UUID.fromString(f[0]);String world=new String(Base64.getUrlDecoder().decode(f[1]),StandardCharsets.UTF_8);
        int budget=Integer.parseInt(f[3]);long expires=Long.parseLong(f[4]);
        if(!id.toString().equals(f[0])||world.isBlank()||world.length()>100||world.codePoints().anyMatch(Character::isISOControl)||budget<1||budget>2000000||expires<=now||expires>now+3605000L)throw new IllegalArgumentException("Invalid task scope");
        return new TaskScope(id,world,EditScope.Operation.valueOf(f[2]),budget,expires);
    }
    boolean permits(String w,EditScope.Operation op,long now){return expires>now&&world.equals(w)&&operation==op;}
}
