package io.github.engrkarlo.orionbridge;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
public final class GuardBudgetSmoke{
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  GuardBudget b=new GuardBudget();UUID id=UUID.randomUUID();
  AtomicInteger count=new AtomicInteger();
  java.util.stream.IntStream.range(0,10000).parallel().forEach(i->GuardBudget.operation(count,1234));
  check(count.get()==1234);check(!GuardBudget.operation(count,1234));
  check(b.reserve(id,100,2));check(b.reserve(id,101,2));check(!b.reserve(id,102,2));check(b.reserve(id,60000000101L,2));
 }
}
