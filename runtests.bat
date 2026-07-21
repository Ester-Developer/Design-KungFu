@echo off
set JAVA="C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe"
set JAVAC="C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\javac.exe"
set CP=target\classes;target\test-classes;src\test\resources
set CP=%CP%;%USERPROFILE%\.m2\repository\org\junit\jupiter\junit-jupiter\5.10.2\junit-jupiter-5.10.2.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.10.2\junit-jupiter-api-5.10.2.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.10.2\junit-jupiter-engine-5.10.2.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\org\junit\platform\junit-platform-launcher\1.10.2\junit-platform-launcher-1.10.2.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\org\junit\platform\junit-platform-engine\1.10.2\junit-platform-engine-1.10.2.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\org\junit\platform\junit-platform-commons\1.10.2\junit-platform-commons-1.10.2.jar
set CP=%CP%;%USERPROFILE%\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar
mkdir tmp_r 2>nul
%JAVAC% -cp %CP% -d tmp_r RunTests.java 2>nul
%JAVA% -cp tmp_r;%CP% RunTests
rmdir /S /Q tmp_r
