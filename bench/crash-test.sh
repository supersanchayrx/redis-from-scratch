#!/usr/bin/env bash
# B6: what survives a kill -9 under each appendfsync policy.
#
# For each policy and run: start the server with the AOF on a Docker volume, run CrashWriter (INCR in a loop,
# one in flight, remembering the last acknowledged value) on the same network, SIGKILL the server mid-stream
# with `docker kill -s KILL` (no shutdown hook runs), restart it on the same volume and read the counter back.
# Results: bench/results/b6/crash.csv
#
# Usage (from the repo root):  mvn -q package -DskipTests && bench/crash-test.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1

REDIS_IMAGE=redis:8.10.2
JAVA_IMAGE=eclipse-temurin:21-jre
NET=rfs-bench
RUNS=${RUNS:-3}
WRITE_SECONDS=${WRITE_SECONDS:-4}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
if command -v cygpath >/dev/null 2>&1; then
  TARGET=$(cygpath -w "$ROOT/target")
else
  TARGET="$ROOT/target"
fi
OUT="$ROOT/bench/results/b6"
if command -v cygpath >/dev/null 2>&1; then
  javac -d "$(cygpath -w "$ROOT/target/bench")" "$(cygpath -w "$ROOT/bench/CrashWriter.java")"
else
  javac -d "$ROOT/target/bench" "$ROOT/bench/CrashWriter.java"
fi
mkdir -p "$OUT"
docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null

start() {
  docker run -d --name crash-server --network "$NET" --cpuset-cpus=2,3 -v rfs-crash:/data -v "$TARGET:/app:ro" \
    "$JAVA_IMAGE" java -jar /app/redis-from-scratch.jar --port 6379 --dir /data --appendonly yes --appendfsync "$1" >/dev/null
  for _ in $(seq 1 60); do
    if docker run --rm --network "$NET" "$REDIS_IMAGE" redis-cli -h crash-server ping 2>/dev/null | grep -q PONG; then return; fi
    sleep 1
  done
  echo "server did not start" >&2; docker logs crash-server >&2; exit 1
}

echo "policy,run,acknowledged,recovered,lost" > "$OUT/crash.csv"
for policy in always everysec no; do
  for run in $(seq 1 "$RUNS"); do
    docker rm -f crash-server crash-writer >/dev/null 2>&1 || true
    docker volume rm -f rfs-crash >/dev/null 2>&1 || true
    docker volume create rfs-crash >/dev/null
    start "$policy"
    # The JRE image has no compiler, so the writer is compiled on the host (above) and run as a class.
    docker run -d --name crash-writer --network "$NET" --cpuset-cpus=6,7 -v "$TARGET:/app:ro" \
      "$JAVA_IMAGE" java -cp /app/bench CrashWriter crash-server 6379 60 >/dev/null
    # Let the writer run for a few seconds, then pull the plug on the server.
    sleep "$WRITE_SECONDS"
    docker kill -s KILL crash-server >/dev/null
    docker wait crash-writer >/dev/null
    acked=$(docker logs crash-writer | sed -n 's/^acked=//p')
    if [ -z "$acked" ] || [ "$acked" = 0 ]; then
      echo "the writer produced no acknowledged writes:" >&2; docker logs crash-writer >&2; exit 1
    fi
    docker rm -f crash-server crash-writer >/dev/null
    start "$policy"
    recovered=$(docker run --rm --network "$NET" "$REDIS_IMAGE" redis-cli -h crash-server get counter)
    recovered=${recovered:-0}
    docker logs crash-server 2>&1 | grep -i -E "truncat|loaded" | sed "s/^/  [$policy run $run] /" || true
    docker rm -f crash-server >/dev/null
    lost=$((acked > recovered ? acked - recovered : 0))
    echo "$policy,$run,$acked,$recovered,$lost" | tee -a "$OUT/crash.csv"
  done
done
docker volume rm -f rfs-crash >/dev/null
