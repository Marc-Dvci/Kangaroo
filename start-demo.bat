@echo off
setlocal EnableDelayedExpansion
REM ===========================================================================
REM  Kangaroo - one-click demo launcher
REM
REM  Builds if needed, starts the real application, and opens the browser on the
REM  narrated walk-through. Everything the film shows is this process computing
REM  live; the demo only supplies the sensor input a laptop cannot.
REM
REM  Requires a JDK 26 on PATH or JAVA_HOME, or a Temurin build unpacked into
REM  .\.jdk by packaging\fetch-jdk26.sh. Nothing else - the voice-over is
REM  committed, so no Python and no network are needed to record.
REM ===========================================================================

cd /d "%~dp0"

set "PORT=8443"
set "JAR=target\kangaroo.jar"

echo.
echo   Kangaroo - demo launcher
echo   ========================
echo.

REM ---- find a JDK 26 ---------------------------------------------------------
set "JAVA_BIN="
if exist ".jdk\bin\java.exe"          set "JAVA_BIN=.jdk\bin\java.exe"
if "!JAVA_BIN!"=="" if exist "..\.toolchain\jdk-26.0.1+8\bin\java.exe" set "JAVA_BIN=..\.toolchain\jdk-26.0.1+8\bin\java.exe"
if "!JAVA_BIN!"=="" if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if "!JAVA_BIN!"=="" (
  where java >nul 2>&1
  if !ERRORLEVEL!==0 set "JAVA_BIN=java"
)
if "!JAVA_BIN!"=="" (
  echo   No Java found.
  echo   Install a JDK 26, or run:  bash packaging/fetch-jdk26.sh
  echo.
  pause
  exit /b 1
)

for /f "tokens=3" %%v in ('"!JAVA_BIN!" -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%~v"
echo   Java     !JAVA_VER!   ^(!JAVA_BIN!^)

REM ---- build if the jar is missing --------------------------------------------
REM  Deliberately a plain existence check rather than a timestamp comparison:
REM  a launcher that silently runs a stale jar is worse than one that rebuilds,
REM  and "del target\kangaroo.jar" is an obvious way to force it.
if not exist "%JAR%" (
  echo   Building ^(first run^) ...
  call mvnw.cmd -q -B package -DskipTests
  if errorlevel 1 (
    echo.
    echo   The build failed. Fix it and run this again.
    pause
    exit /b 1
  )
) else (
  echo   Jar      %JAR%
)

REM ---- narration -------------------------------------------------------------
if not exist "src\main\resources\web\demo\speech\manifest.json" (
  echo.
  echo   The voice-over is missing. Generating it ^(needs Python + edge-tts, once^) ...
  python demo\generate-voiceover.py
  if errorlevel 1 (
    echo   Could not generate the voice-over. The demo will run silently with captions.
  ) else (
    call mvnw.cmd -q -B package -DskipTests
  )
)

REM ---- free the port ---------------------------------------------------------
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":%PORT% .*LISTENING"') do (
  echo   Port %PORT% is busy - stopping PID %%p
  taskkill /F /PID %%p >nul 2>&1
)

echo.
echo   Starting Kangaroo on http://localhost:%PORT%/
echo.
echo   -----------------------------------------------------------------
echo    RECORDING TIPS
echo      * Press F11 in the browser for full screen before you start.
echo      * Set the display to 1920x1080 and browser zoom to 100%%.
echo      * The demo runs about 114 seconds, then holds on a closing card.
echo      * Close this window to stop the server when you are finished.
echo   -----------------------------------------------------------------
echo.

start "" "http://localhost:%PORT%/?demo=1"

"!JAVA_BIN!" --enable-preview --add-modules jdk.incubator.vector ^
  --enable-native-access=ALL-UNNAMED ^
  -jar "%JAR%" --port %PORT% --data "%~dp0target\demo-data"

endlocal
