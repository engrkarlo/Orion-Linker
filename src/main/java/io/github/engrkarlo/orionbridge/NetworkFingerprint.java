package io.github.engrkarlo.orionbridge;

import java.net.InetAddress;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class NetworkFingerprint {
    private final byte[] key;
    private final String guild;
    NetworkFingerprint(String guild,String key){
        if(!guild.matches("[0-9]{17,20}")||!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid correlation key");
        this.guild=guild;this.key=HexFormat.of().parseHex(key);
    }
    String fingerprint(InetAddress address){
        if(address==null||address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return null;
        byte[] raw=address.getAddress();
        if(raw.length==16){
            boolean mapped=true;for(int i=0;i<10;i++)if(raw[i]!=0)mapped=false;
            if(mapped&&raw[10]==(byte)255&&raw[11]==(byte)255)raw=java.util.Arrays.copyOfRange(raw,12,16);
        }
        // Exclude shared carrier/proxy-local and private IPv6 addresses; real forwarding must be confirmed by operator.
        if(raw.length==4&&(raw[0]&255)==100&&(raw[1]&255)>=64&&(raw[1]&255)<=127||raw.length==16&&(raw[0]&254)==252)return null;
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            mac.update(("orion-network-v1\n"+guild+"\n").getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(mac.doFinal(raw));
        }catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}
    }
}
