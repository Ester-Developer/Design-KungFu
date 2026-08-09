# Kung-Fu Chess — Scalable Server Design

## 1. Where we are today

The current server (`ChessWebSocketServer` + `RoomManager` + `Matchmaker`, all in `com.kungfuchess.server`) is a **single JVM process**: one WebSocket port, one in-memory map of rooms, one SQLite file (`users.db`) for accounts and ELO. Every game gets its own isolated `GameEngine`/`Room` (fixed a real bug where all players used to share one global board), a 150ms tick loop keeps state broadcasts smooth, and ELO/rooms/disconnect-grace all work end-to-end. This is fine for a handful of concurrent games on one machine — it is nowhere near enough for the scale below.

## 2. Answers to the four scaling questions

### 2.1 — Would SQLite work for 100M registered users?

**No.** SQLite is a single file with **single-writer** semantics — only one process can write to it at a time, and it has no network protocol, so it cannot be shared across multiple Auth/Rating service instances running on different machines. It also has no built-in replication, so a single disk failure loses the user database entirely.

**Recommendation: PostgreSQL**, run as a managed/clustered service:
- One **primary** for writes (registration, ELO updates, match history inserts).
- **Read replicas** for read-heavy traffic (profile lookups, leaderboards) — reads vastly outnumber writes.
- **Sharding by user-id range** once a single primary's write throughput becomes the bottleneck at very large user counts.

PostgreSQL fits because accounts/ELO/match history are relational and need transactional integrity (e.g. an ELO update touches two rows — winner and loser — atomically); a NoSQL store buys nothing here and loses the transaction guarantee.

### 2.2 — Is one server enough for 10M concurrent players? How do multiple servers find each other's players and rooms?

**No**, one process cannot hold 10M live WebSocket connections plus run every game's real-time tick loop. The fix is **horizontal sharding of game rooms across many server processes ("shards")**, fronted by stateless gateway pods, with **Redis as the shared room registry**:

```
Clients
  │ REST (login/rooms/history)      │ WebSocket (moves/state)
  ▼                                  ▼
API Gateway (stateless, N pods)   WS Gateway (stateless, N pods)
  │                                  │
  ▼                                  │  looks up room_id → shard in Redis
Auth Service ── PostgreSQL           │  on every join/reconnect
                                      ▼
                          Game Server Shard (owns this room's
                          authoritative GameEngine for its lifetime)
```

- When a room is created (or a match is found), the **Game Allocator** picks the least-loaded shard (via heartbeats each shard writes to Redis: `shard:worker:{id} → {host, port, active_rooms}`) and writes `room:{room_id} → {shard_host, shard_port}` into Redis.
- **Any** WS Gateway pod, on **any** node, resolves that same Redis key — so "everyone can play with everyone" and "anyone can join any room" fall out for free: the gateway a client happens to connect to doesn't need to already know about the room, it just asks Redis. Redis is the one thing every gateway and every shard shares, which is exactly why it's the registry rather than a bespoke "which-shard" service.
- Reconnects work the same way: a dropped client reconnects through *any* gateway, which re-resolves the same `room_id` and reattaches to the same shard — no game state is lost, because the gateway never held any state to lose.

**Role split** (who does what):
| Component | Responsibility | Holds state? |
|---|---|---|
| API Gateway | Login, room CRUD, history — REST only | No |
| WS Gateway | Terminates the live socket, relays moves/state, resolves `room_id → shard` in Redis | No |
| Matchmaker | ELO queue, pairs players | Only the queue (in Redis) |
| Game Allocator | Picks a shard for a new room, writes the registry entry | No |
| Game Server Shard | Runs the authoritative `GameEngine` for its assigned rooms — the **only** place game rules are ever decided | Yes — the live game |
| PostgreSQL | Users, ELO, match history | Yes — durable |
| Redis | Sessions, room→shard registry, matchmaking queue, shard heartbeats | Yes — ephemeral/hot |

The client never decides game rules, and neither does either Gateway — the `GameEngine` inside the owning shard is the single source of truth, exactly as it is today in the single-process version.

### 2.3 — Network traffic from 10M concurrent players moving every ~2 seconds

Assumptions: 10,000,000 concurrent players, 1 move every 2s (0.5 moves/sec/player), ~200 bytes per message (compact JSON: room id, move, timestamp, piece), roughly symmetric traffic (one state broadcast out per move in, for a 2-player room).

