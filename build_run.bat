@echo off
set JB=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin
set M2=%USERPROFILE%\.m2\repository
set JP=%M2%\org\junit\jupiter\junit-jupiter-api\5.10.2\junit-jupiter-api-5.10.2.jar
set JE=%M2%\org\junit\jupiter\junit-jupiter-engine\5.10.2\junit-jupiter-engine-5.10.2.jar
set PC=%M2%\org\junit\platform\junit-platform-commons\1.10.2\junit-platform-commons-1.10.2.jar
set PE=%M2%\org\junit\platform\junit-platform-engine\1.10.2\junit-platform-engine-1.10.2.jar
set PL=%M2%\org\junit\platform\junit-platform-launcher\1.10.2\junit-platform-launcher-1.10.2.jar
set OT=%M2%\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar
set JARS=%JP%;%JE%;%PC%;%PE%;%PL%;%OT%

rmdir /s /q out 2>nul
mkdir out

echo [1/3] Compiling main sources...
"%JB%\javac.exe" -cp "%JARS%" -d out -sourcepath src\main\java ^
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
  src\main\java\com\kungfuchess\rules\RuleEngine.java ^
  src\main\java\com\kungfuchess\texttests\ScriptParser.java ^
  src\main\java\com\kungfuchess\texttests\ScriptRunner.java ^
  src\main\java\com\kungfuchess\view\util\Img.java ^
  src\main\java\com\kungfuchess\view\util\TileGenerator.java ^
  src\main\java\com\kungfuchess\view\ImageView.java ^
  src\main\java\com\kungfuchess\view\PieceAnimator.java ^
  src\main\java\com\kungfuchess\view\PieceConfig.java ^
  src\main\java\com\kungfuchess\view\PieceState.java ^
  src\main\java\com\kungfuchess\view\Renderer.java ^
  src\main\java\com\kungfuchess\view\SoundManager.java ^
  src\main\java\com\kungfuchess\TestRunner.java
if errorlevel 1 ( echo MAIN COMPILE FAILED & exit /b 1 )

echo [2/3] Compiling test sources...
"%JB%\javac.exe" -cp "out;%JARS%" -d out ^
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
  src\test\java\tests\unit\TestRuleEngine.java
if errorlevel 1 ( echo TEST COMPILE FAILED & exit /b 1 )

echo [3/3] Running tests...
"%JB%\java.exe" -cp "out;%JARS%;src\test\resources" com.kungfuchess.TestRunner
