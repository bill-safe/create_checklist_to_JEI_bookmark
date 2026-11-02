@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_windows.ps1"
if errorlevel 1 (
  echo Build failed. Verify Java 21 and an internet connection.
  pause
  exit /b 1
)
echo Open build\libs and copy the mod JAR to .minecraft\mods
pause
