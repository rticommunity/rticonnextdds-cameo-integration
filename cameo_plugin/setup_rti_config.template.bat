@echo off
:: (c) Copyright, Real-Time Innovations, 2026.  All rights reserved.
:: RTI grants Licensee a license to use, modify, compile, and create derivative
:: works of the software solely for use with RTI Connext DDS. Licensee may
:: redistribute copies of the software provided that all such copies are subject
:: to this license. The software is provided "as is", with no warranty of any
:: type, including any warranty for fitness for any purpose. RTI is under no
:: obligation to maintain or support the software. RTI shall not be liable for
:: any incidental or consequential damages arising out of the use or inability
:: to use the software.
::
:: ============================================================
:: TEMPLATE: copy this file to setup_rti_config.bat (git-ignored)
:: Set NDDSHOME in the environment before running this script.
:: RTI_LICENSE_FILE is always set to NDDSHOME\rti_license.dat.
:: ============================================================
:: RTI Connext DDS + CAMEO environment setup, for 2024x or 2026x.
::
:: Usage
::   setup_rti_config.bat 2024x | 2026x
::   Double-clicking it (no argument) asks which version.
::
:: Opens a command window with NDDSHOME, CAMEO_HOME,
:: RTI_LICENSE_FILE, PATH and CAMEO_VERSION set, so build.bat and
:: install.bat can then be run there without a version argument.
::
:: NOTE (2026x): its own jre\ ships java.exe but NOT javac.exe, and
:: its API jars are Java 21 bytecode (class file version 65), which
:: 2024x's bundled JDK 17 cannot read ("class file has wrong version
:: 65.0, should be 61.0" -- confirmed live). javac must be available
:: on PATH: version 17+ for 2024x or 21+ for 2026x.
::
:: Self-elevates to Administrator (install.bat writes under
:: Program Files).
:: ============================================================

:: --- Which CAMEO version (asked for when double-clicked) ---
if not "%~1"=="" set "CAMEO_VERSION=%~1"
if "%CAMEO_VERSION%"=="" set /p "CAMEO_VERSION=CAMEO version (2024x or 2026x): "
if /i not "%CAMEO_VERSION%"=="2024x" if /i not "%CAMEO_VERSION%"=="2026x" (
    echo ERROR: unknown CAMEO version "%CAMEO_VERSION%" -- expected 2024x or 2026x.
    pause
    exit /b 1
)

:: Elevated relaunch can land in a different working directory (often
:: System32) -- explicitly move to this script's own directory (cameo_plugin)
:: so build.bat/install.bat/etc. can be run directly from here afterward.
cd /d "%~dp0"

echo ============================================================
echo  Setting up RTI Connext DDS + CAMEO %CAMEO_VERSION% environment
echo ============================================================

:: --- Core environment variables ---
if not defined NDDSHOME (
    echo ERROR: NDDSHOME is not set. Set it to your RTI Connext DDS installation directory.
    goto setup_failed
)
set "RTI_LICENSE_FILE=%NDDSHOME%\rti_license.dat"
if /i "%CAMEO_VERSION%"=="2024x" (
    if not defined CAMEO_HOME set "CAMEO_HOME=C:\Program Files\Magic Systems of Systems Architect"
) else (
    if not defined CAMEO_HOME set "CAMEO_HOME=C:\Program Files\Magic Cyber Systems Engineer"
)

:: --- Print what got set, for sanity checking ---
echo CAMEO_VERSION   = %CAMEO_VERSION%
echo NDDSHOME        = %NDDSHOME%
echo CAMEO_HOME      = %CAMEO_HOME%
echo RTI_LICENSE_FILE= %RTI_LICENSE_FILE%
echo.

