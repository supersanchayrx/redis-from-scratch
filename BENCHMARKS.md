# Benchmarks

Every number below comes from the CSVs in [`bench/results/`](bench/results/), produced by
[`bench/run-bench.sh`](bench/run-bench.sh) (B1–B5), [`bench/crash-test.sh`](bench/crash-test.sh) (B6) and summarised by
[`bench/Summarize.java`](bench/Summarize.java). Measured on 2026-10-22.

**Method.** Docker Desktop 29.3.1 on Windows 11 (WSL2 kernel 6.6), Intel i5-12450H (4P + 4E cores, 12 threads).
Each server runs alone in a Linux container pinned with `--cpuset-cpus=2,3`; `redis-benchmark` 8.10.2 runs in its own
container pinned to `--cpuset-cpus=6-9`, on the same Docker bridge network. `-r 100000 -n 1000000`, 50 clients unless
stated. Java: `eclipse-temurin:21-jre` (21.0.12), `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`, default GC (G1).
Redis 8.10.2 with `--save "" --appendonly no` unless stated. For each configuration: start the server, one untimed
warm-up run, then 3 timed runs with a `FLUSHALL` before each; tables show the **median of the 3**.

**Read this first.** Without pipelining, both servers sit close to what this machine's Docker network can carry:
real Redis tops out around 90–100k requests/s for every simple command, and giving `redis-benchmark` more threads
(`--threads 4`) made Redis *slower* (97k → 62k), not faster. So the unpipelined tables mostly measure the round trip,
and run-to-run noise is about ±5%. The pipelined table (B2) is the better view of what each server costs per command.

## B1. Throughput by command (50 clients, no pipelining)

| Test | This server (req/s) | Redis 8.10.2 (req/s) | This server as % of Redis |
|---|---:|---:|---:|
| PING_MBULK | 65,151 | 86,911 | 75% |
| SET | 68,842 | 97,571 | 71% |
| GET | 70,857 | 94,589 | 75% |
| INCR | 76,208 | 91,149 | 84% |
| LPUSH | 70,358 | 93,084 | 76% |
| RPUSH | 71,235 | 93,240 | 76% |
| LPOP | 70,289 | 91,133 | 77% |
| RPOP | 72,020 | 95,383 | 76% |
| HSET | 69,257 | 96,089 | 72% |
| ZADD | 64,408 | 101,245 | 64% |
| LRANGE_100 | 40,153 | 55,054 | 73% |
| MSET | 79,962 | 94,877 | 84% |

The Java server runs at roughly three quarters of Redis across the board, and even PING (no data structure at all)
is at 75%, so most of the gap is per-request overhead rather than any one data structure: every command allocates
its argument arrays, a `Key` wrapper, a lower-cased name for the table lookup and a value object, where Redis reuses
its query buffer and shared objects. ZADD trails most (64%): each insert allocates a skip-list node with two arrays
(forward pointers and spans) plus a `HashMap` entry, and the random scores from `-r` put every insert at a new place.

Caveat on ordering: B1 runs the 12 tests back to back in one `redis-benchmark` call. SET measured on its own
(the B5 "off" row below, same jar) came out at 83,703 req/s, 96% of Redis's 86,851 in the same setup. The B1 SET
figure is the conservative one.

## B2. Pipelining (`-P 16`, 50 clients)

| Test | Pipeline | This server (req/s) | Redis (req/s) | This server as % of Redis |
|---|---:|---:|---:|---:|
| SET | 1 | 68,842 | 97,571 | 71% |
| SET | 16 | 677,966 | 652,316 | 104% |
| GET | 1 | 70,857 | 94,589 | 75% |
| GET | 16 | 625,000 | 956,938 | 65% |

Pipelining 16 commands per round trip raises both servers by about 10×: the cost per command was mostly the
round trip, and pipelining pays it once per 16 commands. The parser runs every complete command in a read, and
replies go out in one write per client per loop iteration, which is what makes this work.
The runs are noisy (Java SET 628k–732k, Redis SET 572k–709k, Redis GET 775k–1.15M across the three runs), so SET is a
tie within noise. GET shows the real gap once the network is out of the way: Redis is about 1.5× faster per lookup
(its dict and shared reply objects versus a `HashMap` lookup that hashes a fresh `Key` and allocates the bulk reply header).

## B3. Client count (SET)

| Clients | This server (req/s) | Redis (req/s) | This server as % of Redis |
|---:|---:|---:|---:|
| 1 | 16,577 | 16,200 | 102% |
| 50 | 68,842 | 97,571 | 71% |
| 200 | 89,389 | 87,252 | 102% |

One client is pure round-trip latency (about 60 µs here), the same for both. More clients add parallelism only on
the client side: the server is still one thread, but it gets more commands per `select()` wake-up, so syscall cost
per command falls. Pipelining (B2) gets far more out of the same idea than adding clients does.

## B4. Latency (ms, from the B1 runs)

| Test | Server | p50 | p95 | p99 | max |
|---|---|---:|---:|---:|---:|
| SET | this server | 0.359 | 0.647 | 1.111 | 39.647 |
| SET | Redis | 0.263 | 0.383 | 0.567 | 7.135 |
| GET | this server | 0.351 | 0.543 | 0.943 | 7.255 |
| GET | Redis | 0.271 | 0.367 | 0.567 | 2.303 |

Medians are close (about 0.1 ms apart). The tail is where Java pays: p99 roughly doubles, and SET's max across the
three runs ranged 11–52 ms. B7 ties those spikes to G1's stop-the-world young-generation pauses, which stop every
client at once because there is one thread; Redis has no collector, so its tail stays tight.

## B5. AOF cost (SET, 50 clients)

