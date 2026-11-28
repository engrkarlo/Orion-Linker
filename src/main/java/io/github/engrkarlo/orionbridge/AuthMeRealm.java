package io.github.engrkarlo.orionbridge;

/** Keep explicitly configured identity namespaces; never share a default across servers. */
final class AuthMeRealm {
    private AuthMeRealm(){}
    static String forSetup(String existing,String server,boolean offline,boolean authme){
        if(existing==null)existing="";
        if(!existing.isEmpty()){
            if(!existing.matches("[a-z0-9_-]{1,32}"))throw new IllegalArgumentException("Invalid AuthMe realm");
            return existing;
        }
        if(!offline||!authme)return "";
        if(!server.matches("[a-f0-9]{16}"))throw new IllegalArgumentException("Invalid server ID");
        return "server-"+server;
    }
}
