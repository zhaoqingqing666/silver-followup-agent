@echo off
setlocal

title Silver Follow-up Assistant - Frontend
set "PROJECT_ROOT=%~dp0"
set "FRONTEND_DIR=%PROJECT_ROOT%frontend"

echo.
echo ========================================
echo   Silver Follow-up Assistant
echo   Frontend quick start
echo ========================================
echo.

if not exist "%FRONTEND_DIR%\package.json" (
  echo [ERROR] frontend\package.json was not found.
  echo Put this BAT file in the project root folder.
  goto :failed
)

if exist "C:\Program Files\nodejs\node.exe" set "PATH=C:\Program Files\nodejs;%PATH%"

where node.exe >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Node.js was not found.
  echo Install Node.js 22.13 or newer, then run this file again.
  echo Download: https://nodejs.org/
  goto :failed
)

cd /d "%FRONTEND_DIR%"
if errorlevel 1 (
  echo [ERROR] Cannot enter the frontend folder.
  goto :failed
)

echo Node version:
node.exe --version
echo.

if not exist "node_modules" (
  echo [FIRST RUN] Installing dependencies. This may take a few minutes...
  call npm.cmd install
  if errorlevel 1 goto :install_failed
  echo.
  echo [OK] Dependencies installed.
  echo.
)

echo Starting the page at http://localhost:3000
echo Keep this window open. Close it to stop the frontend.
echo.

start "" "http://localhost:3000"
call npm.cmd run dev
if errorlevel 1 goto :run_failed
goto :end

:install_failed
echo.
echo [ERROR] npm install failed. Send a screenshot of this window.
goto :failed

:run_failed
echo.
echo [ERROR] The frontend failed to start. Send a screenshot of this window.
goto :failed

:failed
echo.
pause

:end
endlocal
