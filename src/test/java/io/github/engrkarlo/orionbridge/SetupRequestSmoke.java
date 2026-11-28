package io.github.engrkarlo.orionbridge;
final class SetupRequestSmoke {
    public static void main(String[] args){
        var s=new SetupRequest();long now=1000;String a="a".repeat(64),b="b".repeat(64),c="c".repeat(64),token="d".repeat(64);
        String code=s.open(now),suffix="\n123456789012345678\n0123456789abcdef\n123456789012345679\n"+a+"\n"+b+"\n"+c;
        check(s.stage("f".repeat(32)+suffix,token,now)==null);
        check(s.stage(code+suffix,a,now)==null);
        var p=s.stage(code+suffix,token,now);check(p!=null);
        check(s.stage(code+suffix,token,now)==null);
        check(s.consume("bad",now)==null);
        check(s.consume(p.id(),now)!=null);check(s.consume(p.id(),now)==null);
        code=s.open(now);check(s.stage(code+suffix,token,now+300000)==null);
        code=s.open(now);check(s.stage(code+suffix+"\nUNKNOWN",token,now)==null);var feature=s.stage(code+suffix+"\nCHAT",token,now);check(feature!=null&&feature.credentials().feature().equals("CHAT"));s.cancel();
        code=s.open(now);s.cancel();check(s.stage(code+suffix,token,now)==null);
        code=s.open(now);String claim="9".repeat(64);
        var bootstrap=s.stage(code+suffix+"\nCREDENTIALS\n"+claim,token,now,true);
        check(bootstrap!=null);check(!s.claim(bootstrap.id(),claim,token,now));
        check(s.consume(bootstrap.id(),now)!=null);s.approve(bootstrap,token,now);
        check(!s.claim(bootstrap.id(),token,token,now));check(s.claim(bootstrap.id(),claim,token,now));check(!s.claim(bootstrap.id(),claim,a,now));check(!s.claim(bootstrap.id(),claim,token,now+300000));
        System.out.println("SetupRequestSmoke passed");
    }
    static void check(boolean ok){if(!ok)throw new AssertionError();}
}
