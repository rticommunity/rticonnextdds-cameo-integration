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
:: build.bat — Build the RTI Connext DDS CAMEO System Modeler 2024x Plugin
::
:: Prerequisites
::   NDDSHOME   — RTI Connext DDS installation root
::                e.g.  set NDDSHOME=C:\RTI\rti_connext_dds-7.7.0
::   CAMEO_HOME — CAMEO System Modeler 2024x installation root
::                e.g.  set CAMEO_HOME=C:\Program Files\Cameo Systems Modeler
::
:: Output (relative to this script)
::   build\nddsjava.jar        — copied from %NDDSHOME%\lib\java\nddsjava.jar
::   build\RTIConnextPlugin.jar — compiled plugin JAR (contains ShapeType.xml)
::
:: Usage
::   build.bat           (release build — uses nddsjava.jar)
::   build.bat debug     (debug build   — uses nddsjavad.jar)
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
if defined NDDSHOME (
    set "NDDSHOME=%NDDSHOME:"=%"
    if "!NDDSHOME:~-1!"=="\" set "NDDSHOME=!NDDSHOME:~0,-1!"
)
if defined CAMEO_HOME (
    set "CAMEO_HOME=%CAMEO_HOME:"=%"
    if "!CAMEO_HOME:~-1!"=="\" set "CAMEO_HOME=!CAMEO_HOME:~0,-1!"
)
if defined RTIJDKHOME (
    set "RTIJDKHOME=%RTIJDKHOME:"=%"
    if "!RTIJDKHOME:~-1!"=="\" set "RTIJDKHOME=!RTIJDKHOME:~0,-1!"
)

