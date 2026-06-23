package lobster;

import java.util.*;
import java.io.*;
import java.util.concurrent.ThreadLocalRandom;
import java.lang.management.*;
import com.sun.management.OperatingSystemMXBean;

/**
 * LobsterStream  —  STARTER CODE (you complete the parts marked TODO).
 *
 * Generates a LOBSTER-like stream of limit-order-book events ENTIRELY IN MEMORY and
 * feeds them into Java Collections that form an order book. Nothing is written to
 * disk. Each event object is created, applied to the book, and then immediately
 * discarded (it becomes eligible for garbage collection at once). Only the order-book
 * STATE accumulates in memory.
 *
 * This is how you "process 50 GB of data" without ever storing it: the throughput of
 * generated-and-consumed events is effectively unbounded, while the LIVE memory is just
 * the book. You drive the book until its live data reaches your target, measuring as
 * you go. Generating on the fly (never reading a file) also keeps timing honest: a disk
 * read inside a measured loop would swamp the operation you are trying to measure.
 *
 * The collections used here are exactly the framework structures you are studying:
 *    TreeMap     — each side of the book, keyed by price (kept sorted)      O(log n) per op
 *    ArrayDeque  — the FIFO queue of orders resting at a single price       O(1) at the ends
 *    HashMap     — order id -> order, so a cancel finds its order fast      O(1) average
 *
 * Run it:
 *    javac LobsterStream.java
 *    java -Xms52g -Xmx52g LobsterStream 50      // target in GB; pass a smaller number on a smaller machine
 */
public class LobsterStream {

    // ---- one resting order ----
    static final class Order {
        final long id; final long price; int size; final int side;   // side: 1 = buy, -1 = sell
        Order(long id, long price, int size, int side){ this.id=id; this.price=price; this.size=size; this.side=side; }
    }

    // ---- the order book, built from framework collections ----
    final TreeMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(Collections.reverseOrder()); // highest price first
    final TreeMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();                            // lowest price first
    final HashMap<Long, Order> byId = new HashMap<>();    // id -> order, for fast cancels
    final ArrayList<Long> liveIds = new ArrayList<>();    // ids available to cancel

    long nextId = 1;
    long mid = 100_00;                                    // mid price in cents ($100.00)

    TreeMap<Long, ArrayDeque<Order>> side(int s){ return s == 1 ? bids : asks; }

    // ---- apply a NEW limit order (provided, fully working) ----
    void submit(int side, long price, int size){
        Order o = new Order(nextId++, price, size, side);
        side(side).computeIfAbsent(price, k -> new ArrayDeque<>()).addLast(o);  // price-time priority
        byId.put(o.id, o);
        liveIds.add(o.id);
    }

    // ---- cancel a resting order by id (provided, fully working) ----
    void cancel(long id){
        Order o = byId.remove(id);
        if (o == null) return;                            // already gone (e.g. executed)
        ArrayDeque<Order> q = side(o.side).get(o.price);
        if (q != null){ q.remove(o); if (q.isEmpty()) side(o.side).remove(o.price); }
    }

    // ====================================================================
    // TODO 1 (your coding task): matching / execution.
    // A marketable order arrives on `aggressorSide` and consumes `size` from the BEST
    // prices of the opposite book, honouring price-time priority (FIFO within a level).
    // Walk side(-aggressorSide) from its first entry, take from the head order of each
    // level, reduce or remove filled orders, drop emptied price levels, and remember to
    // remove fully-filled orders from byId. Stop when `size` is exhausted or the book
    // is empty. Write this yourself; this is part of the assessed coding.
    // ====================================================================
    void execute(int aggressorSide, int size){
        // Marketable order from aggressorSide consumes the opposite side
        TreeMap<Long, ArrayDeque<Order>> opp = side(-aggressorSide);
        while (size > 0 && !opp.isEmpty()){
            Map.Entry<Long, ArrayDeque<Order>> bestEntry = opp.firstEntry();
            if (bestEntry == null) break;
            ArrayDeque<Order> q = bestEntry.getValue();
            while (size > 0 && !q.isEmpty()){
                Order head = q.peekFirst();
                if (head == null) break;
                int take = Math.min(size, head.size);
                head.size -= take;
                size -= take;
                if (head.size <= 0){
                    // remove fully filled resting order
                    Order removed = q.pollFirst();
                    if (removed != null) byId.remove(removed.id);
                    // also remove from liveIds if present (swap-remove)
                    for (int i = 0; i < liveIds.size(); i++){
                        if (liveIds.get(i) == removed.id){
                            long last = liveIds.get(liveIds.size() - 1);
                            liveIds.set(i, last);
                            liveIds.remove(liveIds.size() - 1);
                            break;
                        }
                    }
                }
            }
            // if this price level is empty, drop it
            if (q.isEmpty()) opp.remove(bestEntry.getKey());
        }
    }

