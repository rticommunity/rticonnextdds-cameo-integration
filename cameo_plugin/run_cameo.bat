@echo off
:: ============================================================
:: run_cameo.bat -- launches whichever CAMEO install CAMEO_HOME
:: currently points at (set by setup_rti_config.bat for 2024x, or
:: setup_rti_config_26x.bat for 2026x -- run one of those first in
:: this same window). Works for both without needing two separate
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
    echo        Run setup_rti_config.bat ^(2024x^) or setup_rti_config_26x.bat
    echo        ^(2026x^) first in this window, or set CAMEO_HOME manually.
    exit /b 1
)

:: Make sure the RTI Connext native libraries are on PATH for this launch,
:: same requirement install.bat/install_26x.bat remind you of after
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
