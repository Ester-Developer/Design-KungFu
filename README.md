# ♟️ Kung-Fu Chess

A high-performance, real-time multiplayer chess variant where players move simultaneously with no turns or waiting. Built completely from scratch using Python, featuring OpenCV rendering, an asynchronous microservice backend, WebSocket multiplayer, ELO matchmaking, and a fully scalable Kubernetes deployment.

---

## 🎯 Game Features & Mechanics

* **Simultaneous Real-Time Action:** No turns, no waiting—both players can move any piece at any given time.
* **Dynamic Cooldown System:** 
  * *Long Rest:* Standard moves trigger a gold overlay draining over the cell.
  * *Short Rest:* Jumps trigger a faster-draining purple overlay.
* **Win Condition:** Capturing the opposing King instantly ends the match.
* **Smooth State Animations:** Pieces visually transition through states: `idle` $\rightarrow$ `moving` $\rightarrow$ `long_rest` $\rightarrow$ `idle` (or via jumping to `short_rest`).
* **Disconnection Management:** Automated 20-second grace countdown before an abandoned match results in a forfeit.

---

## 🛠️ Technology Stack

| Layer | Technology |
| :--- | :--- |
| **Language** | Python 3.11 |
| **Rendering** | OpenCV + NumPy |
| **Networking** | WebSockets (`websockets` library) |
| **HTTP Services** | FastAPI + Uvicorn |
| **Event Bus** | NATS (`nats-py`) |
| **Hot State** | Redis |
| **Durable Storage** | PostgreSQL (`psycopg2`) |
| **Containerization** | Docker & Docker Compose |
| **Orchestration** | Kubernetes (`k8s/`) |
| **Testing** | pytest |

---

## 🏗️ Microservice Architecture

The backend utilizes a decoupled microservice architecture built to support high concurrency:

* **API Gateway (`8080`):** REST entry point handling user authentication and registration.
* **WS Gateway (`5555`):** WebSocket entry point validating active sessions and routing client messages.
* **Auth Service (`8000`):** Manages user registration, credential validation, and session tokens.
* **Rooms API (`8001`):** Redis-backed room creation and lookup management.
* **Rating Service (`8002`):** Handles ELO calculations and persistence upon match completion.
* **Matchmaker (`8003`):** ELO-based queue pairing players and emitting match events to NATS (`kfc.matched`).
* **Game Allocator (`8004`):** Consumes match events, determines the least-loaded shard worker, and dispatches allocations (`kfc.allocated`).
* **Game Server Shard (`5556–55xx`):** Authoritative multiprocessing game engine maintaining active room sessions (one worker per CPU core).

---

## 🚀 Installation & Running the Game

### Local Execution (Standalone, No Server Required)
```bash
cd logic
python graphics/app.py
Docker Compose (Full Stack Deployment)
Bash
docker compose up --build
To run the client after starting the stack:

Bash
cd client
python main.py --host localhost --port 5555 --api-port 8080
Kubernetes Deployment (Docker Desktop)
Enable Kubernetes within your Docker Desktop settings, then apply the manifests:

Bash
kubectl apply -f k8s/postgres.yaml -f k8s/redis.yaml -f k8s/nats.yaml
kubectl apply -f k8s/auth-service.yaml -f k8s/rating-service.yaml -f k8s/rooms-api.yaml
kubectl apply -f k8s/matchmaker.yaml -f k8s/game-shard.yaml -f k8s/game-allocator.yaml
kubectl apply -f k8s/api-gateway.yaml -f k8s/ws-gateway.yaml
Run the network client:

Bash
cd client
python main.py --host localhost --port 30555 --api-port 30080
🧪 Running Tests
Execute the test suite using pytest inside the logic module:

Bash
cd logic
python -m pytest tests/
👥 License & Project Details
Developed as an advanced full-stack software architecture project demonstrating real-time event-driven programming, multiprocessing, and networked game state synchronization.
