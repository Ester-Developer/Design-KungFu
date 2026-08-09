@echo off
REM Updated to use TestRunner from build_and_test.bat output
set JB=C:\PROGRA~1\ECLIPS~1\JDK-17~1.10-\bin
set M2=%USERPROFILE%\.m2\repository
set JP=%M2%\org\junit\jupiter\junit-jupiter-api\5.10.2\junit-jupiter-api-5.10.2.jar
set JE=%M2%\org\junit\jupiter\junit-jupiter-engine\5.10.2\junit-jupiter-engine-5.10.2.jar
set PC=%M2%\org\junit\platform\junit-platform-commons\1.10.2\junit-platform-commons-1.10.2.jar
set PE=%M2%\org\junit\platform\junit-platform-engine\1.10.2\junit-platform-engine-1.10.2.jar
set PL=%M2%\org\junit\platform\junit-platform-launcher\1.10.2\junit-platform-launcher-1.10.2.jar
set OT=%M2%\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar
set JARS=%JP%;%JE%;%PC%;%PE%;%PL%;%OT%

echo Running tests via TestRunner...
%JB%\java.exe -cp "out;%JARS%;src\test\resources" com.kungfuchess.TestRunner

