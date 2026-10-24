@echo off
chcp 65001 >nul
title 刷了个题 - 测试模拟器
echo ============================================
echo   刷了个题 - 启动 Android 模拟器 (Pixel 6 / API 35)
echo   首次启动约 1 分钟，窗口关闭即模拟器关机
echo   新 APK 放在本文件同目录会自动安装
echo ============================================
set SDK=C:\Users\xutia\AppData\Local\Android\Sdk

if exist "%~dp0app-debug.apk" (
  echo [提示] 检测到 APK，模拟器启动后将自动安装...
)

echo 正在启动模拟器...
start "" "%SDK%\emulator\emulator.exe" -avd shua_test -gpu auto -memory 3072

echo 等待设备上线...
"%SDK%\platform-tools\adb.exe" wait-for-device
:wait_boot
for /f %%i in ('"%SDK%\platform-tools\adb.exe" shell getprop sys.boot_completed 2^>nul') do set BOOT=%%i
if not "%BOOT%"=="1" (
  timeout /t 2 /nobreak >nul
  goto wait_boot
)
echo 模拟器启动完成。

if exist "%~dp0app-debug.apk" (
  echo 安装 APK...
  "%SDK%\platform-tools\adb.exe" install -r "%~dp0app-debug.apk"
)

echo 启动应用...
"%SDK%\platform-tools\adb.exe" shell am start -n com.examhelper.shua/.MainActivity
echo 完成！模拟器窗口已就绪，可以直接在里面操作 App。
pause
