package io.github.engrkarlo.orionbridge;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

public class RetryGateSmoke {
    private static long seconds(long value){return TimeUnit.SECONDS.toNanos(value);}
    private static void check(boolean condition){if(!condition)throw new AssertionError();}
    public static void main(String[] args){
        RetryGate gate=new RetryGate();
        long base=-seconds(20);
        check(gate.ready(base));
        gate.failure(base,0);
        check(!gate.ready(base+seconds(1)));
        check(gate.ready(base+seconds(2)));
        gate.failure(base+seconds(2),120);
        check(!gate.ready(base+seconds(121)));
        check(gate.ready(base+seconds(122)));
        gate.success();
        check(gate.ready(base));
        check(RetryGate.retryAfterSeconds(" 30 ",Instant.EPOCH)==30);
        check(RetryGate.retryAfterSeconds("Thu, 1 Jan 1970 00:01:00 GMT",Instant.EPOCH)==60);
        check(RetryGate.retryAfterSeconds("garbage",Instant.EPOCH)==0);
    }
}
