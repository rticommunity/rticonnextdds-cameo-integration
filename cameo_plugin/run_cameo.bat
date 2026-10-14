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
:: run_cameo.bat -- launches whichever CAMEO install CAMEO_HOME
:: currently points at (set by setup_rti_config.bat 2024x|2026x --
:: run it first in this same window). Works for both without needing two separate
:: launcher scripts, since the two products ship differently-named
:: executables:
::   2024x (Magic Systems of Systems Architect) -> bin\msosa.exe
::   2026x (Magic Cyber Systems Engineer)       -> bin\mcse.exe
:: confirmed against both real installs on this machine -- this
:: script tries both names under %CAMEO_HOME%\bin and launches
:: whichever exists.
:: ============================================================
setlocal enabledelayedexpansion

if defined CAMEO_HOME (
    set "CAMEO_HOME=%CAMEO_HOME:"=%"
    if "!CAMEO_HOME:~-1!"=="\" set "CAMEO_HOME=!CAMEO_HOME:~0,-1!"
)

if "%CAMEO_HOME%"=="" (
    echo ERROR: CAMEO_HOME is not set.
    echo        Run setup_rti_config.bat 2024x^|2026x
    echo        first in this window, or set CAMEO_HOME manually.
    exit /b 1
)

:: Make sure the RTI Connext native libraries are on PATH for this launch,
:: same requirement install.bat reminds you of after
:: deploying the plugin -- harmless no-op if NDDSHOME isn't set (e.g. you
:: only want to run CAMEO itself, not test the plugin).
if defined NDDSHOME (
    set "NDDS_ARCH_DIR="
    for /d %%d in ("%NDDSHOME%\lib\x64Win64*") do (
        if "!NDDS_ARCH_DIR!"=="" set "NDDS_ARCH_DIR=%%d"
    )
    if not "!NDDS_ARCH_DIR!"=="" set "PATH=!NDDS_ARCH_DIR!;%PATH%"
)

set "CAMEO_EXE="
if exist "%CAMEO_HOME%\bin\msosa.exe" set "CAMEO_EXE=%CAMEO_HOME%\bin\msosa.exe"
if exist "%CAMEO_HOME%\bin\mcse.exe" set "CAMEO_EXE=%CAMEO_HOME%\bin\mcse.exe"

if "%CAMEO_EXE%"=="" (
    echo ERROR: Neither bin\msosa.exe nor bin\mcse.exe found under
    echo        CAMEO_HOME=%CAMEO_HOME%
    echo        If this is a different CAMEO edition, add its executable name
    echo        to run_cameo.bat.
    exit /b 1
)

echo Launching %CAMEO_EXE% ...
start "" "%CAMEO_EXE%"

endlocal
