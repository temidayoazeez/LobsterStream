package lobster;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.lang.management.*;
import com.sun.management.OperatingSystemMXBean;

public class BenchmarkCollections {
    // Defaults (can be overridden via command-line args)
    static int[] SIZES = {1_000, 10_000, 100_000, 1_000_000, 10_000_000};
    static int OPS = 1_000_000;
    static int WARMUPS = 1_000_000;
    static final String CSV = "data/timeA.csv";
    static long sink = 0L;

    public static void main(String[] args) throws Exception {
        // parse optional command-line arguments
        // usage: java -Xmx6g BenchmarkCollections [ops] [warmups] [sizes-comma-separated]
        if (args.length > 0) {
            try { OPS = Integer.parseInt(args[0]); } catch (NumberFormatException ex) { System.err.println("Invalid OPS, using default " + OPS); }
        }
        if (args.length > 1) {
            try { WARMUPS = Integer.parseInt(args[1]); } catch (NumberFormatException ex) { System.err.println("Invalid WARMUPS, using default " + WARMUPS); }
        } else {
            WARMUPS = OPS;
        }
        if (args.length > 2) {
            try {
                String[] parts = args[2].split(",");
                int[] parsed = new int[parts.length];
                for (int i = 0; i < parts.length; i++) parsed[i] = Integer.parseInt(parts[i].trim());
                SIZES = parsed;
            } catch (Exception ex) {
                System.err.println("Invalid sizes list, using default sizes");
            }
        }
        System.out.println("Configuration: OPS=" + OPS + " WARMUPS=" + WARMUPS + " SIZES=" + Arrays.toString(SIZES));

        // Start a background monitoring daemon that prints process CPU load, heap used,
        // total/free physical memory, live thread count, and total GC count/time every second.
        Thread monitor = new Thread(() -> {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            Runtime rt = Runtime.getRuntime();
            List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
            ThreadMXBean tmb = ManagementFactory.getThreadMXBean();
            while (true) {
                try {
                    double procCpu = os.getProcessCpuLoad(); // 0.0 - 1.0 or -1 if not available
                    long heapUsed = rt.totalMemory() - rt.freeMemory();
                    long totalPhys = os.getTotalPhysicalMemorySize();
                    long freePhys = os.getFreePhysicalMemorySize();
                    int threadCount = tmb.getThreadCount();
                    long totalGcCount = 0;
                    long totalGcTime = 0;
                    for (GarbageCollectorMXBean gc : gcs) {
                        long c = gc.getCollectionCount();
                        if (c > 0) totalGcCount += c;
                        long t = gc.getCollectionTime();
                        if (t > 0) totalGcTime += t;
                    }
                    String cpuStr = procCpu >= 0 ? String.format("%.2f", procCpu * 100.0) + "%" : "N/A";
                    System.out.printf("[MON] CPU=%s heapUsed=%d totalPhys=%d freePhys=%d threads=%d GCcount=%d GCtime_ms=%d\n",
                            cpuStr, heapUsed, totalPhys, freePhys, threadCount, totalGcCount, totalGcTime);
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    System.err.println("[MON] monitor error: " + t);
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
        });
        monitor.setDaemon(true);
        monitor.setName("benchmark-monitor");
        monitor.start();

        List<Result> results = new ArrayList<>();
        try (BufferedWriter csv = new BufferedWriter(new FileWriter(CSV))) {
            csv.write("structure,operation,n,ns_per_op\n");

            // ArrayList tests
            results.addAll(runListTests("ArrayList", SIZES, csv));
            // LinkedList tests
            results.addAll(runListTests("LinkedList", SIZES, csv));
            // ArrayDeque tests
            results.addAll(runDequeTests("ArrayDeque", SIZES, csv));
            // HashSet tests
            results.addAll(runSetTests("HashSet", SIZES, csv));
            // TreeSet tests
            results.addAll(runSetTests("TreeSet", SIZES, csv));
            // HashMap tests
            results.addAll(runMapTests("HashMap", SIZES, csv));
            // TreeMap tests
            results.addAll(runMapTests("TreeMap", SIZES, csv));
            // PriorityQueue tests
            results.addAll(runPriorityQueueTests("PriorityQueue", SIZES, csv));
        }

        // Print a simple table
        System.out.printf("%-12s %-15s %12s %14s\n", "Structure", "Operation", "n", "ns/op");
        for (Result r : results) {
            System.out.printf("%-12s %-15s %12d %14.2f\n", r.structure, r.operation, r.n, r.nsPerOp);
        }

        // Compute guessed Big-O for each (structure+operation) comparing first and last size
        System.out.println("\nEstimated scaling (smallest -> largest):");
        Map<String, List<Result>> grouped = new LinkedHashMap<>();
        for (Result r : results) {
            String key = r.structure + ":" + r.operation;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<Result>> e : grouped.entrySet()) {
            List<Result> list = e.getValue();
            list.sort(Comparator.comparingInt(x -> x.n));
            Result first = list.get(0);
            Result last = list.get(list.size() - 1);
            double p = Math.log(last.nsPerOp / first.nsPerOp) / Math.log((double) last.n / first.n + 1e-16);
            String guess = guessBigO(p);
            System.out.printf("%-30s p=%.3f  guess=%s\n", e.getKey(), p, guess);
        }

        System.out.println("\nFinal sink: " + sink);
        System.out.println("CSV written to " + CSV);
        // After timing benchmarks, run memory measurements and write memoryB.csv
        measureMemory();
    }

    // Memory measurement: for each structure type, force GC, measure used heap, fill with count Integers, GC, measure used heap again.
    static void measureMemory() throws IOException {
        String outCsv = "data/memoryB.csv";
        int count = 1_000_000; // default number of elements to allocate for measurement
        System.out.println("\nRunning memory measurements with " + count + " Integer elements per structure...");
        try (BufferedWriter csv = new BufferedWriter(new FileWriter(outCsv))) {
            csv.write("structure,n,used_before,used_after,bytes_per_element\n");

            // list of measurement tasks: one structure at a time
            // ArrayList
            runMemoryCase("ArrayList", count, csv, () -> {
                List<Integer> a = new ArrayList<>(count);
                for (int i = 0; i < count; i++) a.add(Integer.valueOf(i));
                return a;
            });
            // LinkedList
            runMemoryCase("LinkedList", count, csv, () -> {
                List<Integer> a = new LinkedList<>();
                for (int i = 0; i < count; i++) a.add(Integer.valueOf(i));
                return a;
            });
            // ArrayDeque
            runMemoryCase("ArrayDeque", count, csv, () -> {
                ArrayDeque<Integer> d = new ArrayDeque<>(count);
                for (int i = 0; i < count; i++) d.add(Integer.valueOf(i));
                return d;
            });
            // HashSet
            runMemoryCase("HashSet", count, csv, () -> {
                HashSet<Integer> s = new HashSet<>(count*2);
                for (int i = 0; i < count; i++) s.add(Integer.valueOf(i));
                return s;
            });
            // TreeSet
            runMemoryCase("TreeSet", count, csv, () -> {
                TreeSet<Integer> s = new TreeSet<>();
                for (int i = 0; i < count; i++) s.add(Integer.valueOf(i));
                return s;
            });
            // HashMap
            runMemoryCase("HashMap", count, csv, () -> {
                HashMap<Integer,Integer> m = new HashMap<>(count*2);
                for (int i = 0; i < count; i++) m.put(Integer.valueOf(i), Integer.valueOf(i));
                return m;
            });
            // TreeMap
            runMemoryCase("TreeMap", count, csv, () -> {
                TreeMap<Integer,Integer> m = new TreeMap<>();
                for (int i = 0; i < count; i++) m.put(Integer.valueOf(i), Integer.valueOf(i));
                return m;
            });
            // PriorityQueue
            runMemoryCase("PriorityQueue", count, csv, () -> {
                PriorityQueue<Integer> q = new PriorityQueue<>();
                for (int i = 0; i < count; i++) q.add(Integer.valueOf(i));
                return q;
            });
        }
        System.out.println("Memory CSV written to memoryB.csv");
    }

    interface SupplierWithException<T> { T get() throws Exception; }

    static void runMemoryCase(String name, int count, BufferedWriter csv, SupplierWithException<Object> supplier) throws IOException {
        Runtime rt = Runtime.getRuntime();
        // force GC and wait briefly
        System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        long before = rt.totalMemory() - rt.freeMemory();

        Object structure = null;
        try {
            structure = supplier.get();
        } catch (Exception ex) {
            System.err.println("Error creating structure " + name + ": " + ex);
        }

        System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        long after = rt.totalMemory() - rt.freeMemory();

        long used = after - before;
        double bytesPer = count > 0 ? (double) used / (double) count : Double.NaN;

        csv.write(String.format("%s,%d,%d,%d,%.2f\n", name, count, before, after, bytesPer));
        csv.flush();
        System.out.printf("MEM: %s count=%d used_before=%d used_after=%d bytes/elem=%.2f\n", name, count, before, after, bytesPer);

        // null and GC before next measurement
        structure = null;
        System.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
    }

    static String guessBigO(double p) {
        if (Double.isNaN(p) || Double.isInfinite(p)) return "unknown";
        if (p < 0.25) return "O(1)";
        if (p < 0.8) return "O(log n) (approx)";
        if (p < 1.3) return "O(n)";
        if (p < 1.8) return "O(n log n) (approx)";
        if (p < 2.5) return "O(n^2)";
        return String.format("O(n^%.2f)", p);
    }

    static List<Result> runListTests(String type, int[] sizes, BufferedWriter csv) throws IOException {
        List<Result> out = new ArrayList<>();
        for (int n : sizes) {
            Random r = new Random(12345 + n);
            List<Integer> list = "ArrayList".equals(type) ? new ArrayList<>() : new LinkedList<>();
            // build with n random elements
            for (int i = 0; i < n; i++) list.add(r.nextInt());
            // prepare op args
            int[] idxs = new int[OPS];
            int[] values = new int[OPS];
            for (int i = 0; i < OPS; i++) {
                idxs[i] = (n == 0) ? 0 : (Math.abs(r.nextInt()) % Math.max(1, Math.min(n, OPS)));
                values[i] = r.nextInt();
            }
            // get(index) — skip LinkedList at n>100K (O(n) per call makes it impractically slow)
            if (n > 0 && !("LinkedList".equals(type) && n > 100_000)) {
                for (int i = 0; i < WARMUPS; i++) {
                    Integer v = list.get(idxs[i % n]);
                    sink += (v == null ? 0 : v.hashCode());
                }
                long t0 = System.nanoTime();
                for (int i = 0; i < OPS; i++) {
                    Integer v = list.get(idxs[i % n]);
                    sink += (v == null ? 0 : v.hashCode());
                }
                long t1 = System.nanoTime();
                double nsop = (t1 - t0) / (double) OPS;
                writeAndAdd(out, csv, type, "get(index)", n, nsop);
            } else if ("LinkedList".equals(type) && n > 100_000) {
                writeAndAdd(out, csv, type, "get(index)", n, -1);
            }
            // add-at-end — warm up on a scratch list, then measure on a fresh n-element list
            {
                List<Integer> warm2 = "ArrayList".equals(type) ? new ArrayList<>() : new LinkedList<>();
                for (int i = 0; i < n; i++) warm2.add(r.nextInt());
                for (int i = 0; i < WARMUPS; i++) { warm2.add(values[i % OPS]); sink += warm2.size(); }
                warm2 = null;
                List<Integer> measured = "ArrayList".equals(type) ? new ArrayList<>(n + OPS) : new LinkedList<>();
                for (int i = 0; i < n; i++) measured.add(r.nextInt());
                long t0 = System.nanoTime();
                for (int i = 0; i < OPS; i++) { measured.add(values[i]); sink += measured.size(); }
                long t1 = System.nanoTime();
                writeAndAdd(out, csv, type, "add-at-end", n, (t1 - t0) / (double) OPS);
            }

            // add-at-front — same pattern
            {
                List<Integer> warm2 = "ArrayList".equals(type) ? new ArrayList<>() : new LinkedList<>();
                for (int i = 0; i < n; i++) warm2.add(r.nextInt());
                for (int i = 0; i < WARMUPS; i++) { warm2.add(0, values[i % OPS]); sink += warm2.size(); }
                warm2 = null;
                List<Integer> measured = "ArrayList".equals(type) ? new ArrayList<>(n + OPS) : new LinkedList<>();
                for (int i = 0; i < n; i++) measured.add(r.nextInt());
                long t0 = System.nanoTime();
                for (int i = 0; i < OPS; i++) {
                    measured.add(0, values[i]);
                    Integer v = measured.get(0);
                    sink += (v == null ? 0 : v.hashCode());
                }
                long t1 = System.nanoTime();
                writeAndAdd(out, csv, type, "add-at-front", n, (t1 - t0) / (double) OPS);
            }

            // contains
            list = "ArrayList".equals(type) ? new ArrayList<>() : new LinkedList<>();
            for (int i = 0; i < n; i++) list.add(r.nextInt());
            for (int i = 0; i < WARMUPS; i++) sink += list.contains(values[i % OPS]) ? 1 : 0;
            { long t0 = System.nanoTime();
              for (int i = 0; i < OPS; i++) sink += list.contains(values[i]) ? 1 : 0;
              writeAndAdd(out, csv, type, "contains", n, (System.nanoTime() - t0) / (double) OPS); }
        }
        return out;
    }

    static List<Result> runDequeTests(String type, int[] sizes, BufferedWriter csv) throws IOException {
        List<Result> out = new ArrayList<>();
        for (int n : sizes) {
            Random r = new Random(23456 + n);
            ArrayDeque<Integer> dq = new ArrayDeque<>();
            for (int i = 0; i < n; i++) dq.add(r.nextInt());
            int[] values = new int[OPS];
            for (int i = 0; i < OPS; i++) values[i] = r.nextInt();

            // add-at-end (offer)
            for (int i = 0; i < WARMUPS; i++) dq.offer(values[i]);
            long t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                dq.offer(values[i]);
                sink += dq.size();
            }
            long t1 = System.nanoTime();
            double nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "offer (add-end)", n, nsop);

            // add-at-front (offerFirst)
            dq = new ArrayDeque<>();
            for (int i = 0; i < n; i++) dq.add(r.nextInt());
            for (int i = 0; i < WARMUPS; i++) dq.addFirst(values[i]);
            t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                dq.addFirst(values[i]);
                Integer v = dq.peekFirst();
                sink += (v == null ? 0 : v.hashCode());
            }
            t1 = System.nanoTime();
            nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "add-at-front", n, nsop);

