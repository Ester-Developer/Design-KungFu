@echo off
REM Usage: run_cloud_service.bat <FullyQualifiedMainClass>
REM REDIS_PORT=6380 (not the standard 6379): this dev machine already has an
REM unrelated Redis container long-running on 6379 — docker-compose.yml maps ours
REM to 6380 instead of touching it. A real deployment would just use 6379.
if not defined REDIS_PORT set REDIS_PORT=6380
REM Example: run_cloud_service.bat com.kungfuchess.cloud.services.AuthServiceMain
set JB=C:\PROGRA~1\ECLIPS~1\JDK-17~1.10-\bin
set M2=%USERPROFILE%\.m2\repository
set WS=%M2%\org\java-websocket\Java-WebSocket\1.5.6\Java-WebSocket-1.5.6.jar
set SL_API=%M2%\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar
set SL_SIMPLE=%M2%\org\slf4j\slf4j-simple\2.0.9\slf4j-simple-2.0.9.jar
set GSON=%M2%\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar
set JEDIS=%M2%\redis\clients\jedis\5.1.0\jedis-5.1.0.jar
set POOL2=%M2%\org\apache\commons\commons-pool2\2.12.0\commons-pool2-2.12.0.jar
set PG=%M2%\org\postgresql\postgresql\42.7.3\postgresql-42.7.3.jar
set NATS=%M2%\io\nats\jnats\2.17.6\jnats-2.17.6.jar
set CHECKQ=%M2%\org\checkerframework\checker-qual\3.42.0\checker-qual-3.42.0.jar
set JARS=%WS%;%SL_API%;%SL_SIMPLE%;%GSON%;%JEDIS%;%POOL2%;%PG%;%NATS%;%CHECKQ%

%JB%\java.exe -cp "out;%JARS%" %1