:: ---------------------------------------------------------------------------
:: 0. Check required environment variables
:: ---------------------------------------------------------------------------
if "%NDDSHOME%"=="" (
    echo ERROR: NDDSHOME is not set.
    echo        Set it to the RTI Connext DDS installation root, e.g.:
    echo          set NDDSHOME=C:\RTI\rti_connext_dds-7.7.0
    exit /b 1
)
if "%CAMEO_HOME%"=="" (
    echo ERROR: CAMEO_HOME is not set.
    echo        Set it to the CAMEO System Modeler installation root, e.g.:
    echo          set CAMEO_HOME=C:\Program Files\Cameo Systems Modeler
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: 1. Locate javac
::    Use %RTIJDKHOME%\bin\javac if available, otherwise use javac on PATH.
:: ---------------------------------------------------------------------------
if defined RTIJDKHOME (
    set "JAVAC=%RTIJDKHOME%\bin\javac"
    set "JAR_TOOL=%RTIJDKHOME%\bin\jar"
) else (
    set "JAVAC=javac"
    set "JAR_TOOL=jar"
)

where "%JAVAC%" >nul 2>&1
if errorlevel 1 (
    if defined RTIJDKHOME (
        if not exist "%JAVAC%.exe" (
            echo ERROR: javac not found. Install a JDK or set RTIJDKHOME.
            exit /b 1
        )
    ) else (
        echo ERROR: javac not found. Install a JDK or set RTIJDKHOME.
        exit /b 1
    )
)

:: ---------------------------------------------------------------------------
:: 2. Select the Connext Java JAR (release vs debug)
:: ---------------------------------------------------------------------------
if /i "%1"=="debug" (
    set "NDDSJAVA_JAR=%NDDSHOME%\lib\java\nddsjavad.jar"
    set "BUILD_MODE=debug"
) else (
    set "NDDSJAVA_JAR=%NDDSHOME%\lib\java\nddsjava.jar"
    set "BUILD_MODE=release"
)

if not exist "%NDDSJAVA_JAR%" (
    echo ERROR: %NDDSJAVA_JAR% not found.
    echo        Verify that NDDSHOME points to a valid Connext installation.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: 3. Locate CAMEO's md.jar (plugin compilation API)
::    Try several known locations for CAMEO 2024x / MagicDraw 2024x.
:: ---------------------------------------------------------------------------
set "MD_JAR="
for %%c in (
    "%CAMEO_HOME%\lib\md.jar"
    "%CAMEO_HOME%\lib\magicdraw\md.jar"
    "%CAMEO_HOME%\plugins\com.nomagic.magicdraw.foundation\lib\md.jar"
) do (
    if exist %%c (
        if "!MD_JAR!"=="" set "MD_JAR=%%~c"
    )
)

:: CAMEO 2024x ships com.nomagic.magicdraw.foundation-<version>.jar in lib\
if "!MD_JAR!"=="" (
    for %%f in ("%CAMEO_HOME%\lib\com.nomagic.magicdraw.foundation-*.jar") do (
        if "!MD_JAR!"=="" set "MD_JAR=%%~f"
    )
)

if "!MD_JAR!"=="" (
    echo ERROR: Cannot locate md.jar under CAMEO_HOME=%CAMEO_HOME%
    echo        Searched:
    echo          %CAMEO_HOME%\lib\md.jar
    echo          %CAMEO_HOME%\lib\magicdraw\md.jar
    echo          %CAMEO_HOME%\plugins\com.nomagic.magicdraw.foundation\lib\md.jar
    echo          %CAMEO_HOME%\lib\com.nomagic.magicdraw.foundation-*.jar
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: 3b. Locate the Cameo Simulation Toolkit plugin dir (needed for
::     DdsEngineListener.java, which uses com.nomagic.magicdraw.simulation.*
::     classes — these live under the CST plugin's own lib\, not CAMEO_HOME\lib\).
:: ---------------------------------------------------------------------------
set "CST_PLUGIN_DIR=%CAMEO_HOME%\plugins\com.nomagic.magicdraw.simulation"
if not exist "%CST_PLUGIN_DIR%\lib" (
    echo ERROR: Cannot locate Cameo Simulation Toolkit plugin under
    echo        %CST_PLUGIN_DIR%
    echo        DdsEngineListener.java requires CST to be installed. If CST lives
    echo        elsewhere on this machine, update CST_PLUGIN_DIR in build.bat.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: 4. Detect Windows architecture directory for native libraries
::    (informational — shown in README; not needed for compilation)
:: ---------------------------------------------------------------------------
set "NDDSHOME_ARCH_DIR="
for /d %%d in ("%NDDSHOME%\lib\x64Win64*") do (
    if "!NDDSHOME_ARCH_DIR!"=="" set "NDDSHOME_ARCH_DIR=%%d"
)

:: ---------------------------------------------------------------------------
:: 5. Create output directories
:: ---------------------------------------------------------------------------
if not exist build\classes mkdir build\classes

:: ---------------------------------------------------------------------------
:: 6. Copy nddsjava.jar into build\ (plugin runtime dependency)
:: ---------------------------------------------------------------------------
echo Copying %NDDSJAVA_JAR% ^-^> build\nddsjava.jar
copy /Y "%NDDSJAVA_JAR%" build\nddsjava.jar >nul

:: ---------------------------------------------------------------------------
:: 7. Compile plugin Java sources
::    CAMEO 2024x splits its API across many JARs in lib\; use wildcard entry.
::
::    NOTE: this list is intentionally explicit (not a wildcard) — added the
::    new com\rti\connext\cameo\actions\*.java files below (the DDS model
::    action library: CreateJsonAction, AddStringKeyAction,
::    PublishToDdsTopicAction, DDSTopicPublisher, JsonPayloadBuilder,
::    DDSModelAction, plus the temporary TestDdsActionsMenuAction), and the
::    new com\rti\connext\cameo\model\*.java files (ModelDdsScanner,
::    TopicModel, DdsXmlGenerator, DdsEngineListener — scans the SysML model
::    for Blocks/Signals/Topics, generates DDS XML config, and listens for
::    SendSignalAction activations during simulation). If you add more source
::    files later, they must be added here too, or switch this to a
::    recursive dir /s /b *.java loop instead.
:: ---------------------------------------------------------------------------
set "CLASSPATH=build\nddsjava.jar;%CAMEO_HOME%\lib\*;%CST_PLUGIN_DIR%\lib\*;%CST_PLUGIN_DIR%\*"

echo.
echo Compiling plugin (%BUILD_MODE%)...
echo   javac          : %JAVAC%
echo   CAMEO md.jar   : %MD_JAR%
echo   Connext JAR    : %NDDSJAVA_JAR%
echo   Sources        : src\com\rti\connext\cameo\*.java (+ actions\*.java, model\*.java)
echo   Output dir     : build\classes\
echo.

"%JAVAC%" -d build\classes -classpath "%CLASSPATH%" ^
    src\com\rti\connext\cameo\RTIConnextPlugin.java ^
    src\com\rti\connext\cameo\RTIConnextActionsConfigurator.java ^
    src\com\rti\connext\cameo\ShapeTypePublisherAction.java ^
    src\com\rti\connext\cameo\ShapeTypeSubscriberAction.java ^
    src\com\rti\connext\cameo\DDSRunner.java ^
    src\com\rti\connext\cameo\actions\DDSModelAction.java ^
    src\com\rti\connext\cameo\actions\DDSTopicPublisher.java ^
    src\com\rti\connext\cameo\actions\JsonPayloadBuilder.java ^
    src\com\rti\connext\cameo\actions\CreateJsonAction.java ^
    src\com\rti\connext\cameo\actions\AddStringKeyAction.java ^
    src\com\rti\connext\cameo\actions\PublishToDdsTopicAction.java ^
    src\com\rti\connext\cameo\actions\TestDdsActionsMenuAction.java ^
    src\com\rti\connext\cameo\model\TopicModel.java ^
    src\com\rti\connext\cameo\model\ModelDdsScanner.java ^
    src\com\rti\connext\cameo\model\ScanModelForTopicsAction.java ^
    src\com\rti\connext\cameo\model\DdsXmlGenerator.java ^
    src\com\rti\connext\cameo\model\DdsEngineListener.java

if errorlevel 1 (
    echo.
    echo ERROR: Compilation failed.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: 8. Package into build\RTIConnextPlugin.jar
::    Resources are NOT embedded — they are deployed separately by install.bat.
:: ---------------------------------------------------------------------------
echo Packaging ^-^> build\RTIConnextPlugin.jar
"%JAR_TOOL%" cf build\RTIConnextPlugin.jar -C build\classes .

if errorlevel 1 (
    echo ERROR: JAR creation failed.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: Done
:: ---------------------------------------------------------------------------
echo.
echo ============================================================
echo  Build complete (%BUILD_MODE%)
echo ============================================================
echo  build\RTIConnextPlugin.jar  - plugin classes
echo  build\nddsjava.jar          - Connext Java API
echo.
echo  Next step: run install.bat to deploy to CAMEO.
if not "!NDDSHOME_ARCH_DIR!"=="" (
    echo.
    echo  IMPORTANT: Add the following to the system PATH before starting CAMEO
    echo  so the Connext native libraries ^(nddsjava.dll, nddscore.dll, etc.^) load:
    echo    !NDDSHOME_ARCH_DIR!
)
echo ============================================================
echo.

endlocal
