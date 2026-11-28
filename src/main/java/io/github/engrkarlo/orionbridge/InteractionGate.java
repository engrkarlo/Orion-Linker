package io.github.engrkarlo.orionbridge;
final class InteractionGate {
 private final java.util.concurrent.atomic.AtomicBoolean claimed=new java.util.concurrent.atomic.AtomicBoolean();
 boolean claim(){return claimed.compareAndSet(false,true);}
}
