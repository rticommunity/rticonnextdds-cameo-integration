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
:: install.bat — Deploy the RTI Connext DDS plugin to CAMEO System Modeler 2024x
::
:: Prerequisites
::   CAMEO_HOME — CAMEO installation root (same as used in build.bat)
::   The plugin must have been built first with build.bat.
::
:: What this script does
::   1. Creates  %CAMEO_HOME%\plugins\com.rti.connext.cameo\
::   2. Copies   plugin.xml  and  build\*.jar  into that directory's lib\
::   3. Prints a reminder about the native library PATH requirement
:: =============================================================================
setlocal enabledelayedexpansion

:: Strip any surrounding quotes / trailing backslash that users may have
:: included in the set command. Each variable must be guarded by "if defined"
:: AND use delayed expansion (!VAR!) for the stripping itself. A variable
:: that is genuinely undefined does NOT safely expand under %VAR:...%
:: substitution syntax (find/replace or substring) — it can leave stray
:: characters (e.g. a bare quote, or literally "~-1") in the command line,
:: which corrupts the variable's value or breaks parsing outright with
:: "The syntax of the command is incorrect." Guarding with "if defined" and
:: using delayed expansion defers evaluation until the block actually runs
:: (i.e. only when the variable really is set), avoiding this entirely.
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
    echo        Set it to the CAMEO System Modeler installation root, e.g.:
    echo          set CAMEO_HOME=C:\Program Files\Cameo Systems Modeler
    exit /b 1
)

if not exist build\RTIConnextPlugin.jar (
    echo ERROR: build\RTIConnextPlugin.jar not found. Run build.bat first.
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

copy /Y plugin.xml               "%PLUGIN_DIR%\plugin.xml" >nul
if errorlevel 1 (
    echo ERROR: Failed to copy plugin.xml to "%PLUGIN_DIR%".
    echo        Run this script from an elevated ^(Administrator^) command prompt.
    exit /b 1
)
copy /Y build\RTIConnextPlugin.jar "%PLUGIN_DIR%\lib\"     >nul
if errorlevel 1 (
    echo ERROR: Failed to copy build\RTIConnextPlugin.jar to "%PLUGIN_DIR%\lib".
    echo        Run this script from an elevated ^(Administrator^) command prompt.
    exit /b 1
)
copy /Y build\nddsjava.jar         "%PLUGIN_DIR%\lib\"     >nul
if errorlevel 1 (
    echo ERROR: Failed to copy build\nddsjava.jar to "%PLUGIN_DIR%\lib".
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
echo  Plugin installed successfully.
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
echo  Then (re)start CAMEO. The plugin menu appears at:
echo    Tools ^> RTI Connext DDS
echo ============================================================
echo.

endlocal
