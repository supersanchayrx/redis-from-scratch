# redis-from-scratch

A Redis-compatible in-memory data store written from scratch in plain Java, to understand what makes Redis fast.
It speaks the real Redis protocol (RESP2), so the stock `redis-cli` and `redis-benchmark` from Redis 8.10.2 connect to
it unchanged. Everything runs on one `java.nio` event-loop thread, with no frameworks and no runtime dependencies.

## Results

Measured against real Redis 8.10.2 on the same machine, each server alone in a container pinned to the same two
CPUs. Method and every table: [BENCHMARKS.md](BENCHMARKS.md).

| | This server | Redis 8.10.2 |
|---|---:|---:|
| SET, 50 clients (B1, all 12 tests in one run) | 68,842 req/s (71%) | 97,571 req/s |
| GET, 50 clients | 70,857 req/s (75%) | 94,589 req/s |
| SET, 50 clients, pipeline 16 | 677,966 req/s (104%) | 652,316 req/s |
| GET, 50 clients, pipeline 16 | 625,000 req/s (65%) | 956,938 req/s |
| SET p50 / p99 latency | 0.36 / 1.11 ms | 0.26 / 0.57 ms |
| SET with `appendfsync always` | 4,926 req/s | 5,065 req/s |

- **Compatibility:** 302 commands, including error cases, produce byte-identical replies to Redis 8.10.2
  ([bench/compat-commands.txt](bench/compat-commands.txt), checked by [bench/RespDiff.java](bench/RespDiff.java)).
  `redis-benchmark` runs every default test it supports with no errors and no "Could not fetch server CONFIG" warning.
- **Crash recovery:** across 9 `kill -9` runs (3 per fsync policy), no acknowledged write was lost
  ([crash test](bench/crash-test.md)). Under `appendfsync always`, group commit puts 28 writes in each fsync at
  50 clients and 122 at 200.
