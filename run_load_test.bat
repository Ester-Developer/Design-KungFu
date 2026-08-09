@echo off
REM Usage: run_load_test.bat [--room-pairs N] [--quick-pairs N] [--api http://host:8080] [--ws ws://host:5555]
REM Requires all six cloud services + Redis/PostgreSQL/NATS to already be running.
set JB=C:\PROGRA~1\ECLIPS~1\JDK-17~1.10-\bin
set M2=%USERPROFILE%\.m2\repository
set WS=%M2%\org\java-websocket\Java-WebSocket\1.5.6\Java-WebSocket-1.5.6.jar
set SL_API=%M2%\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar
set SL_SIMPLE=%M2%\org\slf4j\slf4j-simple\2.0.9\slf4j-simple-2.0.9.jar
set GSON=%M2%\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar
set JARS=%WS%;%SL_API%;%SL_SIMPLE%;%GSON%

%JB%\java.exe -cp "out;%JARS%" com.kungfuchess.cloud.tools.LoadTest %*
