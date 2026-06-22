import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class CompareCollections {
    static int[] SIZES = {1_000, 10_000, 100_000, 1_000_000, 10_000_000};
    static int OPS = 100_000;        // timed iterations (per size)
    static int WARMUPS = 50_000;     // warmup iterations
    static int MEM_COUNT = 1_000_000; // number of elements to allocate for memory measurement

    static long sink = 0L;

    public static void main(String[] args) throws Exception {
        // optional args: ops warmups memcount sizes(comma)
        if (args.length > 0) try { OPS = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        if (args.length > 1) try { WARMUPS = Integer.parseInt(args[1]); } catch (Exception ignored) {}
        if (args.length > 2) try { MEM_COUNT = Integer.parseInt(args[2]); } catch (Exception ignored) {}
        if (args.length > 3) try {
            String[] parts = args[3].split(",");
            int[] parsed = new int[parts.length];
            for (int i = 0; i < parts.length; i++) parsed[i] = Integer.parseInt(parts[i].trim());
            SIZES = parsed;
        } catch (Exception ignored) {}

        System.out.println("CompareCollections configuration: OPS="+OPS+" WARMUPS="+WARMUPS+" MEM_COUNT="+MEM_COUNT+" SIZES="+Arrays.toString(SIZES));

        List<Row> rows = new ArrayList<>();

        // run list tests for ArrayList vs MyArrayList
        runListBench("ArrayList", rows);
        runMapBench("HashMap", rows);

        // write compareD.csv
        try (BufferedWriter bw = new BufferedWriter(new FileWriter("compareD.csv"))) {
            bw.write("structure,implementation,operation,n,ns_per_op,bytes_per_element\n");
            for (Row r : rows) bw.write(String.format(Locale.US, "%s,%s,%s,%d,%.3f,%.3f\n",
                    r.structure, r.impl, r.operation, r.n, r.nsPerOp, r.bytesPerElement));
        }

        // compute Big-O exponent p for each (structure,impl,operation)
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row r : rows) {
            String key = r.structure+"|"+r.impl+"|"+r.operation;
            groups.computeIfAbsent(key, k->new ArrayList<>()).add(r);
        }
        try (BufferedWriter bw = new BufferedWriter(new FileWriter("compareD_bigO.csv"))) {
            bw.write("structure,implementation,operation,p,guess\n");
            for (Map.Entry<String,List<Row>> e : groups.entrySet()){
                List<Row> list = e.getValue();
                list.sort(Comparator.comparingInt(x->x.n));
                Row first = list.get(0); Row last = list.get(list.size()-1);
                double p = Math.log(last.nsPerOp/first.nsPerOp) / Math.log((double)last.n/first.n + 1e-16);
                String guess = guessBigO(p);
                String[] parts = e.getKey().split("\\|");
                bw.write(String.format(Locale.US, "%s,%s,%s,%.3f,%s\n", parts[0], parts[1], parts[2], p, guess));
            }
        }

        System.out.println("Wrote compareD.csv and compareD_bigO.csv");
    }

    static void runListBench(String name, List<Row> rows) throws Exception {
        Random r = new Random(42);
        for (int n : SIZES){
            // build initial content
            List<Integer> jdk = new ArrayList<>(n);
            MyArrayList<Integer> mine = new MyArrayList<>(n);
            for (int i = 0; i < n; i++) { int v = r.nextInt(); jdk.add(v); mine.add(v); }

            // prepare values/indices
            int[] idxs = new int[OPS]; int[] vals = new int[OPS];
            for (int i = 0; i < OPS; i++){ idxs[i] = (n==0)?0:Math.abs(r.nextInt())%Math.max(1, Math.min(n, OPS)); vals[i]=r.nextInt(); }

            // get(index)
            // JDK warmup
            for (int i=0;i<WARMUPS;i++){ Integer v = jdk.get(idxs[i%Math.max(1,n)]); sink += (v==null?0:v.hashCode()); }
            long t0=System.nanoTime();
            for (int i=0;i<OPS;i++){ Integer v = jdk.get(idxs[i%Math.max(1,n)]); sink += (v==null?0:v.hashCode()); }
            long t1=System.nanoTime(); double jdkNs=(t1-t0)/(double)OPS;

            // MyArrayList warmup
            for (int i=0;i<WARMUPS;i++){ Integer v = mine.get(idxs[i%Math.max(1,n)]); sink += (v==null?0:v.hashCode()); }
            t0=System.nanoTime();
            for (int i=0;i<OPS;i++){ Integer v = mine.get(idxs[i%Math.max(1,n)]); sink += (v==null?0:v.hashCode()); }
            t1=System.nanoTime(); double myNs=(t1-t0)/(double)OPS;

            // memory measurement for both using MEM_COUNT
            double jdkBytes = measureMemoryListJdk(MEM_COUNT);
            double myBytes = measureMemoryListMy(MEM_COUNT);

            rows.add(new Row("ArrayList","JDK","get(index)",n,jdkNs,jdkBytes));
            rows.add(new Row("ArrayList","My","get(index)",n,myNs,myBytes));

            // add-at-end
            // JDK
            List<Integer> measuredJ = new ArrayList<>(n+OPS);
            for (int i=0;i<n;i++) measuredJ.add(r.nextInt());
            for (int i=0;i<WARMUPS;i++){ measuredJ.add(vals[i%OPS]); sink+=measuredJ.size(); }
            t0=System.nanoTime(); for (int i=0;i<OPS;i++){ measuredJ.add(vals[i]); sink+=measuredJ.size(); } t1=System.nanoTime(); jdkNs=(t1-t0)/(double)OPS;

            // My
            MyArrayList<Integer> measuredM = new MyArrayList<>(n+OPS);
            for (int i=0;i<n;i++) measuredM.add(r.nextInt());
            for (int i=0;i<WARMUPS;i++){ measuredM.add(vals[i%OPS]); sink+=measuredM.size(); }
            t0=System.nanoTime(); for (int i=0;i<OPS;i++){ measuredM.add(vals[i]); sink+=measuredM.size(); } t1=System.nanoTime(); myNs=(t1-t0)/(double)OPS;

            rows.add(new Row("ArrayList","JDK","add-at-end",n,jdkNs,jdkBytes));
            rows.add(new Row("ArrayList","My","add-at-end",n,myNs,myBytes));
        }
    }

    static void runMapBench(String name, List<Row> rows) throws Exception {
        Random r = new Random(99);
        for (int n : SIZES){
            java.util.HashMap<Integer,Integer> jdk = new java.util.HashMap<>(n*2);
            MyHashMap<Integer,Integer> mine = new MyHashMap<>();
            for (int i=0;i<n;i++){ int k=r.nextInt(); jdk.put(k,i); mine.put(k,i); }

            int[] keys = new int[OPS]; int[] vals = new int[OPS];
            for (int i=0;i<OPS;i++){ keys[i]=r.nextInt(); vals[i]=r.nextInt(); }

            // put
            for (int i=0;i<WARMUPS;i++){ jdk.put(keys[i%OPS], vals[i%OPS]); }
            long t0=System.nanoTime(); for (int i=0;i<OPS;i++){ jdk.put(keys[i], vals[i]); } long t1=System.nanoTime(); double jdkNs=(t1-t0)/(double)OPS;
            for (int i=0;i<WARMUPS;i++){ mine.put(keys[i%OPS], vals[i%OPS]); }
            t0=System.nanoTime(); for (int i=0;i<OPS;i++){ mine.put(keys[i], vals[i]); } t1=System.nanoTime(); double myNs=(t1-t0)/(double)OPS;

            double jdkBytes = measureMemoryMapJdk(MEM_COUNT);
            double myBytes = measureMemoryMapMy(MEM_COUNT);

            rows.add(new Row("HashMap","JDK","put",n,jdkNs,jdkBytes));
            rows.add(new Row("HashMap","My","put",n,myNs,myBytes));

            // get
            for (int i=0;i<WARMUPS;i++){ jdk.get(keys[i%OPS]); }
            t0=System.nanoTime(); for (int i=0;i<OPS;i++){ jdk.get(keys[i]); } t1=System.nanoTime(); jdkNs=(t1-t0)/(double)OPS;
            for (int i=0;i<WARMUPS;i++){ mine.get(keys[i%OPS]); }
            t0=System.nanoTime(); for (int i=0;i<OPS;i++){ mine.get(keys[i]); } t1=System.nanoTime(); myNs=(t1-t0)/(double)OPS;

            rows.add(new Row("HashMap","JDK","get",n,jdkNs,jdkBytes));
            rows.add(new Row("HashMap","My","get",n,myNs,myBytes));
        }
    }

    // Simple memory probes — allocate MEM_COUNT Integer elements into each structure and measure usedBytes/n
    static double measureMemoryListJdk(int count) throws InterruptedException {
        Runtime rt = Runtime.getRuntime(); System.gc(); Thread.sleep(100);
        long before = rt.totalMemory() - rt.freeMemory();
        List<Integer> a = new ArrayList<>(count);
        for (int i=0;i<count;i++) a.add(Integer.valueOf(i));
        System.gc(); Thread.sleep(100);
        long after = rt.totalMemory() - rt.freeMemory();
        a = null; System.gc(); return (after - before) / (double) Math.max(1, count);
    }
    static double measureMemoryListMy(int count) throws InterruptedException {
        Runtime rt = Runtime.getRuntime(); System.gc(); Thread.sleep(100);
        MyArrayList<Integer> a = new MyArrayList<>(count);
        for (int i=0;i<count;i++) a.add(Integer.valueOf(i));
        System.gc(); Thread.sleep(100);
        long after = rt.totalMemory() - rt.freeMemory();
        a = null; System.gc(); return (after - (rt.totalMemory()-rt.freeMemory())) / (double) Math.max(1, count);
    }
    static double measureMemoryMapJdk(int count) throws InterruptedException {
        Runtime rt = Runtime.getRuntime(); System.gc(); Thread.sleep(100);
        java.util.HashMap<Integer,Integer> m = new java.util.HashMap<>(count*2);
        for (int i=0;i<count;i++) m.put(i,i);
        System.gc(); Thread.sleep(100);
        long after = rt.totalMemory() - rt.freeMemory();
        m = null; System.gc(); return (after - (rt.totalMemory()-rt.freeMemory())) / (double) Math.max(1, count);
    }
    static double measureMemoryMapMy(int count) throws InterruptedException {
        Runtime rt = Runtime.getRuntime(); System.gc(); Thread.sleep(100);
        MyHashMap<Integer,Integer> m = new MyHashMap<>();
        for (int i=0;i<count;i++) m.put(i,i);
        System.gc(); Thread.sleep(100);
        long after = rt.totalMemory() - rt.freeMemory();
        m = null; System.gc(); return (after - (rt.totalMemory()-rt.freeMemory())) / (double) Math.max(1, count);
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

    static class Row { String structure, impl, operation; int n; double nsPerOp, bytesPerElement; Row(String s,String i,String o,int n,double t,double b){ structure=s; impl=i; operation=o; this.n=n; nsPerOp=t; bytesPerElement=b; } }
}
