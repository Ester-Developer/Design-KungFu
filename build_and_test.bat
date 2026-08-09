@echo off
set JB=C:\PROGRA~1\ECLIPS~1\JDK-17~1.10-\bin
set M2=%USERPROFILE%\.m2\repository
set JP=%M2%\org\junit\jupiter\junit-jupiter-api\5.10.2\junit-jupiter-api-5.10.2.jar
set JE=%M2%\org\junit\jupiter\junit-jupiter-engine\5.10.2\junit-jupiter-engine-5.10.2.jar
set PC=%M2%\org\junit\platform\junit-platform-commons\1.10.2\junit-platform-commons-1.10.2.jar
set PE=%M2%\org\junit\platform\junit-platform-engine\1.10.2\junit-platform-engine-1.10.2.jar
set PL=%M2%\org\junit\platform\junit-platform-launcher\1.10.2\junit-platform-launcher-1.10.2.jar
set OT=%M2%\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar
set WS=%M2%\org\java-websocket\Java-WebSocket\1.5.6\Java-WebSocket-1.5.6.jar
set SL_API=%M2%\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar
set SL_SIMPLE=%M2%\org\slf4j\slf4j-simple\2.0.9\slf4j-simple-2.0.9.jar
set GSON=%M2%\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar
set SQLITE=%M2%\org\xerial\sqlite-jdbc\3.46.1.0\sqlite-jdbc-3.46.1.0.jar
set JEDIS=%M2%\redis\clients\jedis\5.1.0\jedis-5.1.0.jar
set POOL2=%M2%\org\apache\commons\commons-pool2\2.12.0\commons-pool2-2.12.0.jar
set PG=%M2%\org\postgresql\postgresql\42.7.3\postgresql-42.7.3.jar
set NATS=%M2%\io\nats\jnats\2.17.6\jnats-2.17.6.jar
set CHECKQ=%M2%\org\checkerframework\checker-qual\3.42.0\checker-qual-3.42.0.jar
set JARS=%JP%;%JE%;%PC%;%PE%;%PL%;%OT%;%WS%;%SL_API%;%SL_SIMPLE%;%GSON%;%SQLITE%;%JEDIS%;%POOL2%;%PG%;%NATS%;%CHECKQ%

rmdir /s /q out 2>nul
mkdir out

