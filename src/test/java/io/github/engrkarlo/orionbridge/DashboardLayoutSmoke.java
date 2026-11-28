package io.github.engrkarlo.orionbridge;
import java.util.*;
public final class DashboardLayoutSmoke {
    public static void main(String[] args){
        if(!DashboardLayout.slots(27,0,4,List.of(13,13,0,99,11)).equals(List.of(13,11,1,2)))throw new AssertionError();
        if(!DashboardLayout.slots(27,13,3,List.of(0,13,5)).equals(List.of(0,5,1)))throw new AssertionError();
        if(new HashSet<>(DashboardLayout.slots(54,53,45,List.of())).size()!=45)throw new AssertionError();
    }
}
