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
:: uninstall.bat — Remove the RTI Connext DDS plugin from CAMEO System Modeler 2024x
::
:: Prerequisites
::   CAMEO_HOME — CAMEO installation root (same as used in build.bat/install.bat)
::
:: What this script does
::   1. Removes  %CAMEO_HOME%\plugins\com.rti.connext.cameo\  (entire folder,
::      including plugin.xml, lib\, and resources\)
:: =============================================================================
setlocal enabledelayedexpansion

:: Strip any surrounding quotes / trailing backslash that users may have
:: included in the set command. CAMEO_HOME must be guarded by "if defined"
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

if "%CAMEO_HOME%"=="" (
    echo ERROR: CAMEO_HOME is not set.
    echo        Set it to the CAMEO System Modeler installation root, e.g.:
    echo          set CAMEO_HOME=C:\Program Files\Cameo Systems Modeler
    exit /b 1
)

set "PLUGIN_DIR=%CAMEO_HOME%\plugins\com.rti.connext.cameo"

if not exist "%PLUGIN_DIR%" (
    echo Nothing to do - "%PLUGIN_DIR%" does not exist.
    exit /b 0
)

echo Removing plugin from:  %PLUGIN_DIR%
echo.

rmdir /S /Q "%PLUGIN_DIR%"
if errorlevel 1 (
    echo ERROR: Failed to remove "%PLUGIN_DIR%".
    echo        Run this script from an elevated ^(Administrator^) command prompt.
    echo        Also make sure CAMEO System Modeler is closed ^(files may be locked^).
    exit /b 1
)

if exist "%PLUGIN_DIR%" (
    echo ERROR: "%PLUGIN_DIR%" still exists after the removal attempt.
    echo        Run this script from an elevated ^(Administrator^) command prompt.
    echo        Also make sure CAMEO System Modeler is closed ^(files may be locked^).
    exit /b 1
)

echo ============================================================
echo  Plugin uninstalled successfully.
echo ============================================================
echo.
echo  Restart CAMEO for the change to take effect.
echo ============================================================
echo.

endlocal
