package io.github.engrkarlo.orionbridge;
public final class StartupSummarySmoke {
    public static void main(String[] args){
        if(StartupSummary.status(true,false)!=StartupSummary.Status.UNAVAILABLE||
           StartupSummary.status(false,false)!=StartupSummary.Status.OFF||
           StartupSummary.status(true,true)!=StartupSummary.Status.ACTIVE||
           StartupSummary.clean("v1\n§c\u001b").contains("\n")||
           StartupSummary.clean("v1\n§c\u001b").contains("§")||
           StartupSummary.clean("x".repeat(200)).length()!=100)throw new AssertionError();
    }
}
