<div align="center">

# ♟️ Kung-Fu Chess

**Real-time multiplayer chess. No turns. No mercy.**

Both players move *simultaneously* — there's no waiting for your opponent. Every piece has its own cooldown, so speed and timing matter as much as strategy.

![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)
![WebSocket](https://img.shields.io/badge/Networking-WebSocket-blue)
![Docker](https://img.shields.io/badge/Deploy-Docker%20Compose-2496ED?logo=docker&logoColor=white)
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

> Want the full scaled cloud version (API Gateway, WS Gateway, sharded game servers, Redis/PostgreSQL/NATS via Docker)? See [Running the Scaled Architecture](#-running-the-scaled-architecture) below.

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
* **Disconnect grace** — a disconnected player has 20 seconds to reconnect before the match is forfeited.
* **Live activity log** — client and server both log session/game activity to file for debugging and review.

---

## 🏗️ Architecture

The project ships in two layers, matching a staged rollout — a working single-process server first, then a scaled split.

### Phase 1 — Single-process server

One JVM (`ServerMain`) hosts the WebSocket endpoint, an in-memory `RoomManager` (one isolated `GameEngine` per room), the ELO/Quick-Match matchmaker, and a SQLite-backed user store. This is the simplest way to run the game locally — see [Download & Play](#-download--play) above.

### Phase 2 — Scaled cloud architecture

The single process is split into five independent Java services, so gateways, matchmaking/allocation, and game execution can each scale on their own:

| Service | Port | Responsibility |
| :--- | :--- | :--- |
| **API Gateway** | `8080` | REST entry point — `/login`, `/register`; forwards to Auth Service and issues a short-lived Redis session token. |
| **Auth Service** | `8000` | Credential validation and ELO storage against PostgreSQL. |
| **WS Gateway** | `5555` | Validates session tokens, handles room create/join against the Redis registry, and redirects both players to an allocated game shard. |
| **Game Allocator** | `8004` | Picks the least-loaded game shard (via shard heartbeats in Redis) for a new room. |
| **Game Server Shard** | `5556+` | Hosts the authoritative `GameEngine` for each room it's assigned — the single source of truth for game state — and writes final ELO to PostgreSQL. |

**Shared infrastructure** (via Docker Compose): **Redis** for ephemeral state (session tokens, room→shard routing, shard heartbeats), **PostgreSQL** for durable state (users, ELO), **NATS** for the internal service event bus.

The handshake is a two-hop redirect: client → WS Gateway (auth + room routing) → once both players are seated, the WS Gateway calls the Game Allocator, then tells both clients where to reconnect → client → Game Server Shard (authoritative gameplay).

See [`Server_Design.md`](Server_Design.md) for the full design rationale (database choice, the Redis registry, traffic estimates, and how short match lifetimes shape the autoscaling/no-migration approach) and rollout plan.

---

## 🐳 Running the Scaled Architecture

**Requirements:** everything above, plus [Docker Desktop](https://www.docker.com/products/docker-desktop/).

```bat
:: 1. Start shared infrastructure (Redis, PostgreSQL, NATS)
docker compose up -d

:: 2. Start each service in its own window
run_auth_service.bat
run_api_gateway.bat
run_game_allocator.bat
run_game_shard.bat
run_ws_gateway.bat

:: 3. Connect with the cloud client
run_cloud_client.bat
```

> Redis defaults to port `6380` rather than the standard `6379` — see the comments in `docker-compose.yml` / `run_cloud_service.bat` if you need to change it.

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
| **Event Bus** | NATS ([jnats](https://github.com/nats-io/nats.java)) |
| **Containerization** | Docker & Docker Compose |
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
│   ├── infra/     # Redis registry, Postgres user store, JSON/HTTP helpers
│   └── services/  # Phase 2 microservices (API Gateway, Auth, Allocator, Shard, WS Gateway)
└── bus/        # Internal event bus
```

---

## 👥 Credits

Developed as a course project (CTD 26) demonstrating real-time networked game state synchronization, from a single-process server through a scaled, sharded cloud architecture.
