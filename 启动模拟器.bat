@echo off
title ShuaGeLeTi Emulator
setlocal enabledelayedexpansion
set SDK=C:\Users\xutia\AppData\Local\Android\Sdk
set EMU=%SDK%\emulator\emulator.exe
set ADB=%SDK%\platform-tools\adb.exe
set PING=%SystemRoot%\System32\ping.exe

echo ============================================
echo   ShuaGeLeTi - Start Android Emulator
echo   Pixel 6 / API 35, first boot ~1 min
echo   Auto-installs APK matching device ABI
echo   Safe to re-run: skips boot if already on
echo ============================================

if not exist "%EMU%" (
  echo [ERROR] emulator.exe not found: %EMU%
  pause
  exit /b 1
)

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
  pause
  exit /b 1
)
%PING% -n 3 127.0.0.1 >nul
goto wait_boot

:install
echo Boot complete.
rem pick APK by device ABI
set ABI=
for /f "delims=" %%i in ('"%ADB%" shell getprop ro.product.cpu.abi 2^>nul') do set ABI=%%i
echo Device ABI: !ABI!
set APK=%~dp0app-!ABI!-debug.apk
if not exist "%APK%" set APK=%~dp0app-debug.apk
if exist "%APK%" (
  echo Installing %APK% ...
  "%ADB%" install -r "%APK%"
) else (
  echo [SKIP] no APK found next to this script.
)

echo Launching app...
"%ADB%" shell am start -n com.examhelper.shua/.MainActivity
echo.
echo Done! Close the emulator window to shut it down.
pause
