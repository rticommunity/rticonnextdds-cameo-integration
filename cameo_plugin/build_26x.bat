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
:: build_26x.bat — Build the RTI Connext DDS CAMEO System Modeler 2026x Plugin
::
:: Same shape as build.bat (the 2024x builder) — see that file for the fuller
:: comment history. This one differs only where 2026x itself differs:
::   - Compiles src24x/.../{FumlValueBridge,DdsEngineListener,DdsInboundInjector}
::     .java, which touch fUML.Semantics.Classes.Kernel.* directly and
::     could never compile against 2026x's own moved-and-renamed
::     com.nomagic.magicdraw.simulation.fuml.* package. That was confirmed live
::     against a real 2026x install (Magic Cyber Systems Engineer 2026.1.0) —
::     not assumed. Every other source file is fully shared with build.bat.
::   - Outputs to build_26x\, not build\, so both builds can coexist without
::     clobbering each other.
::   - RTIJDKHOME MUST point at a real JDK 21+ install — 2026x's own jre\
::     ships java.exe but no javac.exe (JRE-only runtime), and 2026x's own API
::     jars are compiled to Java 21 bytecode (class file version 65), which an
::     older javac can't even read for classpath resolution — confirmed live
::     against 2024x's bundled JDK 17, error "class file has wrong version
::     65.0, should be 61.0". This machine already has a working JDK 21 at
::     C:\Program Files\Semeru\jdk-21.0.7.6-openj9 (matches JAVA_HOME) —
::     defaulted to below if RTIJDKHOME isn't already set.
::
:: Prerequisites
::   NDDSHOME   — RTI Connext DDS installation root
::   CAMEO_HOME — CAMEO 2026x installation root
::                e.g.  set CAMEO_HOME=C:\Program Files\Magic Cyber Systems Engineer
::   RTIJDKHOME — a JDK 21+ install (see above)
::
:: Output (relative to this script)
::   build_26x\nddsjava.jar        — copied from %NDDSHOME%\lib\java\nddsjava.jar
::   build_26x\RTIConnextPlugin.jar — compiled plugin JAR
::
:: Usage
::   build_26x.bat           (release build — uses nddsjava.jar)
::   build_26x.bat debug     (debug build   — uses nddsjavad.jar)
:: =============================================================================
setlocal enabledelayedexpansion

:: Strip any surrounding quotes / trailing backslash that users may have
:: included in the set command. See build.bat's own comment for why this
:: guarding shape (if defined + delayed expansion) is needed.
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

:: Default RTIJDKHOME to the JDK 21 already confirmed present on this
:: machine, if not already set -- see header comment for why 2026x's own
:: jre\ can't be used for this.
if not defined RTIJDKHOME set "RTIJDKHOME=C:\Program Files\Semeru\jdk-21.0.7.6-openj9"

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
    echo        Set it to the CAMEO 2026x installation root, e.g.:
    echo          set CAMEO_HOME=C:\Program Files\Magic Cyber Systems Engineer
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: 1. Locate javac
::    Use %RTIJDKHOME%\bin\javac -- 2026x's own jre\ has none (see header).
:: ---------------------------------------------------------------------------
set "JAVAC=%RTIJDKHOME%\bin\javac"
set "JAR_TOOL=%RTIJDKHOME%\bin\jar"

