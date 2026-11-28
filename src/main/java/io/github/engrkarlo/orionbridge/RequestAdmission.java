package io.github.engrkarlo.orionbridge;

/** Atomically stop admissions only when no signed requests are in progress. */
final class RequestAdmission {
    private int active;
    private boolean closed;
    synchronized boolean enter(){if(closed)return false;active++;return true;}
    synchronized void leave(){if(active<=0)throw new IllegalStateException("Unbalanced request");active--;}
    synchronized boolean quiesce(){if(active!=0)return false;closed=true;return true;}
}
