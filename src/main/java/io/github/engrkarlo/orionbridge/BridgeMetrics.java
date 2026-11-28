package io.github.engrkarlo.orionbridge;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

final class BridgeMetrics {
    private static final class Sample {
        final LongAdder count=new LongAdder(),total=new LongAdder();
        final AtomicLong max=new AtomicLong();
        void add(long nanos){count.increment();total.add(nanos);max.accumulateAndGet(nanos,Math::max);}
    }
    private final ConcurrentHashMap<String,Sample> samples=new ConcurrentHashMap<>();
    void measure(String label,Runnable task){
        long start=System.nanoTime();
        try{task.run();}finally{samples.computeIfAbsent(label,k->new Sample()).add(System.nanoTime()-start);}
    }
    String json(){
        var result=new java.util.ArrayList<String>();
        samples.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(e->{
            Sample s=e.getValue();long count=s.count.sum();
            result.add(OrionBridge.quote(e.getKey())+":{\"samples\":"+count+",\"averageNanos\":"+(count==0?0:s.total.sum()/count)+",\"maximumNanos\":"+s.max.get()+"}");
        });
        return "{\"tasks\":{"+String.join(",",result)+"},\"jvmHeapUsed\":"+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())+"}";
    }
}
