package io.github.engrkarlo.orionbridge;

import java.util.UUID;

public final class ReloadSafetySmoke {
    private static void check(boolean condition){if(!condition)throw new AssertionError();}
    private static void replayAcrossReload(){
        var ledger=new AuditAuth.NonceLedger(4096);
        String guild="1".repeat(18),server="a".repeat(16),key="b".repeat(64),nonce="c".repeat(32),time="100000";
        byte[] payload=new byte[0];String sig=AuditAuth.signature(java.util.HexFormat.of().parseHex(key),"GET","/v2/health",guild,server,time,nonce,payload);
        check(new AuditAuth(guild,server,key,ledger).verify("GET","/v2/health",guild,server,time,nonce,sig,payload,100000));
        check(!new AuditAuth(guild,server,key,ledger).verify("GET","/v2/health",guild,server,time,nonce,sig,payload,100001));
    }
    public static void main(String[] args){
        replayAcrossReload();
        var admission=new RequestAdmission();check(admission.enter());check(!admission.quiesce());admission.leave();
        check(admission.quiesce());check(!admission.enter());admission.resume();check(admission.enter());admission.leave();
        long now=1000000;UUID player=UUID.randomUUID(),linked=UUID.randomUUID();
        String identity="java:"+player.toString().replace("-",""),discord="1".repeat(18),code="a".repeat(32);
        var state=new VerificationState();var original=state.join(player,identity,now);var request=state.request(player,now);
        check("APPLIED".equals(state.offer(player,original.session(),identity,request.request(),code,now+300000,now)));
        check("APPLIED".equals(state.claim(player,original.session(),identity,request.request(),discord,"Tester",now)));
        check(state.confirm(player,request.request(),discord,now));
        var oldLinked=state.join(linked,"java:"+linked.toString().replace("-",""),now);
        check("APPLIED".equals(state.lease(linked,oldLinked.session(),oldLinked.identity(),discord,now,now+60000,now,60000)));
        var copy=state.forReload(now+1000);state.clear();
        var resumed=copy.join(player,identity,now+1000);check(request.request().equals(resumed.request()));check(code.equals(resumed.code()));check(resumed.codeUntil()==now+300000);check(resumed.claimant()==null&&!resumed.confirmed());check(!resumed.session().equals(original.session()));
        var relink=copy.join(linked,oldLinked.identity(),now+1000);check(!relink.verified(now+1000));check(!relink.session().equals(oldLinked.session()));
        var expired=copy.forReload(now+300001).join(player,identity,now+300001);check(expired.request()==null);
    }
}
