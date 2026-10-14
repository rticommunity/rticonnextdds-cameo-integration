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
:: build.bat — Build the RTI Connext DDS plugin for CAMEO 2024x or 2026x
::
:: Usage
::   build.bat 2024x           (release build — uses nddsjava.jar)
::   build.bat 2026x debug     (debug build   — uses nddsjavad.jar)
::   The version can be left out if CAMEO_VERSION is already set in this
::   window (setup_rti_config.bat does that).
::
:: Prerequisites
::   NDDSHOME   — RTI Connext DDS installation root
::                e.g.  set NDDSHOME=C:\Program Files\rti_connext_dds-7.5.0
::   CAMEO_HOME — installation root of the CAMEO version being built for
::                e.g.  set CAMEO_HOME=C:\Program Files\Magic Systems of Systems Architect
::   RTIJDKHOME — optional JDK root (default: javac on PATH). REQUIRED for
::                2026x: its own jre\ ships no javac, and its API jars are
::                Java 21 bytecode (class file version 65), which an older JDK
::                such as 2024x's bundled JDK 17 cannot read ("class file has
::                wrong version 65.0, should be 61.0" — confirmed live).
::
:: The two versions differ only in src24x\ vs src26x\ (fUML runtime types
:: moved package between CST versions) and the output folder.
::
:: Output (relative to this script)
::   build\<version>\nddsjava.jar         — copied from %NDDSHOME%\lib\java\
::   build\<version>\RTIConnextPlugin.jar — compiled plugin JAR
:: =============================================================================
setlocal enabledelayedexpansion

:: ---------------------------------------------------------------------------
:: Arguments: CAMEO version (2024x | 2026x) and optional "debug", any order.
:: ---------------------------------------------------------------------------
set "BUILD_MODE=release"
for %%a in (%*) do (
    if /i "%%~a"=="debug" (set "BUILD_MODE=debug") else (set "CAMEO_VERSION=%%~a")
)
if /i "%CAMEO_VERSION%"=="2024x" (
    set "SRC_VERSION_DIR=src24x"
) else if /i "%CAMEO_VERSION%"=="2026x" (
    set "SRC_VERSION_DIR=src26x"
) else (
    echo ERROR: unknown or missing CAMEO version "%CAMEO_VERSION%".
    echo        Usage: build.bat 2024x^|2026x [debug]
    exit /b 1
)
set "OUT=build\%CAMEO_VERSION%"

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
    echo          set NDDSHOME=C:\Program Files\rti_connext_dds-7.5.0
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
if /i "%CAMEO_VERSION%"=="2026x" if not defined RTIJDKHOME (
    echo WARNING: building for 2026x with javac from PATH -- it must be JDK 21+.
)

