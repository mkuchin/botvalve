package io.github.mkuchin;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class RequestCounter {
    private final ConcurrentLinkedDeque<Long> items;
    private final long period;
    private final AtomicLong windowStart;

    public RequestCounter(long time, TimeUnit unit) {
        this.items = new ConcurrentLinkedDeque<>();
        this.period = TimeUnit.NANOSECONDS.convert(time, unit);
        this.windowStart = new AtomicLong();
    }

    public void hit() {
        Long time = System.nanoTime();
        items.add(time);
        trimOnce(time);
    }

    public int getSize() {
        trimOnce(System.nanoTime());
        return items.size();
    }

    public long getLast() {
        return items.getLast();
    }

    private void trimOnce(Long time) {
        long startTime = time - period;
        long windowStartTime = windowStart.get();
        if (windowStartTime < startTime) {
            if (windowStart.compareAndSet(windowStartTime, startTime)) {
                trim(startTime);
            }
        }
    }

    private void trim(long startTime) {
        Iterator<Long> iterator = items.iterator();
        while (iterator.hasNext() && iterator.next() < startTime) {
            iterator.remove();
        }
    }
}