echo [1/3] Compiling main sources...
%JB%\javac.exe -encoding UTF-8 -cp "%JARS%" -d out -sourcepath src\main\java ^
  src\main\java\com\kungfuchess\auth\UserRepository.java ^
  src\main\java\com\kungfuchess\bus\EventBus.java ^
  src\main\java\com\kungfuchess\bus\ConsoleEventLogger.java ^
  src\main\java\com\kungfuchess\bus\EventBusDemo.java ^
  src\main\java\com\kungfuchess\net\MoveNotation.java ^
  src\main\java\com\kungfuchess\net\MoveMessage.java ^
  src\main\java\com\kungfuchess\net\ScreamMessage.java ^
  src\main\java\com\kungfuchess\net\BoardStateMessage.java ^
  src\main\java\com\kungfuchess\net\ErrorMessage.java ^
  src\main\java\com\kungfuchess\net\LoginMessage.java ^
  src\main\java\com\kungfuchess\net\ColorMessage.java ^
  src\main\java\com\kungfuchess\net\ServerFullMessage.java ^
  src\main\java\com\kungfuchess\net\AuthResultMessage.java ^
  src\main\java\com\kungfuchess\net\PlayRequestMessage.java ^
  src\main\java\com\kungfuchess\net\NoMatchMessage.java ^
  src\main\java\com\kungfuchess\net\DisconnectCountdownMessage.java ^
  src\main\java\com\kungfuchess\net\BoardSerializer.java ^
  src\main\java\com\kungfuchess\net\RoomCreateMessage.java ^
  src\main\java\com\kungfuchess\net\RoomJoinMessage.java ^
  src\main\java\com\kungfuchess\net\TokenMessage.java ^
  src\main\java\com\kungfuchess\net\ShardConnectMessage.java ^
  src\main\java\com\kungfuchess\net\ShardJoinMessage.java ^
  src\main\java\com\kungfuchess\net\RoomInfoMessage.java ^
  src\main\java\com\kungfuchess\net\RoomErrorMessage.java ^
  src\main\java\com\kungfuchess\net\DodgeMessage.java ^
  src\main\java\com\kungfuchess\server\ServerSession.java ^
  src\main\java\com\kungfuchess\server\Room.java ^
  src\main\java\com\kungfuchess\server\RoomManager.java ^
  src\main\java\com\kungfuchess\server\Matchmaker.java ^
  src\main\java\com\kungfuchess\server\ChessWebSocketServer.java ^
  src\main\java\com\kungfuchess\server\ServerMain.java ^
  src\main\java\com\kungfuchess\util\ActivityLogger.java ^
  src\main\java\com\kungfuchess\cloud\infra\RedisRegistry.java ^
  src\main\java\com\kungfuchess\cloud\infra\HttpJson.java ^
  src\main\java\com\kungfuchess\cloud\infra\PostgresUserRepository.java ^
  src\main\java\com\kungfuchess\cloud\infra\GameHistoryRepository.java ^
  src\main\java\com\kungfuchess\cloud\services\AuthServiceMain.java ^
  src\main\java\com\kungfuchess\cloud\services\ApiGatewayMain.java ^
  src\main\java\com\kungfuchess\cloud\services\GameAllocatorMain.java ^
  src\main\java\com\kungfuchess\cloud\services\GameShardMain.java ^
  src\main\java\com\kungfuchess\cloud\services\WsGatewayMain.java ^
  src\main\java\com\kungfuchess\cloud\services\MatchmakerMain.java ^
  src\main\java\com\kungfuchess\cloud\tools\LoadTest.java ^
  src\main\java\com\kungfuchess\client\ChessWebSocketClient.java ^
  src\main\java\com\kungfuchess\client\ClientMain.java ^
  src\main\java\com\kungfuchess\client\LoginWindow.java ^
  src\main\java\com\kungfuchess\client\GameOverlayPanel.java ^
  src\main\java\com\kungfuchess\client\CloudClientMain.java ^
  src\main\java\com\kungfuchess\client\TestClient.java ^
  src\main\java\com\kungfuchess\engine\GameEngine.java ^
  src\main\java\com\kungfuchess\input\BoardMapper.java ^
  src\main\java\com\kungfuchess\input\Controller.java ^
  src\main\java\com\kungfuchess\io\BoardParser.java ^
  src\main\java\com\kungfuchess\io\BoardPrinter.java ^
  src\main\java\com\kungfuchess\model\Board.java ^
  src\main\java\com\kungfuchess\model\GameState.java ^
  src\main\java\com\kungfuchess\model\Piece.java ^
  src\main\java\com\kungfuchess\model\Position.java ^
  src\main\java\com\kungfuchess\realtime\Motion.java ^
  src\main\java\com\kungfuchess\realtime\RealTimeArbiter.java ^
  src\main\java\com\kungfuchess\rules\PieceRules.java ^
  src\main\java\com\kungfuchess\rules\PieceValues.java ^
  src\main\java\com\kungfuchess\rules\RuleEngine.java ^
  src\main\java\com\kungfuchess\texttests\ScriptParser.java ^
  src\main\java\com\kungfuchess\texttests\ScriptRunner.java ^
  src\main\java\com\kungfuchess\view\util\Img.java ^
  src\main\java\com\kungfuchess\view\util\TileGenerator.java ^
  src\main\java\com\kungfuchess\view\ViewConstants.java ^
  src\main\java\com\kungfuchess\view\ImageView.java ^
  src\main\java\com\kungfuchess\view\PieceAnimator.java ^
  src\main\java\com\kungfuchess\view\PieceConfig.java ^
  src\main\java\com\kungfuchess\view\PieceState.java ^
  src\main\java\com\kungfuchess\view\Renderer.java ^
  src\main\java\com\kungfuchess\view\SoundManager.java ^
  src\main\java\com\kungfuchess\TestRunner.java
if errorlevel 1 ( echo MAIN COMPILE FAILED & exit /b 1 )

echo [2/3] Compiling test sources...
%JB%\javac.exe -encoding UTF-8 -cp "out;%JARS%" -d out ^
  src\test\java\com\kungfuchess\auth\UserRepositoryTest.java ^
  src\test\java\com\kungfuchess\bus\EventBusTest.java ^
  src\test\java\com\kungfuchess\bus\GameEngineEventIntegrationTest.java ^
  src\test\java\com\kungfuchess\net\MoveNotationTest.java ^
  src\test\java\tests\integration\TestTextScripts.java ^
  src\test\java\tests\unit\TestBoard.java ^
  src\test\java\tests\unit\TestBoardMapper.java ^
  src\test\java\tests\unit\TestBoardParser.java ^
  src\test\java\tests\unit\TestBoardPrinter.java ^
  src\test\java\tests\unit\TestController.java ^
  src\test\java\tests\unit\TestGameEngine.java ^
  src\test\java\tests\unit\TestMotionAndSnapshot.java ^
  src\test\java\tests\unit\TestPieceConfig.java ^
  src\test\java\tests\unit\TestPieceRules.java ^
  src\test\java\tests\unit\TestPosition.java ^
  src\test\java\tests\unit\TestRealTimeArbiter.java ^
  src\test\java\tests\unit\TestRuleEngine.java ^
  src\test\java\tests\unit\TestNewBehaviors.java ^
  src\test\java\tests\unit\TestIssue6Diagnosis.java ^
  src\test\java\tests\unit\TestGameplayFixes.java ^
  src\test\java\tests\unit\TestDodgeAndFixes.java ^
  src\test\java\tests\unit\TestAssetDimensions.java ^
  src\test\java\tests\unit\TestVacatedSquareVisibility.java ^
  src\test\java\tests\unit\TestFrameSymmetry.java ^
  src\test\java\tests\regression\TestKnownGoodBehaviors.java
if errorlevel 1 ( echo TEST COMPILE FAILED & exit /b 1 )

echo [3/3] Running tests...
%JB%\java.exe -cp "out;%JARS%;src\test\resources" com.kungfuchess.TestRunner
