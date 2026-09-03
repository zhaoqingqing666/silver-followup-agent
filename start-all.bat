@echo off
setlocal

title Silver Follow-up Assistant - Full Project
set "PROJECT_ROOT=%~dp0"
set "BACKEND_DIR=%PROJECT_ROOT%backend"

echo.
echo ========================================
echo   Silver Follow-up Assistant
echo   Full project quick start
echo ========================================
echo.

if exist "C:\Program Files\nodejs\node.exe" set "PATH=C:\Program Files\nodejs;%PATH%"

where java.exe >nul 2>nul
if errorlevel 1 (
  echo [MISSING] Java was not found.
  echo Run the backend in IDEA, or use the frontend-only BAT file.
  goto :failed
)

where mvn.cmd >nul 2>nul
if errorlevel 1 (
  echo [MISSING] Maven was not found.
  echo Run SilverAgentApplication in IDEA first.
  echo Then use the frontend-only BAT file.
  goto :failed
)

if not exist "%BACKEND_DIR%\pom.xml" (
  echo [ERROR] backend\pom.xml was not found.
  goto :failed
)

echo [1/2] Starting the Java backend in a new window...
start "Silver Agent - Backend" cmd /k "cd /d ""%BACKEND_DIR%"" ^&^& mvn.cmd spring-boot:run"

echo [2/2] Starting the frontend...
call "%PROJECT_ROOT%start-frontend.bat"
goto :end

:failed
echo.
pause

:end
endlocal
