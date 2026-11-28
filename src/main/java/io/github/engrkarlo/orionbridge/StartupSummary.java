package io.github.engrkarlo.orionbridge;

final class StartupSummary {
    enum Status {
        ACTIVE("ACTIVE"), OFF("OFF"), UNAVAILABLE("UNAVAILABLE");
        private final String label;
        Status(String label){this.label=label;}
        String label(){return label;}
    }
    static Status status(boolean requested,boolean available){
        return available?Status.ACTIVE:requested?Status.UNAVAILABLE:Status.OFF;
    }
    static String clean(String value){
        if(value==null)return "Unknown";
        String clean=value.replaceAll("[\\p{Cntrl}\\p{Cf}§]"," ");
        return clean.substring(0,Math.min(100,clean.length()));
    }
}
