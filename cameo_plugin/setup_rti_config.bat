@echo off
:: ============================================================
:: RTI Connext DDS + CAMEO environment setup and launcher
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

echo ============================================================
echo  Setting up RTI Connext DDS + CAMEO environment
echo ============================================================

:: --- Core environment variables ---
set NDDSHOME=C:\Program Files\rti_connext_dds-7.5.0
set CAMEO_HOME=C:\Program Files\Magic Systems of Systems Architect
set RTIJDKHOME=%CAMEO_HOME%\jre
set RTI_LICENSE_FILE=C:\Users\MKG11\Documents\rti_workspace\7.5.0\rti_license.dat

:: --- PATH additions (native libs + RTI bin tools) ---
set PATH=%NDDSHOME%\lib\x64Win64VS2017;%NDDSHOME%\bin;%PATH%

:: --- Print what got set, for sanity checking ---
echo NDDSHOME        = %NDDSHOME%
echo CAMEO_HOME      = %CAMEO_HOME%
echo RTIJDKHOME      = %RTIJDKHOME%
echo RTI_LICENSE_FILE= %RTI_LICENSE_FILE%
echo

:: --- Sanity checks ---
if not exist "%RTIJDKHOME%\bin\javac.exe" (
    echo WARNING: javac.exe not found at %RTIJDKHOME%\bin - JDK path may be wrong.
)
if not exist "%RTI_LICENSE_FILE%" (
    echo WARNING: License file not found at %RTI_LICENSE_FILE%
)
if not exist "%NDDSHOME%\lib\x64Win64VS2017\nddsjava.dll" (
    echo WARNING: Native DDS libraries not found under %NDDSHOME%\lib\x64Win64VS2017
)

echo.
echo Environment setup complete.
echo You can now run RTI/CAMEO commands in this window.
echo.

cmd /k

