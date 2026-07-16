# RTI Connext DDS — CAMEO System Modeler 2024x Plugin

A CAMEO System Modeler 2024x plugin that runs the **ShapeType DynamicData**
publisher and subscriber from `java_dynamicdata/` in-process, directly inside
CAMEO's JVM. It loads Connext libraries from `%NDDSHOME%\lib\java` and the
architecture-specific native directory under `%NDDSHOME%\lib\`.

The DDS XML Application Creation config is **not embedded** in the JAR — it is
read at runtime from `<plugin-dir>/resources/`. Any `*.xml` file placed there
is picked up automatically, making the plugin type-independent.

---

## Directory layout

```
cameo_plugin/
  plugin.xml                               CAMEO plugin descriptor
  build.bat                                Windows build script
  install.bat                              Deploy to CAMEO plugins directory
  src/com/rti/connext/cameo/
    RTIConnextPlugin.java                  Plugin entry point (extends Plugin)
    RTIConnextActionsConfigurator.java     Adds "RTI Connext DDS" to Tools menu
    ShapeTypePublisherAction.java          Start/Stop publisher action
    ShapeTypeSubscriberAction.java         Start/Stop subscriber action
    DDSRunner.java                         DDS logic (pub + sub background threads)
  resources/
    ShapeType.xml                          XML Application Creation config (deployed, not embedded)
  build/                                   Populated by build.bat (gitignored)
    classes/                               Compiled .class files
    nddsjava.jar                           Copied from %NDDSHOME%\lib\java
    RTIConnextPlugin.jar                   Packaged plugin classes
```

---

## Prerequisites

| Requirement | Notes |
|---|---|
| **RTI Connext DDS 7.x** | `NDDSHOME` must be set |
| **CAMEO System Modeler 2024x** | `CAMEO_HOME` must be set |
| **JDK 17+** | CAMEO 2024x ships a JDK 17 at `%CAMEO_HOME%\jre`; set `RTIJDKHOME=%CAMEO_HOME%\jre` or ensure `javac` 17+ is on PATH |

---

## Build

Open a command prompt and run:

```bat
set NDDSHOME=C:\RTI\rti_connext_dds-7.7.0
set CAMEO_HOME=C:\Program Files\Cameo Systems Modeler
set RTIJDKHOME=%CAMEO_HOME%\jre
cd cameo_plugin
build.bat
```

For a debug build (links against `nddsjavad.jar`):

```bat
build.bat debug
```

`build.bat` will:
1. Copy `%NDDSHOME%\lib\java\nddsjava.jar` → `build\nddsjava.jar`
2. Compile all sources against the CAMEO API JARs in `%CAMEO_HOME%\lib\` and `nddsjava.jar`
3. Package compiled classes into `build\RTIConnextPlugin.jar` (no XML embedded)

---

## Native library setup (required)

The Connext Java API (`nddsjava.jar`) uses JNI and depends on native DLLs in
`%NDDSHOME%\lib\<arch>\`:

| Architecture folder | Toolchain |
|---|---|
| `x64Win64VS2017` | Visual Studio 2017 (most common for 7.x) |
| `x64Win64VS2019` | Visual Studio 2019 |
| `x64Win64VS2022` | Visual Studio 2022 |

**Add the correct folder to the Windows system `PATH`** before launching CAMEO:

```bat
:: Example — adjust the architecture suffix as needed
set PATH=%NDDSHOME%\lib\x64Win64VS2017;%PATH%
```

Or set it permanently via **System Properties → Environment Variables → PATH**.

> The plugin also attempts to set `java.library.path` at runtime. On JDK 17+
> the JVM restricts this reflection trick; if it fails a warning is shown in
> the CAMEO notification log — setting the PATH is the reliable fallback.

Alternatively, add the JVM argument to CAMEO's startup configuration.
For CAMEO 2024x on Windows, edit (or create) `<CAMEO_HOME>\bin\cameo.vmoptions`
and add one line:

```
-Djava.library.path=C:\RTI\rti_connext_dds-7.7.0\lib\x64Win64VS2017
```

---

## Install

After a successful build, run **from the `cameo_plugin\` directory**:

```bat
set CAMEO_HOME=C:\Program Files\Cameo Systems Modeler
install.bat
```

This copies the following into `%CAMEO_HOME%\plugins\com.rti.connext.cameo\`:

```
plugin.xml
lib\RTIConnextPlugin.jar
lib\nddsjava.jar
resources\          ← XML Application Creation config(s)
```

> **Administrator privileges required** — `Program Files` is write-protected.
> Run the command prompt as Administrator before calling `install.bat`.

---

## Using the plugin

1. Start CAMEO System Modeler.
2. Open the **Tools** menu — a **RTI Connext DDS** sub-menu should appear.
3. Click **Start Shape Publisher** to begin writing RED squares on DDS domain 0
   (Square topic). The label changes to *Stop Shape Publisher*.
4. Click **Start Shape Subscriber** to receive samples; each sample is printed
   in the CAMEO notification log (`[RTI Connext] Received: …`).
5. Click the stop entries to cleanly shut down the DDS participants.

Both actions toggle: clicking again stops the running thread and cleans up the
`DomainParticipant`.

---

## How it works

```
CAMEO JVM
  └─ RTIConnextPlugin (Plugin.init)
       └─ RTIConnextActionsConfigurator (AMConfigurator)
            ├─ ShapeTypePublisherAction  ──► DDSRunner.startPublisher()
            │                                 Thread: RTIConnext-Publisher
            │                                   DomainParticipant (SquareParticipant)
            │                                   DynamicDataWriter (Publisher::SquareWriter)
            │                                   write loop (500 ms)
            └─ ShapeTypeSubscriberAction ──► DDSRunner.startSubscriber()
                                              Thread: RTIConnext-Subscriber
                                                DomainParticipant (SquareParticipant)
                                                DynamicDataReader (Subscriber::SquareReader)
                                                WaitSet + StatusCondition receive loop
```

`ShapeType.xml` (deployed to `<plugin-dir>/resources/`) is read at runtime.
The plugin scans that folder for the first `*.xml` file (alphabetically) and
passes its `file:///` URL to the `DomainParticipantFactory`. To use a
different type, replace `resources/ShapeType.xml` with your own XML
Application Creation file — no rebuild needed.

To override the config path entirely, add to `<CAMEO_HOME>\bin\cameo.vmoptions`:

```
-Dcom.rti.connext.cameo.xmlConfig=C:\path\to\MyType.xml
```

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Tools → RTI Connext DDS` not visible | Plugin not installed | Verify folder name is `com.rti.connext.cameo` under `plugins\` |
| `NDDSHOME is not set` warning on startup | Env var missing | Set `NDDSHOME` in system env before starting CAMEO |
| `UnsatisfiedLinkError: nddsjava` | Native DLLs not found | Add `%NDDSHOME%\lib\x64Win64VS2017` to PATH |
| `Unable to create DomainParticipant` | Domain ID conflict or Connext licence | Check `RTI_LICENSE_FILE`; run RTI Shapes Demo to verify Connext works |
| `No *.xml file found in …\resources` | XML config missing from plugin dir | Re-run `install.bat` or copy your XML file into `<plugin-dir>\resources\` |
| `XML config not found at -Dcom.rti.connext.cameo.xmlConfig` | Property path wrong | Check the path in `cameo.vmoptions` |
