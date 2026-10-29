@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
title Minecraft Agent - update

set "REPO=galow5648-art/minecraft-agent-mod"
set "BRANCH=main"
set "PROJ=%USERPROFILE%\minecraft-agent-mod"
set "STATE=%LOCALAPPDATA%\MinecraftAgent"
set "SERVER_DST=C:\MinecraftAgent\server.py"
set "TMPZIP=%TEMP%\mam-update.zip"
set "TMPDIR=%TEMP%\mam-update"

if not exist "%STATE%" mkdir "%STATE%"

echo [1/6] Checking the latest version on GitHub...
set "LATEST="
for /f "delims=" %%S in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "try { (Invoke-RestMethod -Uri 'https://api.github.com/repos/%REPO%/commits/%BRANCH%' -Headers @{'User-Agent'='mc-agent-update'}).sha } catch { 'ERROR' }"') do set "LATEST=%%S"
if "!LATEST!"=="" set "LATEST=ERROR"
if "!LATEST!"=="ERROR" (
  echo Cannot reach GitHub. Check internet and that the repository is public.
  goto :fail
)

set "CURRENT="
if exist "%STATE%\version.txt" set /p CURRENT=<"%STATE%\version.txt"
echo Installed: !CURRENT!
echo Latest:    !LATEST!
if "!CURRENT!"=="!LATEST!" (
  set /p FORCE=Already up to date. Reinstall anyway? y/n: 
  if /i not "!FORCE!"=="y" goto :done
)

echo.
echo [2/6] Close Minecraft now if it is running (the mod file is locked while the game runs).
pause

echo [3/6] Downloading the project archive...
if exist "%TMPZIP%" del /q "%TMPZIP%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://github.com/%REPO%/archive/refs/heads/%BRANCH%.zip' -OutFile '%TMPZIP%'"
if errorlevel 1 goto :fail

echo [4/6] Unpacking and replacing the project folder...
if exist "%TMPDIR%" rmdir /s /q "%TMPDIR%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%TMPZIP%' -DestinationPath '%TMPDIR%' -Force"
if errorlevel 1 goto :fail
if not exist "%TMPDIR%\minecraft-agent-mod-%BRANCH%" (
  echo Unexpected archive layout.
  goto :fail
)
if exist "%PROJ%" rmdir /s /q "%PROJ%"
move "%TMPDIR%\minecraft-agent-mod-%BRANCH%" "%PROJ%" >nul
if errorlevel 1 goto :fail

echo [5/6] Updating the MCP server and saving the version...
if not exist "C:\MinecraftAgent" mkdir "C:\MinecraftAgent"
copy /y "%PROJ%\mcp_server\server.py" "%SERVER_DST%" >nul
if errorlevel 1 goto :fail
> "%STATE%\version.txt" echo !LATEST!
del /q "%TMPZIP%" 2>nul
rmdir /s /q "%TMPDIR%" 2>nul

echo [6/6] Building the mod...
cd /d "%PROJ%"
call build.bat
goto :eof

:done
echo Nothing to do.
pause
exit /b 0

:fail
echo.
echo UPDATE FAILED. Send the text above to Claude.
pause
exit /b 1
