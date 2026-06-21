import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

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
        // TODO: implement the sweep described above.
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

        long events = 0, t0 = System.nanoTime();
        while (usedBytes() < target){                     // stop when LIVE data reaches the target
            s.step(rng);                                  // generate + process + discard, all in memory
            events++;
            if ((events & 0xFFFFFF) == 0){                // report roughly every 16M events
                double secs = (System.nanoTime() - t0) / 1e9;
                System.out.printf("events=%,dM  rate=%,.1fM/s  liveHeap=%,d MB  restingOrders=%,d%n",
                        events / 1_000_000, (events / 1e6) / secs, usedBytes() / 1_048_576, s.byId.size());
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
