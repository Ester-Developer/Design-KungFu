@echo off
set JB=C:\PROGRA~1\ECLIPS~1\JDK-17~1.10-\bin
set M2=%USERPROFILE%\.m2\repository
set WS=%M2%\org\java-websocket\Java-WebSocket\1.5.6\Java-WebSocket-1.5.6.jar
set SL_API=%M2%\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar
set SL_SIMPLE=%M2%\org\slf4j\slf4j-simple\2.0.9\slf4j-simple-2.0.9.jar
set GSON=%M2%\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar

echo Starting Kung-Fu Chess Client (Phase 2 - scaled architecture)...
echo.
%JB%\java.exe -cp "out;%WS%;%SL_API%;%SL_SIMPLE%;%GSON%" com.kungfuchess.client.CloudClientMain %*
