# timeA.csv and memoryB.csv
java -Xms3g -Xmx3g BenchmarkCollections 10000 10000 "1000,10000,100000"

# scaleC.csv and scaleC.monitor.log
java LobsterStream

# compareD.csv and compareD_bigO.csv
java -Xms2g -Xmx2g CompareCollections 20000 5000 20000 "1000,10000,100000"