if not exist "%JAVAC%.exe" (
    echo ERROR: javac not found at %JAVAC%.exe
    echo        RTIJDKHOME must point at a real JDK 21+ install -- 2026x's own
    echo        jre\ is JRE-only, no compiler. Set RTIJDKHOME explicitly if
    echo        C:\Program Files\Semeru\jdk-21.0.7.6-openj9 isn't right for
    echo        this machine.
    exit /b 1
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
::    2026x ships com.nomagic.magicdraw.foundation-<version>.jar directly
::    under lib\ (confirmed live) -- same fallback pattern build.bat already
::    used for this, no 2026x-specific path needed.
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
:: 3b. Locate the Cameo Simulation Toolkit plugin dir
:: ---------------------------------------------------------------------------
set "CST_PLUGIN_DIR=%CAMEO_HOME%\plugins\com.nomagic.magicdraw.simulation"
if not exist "%CST_PLUGIN_DIR%\lib" (
    echo ERROR: Cannot locate Cameo Simulation Toolkit plugin under
    echo        %CST_PLUGIN_DIR%
    echo        DdsEngineListener.java requires CST to be installed. If CST lives
    echo        elsewhere on this machine, update CST_PLUGIN_DIR in build_26x.bat.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: 4. Detect Windows architecture directory for native libraries
::    (informational only; not needed for compilation)
:: ---------------------------------------------------------------------------
set "NDDSHOME_ARCH_DIR="
for /d %%d in ("%NDDSHOME%\lib\x64Win64*") do (
    if "!NDDSHOME_ARCH_DIR!"=="" set "NDDSHOME_ARCH_DIR=%%d"
)

:: ---------------------------------------------------------------------------
:: 5. Create output directories — build_26x\, kept separate from build.bat's
::    own build\ so both plugin versions can be built without clobbering
::    each other
:: ---------------------------------------------------------------------------
if exist build_26x\classes rmdir /s /q build_26x\classes
mkdir build_26x\classes

:: ---------------------------------------------------------------------------
:: 6. Copy nddsjava.jar into build_26x\
:: ---------------------------------------------------------------------------
echo Copying %NDDSJAVA_JAR% ^-^> build_26x\nddsjava.jar
copy /Y "%NDDSJAVA_JAR%" build_26x\nddsjava.jar >nul

:: ---------------------------------------------------------------------------
:: 7. Compile plugin Java sources
::    Everything except the three fUML-touching files is fully shared with
::    build.bat's own src\ tree -- only FumlValueBridge/DdsEngineListener/
::    DdsInboundInjector come from src26x\ instead of src24x\. If you add
::    more source files later, add them here too (and to build.bat), unless
::    they also need a version split.
:: ---------------------------------------------------------------------------
set "CLASSPATH=build_26x\nddsjava.jar;%CAMEO_HOME%\lib\*;%CST_PLUGIN_DIR%\lib\*;%CST_PLUGIN_DIR%\*"

echo.
echo Compiling plugin (%BUILD_MODE%, 2026x)...
echo   javac          : %JAVAC%
echo   CAMEO md.jar   : %MD_JAR%
echo   Connext JAR    : %NDDSJAVA_JAR%
echo   Sources        : src\com\rti\connext\cameo\*.java (+ core\*.java, dds\*.java) + src26x\...\dds\*.java
echo   Output dir     : build_26x\classes\
echo.

"%JAVAC%" -d build_26x\classes -classpath "%CLASSPATH%" ^
    src\com\rti\connext\cameo\RTIConnextPlugin.java ^
    src\com\rti\connext\cameo\RTIConnextActionsConfigurator.java ^
    src\com\rti\connext\cameo\core\TopicModel.java ^
    src\com\rti\connext\cameo\core\ModelTopicScanner.java ^
    src\com\rti\connext\cameo\core\ScanModelForTopicsAction.java ^
    src\com\rti\connext\cameo\core\JsonPayloadBuilder.java ^
    src\com\rti\connext\cameo\dds\DDSTopicPublisher.java ^
    src\com\rti\connext\cameo\dds\DDSTopicSubscriber.java ^
    src\com\rti\connext\cameo\dds\DdsSubscriptionQueue.java ^
    src\com\rti\connext\cameo\dds\DdsXmlGenerator.java ^
    src\com\rti\connext\cameo\dds\ElementPickerUtil.java ^
    src\com\rti\connext\cameo\dds\GenerateDdsXmlAction.java ^
    src\com\rti\connext\cameo\dds\ImportQosProfileAction.java ^
    src26x\com\rti\connext\cameo\dds\FumlValueBridge.java ^
    src26x\com\rti\connext\cameo\dds\DdsEngineListener.java ^
    src26x\com\rti\connext\cameo\dds\DdsInboundInjector.java

if errorlevel 1 (
    echo.
    echo ERROR: Compilation failed.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: 8. Package into build_26x\RTIConnextPlugin.jar
:: ---------------------------------------------------------------------------
echo Packaging ^-^> build_26x\RTIConnextPlugin.jar
"%JAR_TOOL%" cf build_26x\RTIConnextPlugin.jar -C build_26x\classes .

if errorlevel 1 (
    echo ERROR: JAR creation failed.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: Done
:: ---------------------------------------------------------------------------
echo.
echo ============================================================
echo  Build complete (%BUILD_MODE%, 2026x)
echo ============================================================
echo  build_26x\RTIConnextPlugin.jar  - plugin classes
echo  build_26x\nddsjava.jar          - Connext Java API
echo.
echo  Next step: run install_26x.bat to deploy to CAMEO 2026x.
if not "!NDDSHOME_ARCH_DIR!"=="" (
    echo.
    echo  IMPORTANT: Add the following to the system PATH before starting CAMEO
    echo  so the Connext native libraries ^(nddsjava.dll, nddscore.dll, etc.^) load:
    echo    !NDDSHOME_ARCH_DIR!
)
echo ============================================================
echo.

endlocal