    // ---- generate ONE event on the fly, apply it, then let it be discarded ----
    void step(ThreadLocalRandom rng){
        mid += rng.nextInt(-3, 4);                        // slow random walk of the mid price
        double r = rng.nextDouble();
        if (r < 0.62 || liveIds.isEmpty()){               // submit (biased high so the book GROWS to target)
            int side  = rng.nextBoolean() ? 1 : -1;
            int depth = 0; while (rng.nextDouble() > 0.40 && depth < 40) depth++;   // most orders near the touch
            long price = side == 1 ? mid - 100 - 100L*depth : mid + 100 + 100L*depth; // wide spread -> orders rest
            int size  = 100 * (1 + (int)(rng.nextDouble() * 4));
            submit(side, price, size);
        } else if (r < 0.95){                             // cancel a random resting order
            int idx = rng.nextInt(liveIds.size());
            long id = liveIds.get(idx);
            liveIds.set(idx, liveIds.get(liveIds.size() - 1));
            liveIds.remove(liveIds.size() - 1);
            cancel(id);
        } else {                                          // a few percent are executions (TODO 1)
            execute(rng.nextBoolean() ? 1 : -1, 100 * (1 + rng.nextInt(5)));
        }
    }

    static long usedBytes(){ Runtime r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory(); }

