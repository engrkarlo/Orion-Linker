package io.github.engrkarlo.orionbridge;
public final class AuthMeRealmSmoke {
    public static void main(String[] args){
        String a="0123456789abcdef",b="fedcba9876543210";
        check(AuthMeRealm.forSetup("",a,true,true).equals("server-"+a));
        check(!AuthMeRealm.forSetup("",a,true,true).equals(AuthMeRealm.forSetup("",b,true,true)));
        check(AuthMeRealm.forSetup("shared-authme",a,true,true).equals("shared-authme"));
        check(AuthMeRealm.forSetup("",a,false,true).isEmpty());
        check(AuthMeRealm.forSetup("",a,true,false).isEmpty());
        try{AuthMeRealm.forSetup("bad realm",a,true,true);throw new AssertionError();}catch(IllegalArgumentException expected){}
        System.out.println("AuthMeRealmSmoke passed");
    }
    static void check(boolean value){if(!value)throw new AssertionError();}
}
