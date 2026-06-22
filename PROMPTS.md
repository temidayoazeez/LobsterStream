# Part A
Write a Java program that measures the time per operation of ArrayList, LinkedList,
ArrayDeque, HashSet, TreeSet, HashMap, TreeMap, and PriorityQueue. For the relevant
operations (get(index), add-at-end, add-at-front, contains, add, put, get, containsKey,
offer, poll, peek): build the structure with n random elements generated on the fly for
n = 1_000, 10_000, 100_000, 1_000_000, 10_000_000; run 1,000,000 operations as an untimed
warm-up; then time 1,000,000 operations with System.nanoTime(); add every result into a
long 'sink' printed at the end so nothing is optimised away; report nanoseconds per
operation; compute the ratio between sizes and print a guessed Big-O; print a table and
also write timeA.csv with a header. Do not read any data from disk inside a timed loop.

# Part B
Note which structures cost far more per element than others, and be ready to explain where those bytes go (for example a TreeMap node carries two child references and a colour bit; a HashMap keeps a bucket array with spare slots).

# Part C
Add a background daemon thread that, once a second, prints the program's CPU load
(com.sun.management.OperatingSystemMXBean.getProcessCpuLoad), the used heap, the total and
free physical RAM (getTotalMemorySize / getFreeMemorySize), the live thread count, and the
total garbage-collection count and time.

# Part D