    public static void main(String[] args) {
        double gb = args.length > 0 ? Double.parseDouble(args[0]) : 50;
        long target = (long)(gb * 1024 * 1024 * 1024);
        LobsterStream s = new LobsterStream();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // prepare scaleC.csv for measurement outputs (create file early so monitor can append comments to it)
        java.io.BufferedWriter scaleCsv;
        try {
            scaleCsv = new java.io.BufferedWriter(new java.io.FileWriter("data/scaleC.csv"));
            scaleCsv.write("n,bytes_per_resting_order,ns_submit,ns_cancel,ns_bestBid\n");
            scaleCsv.flush();
            // ensure the CSV is closed on JVM exit so the file exists and is flushed
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    scaleCsv.close();
                } catch (IOException ignored) {}
            }));
        } catch (IOException e) { throw new RuntimeException(e); }

    // start background monitor daemon and append its lines to a separate monitor log
        Thread monitor = new Thread(() -> {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            Runtime rt = Runtime.getRuntime();
            List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
            ThreadMXBean tmb = ManagementFactory.getThreadMXBean();
            while (true){
                try{
                    double procCpu = os.getProcessCpuLoad();
                    long heapUsed = rt.totalMemory() - rt.freeMemory();
                    long totalPhys;
                    long freePhys;
                    try {
                        java.lang.reflect.Method mTotal = os.getClass().getMethod("getTotalPhysicalMemorySize");
                        java.lang.reflect.Method mFree = os.getClass().getMethod("getFreePhysicalMemorySize");
                        totalPhys = ((Number)mTotal.invoke(os)).longValue();
                        freePhys = ((Number)mFree.invoke(os)).longValue();
                    } catch (Throwable reflEx) {
                        // If the methods are not available (or are treated as deprecated), fall back to unknown
                        totalPhys = -1;
                        freePhys = -1;
                    }
                    int threadCount = tmb.getThreadCount();
                    long totalGcCount = 0; long totalGcTime = 0;
                    for (GarbageCollectorMXBean gc : gcs){ long c = gc.getCollectionCount(); if (c>0) totalGcCount += c; long t = gc.getCollectionTime(); if (t>0) totalGcTime += t; }
                    String cpuStr = procCpu >= 0 ? String.format("%.2f", procCpu*100.0)+"%" : "N/A";
                    String line = String.format("[MON] CPU=%s heapUsed=%d totalPhys=%d freePhys=%d threads=%d GCcount=%d GCtime_ms=%d",
                            cpuStr, heapUsed, totalPhys, freePhys, threadCount, totalGcCount, totalGcTime);
                    System.out.println(line);
                    // append as a comment line to a separate monitor log so the CSV stays pure
                    try (java.io.FileWriter fw = new java.io.FileWriter("logs/scaleC.monitor.log", true);
                         java.io.BufferedWriter bw = new java.io.BufferedWriter(fw)) {
                        bw.write("#MON " + line + "\n");
                        bw.flush();
                    } catch (IOException ioe) {
                        System.err.println("Failed to append monitor line to logs/scaleC.monitor.log: " + ioe);
                    }
                    Thread.sleep(1000L);
                } catch (InterruptedException ie){ break; } catch (Throwable t){ System.err.println("[MON] error: " + t); try{ Thread.sleep(1000L); } catch (InterruptedException ignored){} }
            }
        });
        monitor.setDaemon(true);
        monitor.setName("lobster-monitor");
        monitor.start();

        // scaleC.csv was created earlier so the monitor can append comment lines to it

        long events = 0, t0 = System.nanoTime();
        long nextSample = 1024;
        while (usedBytes() < target){                     // stop when LIVE data reaches the target
            s.step(rng);                                  // generate + process + discard, all in memory
            events++;
            if ((events & 0xFFFFFF) == 0){                // report roughly every 16M events
                double secs = (System.nanoTime() - t0) / 1e9;
                System.out.printf("events=%,dM  rate=%,.1fM/s  liveHeap=%,d MB  restingOrders=%,d%n",
                        events / 1_000_000, (events / 1e6) / secs, usedBytes() / 1_048_576, s.byId.size());
            }

            // sampling measurement when resting order count grows past nextSample
            int nOrders = s.byId.size();
            if (nOrders >= nextSample){
                try {
                    // compute bytes per resting order
                    double bytesPer = nOrders > 0 ? ((double)usedBytes()) / nOrders : 0.0;

                    // measure best-bid lookup
                    int warmB = 100;
                    int itB = 1000;
                    for (int i=0;i<warmB;i++) { s.bids.firstEntry(); }
                    long tB0 = System.nanoTime();
                    for (int i=0;i<itB;i++) { Map.Entry<Long,ArrayDeque<Order>> e = s.bids.firstEntry(); if (e!=null) { long k=e.getKey(); } }
                    long tB1 = System.nanoTime();
                    double nsBest = (tB1 - tB0) / (double) itB;

                    // measure submit+cancel per-iteration to get submit and cancel times separately
                    int itSC = 200;
                    long totalSubmitNs = 0;
                    long totalCancelNs = 0;
                    ArrayList<Long> created = new ArrayList<>(); created.ensureCapacity(itSC);
                    for (int i=0;i<itSC;i++){
                        int side = (i%2==0)?1:-1;
                        long price = s.mid + (i%5)*100L;
                        int sz = 100;
                        long t0s = System.nanoTime();
                        s.submit(side, price, sz);
                        long t1s = System.nanoTime();
                        long newId = s.nextId - 1;
                        totalSubmitNs += (t1s - t0s);
                        created.add(newId);
                        long t0c = System.nanoTime();
                        s.cancel(newId);
                        long t1c = System.nanoTime();
                        totalCancelNs += (t1c - t0c);
                    }
                    double nsSubmit = totalSubmitNs / (double) itSC;
                    double nsCancel = totalCancelNs / (double) itSC;

                    // write row to CSV
                    String row = String.format(Locale.US, "%d,%.3f,%.3f,%.3f,%.3f\n", nOrders, bytesPer, nsSubmit, nsCancel, nsBest);
                    scaleCsv.write(row);
                    scaleCsv.flush();
                    System.out.print("[SCALE] " + row);

                    // advance nextSample (geometric)
                    nextSample *= 2;
                } catch (IOException ioe){ System.err.println("scale measurement failed: " + ioe); }
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("REACHED ~%.0f GB: processed %,d events in %.1fs (%,.1fM events/s), %,d resting orders%n",
                gb, events, secs, (events / 1e6) / secs, s.byId.size());

        // ================================================================
        // TODO 2 (measurement, to instruct Copilot): around the running stream, time how
        // long submit, cancel, and a best-bid lookup (bids.firstEntry()) take as the book
        // grows, and compute bytes-per-resting-order from usedBytes()/byId.size(). Write
        // the figures to scaleC.csv. Keep the generation on the fly; never store events.
        // ================================================================
    }
}
