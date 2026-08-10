<div align="center">

# ♟️ Kung-Fu Chess

**Real-time multiplayer chess. No turns. No mercy.**

Both players move *simultaneously* — there's no waiting for your opponent. Every piece has its own cooldown, so speed and timing matter as much as strategy.

![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)
![WebSocket](https://img.shields.io/badge/Networking-WebSocket-blue)
![Docker](https://img.shields.io/badge/Deploy-Docker%20Compose-2496ED?logo=docker&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Deploy-Kubernetes-326CE5?logo=kubernetes&logoColor=white)
![ELO](https://img.shields.io/badge/Matchmaking-ELO-brightgreen)

</div>

---

## 📥 Download & Play

**Requirements:** [JDK 17](https://adoptium.net/) and Git.

```bat
:: 1. Clone the repository
git clone https://github.com/Ester-Developer/Design-KungFu.git
cd Design-KungFu

:: 2. Build the project
build_and_test.bat

:: 3. Start the server (leave this window open)
run_server.bat

:: 4. Start the client (open a new window — run it once per player)
run_client.bat
```

Log in (an account is created automatically on first login), then either hit **Play** for an ELO-matched quick game, or open **Room** to create/share a 4-character code with a friend.

> Want the full scaled cloud version (API Gateway, WS Gateway, Matchmaker, sharded game servers, Redis/PostgreSQL/NATS)? See [Running the Scaled Architecture](#-running-the-scaled-architecture) below.

---

## 🖼️ Screenshots

<table>
<tr>
<td align="center" width="50%">
<img src="https://raw.githubusercontent.com/Ester-Developer/Design-KungFu/main/docs/images/welcome-splash.png" alt="Welcome screen" width="100%"><br>
<sub>Welcome screen</sub>
</td>
<td align="center" width="50%">
<img src="https://raw.githubusercontent.com/Ester-Developer/Design-KungFu/main/docs/images/home-menu.png" alt="Home menu" width="100%"><br>
<sub>Play or open a Room</sub>
</td>
</tr>
<tr>
<td align="center">
<img src="https://raw.githubusercontent.com/Ester-Developer/Design-KungFu/main/docs/images/room-dialog.png" alt="Room dialog" width="100%"><br>
<sub>Create or join a room by code</sub>
</td>
<td align="center">
<img src="https://raw.githubusercontent.com/Ester-Developer/Design-KungFu/main/docs/images/quick-match-searching.png" alt="Quick match searching" width="100%"><br>
<sub>ELO-based quick matchmaking</sub>
</td>
</tr>
<tr>
<td align="center">
<img src="https://raw.githubusercontent.com/Ester-Developer/Design-KungFu/main/docs/images/match-start.png" alt="Match start" width="100%"><br>
<sub>A fresh match — room code, players, and live ELO shown</sub>
</td>
<td align="center">
<img src="https://raw.githubusercontent.com/Ester-Developer/Design-KungFu/main/docs/images/cooldown-overlay.png" alt="Piece cooldown overlay" width="100%"><br>
<sub>Per-piece cooldown overlay after a move</sub>
</td>
</tr>
<tr>
<td align="center">
<img src="https://raw.githubusercontent.com/Ester-Developer/Design-KungFu/main/docs/images/match-in-progress.png" alt="Match in progress" width="100%"><br>
<sub>Live move log for both players</sub>
</td>
<td align="center">
<img src="https://raw.githubusercontent.com/Ester-Developer/Design-KungFu/main/docs/images/game-over.png" alt="Game over" width="100%"><br>
<sub>Game over — capture the king to win</sub>
</td>
</tr>
</table>

---

## 🎯 Game Features

* **Simultaneous real-time action** — both players can move any piece at any moment; there are no turns.
* **Per-piece cooldowns** — a move triggers a rest period on that piece (short for jumps, longer for regular moves) before it can act again.
* **Win condition** — capturing the opposing king ends the match instantly.
* **ELO rating** — every account has a rating that updates after each game (K-factor 32).
* **Rooms** — create a room and share its 4-character code, or join one with a code; a third player who joins becomes a spectator.
* **Quick Match** — ELO±100 matchmaking queue that pairs opponents automatically.
* **Disconnect grace with auto-reconnect** — a disconnected player has 20 seconds to reconnect before the match is forfeited; the client retries automatically in the background, and if it succeeds the opponent's countdown cancels and play resumes with no state lost.
* **Match history** — every finished game and its full move log is persisted (scaled architecture only) and queryable via the API Gateway's `/history` endpoint.
* **Live activity log** — client and server both log session/game activity to file for debugging and review.

---

## 🏗️ Architecture

The project ships in two layers, matching a staged rollout — a working single-process server first, then a scaled split.

### Phase 1 — Single-process server

One JVM (`ServerMain`) hosts the WebSocket endpoint, an in-memory `RoomManager` (one isolated `GameEngine` per room), the ELO/Quick-Match matchmaker, and a SQLite-backed user store. This is the simplest way to run the game locally — see [Download & Play](#-download--play) above.

### Phase 2 — Scaled cloud architecture

The single process is split into six independent Java services, so gateways, matchmaking/allocation, and game execution can each scale on their own. The **client never decides game rules, and neither does either gateway** — the `GameEngine` inside the Game Server Shard that owns a room is the single source of truth, exactly as in Phase 1.

```mermaid
flowchart TB
    C(["🖥️ Client<br/>Swing GUI"])

    subgraph GW["Stateless Gateways"]
        AG["API Gateway  :8080<br/>REST — login / register / history"]
        WSG["WS Gateway  :5555<br/>live connections, room + queue routing"]
    end

    subgraph SVC["Matching &amp; Allocation"]
        AUTH["Auth Service  :8000"]
        MM["Matchmaker  :8003<br/>ELO±100 queue"]
        GA["Game Allocator  :8004<br/>picks least-loaded shard"]
    end

    GS["Game Server Shard  :5556+<br/>authoritative GameEngine<br/>(single source of truth)"]

    R[("Redis<br/>sessions · room→shard<br/>queue · heartbeats")]
    PG[("PostgreSQL<br/>users · ELO<br/>games · moves")]
    N{{"NATS<br/>kfc.matched.&lt;user&gt;"}}

    C -- "① REST login / register" --> AG
    AG --> AUTH --> PG
    AG -. issues token .-> R

    C -- "② WS: TOKEN, then ROOM_CREATE/JOIN or PLAY" --> WSG
    WSG -. validate + room registry .-> R
    WSG -- Quick Match --> MM
    MM -. ELO queue .-> R
    MM --> GA
    WSG -- room ready --> GA
    GA -. reads shard heartbeats .-> R
    MM -- "③ publish match result" --> N
    N -- "④ notify" --> WSG
    WSG -- "⑤ SHARD_CONNECT redirect" --> C

    C -- "⑥ WS: SHARD_JOIN, moves" --> GS
    GS -- ELO + match history --> PG
    GS -. heartbeat .-> R
```

| Service | Port | Responsibility |
| :--- | :--- | :--- |
| **API Gateway** | `8080` | REST entry point — `/login`, `/register`, `/history`; forwards auth to the Auth Service and issues a short-lived Redis session token. |
| **Auth Service** | `8000` | Credential validation and ELO storage against PostgreSQL. |
| **WS Gateway** | `5555` | Validates session tokens, handles room create/join and Quick Match requests, and redirects players to an allocated game shard. |
| **Matchmaker** | `8003` | ELO±100 queue (Redis sorted set); pairs players, allocates a shard, and publishes the result over NATS to whichever gateway holds each player's socket. |
| **Game Allocator** | `8004` | Picks the least-loaded game shard (via shard heartbeats in Redis) for a new room. |
| **Game Server Shard** | `5556+` | Hosts the authoritative `GameEngine` for each room it's assigned and writes final ELO + match history to PostgreSQL. |

**Why each store is what it is:**
- **Redis** holds only *ephemeral, hot* state — session tokens, the room→shard registry, the matchmaking queue, shard heartbeats — never anything that needs to survive a restart.
- **PostgreSQL** holds *durable* state — users, ELO, match/move history — and replaces Phase 1's SQLite specifically because SQLite is single-writer and can't be shared across multiple service instances (see `Server_Design.md` §2.1).
- **NATS** carries exactly one thing: the Matchmaker notifying whichever WS Gateway instance holds a given player's socket that they've been paired — the one place in the system where a result has to reach a *specific other instance* asynchronously, rather than a synchronous REST call or a shared Redis key both sides already know to look at.

The handshake is a two-hop redirect: client → WS Gateway (auth + room/queue routing) → once two players are paired (by room code or Quick Match), the responsible service calls the Game Allocator, then tells both clients where to reconnect → client → Game Server Shard (authoritative gameplay).

Every service exposes `GET /health` and `GET /metrics`; a concurrent load-test tool (`run_load_test.bat`) drives both the room and Quick Match flows in parallel and reports success rate and pairing latency.

See [`Server_Design.md`](Server_Design.md) for the full design rationale (database choice, the Redis registry, traffic estimates, and how short match lifetimes shape the autoscaling/no-migration approach) and rollout plan.

---

## 🐳 Running the Scaled Architecture

**Requirements:** everything above, plus [Docker Desktop](https://www.docker.com/products/docker-desktop/).

```bat
:: Builds and starts Redis, PostgreSQL, NATS, and all six Java services
docker compose up --build -d

:: Connect with the cloud client
run_cloud_client.bat
```

Run `run_cloud_client.bat` again for a second player. Both **Room** (create/share a code) and **Play (Quick Match)** work against this stack.

> Redis's host-side port defaults to `6380` rather than the standard `6379` — see the comment in `docker-compose.yml` if you need to change it. Services talking to each other *inside* the Docker network still use the standard `6379`.

<details>
<summary>Running the services without Docker (each in its own window)</summary>

```bat
docker compose up -d redis postgres nats
run_auth_service.bat
run_api_gateway.bat
run_game_allocator.bat
run_matchmaker.bat
run_game_shard.bat
run_ws_gateway.bat
run_cloud_client.bat
```
</details>

### Load testing

```bat
run_load_test.bat --room-pairs 5 --quick-pairs 5 --api http://localhost:8080 --ws ws://localhost:5555
```

Spins up N room-code pairs and M Quick Match pairs concurrently and reports success rate plus pairing→shard-redirect latency (min/p50/p95/max).

---

## ☸️ Running on Kubernetes

Manifests for the same nine components live in [`k8s/`](k8s/) — a `Namespace`, a shared `ConfigMap` for service-discovery env vars, `Deployment`+`Service` pairs for Redis/NATS/the six Java services, and a `StatefulSet`+`PersistentVolumeClaim` for PostgreSQL (the one durable component). Every service's `readinessProbe`/`livenessProbe` hits its `/health` endpoint.

```bat
:: Build the one shared image all six Java services run from
docker build -t kfchess:latest .

:: Apply everything (numbered filenames control apply order)
kubectl apply -f k8s/

:: Watch it come up
kubectl get pods -n kfchess -w
```

`api-gateway`, `ws-gateway`, and `game-shard` are `LoadBalancer` Services (client-facing — on Docker Desktop's local Kubernetes these publish to `localhost` on their usual ports, same as Docker Compose); the rest are `ClusterIP` (internal-only), mirroring the reachability split already made in `docker-compose.yml`.

> **Status:** deployed and verified against a live local cluster (`kind`, via Docker Desktop) — all 9 pods reach `Running`/`Ready`, and a concurrent load test against the cluster's `LoadBalancer` addresses succeeded across both room-code and Quick Match flows. If your cluster has no `LoadBalancer` controller (`kubectl get svc -n kfchess` shows `<pending>` under `EXTERNAL-IP`), use `kubectl port-forward` instead, e.g. `kubectl port-forward -n kfchess svc/api-gateway 8080:8080`. Only `game-shard` needs special care when scaling past one replica: each shard needs its own externally-reachable address (the comment at the top of `k8s/25-game-shard.yaml` explains why that's a second Deployment+Service, not `replicas: N`).
>
> **After rebuilding the image:** Docker Desktop's `kind`-provisioned cluster runs its node in an isolated environment that does **not** share the host Docker daemon's image cache — `docker build -t kfchess:latest .` alone won't update already-running pods even with a matching tag. Force a fresh pull with `kubectl rollout restart deployment/<name> -n kfchess`, and if that still doesn't pick up the change, verify with `kubectl get pod -n kfchess -l app=<name> -o jsonpath='{.items[0].status.containerStatuses[0].imageID}'` against `docker images kfchess:latest --format '{{.ID}}'` — a mismatch means the node truly never saw the new image, which usually means the `docker-compose` stack (which doesn't have this issue) is the faster loop for iterating.

---

## 🛠️ Technology Stack

| Layer | Technology |
| :--- | :--- |
| **Language** | Java 17 |
| **Rendering** | Java Swing / AWT |
| **Networking** | WebSockets ([Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket)) |
| **HTTP Services** | JDK `HttpServer` / `HttpClient` (no extra web framework) |
| **Serialization** | Gson |
| **Ephemeral State** | Redis ([Jedis](https://github.com/redis/jedis)) |
| **Durable Storage** | SQLite (Phase 1) / PostgreSQL (Phase 2, JDBC) |
| **Event Bus** | NATS ([jnats](https://github.com/nats-io/nats.java)) — delivers Quick Match pairing results |
| **Containerization** | Docker & Docker Compose |
| **Orchestration** | Kubernetes (`k8s/`) |
| **Testing** | JUnit 5 |

---

## 🧪 Running Tests

```bat
runtests.bat
```

Runs the project's unit and regression suite via `TestRunner`.

---

## 📁 Project Structure

```
src/main/java/com/kungfuchess/
├── engine/     # GameEngine — authoritative game rules and state (single source of truth)
├── view/       # Swing rendering
├── server/     # Phase 1 single-process server (rooms, matchmaking, WebSocket)
├── client/     # Swing client (Phase 1 and Phase 2 entry points)
├── net/        # Wire-protocol message types shared by client and server
├── auth/       # Phase 1 SQLite-backed user/ELO store
├── cloud/
│   ├── infra/     # Redis registry, Postgres user/history repositories, JSON/HTTP helpers
│   ├── services/  # Phase 2 microservices (API Gateway, Auth, Matchmaker, Allocator, Shard, WS Gateway)
│   └── tools/     # LoadTest — concurrent room + Quick Match load generator
└── bus/        # Internal event bus

k8s/            # Kubernetes manifests mirroring docker-compose.yml
libs/           # Vendored runtime jars for the Docker image (no Maven resolver in this environment)
```

---

## 👥 Credits

Developed as a course project (CTD 26) demonstrating real-time networked game state synchronization, from a single-process server through a scaled, sharded cloud architecture deployable via Docker Compose or Kubernetes.
