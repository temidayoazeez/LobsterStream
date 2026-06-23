#!/usr/bin/env zsh
# Run the BenchmarkCollections program and produce timeA.csv and memoryB.csv
# Usage:
#   ./run_benchmarks.sh [ops] [warmups] [sizes] [heap]
# Examples:
#   ./run_benchmarks.sh            # uses defaults (ops=1000000, warmups=1000000, sizes=1000,10000,100000,1000000,10000000, heap=8g)
#   ./run_benchmarks.sh 1000000 100000 "1000,10000,100000" 6g

set -euo pipefail

OPS=${1:-1000000}
WARMUPS=${2:-$OPS}
SIZES=${3:-"1000,10000,100000,1000000,10000000"}
HEAP=${4:-8g}

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

echo "Compiling BenchmarkCollections.java..."
javac BenchmarkCollections.java

LOG_FILE="$ROOT_DIR/benchmark_run.log"
echo "Starting benchmark: OPS=$OPS WARMUPS=$WARMUPS SIZES=$SIZES HEAP=-Xmx$HEAP" | tee "$LOG_FILE"

echo "Running java -Xmx${HEAP} BenchmarkCollections $OPS $WARMUPS \"$SIZES\""
# Run and tee output so you can inspect progress and keep a log
java -Xmx${HEAP} BenchmarkCollections $OPS $WARMUPS "$SIZES" 2>&1 | tee -a "$LOG_FILE"

echo "\nBenchmark finished."
if [ -f "$ROOT_DIR/timeA.csv" ]; then
  echo "Timing CSV: $ROOT_DIR/timeA.csv"
  echo "Last 20 lines of timeA.csv:"
  tail -n 20 "$ROOT_DIR/timeA.csv"
else
  echo "Timing CSV not found."
fi

if [ -f "$ROOT_DIR/memoryB.csv" ]; then
  echo "Memory CSV: $ROOT_DIR/memoryB.csv"
  echo "Contents of memoryB.csv:"
  cat "$ROOT_DIR/memoryB.csv"
else
  echo "Memory CSV not found."
fi

echo "Log file: $LOG_FILE"

echo "Done."
