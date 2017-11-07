package io.github.mkuchin;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class BotValve extends ValveBase {
    private static final Log log = LogFactory.getLog(BotValve.class);
    private static final int NANOS_IN_MILLIS = 1_000_000;
    private static final int MILLIS_IN_SEC = 1_000;
    private static final String API_PREFIX = "/botvalve/";
    private static final int API_PREFIX_LEN = API_PREFIX.length();
    private ConcurrentHashMap<String, RequestCounter> counters;
    private ConcurrentHashMap<String, Long> blocked;
    private boolean enabled = true;
    private int period = 60;
    private TimeUnit unit = TimeUnit.SECONDS;
    private int expiry = 5 * 60 * MILLIS_IN_SEC;
    private int threshold = 100;
    private String pushKey;
    private String pushTitle;
    private long lastClean;
    private long cleanIpsTimeout = 30 * 60 * MILLIS_IN_SEC;

    public BotValve() {
        super();
        reset();
    }

    @Override
    protected synchronized void startInternal() throws LifecycleException {
        super.startInternal();
        log.info("BotValve started");
        log.info("Period: " + period + " " + unit.name());
        log.info("Block threshold: " + threshold);
        log.info("Expiry: " + expiry);
        log.info("Clean IP timeout: " + cleanIpsTimeout);
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        String ip = request.getRemoteAddr();
        //String ip = request.getHeader("X-Real-IP");
        //if (ip == null || ip.isEmpty()){
        //    ip = request.getRemoteAddr();
        //}    
       
        if (enabled && blocked.get(ip) != null) {
            deny(ip, request, response);
            return;
        }
        if (request.getRequestURI().startsWith(API_PREFIX)) {
            handleApi(request, response);
            return;
        }
        RequestCounter counter = counters.get(ip);
        if (counter == null) {
            counter = createCounter();
            RequestCounter oldCounter = counters.putIfAbsent(ip, counter);
            if (oldCounter != null) {
                counter = oldCounter;
            }
        }
        counter.hit();
        if (counter.getSize() > threshold && !blocked.contains(ip)) {
            pushNotification(ip);
            blocked.put(ip, System.currentTimeMillis());
            log.error("Blocked: " + ip);
            if (enabled)
                deny(ip, request, response);
        }
        getNext().invoke(request, response);
    }

    private void pushNotification(String ip) {
        if(pushKey != null) {
            HttpURLConnection con = null;
            try {
                URL obj = new URL(String.format("https://api.simplepush.io/send/%s/%s/%s", pushKey, pushTitle, "Blocked: " + ip));
                 con = (HttpURLConnection) obj.openConnection();
                int responseCode = con.getResponseCode();
                if(responseCode != HttpServletResponse.SC_OK)
                    log.error("Error pushing notification, response code: " + responseCode);
            } catch (Exception e) {
                log.error("Excepiton pushing notification", e);
            } finally {
                if(con!=null)
                    con.disconnect();
            }

        }
    }

    private void handleApi(Request request, Response response) throws IOException {
        String uri = request.getRequestURI();
        log.debug("Api request: " + uri);
        String action = uri.substring(uri.indexOf(API_PREFIX) + API_PREFIX_LEN);
        log.debug("Api action: " + action);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/plain");
        Writer writer = response.getWriter();
        PrintWriter out = new PrintWriter(writer);
        out.println("botValve API");
        switch (action) {
            case "":
                out.println("Actions: /status, /reset, /enable, /disable");
                break;
            case "disable":
                enabled = false;
                out.println("ok");
                break;
            case "enable":
                enabled = true;
                out.println("ok");
                break;
            case "reset":
                reset();
                out.println("ok");
                break;
            case "status":
                out.println("Blocked ips:");
                long time = System.currentTimeMillis();
                for (String ip : blocked.keySet()) {
                    out.printf("%16s - %5s sec\n", ip, (time - blocked.get(ip)) / MILLIS_IN_SEC);
                }
                PriorityQueue<Map.Entry<String, RequestCounter>> queue = new PriorityQueue<>(new Comparator<Map.Entry<String, RequestCounter>>() {
                    @Override
                    public int compare(Map.Entry<String, RequestCounter> o1, Map.Entry<String, RequestCounter> o2) {
                        return o2.getValue().getSize() - o1.getValue().getSize();
                    }
                });
                Set<Map.Entry<String, RequestCounter>> counterEntries = counters.entrySet();
                out.println("uniq ips: " + counterEntries.size());
                queue.addAll(counterEntries);
                out.println("Top ips:");
                for (int i = 0; i < 20; i++) {
                    Map.Entry<String, RequestCounter> entry = queue.poll();
                    if (entry == null)
                        break;
                    out.printf("%16s  %5s\n", entry.getKey(), entry.getValue().getSize());
                }

                break;
        /*   "set":
                String name = request.
                break;
            */
            default:
                writer.write("Unrecognized action");
        }

        writer.flush();

    }

    private void reset() {
        this.counters = new ConcurrentHashMap<>(10000, 0.9f, 4);
        this.blocked = new ConcurrentHashMap<>(10, 0.9f, 1);
        this.lastClean = System.currentTimeMillis();
    }

    private RequestCounter createCounter() {
        return new RequestCounter(period, unit);
    }

    private void deny(String ip, Request request, Response response) {
//         response.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
        response.setStatus(429); // Too Many Requests todo add Retry-After: 3600
    }

    public void setPeriod(int period) {
        this.period = period;
    }

    public void setUnit(String unit) {
        this.unit = TimeUnit.valueOf(unit);
    }

    public void setExpiry(int expiry) {
        this.expiry = expiry * MILLIS_IN_SEC;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    public void setCleanIpsTimeout(long cleanIpsTimeout) {
        this.cleanIpsTimeout = cleanIpsTimeout * MILLIS_IN_SEC;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setPushKey(String pushKey) {
        this.pushKey = pushKey;
    }

    public void setPushTitle(String pushTitle) {
        this.pushTitle = pushTitle;
    }

    @Override
    public void backgroundProcess() {
        Set<String> keySet = blocked.keySet();
        long time = System.currentTimeMillis();
        for (String ip : keySet) {
            if (time - blocked.get(ip) > expiry) {
                log.warn("Expired block: " + ip);
                blocked.remove(ip);
            }
        }
        if (time - lastClean > cleanIpsTimeout) {
            lastClean = time;
            log.warn("Cleaning IPs, initial size=" + counters.size());
            keySet = counters.keySet();
            long nanoTime = System.nanoTime();
            long nanoTimeout = cleanIpsTimeout * NANOS_IN_MILLIS;
            for (String ip : keySet) {
                Long lastTime = counters.get(ip).getLatest();
                if (lastTime == null || nanoTime - lastTime > nanoTimeout) {
                    counters.remove(ip);
                }
            }
            log.warn("Cleaning IPs, final size=" + counters.size());
        }
    }
}
