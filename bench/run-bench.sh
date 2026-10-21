#!/usr/bin/env bash
# Reproduces BENCHMARKS.md tables B1-B5.
#
# Each server runs alone in a Linux container pinned to SERVER_CPUS; redis-benchmark 8.10.2 runs in its own
# container pinned to BENCH_CPUS, on the same Docker network. For each configuration: start the server,
# one untimed warm-up run, then RUNS timed runs with a FLUSHALL before each. Raw CSVs go to bench/results/.
#
# Usage (from the repo root, Git Bash or Linux):  mvn -q package -DskipTests && bench/run-bench.sh [table...]
# Tables: b1 b2 b3 b5 (B4 is read from B1's CSVs). With no arguments, all of them.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # stop Git Bash rewriting /app and friends into Windows paths

REDIS_IMAGE=redis:8.10.2
JAVA_IMAGE=eclipse-temurin:21-jre
NET=rfs-bench
SERVER_CPUS=${SERVER_CPUS:-2,3}
BENCH_CPUS=${BENCH_CPUS:-6-9}
N=${N:-1000000}
R=100000
RUNS=${RUNS:-3}
JAVA_OPTS=${JAVA_OPTS:--Xms1g -Xmx1g -XX:+AlwaysPreTouch}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
if command -v cygpath >/dev/null 2>&1; then TARGET=$(cygpath -w "$ROOT/target"); else TARGET="$ROOT/target"; fi
OUT="$ROOT/bench/results"
mkdir -p "$OUT"

docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null

stop_server() { docker rm -f bench-server >/dev/null 2>&1 || true; }

# start_server java|redis [extra server flags...]
start_server() {
  local kind=$1; shift
  stop_server
  if [ "$kind" = java ]; then
    docker run -d --name bench-server --network "$NET" --cpuset-cpus="$SERVER_CPUS" -v "$TARGET:/app:ro" \
      "$JAVA_IMAGE" java $JAVA_OPTS -jar /app/redis-from-scratch.jar --port 6379 --dir /tmp --loglevel warning "$@" >/dev/null
  else
    docker run -d --name bench-server --network "$NET" --cpuset-cpus="$SERVER_CPUS" \
      "$REDIS_IMAGE" redis-server --save "" --appendonly no --dir /tmp "$@" >/dev/null
  fi
  for _ in $(seq 1 60); do
    if docker run --rm --network "$NET" "$REDIS_IMAGE" redis-cli -h bench-server ping 2>/dev/null | grep -q PONG; then
      return
    fi
    sleep 1
  done
  echo "server did not come up" >&2; docker logs bench-server >&2; exit 1
}

flushall() { docker run --rm --network "$NET" "$REDIS_IMAGE" redis-cli -h bench-server flushall >/dev/null; }

bench() { docker run --rm --network "$NET" --cpuset-cpus="$BENCH_CPUS" "$REDIS_IMAGE" \
  redis-benchmark -h bench-server -p 6379 -r "$R" --csv "$@"; }

# measure <table> <label> <redis-benchmark flags...>: warm-up, then RUNS timed runs
measure() {
  local table=$1 label=$2; shift 2
  mkdir -p "$OUT/$table"
  echo "[$table] $label: warm-up"
  bench "$@" >/dev/null
  for run in $(seq 1 "$RUNS"); do
    flushall
    echo "[$table] $label: run $run"
    bench "$@" > "$OUT/$table/$label-run$run.csv"
  done
}

B1_TESTS=ping_mbulk,set,get,incr,lpush,rpush,lpop,rpop,hset,zadd,lrange_100,mset

b1() { for s in java redis; do start_server $s; measure b1 "$s" -t "$B1_TESTS" -n "$N" -c 50; done; }
b2() { for s in java redis; do start_server $s; measure b2 "$s-P16" -t set,get -n "$N" -c 50 -P 16; done; }
b3() {
  for s in java redis; do
    start_server $s
    measure b3 "$s-c1" -t set -n $((N / 5)) -c 1
    measure b3 "$s-c200" -t set -n "$N" -c 200
  done
}
b5off() {
  start_server java; measure b5 "java-off" -t set -n "$N" -c 50
  start_server redis; measure b5 "redis-off" -t set -n "$N" -c 50
}
b5() {
  b5off
  for policy in no everysec always; do
    start_server java --appendonly yes --appendfsync "$policy"
    measure b5 "java-$policy" -t set -n "$N" -c 50
    start_server redis --appendonly yes --appendfsync "$policy"
    measure b5 "redis-$policy" -t set -n "$N" -c 50
  done
}

docker version --format 'docker {{.Server.Version}}' > "$OUT/environment.txt"
docker info --format 'cpus={{.NCPU}} mem={{.MemTotal}} kernel={{.KernelVersion}}' >> "$OUT/environment.txt"
echo "server_cpus=$SERVER_CPUS bench_cpus=$BENCH_CPUS n=$N r=$R runs=$RUNS java_opts=$JAVA_OPTS" >> "$OUT/environment.txt"

tables=${*:-b1 b2 b3 b5}
for t in $tables; do "$t"; done
stop_server
echo "done: results in $OUT"