- **Where Java loses:** per-request allocation (about 75% of Redis's unpipelined throughput, 65% on pipelined GET),
  and GC in the tail: p99 is twice Redis's, and the worst max latencies line up with G1 young-generation pauses (15–24 ms, B7).

## Run it

Requires JDK 21 and Maven.

```bash
mvn package
java -jar target/redis-from-scratch.jar --port 6380
redis-cli -p 6380 set hello world
redis-cli -p 6380 get hello
```

Flags use redis.conf names: `--port`, `--bind`, `--dir`, `--appendonly yes|no`, `--appendfsync always|everysec|no`,
`--hz`, `--loglevel`. Redis has no Windows build, so on Windows use the tools from the image:
`docker run --rm -it redis:8.10.2 redis-cli -h host.docker.internal -p 6380`.

`mvn test` runs 162 tests: parser splits, skip-list invariants, expiry with a fake clock, AOF replay and repair,
and end-to-end tests over real sockets.

### A session with the stock redis-cli

Commands piped one at a time into `redis-cli --no-raw` from the `redis:8.10.2` image. This is a selection from the
session; each reply is pasted as it came back.

```
127.0.0.1:6380> SET greeting "hello world" EX 100
OK
127.0.0.1:6380> TTL greeting
(integer) 99
127.0.0.1:6380> INCR visits
(integer) 1
127.0.0.1:6380> LPUSH queue job1 job2 job3
(integer) 3
127.0.0.1:6380> LRANGE queue 0 -1
1) "job3"
2) "job2"
3) "job1"
127.0.0.1:6380> HSET user:1 name Ada lang Java
(integer) 2
127.0.0.1:6380> HGETALL user:1
1) "name"
2) "Ada"
3) "lang"
4) "Java"
127.0.0.1:6380> ZADD leaderboard 3200 ada 2950 bob 4100 cy 2950 alice
(integer) 4
127.0.0.1:6380> ZRANGE leaderboard 0 -1 WITHSCORES
1) "alice"
2) "2950"
3) "bob"
4) "2950"
5) "ada"
6) "3200"
7) "cy"
8) "4100"
127.0.0.1:6380> ZRANK leaderboard ada
(integer) 2
127.0.0.1:6380> GET queue
(error) WRONGTYPE Operation against a key holding the wrong kind of value
```

Then the process was killed with `taskkill /F` (no shutdown hook) and restarted with the AOF on (log line without
its timestamp; the last reply is cut short here, and it matched the one above):

```
INFO DB loaded from append only file: 6 commands, 358 bytes in 0.020 seconds
127.0.0.1:6380> TTL greeting
(integer) 75
127.0.0.1:6380> GET visits
"2"
127.0.0.1:6380> ZRANGE leaderboard 0 -1 WITHSCORES
1) "alice"
2) "2950"
...
```

The TTL kept counting while the server was down, because the AOF stores absolute expiry times.

Pub/sub, with one `redis-cli` subscribed and another publishing (`PUBLISH news hello` returned 1). Output is raw
because the subscriber's stdout wasn't a terminal:

```
$ redis-cli -h host.docker.internal -p 6380 SUBSCRIBE news
subscribe
news
1
message
news
hello
```

## Design

```
                one thread: the event loop
 ┌─────────────────────────────────────────────────────────────────────┐
 │ select(timeout = time to the next timer)                            │
 │   accept  → non-blocking socket, TCP_NODELAY, OP_READ               │
 │   read    → ≤16 KB into the client's buffer                         │
 │             → incremental RESP parser → every complete command:     │
 │                 command table → arity → handler → keyspace          │
 │                 reply appended to the client's output queue         │
 │                 write command → effect form → AOF buffer            │
 │   write   → drain leftovers; OP_WRITE off once empty                │
 │ timers    → active expiry (10/s), everysec fsync, pub/sub limits    │
 │ before sleep                                                        │
 │   1. AOF buffer → file (+ fsync under "always")                     │
 │   2. one write() per client with replies queued                     │
 └─────────────────────────────────────────────────────────────────────┘
```

| Component | What it does |
|---|---|
| [Event loop](src/main/java/io/github/supersanchayrx/redis/server/EventLoop.java) | One `Selector` thread for every socket. Replies are queued and written once per iteration; `OP_WRITE` is set only while a client has bytes left over from a partial write (otherwise `select()` spins). One client's I/O error closes that client only |
| [RESP parser](src/main/java/io/github/supersanchayrx/redis/protocol/RespParser.java) | A state machine that keeps its place between reads, so a command split across TCP packets parses the same as one that arrived whole, and 16 pipelined commands in one packet all run. Inline commands too (`PING\r\n`, which `redis-benchmark`'s ping_inline sends). Redis's exact protocol errors; never allocates from an untrusted length |
| [Keyspace](src/main/java/io/github/supersanchayrx/redis/store/Keyspace.java) | `HashMap` from a binary-safe [`Key`](src/main/java/io/github/supersanchayrx/redis/store/Key.java) (content equality, cached hash, unsigned ordering like `memcmp`) to a sealed `Value` type. Emptied lists, hashes and sorted sets are deleted, as in Redis |
| [Expiry](src/main/java/io/github/supersanchayrx/redis/store/ExpiryCycle.java) | Lazy (every lookup checks first) plus Redis's active cycle: 10 times a second, sample 20 random keys with a TTL, delete the expired ones, repeat while more than 10% were stale, within 25% of the tick. Random sampling is O(1) through an [array-backed index](src/main/java/io/github/supersanchayrx/redis/store/Expires.java) |
| [Skip list](src/main/java/io/github/supersanchayrx/redis/ds/SkipList.java) | Sorted sets: ordered by (score, member bytes), max level 32, p = 1/4, a span on every forward pointer so ZRANK and ZRANGE by index are O(log n), plus a member → node map for O(1) ZSCORE |
| [Linked list](src/main/java/io/github/supersanchayrx/redis/ds/LinkedList.java) | Lists: doubly linked, O(1) at both ends, index access walks from the nearer end |
| [Pub/sub](src/main/java/io/github/supersanchayrx/redis/pubsub/PubSub.java) | Channels and glob patterns. A message is encoded once for all subscribers. A subscriber that stops reading is cut off above 32 MB queued (or 8 MB for 60 s), Redis's defaults |
| [AOF](src/main/java/io/github/supersanchayrx/redis/persist/Aof.java) | Write commands in their *effect form* (`SET … EX 10` is logged as `SET … PXAT <ms>`, EXPIRE as PEXPIREAT, an expiry as DEL), so a replay at any later time rebuilds the same keyspace. Written before replies (group commit), with `always` / `everysec` (fsync on a background thread, like Redis's bio) / `no` |
| [AOF loader](src/main/java/io/github/supersanchayrx/redis/persist/AofLoader.java) | Replays through the same parser and dispatcher before the port opens. A command cut off at the end of the file is truncated away with a warning; damage anywhere else refuses to start, with the byte offset |

Commands (92): strings (`GET` `SET` with `EX`/`PX`/`EXAT`/`PXAT`/`NX`/`XX`/`KEEPTTL`/`GET`, `MGET` `MSET` `INCR`
family, `APPEND` …), keys and TTLs (`DEL` `EXISTS` `TYPE` `KEYS` with globs, `EXPIRE` family with `NX`/`XX`/`GT`/`LT`, `TTL`
`PTTL` `PERSIST` …), lists (`LPUSH` … `LRANGE` `LINDEX` `LSET` `LREM` `LTRIM`), hashes (`HSET` … `HINCRBY`), sorted sets
(`ZADD` with all its options, `ZRANGE` by rank or score with `REV`/`LIMIT`/`WITHSCORES`, `ZRANK` `ZCOUNT` `ZPOPMIN` …),
pub/sub (`SUBSCRIBE` `PSUBSCRIBE` `PUBLISH` `PUBSUB` …), and server commands (`PING` `CONFIG GET` `INFO` `DBSIZE`
`FLUSHALL` `BGREWRITEAOF` `SHUTDOWN` …).

## What goes wrong, and what the server does

| What goes wrong | What the server does |
|---|---|
| Half a command arrives | The parser keeps its state and waits for more bytes |
| A client sends garbage | Replies with Redis's protocol error, then closes that connection; other clients don't notice |
| A client announces a 1 GB argument | Lengths over 512 MB are rejected; below that, the buffer grows only as bytes actually arrive |
| A client stops reading replies | Its output stays queued and `OP_WRITE` drains it when it reads again. A subscriber is disconnected over the output limits, so it can't run the server out of memory |
| A client disconnects mid-command | `read()` returns −1: the socket is closed and its subscriptions removed |
| The process is killed mid-write | On restart, a truncated last command is cut off and everything before it is replayed |
| The disk is slow | `always` waits on fsync every iteration (B5); `everysec` fsyncs on a background thread so the loop doesn't |
| The wall clock jumps forward | Keys expire early, as in Redis (expiry times are absolute) |
| A GC pause | Every client waits; it shows in max latency (B4) |

## What's not implemented

RDB snapshots, replication, cluster, transactions (`MULTI`/`EXEC`), Lua, RESP3 (`HELLO 3`), sets and streams,
blocking commands (`BLPOP`), `ZRANGE BYLEX`, `INCRBYFLOAT`, and more than one database (`SELECT 0` only).
The AOF is a single file; Redis 7+ uses a multi-part AOF directory with a manifest.
`BGREWRITEAOF` rewrites the file but blocks the loop while doing it: Redis rewrites in a forked child using
copy-on-write memory, and the JVM can't fork.

## Roadmap

- [x] Scope and design
- [x] Event loop: accept, non-blocking read/write, per-client buffers
- [x] Incremental RESP parser and reply encoder (pipelining-safe)
- [x] Strings and keyspace, checked against `redis-cli`
- [x] TTLs: lazy and active expiry
- [x] Lists
- [x] Hashes
- [x] Sorted sets on a skip list
- [x] Pub/sub
- [x] AOF append and replay on restart
- [x] Tests: parser split tests, skip-list invariants, AOF crash and replay
- [x] Benchmarks against real Redis
- [x] GC and tail latency: G1 vs generational ZGC (B7)
- [ ] Memory per key vs Redis (B8)

## References

- Redis protocol specification: https://redis.io/docs/latest/develop/reference/protocol-spec/
- William Pugh, *Skip Lists: A Probabilistic Alternative to Balanced Trees* (CACM 1990)
- Redis persistence (AOF): https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/
- How Redis expires keys: https://redis.io/docs/latest/commands/expire/