:: ---------------------------------------------------------------------------
:: 2. Select the Connext Java JAR (release vs debug)
:: ---------------------------------------------------------------------------
if /i "%BUILD_MODE%"=="debug" (
    set "NDDSJAVA_JAR=%NDDSHOME%\lib\java\nddsjavad.jar"
) else (
    set "NDDSJAVA_JAR=%NDDSHOME%\lib\java\nddsjava.jar"
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
::    Wipe %OUT%\classes first — javac -d never deletes stale .class files
::    for since-removed .java sources, and the packaging step below jars up
::    everything under %OUT%\classes, so leftover classes from a deleted
::    source file would silently ride along into RTIConnextPlugin.jar.
:: ---------------------------------------------------------------------------
if exist "%OUT%\classes" rmdir /s /q "%OUT%\classes"
mkdir "%OUT%\classes"

:: ---------------------------------------------------------------------------
:: 6. Copy nddsjava.jar into build\ (plugin runtime dependency)
:: ---------------------------------------------------------------------------
echo Copying %NDDSJAVA_JAR% ^-^> %OUT%\nddsjava.jar
copy /Y "%NDDSJAVA_JAR%" %OUT%\nddsjava.jar >nul

:: ---------------------------------------------------------------------------
:: 7. Compile plugin Java sources
::    CAMEO 2024x splits its API across many JARs in lib\; use wildcard entry.
::
::    NOTE: this list is intentionally explicit (not a wildcard) —
::    com\rti\connext\cameo\core\*.java (TopicModel, ModelTopicScanner,
::    ScanModelForTopicsAction, JsonPayloadBuilder — backend-agnostic SysML
::    model scanning, kept separate from any specific pub/sub backend) and
::    com\rti\connext\cameo\dds\*.java (DDSTopicPublisher, DDSTopicSubscriber,
::    DdsSubscriptionQueue, DdsXmlGenerator, GenerateDdsXmlAction,
::    ImportQosProfileAction, DdsEngineListener — the RTI Connext DDS
::    backend: generates DDS XML config, imports QoS profile XML into the
::    model (this plugin's only WRITE path), publishes samples, listens for
::    SendSignalAction activations during simulation, and (DDSTopicSubscriber/
::    DdsSubscriptionQueue) receives inbound DDS samples and queues them for
::    a Groovy polling script to inject back into a running simulation via
::    ALH.sendSignal() — see groovy/PollDdsSubscriptionAndInject.groovy).
::    If you add more source files later, they must
::    be added here too, or switch this to a recursive dir /s /b *.java loop
::    instead.
:: ---------------------------------------------------------------------------
set "CLASSPATH=%OUT%\nddsjava.jar;%CAMEO_HOME%\lib\*;%CST_PLUGIN_DIR%\lib\*;%CST_PLUGIN_DIR%\*"

echo.
echo Compiling plugin (%BUILD_MODE%, %CAMEO_VERSION%)...
echo   javac          : %JAVAC%
echo   CAMEO md.jar   : %MD_JAR%
echo   Connext JAR    : %NDDSJAVA_JAR%
echo   Sources        : src\ + %SRC_VERSION_DIR%\
echo   Output dir     : %OUT%\classes\
echo.

"%JAVAC%" -encoding UTF-8 -d %OUT%\classes -classpath "%CLASSPATH%" ^
    src\com\rti\connext\cameo\RTIConnextPlugin.java ^
    src\com\rti\connext\cameo\RTIConnextActionsConfigurator.java ^
    src\com\rti\connext\cameo\core\TopicModel.java ^
    src\com\rti\connext\cameo\core\ModelTopicScanner.java ^
    src\com\rti\connext\cameo\core\ScanModelForTopicsAction.java ^
    src\com\rti\connext\cameo\core\JsonPayloadBuilder.java ^
    src\com\rti\connext\cameo\core\SimulationScope.java ^
    src\com\rti\connext\cameo\dds\DDSTopicPublisher.java ^
    src\com\rti\connext\cameo\dds\DDSTopicSubscriber.java ^
    src\com\rti\connext\cameo\dds\DdsSubscriptionQueue.java ^
    src\com\rti\connext\cameo\dds\DdsXmlGenerator.java ^
    src\com\rti\connext\cameo\dds\DdsRuntimeConfig.java ^
    src\com\rti\connext\cameo\dds\ElementPickerUtil.java ^
    src\com\rti\connext\cameo\dds\GenerateDdsXmlAction.java ^
    src\com\rti\connext\cameo\dds\ImportQosProfileAction.java ^
    %SRC_VERSION_DIR%\com\rti\connext\cameo\dds\FumlValueBridge.java ^
    %SRC_VERSION_DIR%\com\rti\connext\cameo\dds\DdsEngineListener.java ^
    %SRC_VERSION_DIR%\com\rti\connext\cameo\dds\DdsInboundInjector.java

if errorlevel 1 (
    echo.
    echo ERROR: Compilation failed.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: 8. Package into %OUT%\RTIConnextPlugin.jar
::    Resources are NOT embedded — they are deployed separately by install.bat.
:: ---------------------------------------------------------------------------
echo Packaging ^-^> %OUT%\RTIConnextPlugin.jar
"%JAR_TOOL%" cf %OUT%\RTIConnextPlugin.jar -C %OUT%\classes .

if errorlevel 1 (
    echo ERROR: JAR creation failed.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: Done
:: ---------------------------------------------------------------------------
echo.
echo ============================================================
echo  Build complete (%BUILD_MODE%, %CAMEO_VERSION%)
echo ============================================================
echo  %OUT%\RTIConnextPlugin.jar  - plugin classes
echo  %OUT%\nddsjava.jar          - Connext Java API
echo.
echo  Next step: run install.bat %CAMEO_VERSION% to deploy to CAMEO.
if not "!NDDSHOME_ARCH_DIR!"=="" (
    echo.
    echo  IMPORTANT: Add the following to the system PATH before starting CAMEO
    echo  so the Connext native libraries ^(nddsjava.dll, nddscore.dll, etc.^) load:
    echo    !NDDSHOME_ARCH_DIR!
)
echo ============================================================
echo.

endlocal
