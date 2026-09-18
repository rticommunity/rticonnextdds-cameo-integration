@echo off
:: ============================================================
:: RTI Connext DDS + CAMEO 26x environment setup and launcher
:: Same shape as setup_rti_config.bat, pointed at the 2026x
:: (Magic Cyber Systems Engineer) install instead of 2024x.
::
:: NOTE: 2026x's own jre\ ships java.exe but NOT javac.exe (JRE
:: only, no compiler) -- and its own API jars are compiled to
:: Java 21 bytecode (class file version 65), which the 2024x
:: install's bundled JDK 17 cannot even read for classpath
:: resolution ("class file has wrong version 65.0, should be
:: 61.0" -- confirmed live). RTIJDKHOME below MUST point at a
:: real JDK 21+ install; it does NOT need to be bundled with
:: CAMEO_HOME itself, it just needs a modern-enough javac.
::
:: Run this file directly (double-click) - it self-elevates to
:: Administrator automatically.
:: ============================================================

:: --- Self-elevate if not already running as admin ---
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator privileges...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

:: Elevated relaunch can land in a different working directory (often
:: System32) -- explicitly move to this script's own directory (cameo_plugin)
:: so build_26x.bat/install_26x.bat/etc. can be run directly from here
:: afterward.
cd /d "%~dp0"

echo ============================================================
echo  Setting up RTI Connext DDS + CAMEO 26x environment
echo ============================================================

:: --- Core environment variables ---
set NDDSHOME=C:\Program Files\rti_connext_dds-7.5.0
set CAMEO_HOME=C:\Program Files\Magic Cyber Systems Engineer
set RTI_LICENSE_FILE=C:\Users\MKG11\Documents\rti_workspace\7.5.0\rti_license.dat

:: --- Locate a JDK 21+ install for RTIJDKHOME ---
:: 2026x's own jre\ has no javac -- a JDK 21 (Semeru/OpenJ9) is already
:: installed on this machine and matches this system's JAVA_HOME, so use
:: it directly rather than assuming a Temurin/Adoptium layout.
if not defined RTIJDKHOME set "RTIJDKHOME=C:\Program Files\Semeru\jdk-21.0.7.6-openj9"

:: --- PATH additions (native libs + RTI bin tools) ---
set PATH=%NDDSHOME%\lib\x64Win64VS2017;%NDDSHOME%\bin;%PATH%

:: --- Print what got set, for sanity checking ---
echo NDDSHOME        = %NDDSHOME%
echo CAMEO_HOME      = %CAMEO_HOME%
echo RTIJDKHOME      = %RTIJDKHOME%
echo RTI_LICENSE_FILE= %RTI_LICENSE_FILE%
echo.

:: --- Sanity checks ---
if not exist "%RTIJDKHOME%\bin\javac.exe" (
    echo WARNING: javac.exe not found at %RTIJDKHOME%\bin - JDK path may be wrong.
)
if not exist "%CAMEO_HOME%\lib" (
    echo WARNING: CAMEO_HOME does not look like a valid install - %CAMEO_HOME%\lib not found.
)
if not exist "%RTI_LICENSE_FILE%" (
    echo WARNING: License file not found at %RTI_LICENSE_FILE%
)
if not exist "%NDDSHOME%\lib\x64Win64VS2017\nddsjava.dll" (
    echo WARNING: Native DDS libraries not found under %NDDSHOME%\lib\x64Win64VS2017
)

echo.
echo Environment setup complete.
echo You can now run RTI/CAMEO 26x build/install commands in this window.
echo.

cmd /k
