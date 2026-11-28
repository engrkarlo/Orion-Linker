package io.github.engrkarlo.orionbridge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived online sessions; all mutations serialized by the caller on the main thread. */
final class VerificationState {
    record Session(UUID player,UUID session,String identity,UUID request,String code,long codeUntil,String claimant,String display,boolean confirmed,String linked,long leaseUntil,long leaseIssued,long requestedAt,boolean regenerate){
        boolean verified(long now){return identity!=null&&linked!=null&&leaseUntil>now;}
        boolean hasCode(long now){return code!=null&&codeUntil>now;}
    }
    private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
    private static final int MAX_DETACHED=4096;
    // Only the challenge survives a client disconnect. Never carry over a lease,
    // trusted profile, confirmation click or online session identifier.
    private final LinkedHashMap<UUID,Session> detached=new LinkedHashMap<>();
    private boolean resumable(Session s,long now){
        return s!=null&&s.identity()!=null&&s.request()!=null&&
            (s.code()!=null?s.codeUntil()>now:s.requestedAt()>0&&now-s.requestedAt()<300000);
    }
    void pruneDetached(long now){detached.values().removeIf(s->!resumable(s,now));}
    Session join(UUID player,String identity){return join(player,identity,System.currentTimeMillis());}
    Session join(UUID player,String identity,long now){
        Session s=new Session(player,UUID.randomUUID(),identity,null,null,0,null,null,false,null,0,0,0,false);
        if(identity!=null){
            Session old=detached.remove(player);
            if(resumable(old,now)&&identity.equals(old.identity()))
                s=new Session(player,s.session(),identity,old.request(),old.code(),old.codeUntil(),null,null,false,null,0,0,old.requestedAt(),old.regenerate());
        }
        sessions.put(player,s);return s;
    }
    Session get(UUID player){return sessions.get(player);}
    void quit(UUID player){quit(player,System.currentTimeMillis());}
    void quit(UUID player,long now){
        Session s=sessions.remove(player);
        if(!resumable(s,now))return;
        if(detached.size()>=MAX_DETACHED){pruneDetached(now);if(detached.size()>=MAX_DETACHED)detached.remove(detached.keySet().iterator().next());}
        detached.put(player,s);
    }
    // Transfer only unexpired challenges. New sessions require fresh signed
    // authorization; old clicks, confirmations and privilege leases cannot survive.
    VerificationState forReload(long now){
        var next=new VerificationState();
        for(var s:detached.values())if(resumable(s,now)&&next.detached.size()<MAX_DETACHED)next.detached.put(s.player(),s);
        for(var s:sessions.values())if(resumable(s,now)){
            if(next.detached.size()>=MAX_DETACHED)next.detached.remove(next.detached.keySet().iterator().next());
            next.detached.put(s.player(),s);
        }
        return next;
    }
    void clear(){sessions.clear();detached.clear();}
    List<Session> snapshot(){return List.copyOf(sessions.values());}
    boolean restricted(UUID player,long now){Session s=get(player);return s==null||!s.verified(now);}
    Session request(UUID player,long now){
        Session s=get(player);
        if(s==null||s.identity()==null||s.verified(now)||s.requestedAt()!=0&&now-s.requestedAt()<10000)return s;
        var next=new Session(player,s.session(),s.identity(),UUID.randomUUID(),null,0,null,null,false,null,0,s.leaseIssued(),now,s.request()!=null);
        sessions.put(player,next);return next;
    }
    Session expireChallenge(UUID player,long now){
        Session s=get(player);
        if(s==null||s.request()==null||s.hasCode(now)||s.code()==null&&now-s.requestedAt()<300000)return s;
        var next=new Session(player,s.session(),s.identity(),null,null,0,null,null,false,s.linked(),s.leaseUntil(),s.leaseIssued(),0,false);
        sessions.put(player,next);return next;
    }
    private Session match(UUID player,UUID session,String identity){
        Session s=get(player);return s!=null&&s.session().equals(session)&&Objects.equals(s.identity(),identity)&&identity!=null?s:null;
    }
    String offer(UUID player,UUID session,String identity,UUID request,String code,long until,long now){
        Session s=match(player,session,identity);
        if(s==null||!Objects.equals(s.request(),request)||s.verified(now)||!code.matches("[a-f0-9]{32}")||until<=now||until>now+300000)return "STALE";
        if(s.code()!=null)return s.code().equals(code)&&s.codeUntil()==until?"APPLIED":"CONFLICT";
        sessions.put(player,new Session(player,session,identity,request,code,until,null,null,false,null,0,s.leaseIssued(),s.requestedAt(),s.regenerate()));
        return "APPLIED";
    }
    String resume(UUID player,UUID session,String identity,UUID expected,UUID request,String code,long until,long now){
        Session s=match(player,session,identity);
        if(s==null||!Objects.equals(s.request(),expected)||s.regenerate()||s.hasCode(now)||s.verified(now)||s.linked()!=null||!code.matches("[a-f0-9]{32}")||until<=now||until>now+300000)return "STALE";
        sessions.put(player,new Session(player,session,identity,request,code,until,null,null,false,null,0,s.leaseIssued(),now,false));
        return "APPLIED";
    }
    String claim(UUID player,UUID session,String identity,UUID request,String discord,String display,long now){
        Session s=match(player,session,identity);
        if(s==null||!Objects.equals(s.request(),request)||!s.hasCode(now)||!discord.matches("[0-9]{17,20}")||display.isBlank()||display.length()>100||display.codePoints().anyMatch(Character::isISOControl))return "STALE";
        if(s.claimant()!=null)return s.claimant().equals(discord)?"APPLIED":"CONFLICT";
        sessions.put(player,new Session(player,session,identity,request,s.code(),s.codeUntil(),discord,display,false,null,0,s.leaseIssued(),s.requestedAt(),s.regenerate()));
        return "APPLIED";
    }
    boolean confirm(UUID player,UUID request,String discord,long now){
        Session s=get(player);
        if(s==null||!Objects.equals(s.request(),request)||discord==null||!discord.equals(s.claimant())||!s.hasCode(now))return false;
        sessions.put(player,new Session(player,s.session(),s.identity(),request,s.code(),s.codeUntil(),discord,s.display(),true,null,0,s.leaseIssued(),s.requestedAt(),s.regenerate()));
        return true;
    }
    String lease(UUID player,UUID session,String identity,String discord,long issued,long until,long now,long maxLease){
        Session s=match(player,session,identity);
        if(s==null||!discord.matches("[0-9]{17,20}")||issued<=s.leaseIssued()||issued>now+5000||issued<now-60000||until<=now||until>issued+maxLease)return "STALE";
        sessions.put(player,new Session(player,session,identity,null,null,0,null,null,false,discord,until,issued,0,false));
        return "APPLIED";
    }
    String restrict(UUID player,UUID session,String identity,long issued,long now){
        Session s=match(player,session,identity);
        if(s==null||issued<=s.leaseIssued()||issued>now+5000||issued<now-60000)return "STALE";
        sessions.put(player,new Session(player,session,identity,null,null,0,null,null,false,null,0,issued,0,false));
        return "APPLIED";
    }
}
