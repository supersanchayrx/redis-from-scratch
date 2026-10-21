# redis-from-scratch

A Redis-compatible in-memory data store written from scratch in plain Java, to understand what makes Redis fast. It speaks the real Redis protocol (RESP), so the stock `redis-cli` and `redis-benchmark` connect to it unchanged. Everything runs on a single-threaded `java.nio` event loop, with no frameworks and no runtime dependencies.

> **Status:** work in progress. The design and roadmap are below. Benchmark results will be added here once they are measured.

## Why

Redis serves hundreds of thousands of requests per second on one core. It doesn't use threads or locks for that. It relies on:

- **Non-blocking I/O.** One thread multiplexes every client socket through `epoll`/`kqueue`, so no thread ever sits waiting on a slow client.
- **No locks.** Commands run one at a time on that thread. Each one is atomic for free, and there's no contention.
- **The right data structure per type.** Hash tables for keys, skip lists for sorted sets, and linked lists for lists, each chosen for its access pattern.
- **Cheap durability.** An append-only log of write commands, replayed on restart.

This project rebuilds each of those pieces in Java, then measures how close it gets to the real thing.

## Design

| Component | What it does |
|---|---|
| **Event loop** | One `Selector` thread accepts connections and reads and writes every client socket without blocking. Each client keeps its own read and write buffers, so partial reads and pipelined commands both work |
| **RESP parser** | Incremental parser for RESP2 arrays and bulk strings. It resumes where it stopped when a command arrives split across TCP reads |
| **Keyspace** | A hash map from key to typed value, with a separate expiry table |
| **Expiry** | Lazy (checked when a key is read) plus active: a periodic task samples keys that have a TTL and deletes the expired ones, the same way Redis does |
| **Strings** | `GET` `SET` (`EX`/`PX`/`NX`/`XX`) `DEL` `EXISTS` `INCR` `DECR` `MGET` `MSET` `EXPIRE` `TTL` `PERSIST` |
| **Lists** | Doubly linked list: `LPUSH` `RPUSH` `LPOP` `RPOP` `LRANGE` `LLEN` |
| **Hashes** | `HSET` `HGET` `HDEL` `HGETALL` `HLEN` |
| **Sorted sets** | Hand-written **skip list** plus a member-to-score map: `ZADD` `ZREM` `ZSCORE` `ZRANK` `ZRANGE` (`WITHSCORES`) `ZCARD`, with O(log n) insert, delete and rank |
| **Pub/sub** | `SUBSCRIBE` `UNSUBSCRIBE` `PUBLISH`. A subscribed connection switches into push mode |
| **Persistence (AOF)** | Every write command is appended to `appendonly.aof` in RESP form, then replayed at startup to rebuild the keyspace. The fsync policy is configurable (`always` / `everysec` / `no`) |

## Run

Requires JDK 21 and Maven.

```bash
mvn package
java -jar target/redis-from-scratch.jar --port 6380
redis-cli -p 6380 set hello world
redis-cli -p 6380 get hello
```

## Benchmarks (planned)

Using `redis-benchmark` against this server and against real Redis on the same machine:

- `SET` / `GET` / `INCR` / `LPUSH` / `ZADD` throughput (ops/s) at 1, 50 and 200 clients;
- the speedup from pipelining (`-P 16`);
- p50 / p99 latency;
- AOF cost: throughput with `appendfsync always` vs `everysec`.

The method and raw results will live in `BENCHMARKS.md`.

## Roadmap

- [x] Scope and design
- [ ] Event loop: accept, non-blocking read/write, per-client buffers
- [ ] Incremental RESP parser and reply encoder (pipelining-safe)
- [ ] Strings and keyspace, checked against `redis-cli`
- [ ] TTLs: lazy and active expiry
- [ ] Lists
- [ ] Hashes
- [ ] Sorted sets on a skip list
- [ ] Pub/sub
- [ ] AOF append and replay on restart
- [ ] Tests: parser fuzzing, skip-list invariants, AOF crash and replay
- [ ] Benchmarks against real Redis

## Planned structure

```
src/main/java/.../
  server/     # event loop, client connections, buffers
  protocol/   # RESP parser and encoder
  store/      # keyspace, expiry, typed values
  ds/         # skip list, linked list
  commands/   # one class per command family, behind a Command interface
  persist/    # AOF writer and replay
  pubsub/     # channels and subscribers
src/test/java/...
```

## References

- Redis protocol specification: https://redis.io/docs/latest/develop/reference/protocol-spec/
- William Pugh, *Skip Lists: A Probabilistic Alternative to Balanced Trees* (CACM 1990)
- Redis persistence (AOF): https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/
