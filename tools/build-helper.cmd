@echo off
rem ---------------------------------------------------------------------------
rem build-helper.cmd -- compile the optional wrong_seq root helper and install it
rem on a connected device.
rem
rem The helper is NOT packaged in the APK: it is a standalone binary a rooted
rem device installs to /data/local/tmp. The app looks for it there (and in its
rem own nativeLibraryDir) at connect time.
rem
rem Usage:
rem   tools\build-helper.cmd            (build + push to the connected device)
rem   tools\build-helper.cmd build-only (build without pushing)
rem
rem Requires ANDROID_NDK_HOME or ANDROID_NDK_ROOT to point at an NDK.
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "SRC=%SCRIPT_DIR%..\app\src\main\cpp\spoof_helper.c"
set "OUT=%SCRIPT_DIR%build"

if not exist "%SRC%" (
    echo [!] cannot find spoof_helper.c at %SRC%
    exit /b 1
)

set "NDK=%ANDROID_NDK_HOME%"
if "%NDK%"=="" set "NDK=%ANDROID_NDK_ROOT%"
if "%NDK%"=="" (
    echo [!] set ANDROID_NDK_HOME to your Android NDK, e.g.
    echo     set ANDROID_NDK_HOME=C:\Android\ndk\26.3.11579264
    exit /b 1
)

set "CLANG="
for /d %%D in ("%NDK%\toolchains\llvm\prebuilt\*") do set "CLANG=%%D\bin"

if not exist "%CLANG%" (
    echo [!] no clang toolchain under %NDK%\toolchains\llvm\prebuilt
    exit /b 1
)

if not exist "%OUT%" mkdir "%OUT%"

rem Build for every 64-bit ABI the app supports; 32-bit devices are rare and
rem aarch64/armv7 cover everything a rooted phone runs today.
set "TARGETS=aarch64-linux-android24 armv7a-linux-androideabi24"
set "BUILT="
for %%T in (%TARGETS%) do (
    if "%%T"=="aarch64-linux-android24" (set "EXE=spoofhelper-arm64") else (set "EXE=spoofhelper-arm")
    echo [*] building %%T -^> %OUT%\!EXE!
    "%CLANG%\%%T-clang" "%SRC%" -o "%OUT%\!EXE!" -pthread -O2 -s || exit /b 1
    set "BUILT=!BUILT! %OUT%\!EXE!"
)

echo [+] built:!BUILT!

if /i "%~1"=="build-only" (
    echo [*] build-only requested, skipping install
    exit /b 0
)

for %%F in (%BUILT%) do (
    echo [*] installing %%~nxF
    adb push "%%F" /data/local/tmp/spoofhelper || (echo [!] adb push failed & exit /b 1)
    adb shell chmod 755 /data/local/tmp/spoofhelper || exit /b 1
)

echo [+] done. Enable "Root mode" in the app and pick the wrong_seq desync.
endlocal
