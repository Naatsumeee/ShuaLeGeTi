@echo off
title ShuaGeLeTi Emulator
setlocal enabledelayedexpansion
set SDK=C:\Users\xutia\AppData\Local\Android\Sdk
set EMU=%SDK%\emulator\emulator.exe
set ADB=%SDK%\platform-tools\adb.exe
set PING=%SystemRoot%\System32\ping.exe
set FINDSTR=%SystemRoot%\System32\findstr.exe

echo ============================================
echo   ShuaGeLeTi - Start Android Emulator
echo   Pixel 6 / API 35, first boot ~1 min
echo   app-debug.apk (same folder) auto-installs
echo   Safe to re-run: skips boot if already on
echo ============================================

if not exist "%EMU%" (
  echo [ERROR] emulator.exe not found: %EMU%
  pause
  exit /b 1
)

rem ---- check if emulator already booted ----
set BOOT=
for /f "delims=" %%i in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set BOOT=%%i
if "!BOOT!"=="1" (
  echo Emulator already running, skip boot.
  goto install
)

echo Starting emulator window...
start "Android Emulator" "%EMU%" -avd shua_test -gpu swiftshader_indirect -memory 3072 -no-snapshot -no-boot-anim

echo Waiting for device...
"%ADB%" wait-for-device

echo Waiting for boot (max 180s)...
set /a TRIES=0
:wait_boot
set BOOT=
for /f "delims=" %%i in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set BOOT=%%i
if "!BOOT!"=="1" goto install
set /a TRIES+=1
if !TRIES! GEQ 90 (
  echo [TIMEOUT] Emulator did not finish booting in 180s.
  echo If the emulator window shows an error, tell the agent.
  pause
  exit /b 1
)
%PING% -n 3 127.0.0.1 >nul
goto wait_boot

:install
echo Boot complete.
if exist "%~dp0app-debug.apk" (
  echo Installing APK...
  "%ADB%" install -r "%~dp0app-debug.apk"
) else (
  echo [SKIP] app-debug.apk not found next to this script.
)

echo Launching app...
"%ADB%" shell am start -n com.examhelper.shua/.MainActivity
echo.
echo Done! Use the app inside the emulator window.
echo Close the emulator window to shut it down.
pause
