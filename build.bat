@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Minecraft Agent Mod - build

echo ============================================================
echo  Minecraft Agent Mod - build for Fabric 26.3
echo ============================================================
echo.
echo This script may download and install:
echo   1. JDK 25 from adoptium.net - needed for building.
echo      Only if you do not already have Java 25. About 200 MB.
echo   2. Gradle 9.7.1 from gradle.org - build tool. About 150 MB.
echo   3. Fabric API 0.165.0+26.3 from Modrinth - required by the mod.
echo.
echo Everything goes to: %LOCALAPPDATA%\MinecraftAgent\tools
echo Nothing else on your computer is changed.
echo.
set /p CONSENT=Continue? Type y and press Enter: 
if /i not "%CONSENT%"=="y" goto :cancel

set "TOOLS=%LOCALAPPDATA%\MinecraftAgent\tools"
if not exist "%TOOLS%" mkdir "%TOOLS%"

rem ---- 1. JDK 25 ----
set "JDK_HOME="
if exist "%TOOLS%\jdk25\bin\java.exe" set "JDK_HOME=%TOOLS%\jdk25"
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" (
    "%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /r /c:"version \"25" >nul && set "JDK_HOME=%JAVA_HOME%"
  )
)
if not defined JDK_HOME (
  java -version 2>&1 | findstr /r /c:"version \"25" >nul && set "JDK_HOME=AUTO"
)
if "%JDK_HOME%"=="AUTO" (
  for /f "delims=" %%J in ('where java') do if not defined JDK_BIN set "JDK_BIN=%%~dpJ.."
  set "JDK_HOME=%JDK_BIN%"
)
if not defined JDK_HOME (
  echo Downloading JDK 25...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile '%TOOLS%\jdk25.zip'; Expand-Archive -Path '%TOOLS%\jdk25.zip' -DestinationPath '%TOOLS%\jdk-tmp' -Force; $d = Get-ChildItem '%TOOLS%\jdk-tmp' -Directory | Select-Object -First 1; Move-Item $d.FullName '%TOOLS%\jdk25'; Remove-Item '%TOOLS%\jdk-tmp' -Recurse -Force; Remove-Item '%TOOLS%\jdk25.zip'"
  if errorlevel 1 goto :fail
  set "JDK_HOME=%TOOLS%\jdk25"
)
set "JAVA_HOME=%JDK_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Using Java: %JAVA_HOME%

rem ---- 2. Gradle 9.7.1 ----
if not exist "%TOOLS%\gradle-9.7.1\bin\gradle.bat" (
  echo Downloading Gradle 9.7.1...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-9.7.1-bin.zip' -OutFile '%TOOLS%\gradle.zip'; Expand-Archive -Path '%TOOLS%\gradle.zip' -DestinationPath '%TOOLS%' -Force; Remove-Item '%TOOLS%\gradle.zip'"
  if errorlevel 1 goto :fail
)

rem ---- 3. Build ----
echo Building the mod...
cd /d "%~dp0"
call "%TOOLS%\gradle-9.7.1\bin\gradle.bat" build --no-daemon
if errorlevel 1 goto :fail

rem ---- 4. Copy to mods (remove old versions of this mod first) ----
set "MODS=%APPDATA%\.tlauncher\legacy\minecraft\game\mods"
if not exist "%MODS%" mkdir "%MODS%"
if exist "%MODS%\minecraft-agent-mod-*.jar" (
  echo Removing old version of the mod...
  del /q "%MODS%\minecraft-agent-mod-*.jar"
)
copy /y "%~dp0build\libs\minecraft-agent-mod-1.0.0.jar" "%MODS%\" >nul
if errorlevel 1 goto :fail

rem ---- 5. Fabric API ----
dir /b "%MODS%\fabric-api-*.jar" >nul 2>&1
if errorlevel 1 (
  echo Downloading Fabric API 0.165.0+26.3...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://cdn.modrinth.com/data/P7dR8mSH/versions/EQKgpeKu/fabric-api-0.165.0%%2B26.3.jar' -OutFile '%MODS%\fabric-api-0.165.0+26.3.jar'"
)

echo.
echo ============================================================
echo  DONE. Mod installed to:
echo  %MODS%
echo  Start Minecraft with the Fabric 26.3 profile.
echo ============================================================
pause
exit /b 0

:cancel
echo Cancelled.
pause
exit /b 0

:fail
echo.
echo BUILD FAILED. Send me the error text above.
pause
exit /b 1