| AOF | This server (req/s) | Redis (req/s) | This server as % of Redis |
|---|---:|---:|---:|
| off | 83,703 | 86,851 | 96% |
| appendfsync no | 91,308 | 92,030 | 99% |
| appendfsync everysec | 88,292 | 96,321 | 92% |
| appendfsync always | 4,926 | 5,065 | 97% |

`no` and `everysec` cost nothing measurable: the AOF write is one `write()` per loop iteration for all the commands
in it, and the differences between off / no / everysec are inside the ±5% noise. `always` drops both servers to
about 5k req/s, because each loop iteration waits for an fsync to the container's disk (about 6 ms each here).
Both servers make that bearable the same way, with **group commit**: everything written in one iteration is
fsynced once, and only then are any replies sent. Counted from `INFO` ([group-commit.txt](bench/results/b5/group-commit.txt),
50k SETs, `appendfsync always`):

| Clients | req/s | fsyncs | Writes per fsync |
|---:|---:|---:|---:|
| 1 | 179 | 50,000 | 1.0 |
| 50 | 5,237 | 1,773 | 28.2 |
| 200 | 15,980 | 409 | 122.2 |

With one client every write waits for its own fsync. With 200, one fsync covers 122 writes, and throughput rises
almost 90× on the same disk.

**The `everysec` fsync thread.** Redis runs the once-a-second fsync on a background thread (bio) so a slow disk can't
stall the event loop. This server does the same (`--aof-fsync-thread yes`, the default; the thread only ever calls
`force()` and never touches the keyspace). With it off, the fsync runs on the loop. Longest loop iteration from
`INFO`, over two back-to-back 1M-SET runs, three times each ([summary.csv](bench/results/b5-fsync-thread/summary.csv)):

| fsync on | Longest loop iteration | Max SET latency (first run) |
|---|---:|---:|
| the loop thread | 13.8 / 15.0 / 17.2 ms | 12.1 / 16.9 / 17.7 ms |
| the background thread | 9.6 / 10.0 / 13.8 ms | 7.3 / 9.7 / 14.3 ms |

A single fsync takes up to about 18 ms on this disk, and moving it off the loop takes that out of the stall budget.
What remains (about 10 ms) is mostly GC, see B4.

## B6. Crash test: `kill -9` mid-stream

[`bench/crash-test.sh`](bench/crash-test.sh): the server runs with the AOF on a Docker volume; a writer sends
`INCR counter` one at a time and records the last value the server acknowledged; after about 4 s the container is
killed with `docker kill -s KILL` (SIGKILL, no shutdown hook); the server is restarted on the same volume and
`GET counter` is compared with the last acknowledged value. Details in [bench/crash-test.md](bench/crash-test.md).

| Policy | Run | Writes acknowledged | Recovered after restart | Acknowledged but lost |
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

No acknowledged write was lost under any policy, and that is the expected result for a **process** kill: the AOF
buffer is written to the kernel (`write()`) before any reply is sent, so once a client has its reply the data is
in the OS page cache, which survives the process dying. The fsync policy only decides when the page cache reaches
the disk, which matters for a **power loss or kernel crash**. This test doesn't simulate one, so it does not show
`everysec` or `no` losing data. They can: up to about a second for `everysec` and up to the kernel's writeback delay
(around 30 s on Linux) for `no`. What it does show is that `always` never acknowledged a write that wasn't fsynced.

Recovered is sometimes one *more* than acknowledged (all three `always` runs, one `no` run). That is correct
behaviour: the last INCR was written (and under `always`, fsynced) but the kill landed before its reply reached the
client. The client never heard "OK", so it must treat that write as unknown, not as lost or as done.

Under `always` the one-at-a-time writer manages about 175 writes/s: each write waits for its own fsync
(about 5.7 ms), the 1-client row of the group-commit table above.

## B7. GC and tail latency

SET and GET (`-t set,get`, 1M requests each, 50 clients), same setup as B1, once with the default G1 and once with
generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`), each with `-Xlog:gc`. One warm-up run, then the log is cleared
and three timed runs follow. Raw CSVs and both GC logs: [bench/results/b7/](bench/results/b7/).

| GC | Run | SET req/s | SET p99 | SET max | GET max |
|---|---:|---:|---:|---:|---:|
| G1 | 1 | 87,192 | 0.807 | 6.4 | 3.4 |
| G1 | 2 | 84,674 | 0.815 | **16.8** | 4.0 |
| G1 | 3 | 86,155 | 0.879 | **25.2** | 4.0 |
| ZGC (generational) | 1 | 83,257 | 0.919 | 11.1 | 2.9 |
| ZGC (generational) | 2 | 79,859 | 0.951 | 5.3 | 3.5 |
| ZGC (generational) | 3 | 85,048 | 0.815 | 6.6 | 10.3 |

(latencies in ms)

The G1 log has exactly two stop-the-world pauses across the three timed runs, both young collections:
**15.3 ms** and **24.4 ms**. The two worst SET max latencies, 16.8 ms and 25.2 ms, are those pauses plus a
normal request. So B4's tail is the young-generation collector: every request in flight waits for it,
because there is only one thread to wait on. Each collection reclaims about 600 MB, so at this allocation rate the
heap fills every 30 s or so.

ZGC collects concurrently; its log at this level records no pauses, and its worst SET latency was 11 ms rather
than 25 ms, for about 3% less SET throughput (median 83,257 vs 86,155). Its remaining spikes are smaller and not tied
to logged collections. The real fix is to allocate less per command (see B1); a collector only changes how the cost
of allocation is paid.

## Not measured yet

- **B8. Memory per key:** 1M keys, Redis `used_memory` vs JVM heap after a full GC.
- A power-loss version of B6 (killing the Docker VM rather than the process).
