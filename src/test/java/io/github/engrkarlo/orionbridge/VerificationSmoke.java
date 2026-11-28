package io.github.engrkarlo.orionbridge;
import java.util.UUID;
public final class VerificationSmoke {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        var state=new VerificationState();var player=UUID.randomUUID();String identity="java:"+"a".repeat(32),discord="111111111111111111";long now=1000000;
        check(state.restricted(player,now));
        var session=state.join(player,identity);var request=state.request(player,now);
        check(state.restricted(player,now));check(request.request()!=null);
        check(state.request(player,now+1000).request().equals(request.request()));
        check(state.offer(player,UUID.randomUUID(),identity,request.request(),"b".repeat(32),now+300000,now).equals("STALE"));
        check(state.offer(player,session.session(),identity,request.request(),"b".repeat(32),now+300000,now).equals("APPLIED"));
        check(!state.confirm(player,request.request(),discord,now));
        check(state.claim(player,session.session(),identity,request.request(),discord,"Example",now).equals("APPLIED"));
        check(!state.confirm(player,UUID.randomUUID(),discord,now));
        check(state.confirm(player,request.request(),discord,now));check(state.restricted(player,now));
        check(state.lease(player,session.session(),identity,discord,now,now+60000,now,60000).equals("APPLIED"));
        check(!state.restricted(player,now));check(state.restricted(player,now+60001));
        check(state.restrict(player,session.session(),identity,now+1,now+1).equals("APPLIED"));
        check(state.lease(player,session.session(),identity,discord,now,now+60000,now+1,60000).equals("STALE"));
        state.quit(player);state.join(player,identity);
        check(state.lease(player,session.session(),identity,discord,now+2,now+60002,now+2,60000).equals("STALE"));
        check(state.restricted(player,now+2));
        var first=state.join(player,identity,now);var req=state.request(player,now);
        check(state.offer(player,first.session(),identity,req.request(),"c".repeat(32),now+300000,now).equals("APPLIED"));
        check(state.claim(player,first.session(),identity,req.request(),discord,"Example",now).equals("APPLIED"));
        check(state.confirm(player,req.request(),discord,now));
        state.quit(player,now+1000);
        var authPending=state.join(player,null,now+2000);check(authPending.request()==null);
        var resumed=state.join(player,identity,now+3000);
        check(!resumed.session().equals(first.session()));check(resumed.request().equals(req.request()));
        check(resumed.code().equals("c".repeat(32)));check(resumed.codeUntil()==now+300000);
        check(!resumed.confirmed()&&resumed.claimant()==null&&state.restricted(player,now+3000));
        check(state.claim(player,first.session(),identity,req.request(),discord,"Example",now+3000).equals("STALE"));
        check(state.claim(player,resumed.session(),identity,req.request(),discord,"Example",now+3000).equals("APPLIED"));
        check(state.confirm(player,req.request(),discord,now+3000));
        state.quit(player,now+4000);
        check(state.join(player,identity,now+300000).request()==null);
        var pending=state.request(player,now+300001);state.quit(player,now+300002);
        check(state.join(player,"java:"+"b".repeat(32),now+300003).request()==null);
        check(state.join(player,identity,now+300004).request()==null);
    }
}
