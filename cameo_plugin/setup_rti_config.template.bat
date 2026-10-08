@echo off
:: ============================================================
:: TEMPLATE: copy this file to setup_rti_config.bat (git-ignored)
:: and edit the paths under "Core environment variables" to match
:: your own RTI Connext / CAMEO installs and license location.
:: ============================================================
:: RTI Connext DDS + CAMEO environment setup, for 2024x or 2026x.
::
:: Usage
::   setup_rti_config.bat 2024x | 2026x
::   Double-clicking it (no argument) asks which version.
::
:: Opens a command window with NDDSHOME, CAMEO_HOME, RTIJDKHOME,
:: RTI_LICENSE_FILE, PATH and CAMEO_VERSION set, so build.bat and
:: install.bat can then be run there without a version argument.
::
:: NOTE (2026x): its own jre\ ships java.exe but NOT javac.exe, and
:: its API jars are Java 21 bytecode (class file version 65), which
:: 2024x's bundled JDK 17 cannot read ("class file has wrong version
:: 65.0, should be 61.0" -- confirmed live). RTIJDKHOME must point
:: at a real JDK 21+ install for 2026x; it does not need to be
:: bundled with CAMEO.
::
:: Self-elevates to Administrator (install.bat writes under
:: Program Files).
:: ============================================================

:: --- Which CAMEO version (asked for when double-clicked) ---
set "CAMEO_VERSION=%~1"
if "%CAMEO_VERSION%"=="" set /p "CAMEO_VERSION=CAMEO version (2024x or 2026x): "
if /i not "%CAMEO_VERSION%"=="2024x" if /i not "%CAMEO_VERSION%"=="2026x" (
    echo ERROR: unknown CAMEO version "%CAMEO_VERSION%" -- expected 2024x or 2026x.
    pause
    exit /b 1
)

:: --- Self-elevate if not already running as admin ---
:: The version is passed on explicitly: the elevated relaunch is a new
:: process and would otherwise start without it.
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator privileges...
    powershell -Command "Start-Process '%~f0' -ArgumentList '%CAMEO_VERSION%' -Verb RunAs"
    exit /b
)

:: Elevated relaunch can land in a different working directory (often
:: System32) -- explicitly move to this script's own directory (cameo_plugin)
:: so build.bat/install.bat/etc. can be run directly from here afterward.
cd /d "%~dp0"

echo ============================================================
echo  Setting up RTI Connext DDS + CAMEO %CAMEO_VERSION% environment
echo ============================================================

:: --- Core environment variables ---
set NDDSHOME=C:\Program Files\rti_connext_dds-7.5.0
set RTI_LICENSE_FILE=%USERPROFILE%\rti_workspace\7.5.0\rti_license.dat
if /i "%CAMEO_VERSION%"=="2024x" (
    set "CAMEO_HOME=C:\Program Files\Magic Systems of Systems Architect"
    rem 2024x's bundled jre\ is a JDK 17 and includes javac.
    if not defined RTIJDKHOME set "RTIJDKHOME=C:\Program Files\Magic Systems of Systems Architect\jre"
) else (
    set "CAMEO_HOME=C:\Program Files\Magic Cyber Systems Engineer"
    rem 2026x needs a separate JDK 21+ (see the NOTE above).
    if not defined RTIJDKHOME set "RTIJDKHOME=C:\Program Files\Semeru\jdk-21.0.7.6-openj9"
)

:: --- PATH additions (native libs + RTI bin tools) ---
set PATH=%NDDSHOME%\lib\x64Win64VS2017;%NDDSHOME%\bin;%PATH%

:: --- Print what got set, for sanity checking ---
echo CAMEO_VERSION   = %CAMEO_VERSION%
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
echo Environment setup complete. In this window you can now run:
echo   build.bat      ^(builds for %CAMEO_VERSION%^)
echo   install.bat    ^(installs into %CAMEO_HOME%^)
echo   run_cameo.bat
echo.

cmd /k
