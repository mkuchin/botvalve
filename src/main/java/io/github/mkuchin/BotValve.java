package io.github.mkuchin;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class BotValve extends ValveBase {
    private static final Log log =LogFactory.getLog(BotValve.class);
    private static final int NANOS_IN_MILLIS = 1_000_000;
    private ConcurrentHashMap<String, RequestCounter> counters;
    private ConcurrentHashMap<String, Long> blocked;
    private int period = 60;
    private String unit = TimeUnit.SECONDS.name();
    private int expiry = 5 * 60 * 1000;
    private int threshold = 100;
    private long lastClean;
    private final long cleanIpsTimeout = 30 * 60 * 1000;

    public BotValve() {
        super();
        this.counters = new ConcurrentHashMap<>(10000, 0.9f, 4);
        this.blocked = new ConcurrentHashMap<>(10, 0.9f, 1);
        this.lastClean = System.currentTimeMillis();
    }

    @Override
    protected synchronized void startInternal() throws LifecycleException {
        super.startInternal();
        containerLog.info("BotValve started");
        containerLog.info("Expiry: " + expiry);
        containerLog.info("Threshold: " + threshold);
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        String ip = request.getRemoteAddr();
        if(blocked.get(ip) != null) {
            deny(ip, request, response);
            return;
        }
        RequestCounter counter = counters.get(ip);
        if(counter == null) {
            counter = createCounter();
            RequestCounter oldCounter = counters.putIfAbsent(ip, counter);
            if(oldCounter != null) {
                counter = oldCounter;
            }
        }
        counter.hit();
        if(counter.getSize() > threshold) {
            blocked.put(ip, System.currentTimeMillis());
            containerLog.error("Blocked: " + ip);
            deny(ip, request, response);
        }
        getNext().invoke(request, response);

    }

    private RequestCounter createCounter() {
        return new RequestCounter(period, TimeUnit.valueOf(unit));
    }

    private void deny(String ip, Request request, Response response) {
        response.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
    }

    public void setPeriod(int period) {
        this.period = period;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public void setExpiry(int expiry) {
        log.info("setExpiry:" + expiry);
        this.expiry = expiry;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    @Override
    public void backgroundProcess() {
        Set<String> keySet = blocked.keySet();
        long time = System.currentTimeMillis();
        for(String ip: keySet) {
           if(time - blocked.get(ip) > expiry) {
               containerLog.warn("Expired block: " + ip);
               blocked.remove(ip);
           }
        }
        if(time - lastClean > cleanIpsTimeout) {
            lastClean = time;
            containerLog.warn("Cleaning IPs, initial size=" + counters.size());
            keySet = counters.keySet();
            long nanoTime = System.nanoTime();
            long nanoTimeout =  cleanIpsTimeout * NANOS_IN_MILLIS;
            for(String ip: keySet) {
                if(counters.get(ip).getLast() - nanoTime > nanoTimeout) {
                    counters.remove(ip);
                }
            }
            containerLog.warn("Cleaning IPs, final size=" + counters.size());
        }
    }
}
