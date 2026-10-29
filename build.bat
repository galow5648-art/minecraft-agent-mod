@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
title Minecraft Agent Mod - build

echo ============================================================
echo  Сборка мода Minecraft Agent (Fabric 26.3)
echo ============================================================
echo.
echo Этот скрипт может скачать и установить следующее:
echo   1. JDK 25 (Eclipse Temurin, сайт https://adoptium.net) - нужен для сборки.
echo      Ставится только если у тебя нет Java 25. Около 200 МБ.
echo   2. Gradle 9.7.1 (сайт https://gradle.org) - система сборки. Около 150 МБ.
echo   3. Fabric API 0.165.0+26.3 (Modrinth) - библиотека, без неё мод не запустится.
echo.
echo Всё ставится в папку %LOCALAPPDATA%\MinecraftAgent\tools.
echo Ничего не удаляется и не меняется на компьютере, кроме этой папки и папки mods.
echo.
set /p CONSENT="Продолжить? (y/n): "
if /i not "!CONSENT!"=="y" (
    echo Отменено.
    exit /b 0
)

set "TOOLS=%LOCALAPPDATA%\MinecraftAgent\tools"
if not exist "%TOOLS%" mkdir "%TOOLS%"

REM --- 1. JDK 25 ---
set "JAVA_OK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_OK=1"
if not defined JAVA_OK (
    for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JV=%%~v"
    echo !JV! | findstr /b "25." >nul && set "JAVA_OK=1"
)
if not defined JAVA_OK (
    if not exist "%TOOLS%\jdk25\bin\java.exe" (
        echo Скачиваю JDK 25 с adoptium.net...
        powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile '%TOOLS%\jdk25.zip'; Expand-Archive -Path '%TOOLS%\jdk25.zip' -DestinationPath '%TOOLS%\jdk-tmp' -Force; $d=Get-ChildItem '%TOOLS%\jdk-tmp' -Directory | Select-Object -First 1; Move-Item $d.FullName '%TOOLS%\jdk25'; Remove-Item '%TOOLS%\jdk-tmp' -Recurse -Force; Remove-Item '%TOOLS%\jdk25.zip'"
        if errorlevel 1 goto :fail
    )
    set "JAVA_HOME=%TOOLS%\jdk25"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM --- 2. Gradle 9.7.1 ---
if not exist "%TOOLS%\gradle-9.7.1\bin\gradle.bat" (
    echo Скачиваю Gradle 9.7.1 с services.gradle.org...
    powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-9.7.1-bin.zip' -OutFile '%TOOLS%\gradle.zip'; Expand-Archive -Path '%TOOLS%\gradle.zip' -DestinationPath '%TOOLS%' -Force; Remove-Item '%TOOLS%\gradle.zip'"
    if errorlevel 1 goto :fail
)

REM --- 3. Сборка ---
echo Собираю мод...
pushd "%~dp0"
call "%TOOLS%\gradle-9.7.1\bin\gradle.bat" build --no-daemon
if errorlevel 1 (
    popd
    goto :fail
)
popd

REM --- 4. Копирование в mods ---
set "MODS=%~1"
if "%MODS%"=="" set "MODS=%APPDATA%\.tlauncher\legacy\minecraft\game\mods"
if not exist "%MODS%" mkdir "%MODS%"
copy /y "%~dp0build\libs\minecraft-agent-mod-1.0.0.jar" "%MODS%\" >nul
if errorlevel 1 goto :fail

REM --- 5. Fabric API (если его ещё нет в mods) ---
dir /b "%MODS%\fabric-api-*.jar" >nul 2>&1
if errorlevel 1 (
    echo Скачиваю Fabric API 0.165.0+26.3 с Modrinth...
    powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://cdn.modrinth.com/data/P7dR8mSH/versions/EQKgpeKu/fabric-api-0.165.0%%2B26.3.jar' -OutFile '%MODS%\fabric-api-0.165.0+26.3.jar'"
)

echo.
echo ============================================================
echo  Готово. Мод установлен в:
echo  %MODS%
echo  Запусти Minecraft с профилем Fabric 26.3.
echo ============================================================
pause
exit /b 0

:fail
echo.
echo Сборка не удалась. Покажи мне текст ошибки выше.
pause
exit /b 1
