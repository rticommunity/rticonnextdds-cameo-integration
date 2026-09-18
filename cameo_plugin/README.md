# RTI Connext DDS — CAMEO System Modeler Plugin

A CAMEO System Modeler plugin that connects a live SysML simulation to real
RTI Connext DDS traffic: it scans a model for `«DDS_Topic»`-stereotyped
Signals, generates a matching DDS XML Application Creation config, and — while
a Cameo Simulation Toolkit (CST) run is active — publishes real DDS samples
when a modeled Signal fires and injects real inbound DDS samples back into the
simulation as live signals.

Ships in two parallel builds: **2024x** (`build.bat`/`install.bat`, sources
under `src/` + `src24x/`) and **2026x** (`build_26x.bat`/`install_26x.bat`,
sources under `src/` + `src26x/`) — the two CST versions moved some fUML
runtime types between packages, so each version gets its own small
`dds`-package override; everything else is shared.

**For the full design — every class, the design-time scan/generate flow, the
simulation-time publish/subscribe pipeline, the required SysML/DDS profile
shape, and the current list of known gaps — see
[`architecture.md`](architecture.md).** This file only covers building,
installing, and running it.

---

## Tools menu

Once installed, CAMEO's **Tools** menu gets an **RTI Connext DDS** submenu
with three actions:

- **Scan Model for DDS Topics** — debug tool; logs every DDS Topic/type found
  in the open project.
- **Generate DDS XML** — finds a `«DDS_Domain»`-stereotyped package, scans it
  for `«DDS_Topic»`-stereotyped Signals, optionally applies an imported
  `«DDS_QosProfile»`, and writes a DDS XML Application Creation config file.
- **Import QoS Profile** — reads an RTI Connext QoS profile XML file and
  creates a corresponding `«DDS_QosProfile»` model element so `Generate DDS
  XML` can offer it later.

Simulation-time publish/inject happens automatically while a CST run is
active — no menu action needed for that part; see `architecture.md` for how
it's wired into the model (Groovy Opaque Behaviors + `DdsEngineListener`).

---

## Prerequisites

| Requirement | 2024x | 2026x |
|---|---|---|
| **RTI Connext DDS 7.x** | `NDDSHOME` must be set | same |
| **CAMEO / Magic Systems of Systems Architect** | `CAMEO_HOME` — 2024x install root | `CAMEO_HOME` — 2026x (Magic Cyber Systems Engineer) install root |
| **JDK** | 17+ (`RTIJDKHOME`, or `javac` 17+ on PATH) | 21+ (`RTIJDKHOME`, or `javac` 21+ on PATH) — 2026x's own bundled `jre\` has no compiler |

---

## Build

**2024x:**

```bat
set NDDSHOME=C:\RTI\rti_connext_dds-7.7.0
set CAMEO_HOME=C:\Program Files\Magic Systems of Systems Architect
set RTIJDKHOME=<path to a JDK 17+>
cd cameo_plugin
build.bat
```

**2026x:**

```bat
set NDDSHOME=C:\RTI\rti_connext_dds-7.7.0
set CAMEO_HOME=C:\Program Files\Magic Cyber Systems Engineer
set RTIJDKHOME=<path to a JDK 21+>
cd cameo_plugin
build_26x.bat
```

Each script copies `%NDDSHOME%\lib\java\nddsjava.jar` into its own `build\` /
`build_26x\` output directory, compiles the shared `src/` sources plus that
version's `src24x/`/`src26x/` override, and packages
`build\RTIConnextPlugin.jar` / `build_26x\RTIConnextPlugin.jar`. Pass `debug`
as an argument to either script to link against `nddsjavad.jar` instead.

---

## Native library setup (required)

The Connext Java API (`nddsjava.jar`) needs its native DLLs
(`%NDDSHOME%\lib\<arch>\`, e.g. `x64Win64VS2017`) findable before CAMEO
starts:

```bat
set PATH=%NDDSHOME%\lib\x64Win64VS2017;%PATH%
```

...or add `-Djava.library.path=<that path>` to `<CAMEO_HOME>\bin\*.vmoptions`.
`setup_rti_config.bat`/`setup_rti_config_26x.bat` automate this (and the
`RTI_LICENSE_FILE` env var) for a given machine — see those scripts.

---

## Install

Requires **Administrator privileges** (writes under `Program Files`).

```bat
set CAMEO_HOME=<CAMEO or MCSE install root, matching the build above>
install.bat        :: or install_26x.bat
```

Copies `plugin.xml` (or `plugin_26x.xml`) and the matching `build*\*.jar`
into `%CAMEO_HOME%\plugins\com.rti.connext.cameo\`. `uninstall.bat` removes
that folder entirely; safe to re-run.

`run_cameo.bat` launches whichever of `bin\msosa.exe` (2024x) or
`bin\mcse.exe` (2026x) exists under `%CAMEO_HOME%`, for convenience.

---

## Required model shape

The model needs a `«DDS_Domain»` package containing `«DDS_Topic»`-stereotyped
Signals, each typed by a Block whose properties become the DDS struct's
members (`«DDS_Member»` stereotype for key/max-length tags), and Blocks
wired via Ports/InterfaceBlocks/FlowProperties to mark which Block
publishes/subscribes to which topic. **See `architecture.md`'s "Required
SysML/DDS profile shape" section for the full, exact stereotype/tag
reference** — this is the part most worth reading before modeling a new
service against this plugin.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Tools → RTI Connext DDS` not visible | Plugin not installed for the CAMEO version you're running | Confirm you ran `install.bat` (2024x) vs `install_26x.bat` (2026x) matching the running app |
| `NDDSHOME is not set` / `CAMEO_HOME is not set` | Env var missing at build time | Set both before running `build.bat`/`build_26x.bat` |
| `UnsatisfiedLinkError: nddsjava` | Native DLLs not found | Add `%NDDSHOME%\lib\x64Win64VS2017` (or your arch) to PATH before starting CAMEO |
| `RTI Connext DDS No source for License information` / `Failed to create DomainParticipant` | `RTI_LICENSE_FILE` not set for the process | Set `RTI_LICENSE_FILE` before starting CAMEO (or the standalone tool hitting this) |
| `class file has wrong version` during build | Wrong JDK for the CAMEO version | 2024x needs a JDK 17+ compiler, 2026x needs 21+ — set `RTIJDKHOME` explicitly |
| Generated XML fails to load / `RTIXMLObject_addChild` duplicate-name error | A payload Block shares its name with its Signal | See `architecture.md`'s `DdsXmlGenerator.java` section — `register_type` names get a `_Type` suffix specifically to avoid this |

For anything not covered here — how a specific class works, why a design
decision was made, or the current list of known gaps — `architecture.md` is
the maintained source of truth.
