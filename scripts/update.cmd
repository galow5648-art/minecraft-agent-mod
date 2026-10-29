@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
title Minecraft Agent - update

set "REPO=galow5648-art/minecraft-agent-mod"
set "BRANCH=main"
set "PROJ=%USERPROFILE%\minecraft-agent-mod"
set "STATE=%LOCALAPPDATA%\MinecraftAgent"
set "SERVER_DST=C:\MinecraftAgent\server.py"
set "VAULT=C:\Users\VFFD\Desktop\dataStoreage\minemodcraft"
set "NOTES_DST=%VAULT%\minecraft-agent"
set "TMPZIP=%TEMP%\mam-update.zip"
set "TMPDIR=%TEMP%\mam-update"
set "WATCH_MIN=15"

if not exist "%STATE%" mkdir "%STATE%"

if /i "%~1"=="watch" goto :watch_loop
goto :run_once

:watch_loop
echo Watch mode: checking GitHub every %WATCH_MIN% minutes. Close this window to stop.
:watch_next
call :run_once_quiet
echo Next check in %WATCH_MIN% minutes...
timeout /t 900 /nobreak >nul 2>&1
goto :watch_next

:run_once
call :check_version
if "!NEED_UPDATE!"=="0" (
  echo Mod is up to date. Syncing notes only.
  call :sync_notes
  pause
  exit /b 0
)
echo.
echo Close Minecraft now if it is running (the mod jar is locked while the game runs).
pause
call :download_and_install
if errorlevel 1 goto :fail
call :sync_notes
cd /d "%PROJ%"
call build.bat
exit /b 0

:run_once_quiet
call :check_version
if "!NEED_UPDATE!"=="1" (
  echo New version found, but Minecraft must be closed to update. Run update.cmd manually.
) else (
  call :sync_notes
)
exit /b 0

:check_version
set "LATEST="
for /f "delims=" %%S in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "try { (Invoke-RestMethod -Uri 'https://api.github.com/repos/%REPO%/commits/%BRANCH%' -Headers @{'User-Agent'='mc-agent-update'}).sha } catch { 'ERROR' }"') do set "LATEST=%%S"
if "!LATEST!"=="" set "LATEST=ERROR"
if "!LATEST!"=="ERROR" (
  echo Cannot reach GitHub. Check internet and that the repository is public.
  set "NEED_UPDATE=0"
  exit /b 0
)
set "CURRENT="
if exist "%STATE%\version.txt" set /p CURRENT=<"%STATE%\version.txt"
set "LATEST_SHA=!LATEST!"
echo Installed: !CURRENT!
echo Latest:    !LATEST!
set "NEED_UPDATE=1"
if "!CURRENT!"=="!LATEST!" set "NEED_UPDATE=0"
exit /b 0

:download_and_install
echo Downloading the project archive...
if exist "%TMPZIP%" del /q "%TMPZIP%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://github.com/%REPO%/archive/refs/heads/%BRANCH%.zip' -OutFile '%TMPZIP%'"
if errorlevel 1 exit /b 1

echo Unpacking and replacing the project folder...
if exist "%TMPDIR%" rmdir /s /q "%TMPDIR%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%TMPZIP%' -DestinationPath '%TMPDIR%' -Force"
if errorlevel 1 exit /b 1
if not exist "%TMPDIR%\minecraft-agent-mod-%BRANCH%" (
  echo Unexpected archive layout.
  exit /b 1
)
if exist "%PROJ%" rmdir /s /q "%PROJ%"
move "%TMPDIR%\minecraft-agent-mod-%BRANCH%" "%PROJ%" >nul
if errorlevel 1 exit /b 1

echo Updating the MCP server...
if not exist "C:\MinecraftAgent" mkdir "C:\MinecraftAgent"
copy /y "%PROJ%\mcp_server\server.py" "%SERVER_DST%" >nul
if errorlevel 1 exit /b 1
> "%STATE%\version.txt" echo !LATEST_SHA!
del /q "%TMPZIP%" 2>nul
rmdir /s /q "%TMPDIR%" 2>nul
exit /b 0

:sync_notes
rem Copies obsidian\*.md from the repo into the vault. Never deletes files in the vault.
rem /XO: do not overwrite a note that is newer in the vault (your own edits are kept).
if not exist "%PROJ%\obsidian" (
  echo No obsidian folder in the project yet, skipping notes.
  exit /b 0
)
if not exist "%VAULT%" (
  echo Vault folder not found: %VAULT%
  echo Notes were NOT copied. Check the path at the top of update.cmd.
  exit /b 0
)
if not exist "%NOTES_DST%" mkdir "%NOTES_DST%"
robocopy "%PROJ%\obsidian" "%NOTES_DST%" *.md /E /XO /NP /NJH /NJS >nul
if errorlevel 8 (
  echo Notes copy failed.
) else (
  echo Notes synced to: %NOTES_DST%
)
exit /b 0

:fail
echo.
echo UPDATE FAILED. Send the text above to Claude.
pause
exit /b 1