| Metric | Value |
|---|---|
| Inbound (moves) | 10,000,000 × 0.5 = **5,000,000 msg/s** |
| Outbound (state broadcasts) | ≈ **5,000,000 msg/s** |
| Bandwidth per direction | 5,000,000 × 200 B = 1,000,000,000 B/s ≈ **8 Gbps** |
| Total (both directions) | ≈ **16 Gbps** |

**Is that a lot?** For a *single machine* — yes, 16 Gbps would saturate a typical 10 Gbps NIC outright, and the CPU cost of serializing/handling 10M msg/s on one process is impossible regardless of network capacity. Spread across, say, 300–500 shard/gateway pods, though, that's only **~30–50 Mbps per pod** — trivial for standard cloud networking. This is *the* argument for horizontal sharding rather than a bigger single box: no single machine's NIC or CPU could carry this load no matter how large it is.

### 2.4 — Games last 30–90 seconds — what does that mean for the docker roles?

Short, bursty room lifetimes shape several design choices:

- **No mid-game migration needed.** A room is pinned to whichever shard worker created it for its *entire* lifetime (worst case ~90s) — there's no need to build live game-state migration between shards, which would be real complexity for a 90-second-lived object.
- **High room turnover ⇒ autoscale on active-room count, not raw CPU.** Because rooms churn every ~30-90s, a shard's load looks more like a queue-depth metric than a steady CPU curve; the Game Allocator's "least active rooms" heuristic and the shard's own HPA should scale on `active_rooms`, which reacts fast to bursts (e.g. everyone finishing a match at once).
- **Stateless gateways can restart/redeploy freely, mid-game state can't.** Because API/WS Gateways hold no game state, they can be scaled up/down or rolled without any coordination. Game Server Shards *do* hold live state, so their rollout/shutdown must be graceful (finish in-flight rooms — at most ~90s to drain — before terminating), unlike the gateways which can be killed instantly.
- **One process, many worker rooms per shard.** Since a single game is cheap (short-lived, small board state), a shard container should run many rooms concurrently — Java's real threads (unlike Python's GIL-bound processes) let one JVM shard use every core directly, so unlike a Python implementation there's no need for one-OS-process-per-core; a shard can be a single JVM with an internal thread pool, one room per thread (or an async loop), simplifying deployment.

## 3. Target architecture (Java, matching the diagram reviewed with the course)

| Service | Responsibility | Tech |
|---|---|---|
| **API Gateway** | Login, register, room CRUD/history — REST only | Java, `com.sun.net.httpserver` (no new framework dependency) |
| **WS Gateway** | Terminates client WebSocket, validates session, relays to the resolved shard | Java, `Java-WebSocket` (already a dependency) |
| **Matchmaker** | ELO-bucketed queue, pairs players, publishes match events | Java |
| **Game Allocator** | Picks least-loaded shard, writes the Redis registry entry | Java |
| **Game Server Shard** | Runs authoritative `GameEngine`/`Room` per assigned game (reuses today's engine/rules code unchanged) | Java |
| **Observability** | Health checks, structured logs (already have `ActivityLogger`), basic metrics | Java + existing logging |

**Internal event bus**: NATS carries only low-volume control-plane events ("matched", "allocated") between Matchmaker/Allocator/Gateways — not live gameplay traffic, which flows directly WS Gateway → Shard once Redis resolves the address (matches Section 2.3's traffic analysis: that path must stay as short as possible).

**No separate Rating service** — unlike some other implementations of this assignment, ELO updates are written directly by the Game Server Shard to PostgreSQL at game end, alongside the match result. This matches the reference diagram reviewed with the course and avoids an extra network hop for a value only written once per game.

**Rollout plan** (small-working-thing-first, per the course's own guidance):
1. `docker-compose.yml` bringing up Redis, PostgreSQL, NATS, and one instance each of the six services above — enough to prove a client can log in, create/join a room by code, play a full game, and see ELO persisted, end-to-end, on one machine.
2. Multiple Game Server Shard instances + the Game Allocator's least-loaded selection, to prove horizontal sharding actually routes correctly.
3. Kubernetes/K3s manifests mirroring the same service list, adding HPA (scaling on active-room-count per Section 2.4) and multi-replica gateways.

Phase 1 (this repo's current single-process server) stays available as the offline/local-practice path — nothing about it changes; the scaled architecture is an alternative deployment of the *same* game rules code (`GameEngine`, `RuleEngine`, `RealTimeArbiter`), not a rewrite of them.
