# ClipboardConnect review and storage design

This review compares WorldEditSync with [IntellectualSites/ClipboardConnect](https://github.com/IntellectualSites/ClipboardConnect) at commit `d0cad85` (reviewed 2026-07-21). ClipboardConnect is GPL-3.0 while WorldEditSync is Apache-2.0, so no ClipboardConnect source code was copied. The implementation in this repository was designed independently from the observable behavior and architecture.

## What ClipboardConnect does well

- Redis is both durable-enough short-term storage and a low-latency notification bus. A server publishes a small player/server message after writing the clipboard, so another server does not have to wait for a polling interval.
- Clipboard keys have a configurable expiry. This puts a bound on retained player data and works naturally for temporary clipboards.
- It supports WorldEdit and FAWE and provides explicit save/load commands in addition to join/quit automation.
- Its guided setup and generated KeyDB Compose file lower the operational barrier for administrators unfamiliar with Redis.

## Differences we should preserve

- ClipboardConnect selects FAWE FAST format on FAWE servers and Sponge schematic format elsewhere. A mixed FAWE/WorldEdit network can therefore produce a format that the receiving server did not select. WorldEditSync keeps one canonical Sponge schematic representation so either implementation can consume the same stored object.
- ClipboardConnect writes through a Redis stream and removes the previous value first. WorldEditSync replaces payload and metadata atomically, retaining the old version if serialization or encryption fails before the write.
- Redis/WorldEdit work can run directly from join, quit, or pub/sub callbacks in ClipboardConnect. WorldEditSync keeps serialization, encryption, SQL/Redis/S3 I/O, and deserialization off Paper/Folia server threads and revalidates the player/clipboard identity before applying results.
- WorldEditSync enforces maximum payload size, SHA-256 integrity, authenticated encryption, version-consistent reads, bounded workers, retryable initialization, and polling fallback. These safeguards remain common to every storage adapter.

## Adopted ideas

The Redis adapter stores `hash`, `updated_at`, `size`, and encrypted `data` fields in one Redis hash. A Lua script atomically updates the fields, applies TTL, and publishes the player UUID. Every Paper server subscribes to the update channel for low latency and also polls as a recovery path after missed notifications or Redis reconnects. The same protocol works with Redis, Valkey, and KeyDB.

The configurable `database.ttl-minutes` option applies native expiry in Redis and conditional lazy deletion in SQL. Setting it to zero retains data indefinitely.

## Extensible storage boundary

All persistent modes implement `ClipboardStorage`:

- `initialize()` establishes a connection and creates required schema.
- `inspect(playerId)` reads only immutable hash/size/version metadata.
- `upload(...)` atomically replaces data and metadata.
- `download(playerId, expected)` returns only the exact inspected version and rejects concurrent replacement.
- `setUpdateListener(...)` optionally supplies push notifications; polling remains the baseline.
- `close()` releases pools, sockets, and subscriber threads.

`StorageSyncEngine` owns Paper/Folia scheduling, WorldEdit serialization, conflict checks, encryption verification, retries, and player lifecycle. A future MongoDB, Cassandra, or other adapter therefore does not need to duplicate the synchronization state machine.

## Implemented backends

| Backend | Storage primitive | Atomicity / race protection |
| --- | --- | --- |
| S3-compatible | Object plus metadata | Bounded object read and post-download hash verification |
| Redis / Valkey / KeyDB | Redis hash plus pub/sub | Atomic Lua write; metadata-qualified Lua read |
| MySQL / MariaDB | `LONGBLOB` row | Atomic upsert; metadata-qualified select |
| PostgreSQL | `BYTEA` row | `ON CONFLICT` upsert; metadata-qualified select |
| SQLite | `BLOB` row in WAL mode | Single-writer pool; metadata-qualified select |

SQLite is not a network database, and [WAL mode does not work over a network filesystem](https://sqlite.org/wal.html#overview). It is useful for multiple Paper processes on one host when they share the exact local database file. Use Redis, MySQL/MariaDB, or PostgreSQL for independent hosts.

The Redis setup wizard and manual save/load commands are useful usability ideas but are intentionally separate from the storage layer. They can be added later without changing stored data or backend contracts.
