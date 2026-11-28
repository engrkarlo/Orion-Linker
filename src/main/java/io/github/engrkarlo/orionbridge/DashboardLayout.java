package io.github.engrkarlo.orionbridge;
import java.util.*;
final class DashboardLayout {
    private DashboardLayout(){}
    static List<Integer> slots(int size,int info,int count,List<Integer> preferred){
        if(size<9||size>54||size%9!=0||info<0||info>=size||count<0||count>=size)throw new IllegalArgumentException("Invalid layout size");
        Set<Integer> usable=new LinkedHashSet<>();
        for(Integer i:preferred)if(i!=null&&i>=0&&i<size&&i!=info)usable.add(i);
        for(int i=0;i<size;i++)if(i!=info)usable.add(i);
        return usable.stream().limit(count).toList();
    }
}
