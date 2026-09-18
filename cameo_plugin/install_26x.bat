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
:: =============================================================================
:: install_26x.bat -- Deploy the RTI Connext DDS plugin to CAMEO 2026x
::
:: Same shape as install.bat -- see that file for the 2024x deployer. Differs
:: only in reading from build_26x\ instead of build\, and copying
:: plugin_26x.xml (declares SimulationToolkit version="2026x Refresh1"
:: internalVersion="202600000", matching what's actually installed -- confirmed
:: live against the real plugin.xml under CAMEO_HOME's own CST plugin
:: directory) instead of the 2024x-targeted plugin.xml.
::
:: Prerequisites
::   CAMEO_HOME -- CAMEO 2026x installation root (same as used in build_26x.bat)
::   The plugin must have been built first with build_26x.bat.
::
:: What this script does
::   1. Creates  %CAMEO_HOME%\plugins\com.rti.connext.cameo\
::   2. Copies   plugin_26x.xml (as plugin.xml) and build_26x\*.jar into that
::               directory's lib\
::   3. Prints a reminder about the native library PATH requirement
:: =============================================================================
setlocal enabledelayedexpansion

:: Strip any surrounding quotes / trailing backslash that users may have
:: included in the set command. See build.bat's own comment for why this
:: guarding shape (if defined + delayed expansion) is needed.
if defined CAMEO_HOME (
    set "CAMEO_HOME=%CAMEO_HOME:"=%"
    if "!CAMEO_HOME:~-1!"=="\" set "CAMEO_HOME=!CAMEO_HOME:~0,-1!"
)
if defined NDDSHOME (
    set "NDDSHOME=%NDDSHOME:"=%"
    if "!NDDSHOME:~-1!"=="\" set "NDDSHOME=!NDDSHOME:~0,-1!"
)

if "%CAMEO_HOME%"=="" (
    echo ERROR: CAMEO_HOME is not set.
    echo        Set it to the CAMEO 2026x installation root, e.g.:
    echo          set CAMEO_HOME=C:\Program Files\Magic Cyber Systems Engineer
    exit /b 1
)

if not exist build_26x\RTIConnextPlugin.jar (
    echo ERROR: build_26x\RTIConnextPlugin.jar not found. Run build_26x.bat first.
    exit /b 1
)

set "PLUGIN_DIR=%CAMEO_HOME%\plugins\com.rti.connext.cameo"

echo Installing plugin to:  %PLUGIN_DIR%
echo.

if not exist "%PLUGIN_DIR%" mkdir "%PLUGIN_DIR%"
if not exist "%PLUGIN_DIR%" (
    echo ERROR: Failed to create "%PLUGIN_DIR%".
    echo        Run this script from an elevated ^(Administrator^) command prompt.
    exit /b 1
)
if not exist "%PLUGIN_DIR%\lib" mkdir "%PLUGIN_DIR%\lib"
if not exist "%PLUGIN_DIR%\lib" (
    echo ERROR: Failed to create "%PLUGIN_DIR%\lib".
    echo        Run this script from an elevated ^(Administrator^) command prompt.
    exit /b 1
)

copy /Y plugin_26x.xml            "%PLUGIN_DIR%\plugin.xml" >nul
if errorlevel 1 (
    echo ERROR: Failed to copy plugin_26x.xml to "%PLUGIN_DIR%".
    echo        Run this script from an elevated ^(Administrator^) command prompt.
    exit /b 1
)
copy /Y build_26x\RTIConnextPlugin.jar "%PLUGIN_DIR%\lib\"     >nul
if errorlevel 1 (
    echo ERROR: Failed to copy build_26x\RTIConnextPlugin.jar to "%PLUGIN_DIR%\lib".
    echo        Run this script from an elevated ^(Administrator^) command prompt.
    exit /b 1
)
copy /Y build_26x\nddsjava.jar          "%PLUGIN_DIR%\lib\"     >nul
if errorlevel 1 (
    echo ERROR: Failed to copy build_26x\nddsjava.jar to "%PLUGIN_DIR%\lib".
    echo        Run this script from an elevated ^(Administrator^) command prompt.
    exit /b 1
)
if not exist "%PLUGIN_DIR%\resources" mkdir "%PLUGIN_DIR%\resources"
xcopy /Y /E resources\* "%PLUGIN_DIR%\resources\"         >nul
if errorlevel 1 (
    echo ERROR: Failed to copy resources\* to "%PLUGIN_DIR%\resources".
    echo        Run this script from an elevated ^(Administrator^) command prompt.
    exit /b 1
)

echo ============================================================
echo  Plugin installed successfully ^(2026x^).
echo ============================================================
echo.
echo  REQUIRED: add the Connext native library folder to the system
echo  PATH before starting CAMEO, so the JNI libraries are found:
echo.
if defined NDDSHOME (
    set "ARCH_DIR="
    for /d %%d in ("%NDDSHOME%\lib\x64Win64*") do (
        if "!ARCH_DIR!"=="" set "ARCH_DIR=%%d"
    )
    if "!ARCH_DIR!"=="" (
        echo    ^(NDDSHOME set but no x64Win64* directory found^)
    ) else (
        echo    !ARCH_DIR!
    )
) else (
    echo    ^%%NDDSHOME^%%\lib\x64Win64VS2017
)
echo.
echo  Add it permanently via:
echo    System Properties ^> Environment Variables ^> PATH
echo.
echo  Then (re)start CAMEO 2026x. The plugin menu appears at:
echo    Tools ^> RTI Connext DDS
echo ============================================================
echo.

endlocal
