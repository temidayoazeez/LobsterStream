# timeA.csv and memoryB.csv
java -Xms2g -Xmx2g BenchmarkCollections 10000 10000 "1000,10000,100000"

# scaleC.csv and scaleC.monitor.log
java -Xms2g -Xmx2g LobsterStream 1

# compareD.csv and compareD_bigO.csv
java -Xms2g -Xmx2g CompareCollections 20000 5000 20000 "1000,10000,100000"