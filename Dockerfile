# One image for all six cloud services (Server_Design.md §3) — which service a
# container runs is selected purely by the MAIN_CLASS env var docker-compose.yml
# sets per service. No Maven resolver is available in this environment, so the
# classpath is the already-compiled out/ (see build_and_test.bat) plus the
# runtime jars vendored into libs/ — both committed to the repo, matching how the
# rest of this project's build already works (no separate Docker-side compile step).
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY out ./out
COPY libs ./libs
# PieceConfig/ImageView read piece state configs (is_loop, next_state_when_finished,
# speed_m_per_sec) via the relative path "src/main/resources/pieces/..." — resolved
# against the JVM's working directory, not the classpath. Game Server Shard actually
# NEEDS these at runtime (RealTimeArbiter uses them to compute the post-landing rest
# duration), so without this COPY every state lookup silently misses and falls back
# to defaults (is_loop defaults to true), collapsing every piece's post-landing
# cooldown to zero — no rest chain, no visible cooldown ring, immediate re-move.
COPY src/main/resources ./src/main/resources

ENV MAIN_CLASS=com.kungfuchess.cloud.services.AuthServiceMain
ENTRYPOINT ["sh", "-c", "exec java -cp out:libs/* $MAIN_CLASS"]
