@echo off
setlocal
set "ROOT=%~dp0"
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE (
  where java >nul 2>nul
  if not errorlevel 1 set "JAVA_EXE=java"
)
if not defined JAVA_EXE (
  for /d %%D in ("%ROOT%..\..\work\toolchain\jdk-extract\jdk-*") do set "JAVA_EXE=%%~fD\bin\java.exe"
)
if defined JAVA_EXE if not "%JAVA_EXE%"=="java" if not exist "%JAVA_EXE%" set "JAVA_EXE="
set "JAR=%ROOT%target\GeoCoinPortableMiner.jar"
if not defined JAVA_EXE (
  echo Java 21 or newer was not found. Set JAVA_HOME or add Java to PATH.
  pause
  exit /b 1
)
if not exist "%JAR%" (
  echo Miner JAR not found. Run scripts\build.cmd first.
  pause
  exit /b 1
)
"%JAVA_EXE%" -jar "%JAR%"
if errorlevel 1 pause
endlocal