:: --- Sanity checks ---
set "RTI_SETUP_FAILED="
where javac >nul 2>&1
if errorlevel 1 (
    echo ERROR: javac is not available. Add a JDK bin directory to PATH.
    set "RTI_SETUP_FAILED=1"
)
if not exist "%CAMEO_HOME%\lib" (
    echo ERROR: CAMEO library directory "%CAMEO_HOME%\lib" not found. Set CAMEO_HOME.
    set "RTI_SETUP_FAILED=1"
)
if not exist "%RTI_LICENSE_FILE%" (
    echo ERROR: License file "%RTI_LICENSE_FILE%" not found. Place rti_license.dat in NDDSHOME.
    set "RTI_SETUP_FAILED=1"
)
if not exist "%NDDSHOME%\lib\x64Win64VS2017\nddsjava.dll" (
    echo ERROR: Native DDS library "%NDDSHOME%\lib\x64Win64VS2017\nddsjava.dll" not found.
    set "RTI_SETUP_FAILED=1"
)
if not exist "%NDDSHOME%\lib\java\nddsjava.jar" (
    echo ERROR: DDS Java library "%NDDSHOME%\lib\java\nddsjava.jar" not found.
    set "RTI_SETUP_FAILED=1"
)
for %%F in (build.bat install.bat run_cameo.bat) do if not exist "%~dp0%%F" (
    echo ERROR: Required script "%~dp0%%F" is missing from this checkout.
    set "RTI_SETUP_FAILED=1"
)
if defined RTI_SETUP_FAILED goto setup_failed

set "RTI_JAVA_MAJOR="
for /f "tokens=2 delims=. " %%V in ('javac -version 2^>^&1') do set "RTI_JAVA_MAJOR=%%V"
echo(%RTI_JAVA_MAJOR%| findstr /r /x "[0-9][0-9]*" >nul
if errorlevel 1 (
    echo ERROR: Could not determine the JDK version. Check javac on PATH.
    goto setup_failed
)
set "RTI_JAVA_MINIMUM=17"
if /i "%CAMEO_VERSION%"=="2026x" set "RTI_JAVA_MINIMUM=21"
if %RTI_JAVA_MAJOR% lss %RTI_JAVA_MINIMUM% (
    echo ERROR: CAMEO %CAMEO_VERSION% requires JDK %RTI_JAVA_MINIMUM% or newer; found %RTI_JAVA_MAJOR%. Update javac on PATH.
    goto setup_failed
)

:: --- Self-elevate if not already running as admin ---
:: Pass process-local configuration explicitly to the elevated process.
:: Keep its command window open even if setup exits with an error.
set "RTI_SETUP_SCRIPT=%~f0"
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator privileges...
    powershell -NoProfile -Command "$bootstrap = ''; foreach ($name in 'NDDSHOME','CAMEO_HOME','PATH','RTI_LICENSE_FILE','CAMEO_VERSION','RTI_SETUP_SCRIPT') { $value = [Environment]::GetEnvironmentVariable($name); if ($null -ne $value) { $bootstrap += '$env:' + $name + ' = ''' + $value.Replace('''','''''') + '''; ' } }; $bootstrap += '$arguments = ''call '' + [char]34 + $env:RTI_SETUP_SCRIPT + [char]34 + '' '' + $env:CAMEO_VERSION; Start-Process -FilePath $env:ComSpec -ArgumentList ''/d'',''/k'',$arguments -WindowStyle Normal -ErrorAction Stop'; $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($bootstrap)); Start-Process powershell.exe -ArgumentList '-NoProfile','-EncodedCommand',$encoded -WindowStyle Hidden -Verb RunAs -ErrorAction Stop"
    if errorlevel 1 (
        echo ERROR: Could not open the administrator command window.
        goto setup_failed
    )
    exit /b
)

:: --- PATH additions (native libs + RTI bin tools) ---
set "PATH=%NDDSHOME%\lib\x64Win64VS2017;%NDDSHOME%\bin;%PATH%"

echo.
echo Environment setup complete. In this window you can now run:
echo   build.bat      ^(builds for %CAMEO_VERSION%^)
echo   install.bat    ^(installs into %CAMEO_HOME%^)
echo   run_cameo.bat
echo.

cmd /k
exit /b

:setup_failed
echo.
echo Setup failed. Correct the errors above and run this script again.
pause
exit /b 1
