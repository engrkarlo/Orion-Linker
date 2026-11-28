package io.github.engrkarlo.orionbridge;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/** Monotonic retry window; one telemetry worker writes, the server thread reads. */
final class RetryGate {
    private record Window(long start,long nanos){}
    private volatile Window window;
    private int failures;
    boolean ready(long now){
        Window w=window;
        return w==null||now-w.start()>=w.nanos();
    }
    void success(){failures=0;window=null;}
    void failure(long now,long retryAfter){
        failures=Math.min(failures+1,7);
        long seconds=Math.max(Math.min(60,1L<<failures),Math.max(0,Math.min(86400,retryAfter)));
        window=new Window(now,TimeUnit.SECONDS.toNanos(seconds));
    }
    static long retryAfterSeconds(String value,Instant now){
        try{return Math.max(0,Long.parseLong(value.trim()));}
        catch(NumberFormatException ignored){}
        try{
            Duration remaining=Duration.between(now,ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return remaining.isNegative()?0:remaining.getSeconds()+(remaining.getNano()>0?1:0);
        }catch(RuntimeException ignored){return 0;}
    }
}