            // peek
            for (int i = 0; i < WARMUPS; i++) {
                Integer v = dq.peek();
                sink += (v == null ? 0 : v.hashCode());
            }
            t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                Integer v = dq.peek();
                sink += (v == null ? 0 : v.hashCode());
            }
            t1 = System.nanoTime();
            nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "peek", n, nsop);

            // poll — refill after each poll so the deque never empties
            dq = new ArrayDeque<>();
            for (int i = 0; i < n; i++) dq.add(r.nextInt());
            for (int i = 0; i < WARMUPS; i++) { dq.poll(); dq.add(values[i % OPS]); }
            t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                Integer v = dq.poll();
                sink += (v == null ? 0 : v.hashCode());
                dq.add(values[i]);
            }
            t1 = System.nanoTime();
            nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "poll", n, nsop);

            // contains
            dq = new ArrayDeque<>();
            for (int i = 0; i < n; i++) dq.add(r.nextInt());
            for (int i = 0; i < WARMUPS; i++) sink += dq.contains(values[i]) ? 1 : 0;
            t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                sink += dq.contains(values[i]) ? 1 : 0;
            }
            t1 = System.nanoTime();
            nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "contains", n, nsop);
        }
        return out;
    }

    static List<Result> runSetTests(String type, int[] sizes, BufferedWriter csv) throws IOException {
        List<Result> out = new ArrayList<>();
        for (int n : sizes) {
            Random r = new Random(34567 + n);
            Collection<Integer> set = "HashSet".equals(type) ? new HashSet<>() : new TreeSet<>();
            int[] values = new int[OPS];
            for (int i = 0; i < n; i++) set.add(r.nextInt());
            for (int i = 0; i < OPS; i++) values[i] = r.nextInt();

            // add
            for (int i = 0; i < WARMUPS; i++) sink += (((Collection<Integer>) set).add(values[i])) ? 1 : 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                boolean added = ((Collection<Integer>) set).add(values[i]);
                sink += added ? 1 : 0;
            }
            long t1 = System.nanoTime();
            double nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "add", n, nsop);

            // contains
            for (int i = 0; i < WARMUPS; i++) sink += set.contains(values[i]) ? 1 : 0;
            t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                sink += set.contains(values[i]) ? 1 : 0;
            }
            t1 = System.nanoTime();
            nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "contains", n, nsop);
        }
        return out;
    }

    static List<Result> runMapTests(String type, int[] sizes, BufferedWriter csv) throws IOException {
        List<Result> out = new ArrayList<>();
        for (int n : sizes) {
            Random r = new Random(45678 + n);
            Map<Integer, Integer> map = "HashMap".equals(type) ? new HashMap<>() : new TreeMap<>();
            int[] keys = new int[OPS];
            int[] vals = new int[OPS];
            for (int i = 0; i < n; i++) map.put(r.nextInt(), r.nextInt());
            for (int i = 0; i < OPS; i++) {
                keys[i] = r.nextInt();
                vals[i] = r.nextInt();
            }

            // put
            for (int i = 0; i < WARMUPS; i++) sink += (map.put(keys[i], vals[i]) == null ? 1 : 0);
            long t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                Integer prev = map.put(keys[i], vals[i]);
                sink += (prev == null ? 1 : 0);
            }
            long t1 = System.nanoTime();
            double nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "put", n, nsop);

            // get
            for (int i = 0; i < WARMUPS; i++) {
                Integer v = map.get(keys[i]);
                sink += (v == null ? 0 : v.hashCode());
            }
            t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                Integer v = map.get(keys[i]);
                sink += (v == null ? 0 : v.hashCode());
            }
            t1 = System.nanoTime();
            nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "get", n, nsop);

            // containsKey
            for (int i = 0; i < WARMUPS; i++) sink += map.containsKey(keys[i]) ? 1 : 0;
            t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                sink += map.containsKey(keys[i]) ? 1 : 0;
            }
            t1 = System.nanoTime();
            nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "containsKey", n, nsop);
        }
        return out;
    }

    static List<Result> runPriorityQueueTests(String type, int[] sizes, BufferedWriter csv) throws IOException {
        List<Result> out = new ArrayList<>();
        for (int n : sizes) {
            Random r = new Random(56789 + n);
            PriorityQueue<Integer> pq = new PriorityQueue<>();
            int[] values = new int[OPS];
            for (int i = 0; i < n; i++) pq.offer(r.nextInt());
            for (int i = 0; i < OPS; i++) values[i] = r.nextInt();

            // offer
            for (int i = 0; i < WARMUPS; i++) sink += pq.offer(values[i]) ? 1 : 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                boolean ok = pq.offer(values[i]);
                sink += ok ? 1 : 0;
            }
            long t1 = System.nanoTime();
            double nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "offer", n, nsop);

            // peek
            for (int i = 0; i < WARMUPS; i++) {
                Integer v = pq.peek();
                sink += (v == null ? 0 : v.hashCode());
            }
            t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                Integer v = pq.peek();
                sink += (v == null ? 0 : v.hashCode());
            }
            t1 = System.nanoTime();
            nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "peek", n, nsop);

            // poll — refill after each poll so the queue never empties
            pq = new PriorityQueue<>();
            for (int i = 0; i < n; i++) pq.offer(r.nextInt());
            for (int i = 0; i < WARMUPS; i++) {
                Integer v = pq.poll(); sink += (v == null ? 0 : v.hashCode());
                pq.offer(values[i % OPS]);
            }
            t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                Integer v = pq.poll(); sink += (v == null ? 0 : v.hashCode());
                pq.offer(values[i]);
            }
            t1 = System.nanoTime();
            nsop = (t1 - t0) / (double) OPS;
            writeAndAdd(out, csv, type, "poll", n, nsop);
        }
        return out;
    }

    static void writeAndAdd(List<Result> out, BufferedWriter csv, String structure, String operation, int n, double nsop) throws IOException {
        out.add(new Result(structure, operation, n, nsop));
        csv.write(String.format("%s,%s,%d,%.3f\n", structure, operation, n, nsop));
        csv.flush();
        // print progress so the user can see activity in the launching terminal
        System.out.printf("WROTE: %s,%s,%d,%.3f\n", structure, operation, n, nsop);
        System.out.flush();
    }

    static class Result {
        String structure;
        String operation;
        int n;
        double nsPerOp;
        Result(String s, String o, int n, double t) { structure = s; operation = o; this.n = n; nsPerOp = t; }
    }
}