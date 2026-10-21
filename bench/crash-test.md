# Crash test (B6)

The question: after a hard kill, is every write that a client was told succeeded still there?

## Steps (what `bench/crash-test.sh` does)

1. `javac` compiles `bench/CrashWriter.java` into `target/bench/` (the JRE image has no compiler).
2. Create a fresh Docker volume `rfs-crash` and start the server on it:
   `java -jar /app/redis-from-scratch.jar --port 6379 --dir /data --appendonly yes --appendfsync <policy>`.
3. Start `CrashWriter` on the same Docker network. It sends `INCR counter`, waits for the reply, records the value,
   and repeats. Only one command is ever in flight, so "the last value received" is exactly the number of writes the
   client knows were acknowledged.
4. After 4 s: `docker kill -s KILL crash-server`. SIGKILL: no shutdown hook, no final flush.
5. The writer sees the connection drop and prints `acked=N`.
6. Start a new server container on the same volume. It replays the AOF before opening the port.
7. `redis-cli GET counter` is the recovered value. Lost = max(0, acknowledged − recovered).
8. Three runs for each of `always`, `everysec` and `no`.

## Observed (2026-10-22)

| Policy | Run | Acknowledged | Recovered | Lost |
|---|---:|---:|---:|---:|
| always | 1 | 719 | 720 | 0 |
| always | 2 | 702 | 703 | 0 |
| always | 3 | 703 | 704 | 0 |
| everysec | 1 | 42,351 | 42,351 | 0 |
| everysec | 2 | 42,952 | 42,952 | 0 |
| everysec | 3 | 43,118 | 43,118 | 0 |
| no | 1 | 43,709 | 43,709 | 0 |
| no | 2 | 42,284 | 42,284 | 0 |
| no | 3 | 45,929 | 45,930 | 0 |

Raw: [results/b6/crash.csv](results/b6/crash.csv). Every restart loaded the file without a truncation warning:
the kill always landed between commands. The truncated-tail repair itself is covered by `AofTest.truncatedTailIsRepaired`,
which cuts the last 7 bytes off a valid file.

## What it shows, and what it doesn't

- **Shown:** under every policy, nothing acknowledged was lost to a process kill. The AOF buffer is written to the
  kernel before replies go out (the same order as Redis's `beforeSleep()`), and the page cache outlives the process.
- **Shown:** under `always`, the extra write that is sometimes recovered (recovered = acknowledged + 1) is one the
  server had fsynced but whose reply never reached the client. That's correct: the client must treat it as unknown.
- **Not shown:** the difference between the policies. That needs the page cache itself to be lost (a power cut or a
  kernel crash), which a process kill doesn't do. `everysec` can then lose about a second of writes, and `no` whatever
  the kernel hadn't written back yet. Killing the Docker Desktop VM would simulate it; that isn't done here.
