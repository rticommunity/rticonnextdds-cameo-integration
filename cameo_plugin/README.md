# RTI Connext DDS — CAMEO System Modeler Plugin

A CAMEO System Modeler plugin that connects a live SysML simulation to real
RTI Connext DDS traffic: it scans a model for `«DDS_Topic»`-stereotyped
Signals, generates a matching DDS XML Application Creation config, and — while
a Cameo Simulation Toolkit (CST) run is active — publishes real DDS samples
when a modeled Signal fires and injects real inbound DDS samples back into the
simulation as live signals.

Supports **CAMEO 2024x** and **2026x** from one set of scripts: pass the
version (`build.bat 2024x`, `install.bat 2026x`, ...). The two Cameo
Simulation Toolkit versions moved some fUML runtime types between packages,
so each has a small override (`src24x/` or `src26x/`); everything under
`src/` is shared.

**For the full design — every class, the design-time scan/generate flow, the
simulation-time publish/subscribe pipeline, the required SysML/DDS profile
shape, and the current list of known gaps — see
[`docs/architecture.md`](docs/architecture.md).** This file only covers
building, installing, and running it.

## Layout

| Path | Contents |
|---|---|
| `build.bat`, `install.bat`, `uninstall.bat`, `run_cameo.bat` | Build, deploy, remove, launch |
| `setup_rti_config.template.bat` | Per-machine environment setup (copy, then edit) |
| `plugin/2024x/`, `plugin/2026x/` | `plugin.xml` for each CAMEO version |
| `groovy/` | Scripts pasted into the model's Opaque Behaviors |
| `src/` | Shared Java sources (`core/` model scanning, `dds/` RTI Connext) |
| `src24x/`, `src26x/` | Version-specific overrides |
| `docs/` | Architecture and design notes |
| `build/<version>/` | Build output (git-ignored) |

---

## Tools menu

Once installed, CAMEO's **Tools** menu gets an **RTI Connext DDS** submenu
with three actions:

- **Scan Model for DDS Topics** — debug tool; logs every DDS Topic/type found
  in the open project.
- **Generate DDS XML** — exports the DDS XML Application Creation config for
  a `«DDS_Domain»` package to a file of your choice (e.g. to hand to another
  team). Not needed to run a simulation: see below.
- **Import QoS Profile** — reads an RTI Connext QoS profile XML file and
  creates a corresponding `«DDS_QosProfile»` model element.

Simulation-time publish/inject happens automatically while a CST run is
active, once `groovy/RegisterDdsEngineListener.groovy` is placed in the model
(see [`docs/architecture.md`](docs/architecture.md)). At every simulation
start the plugin **generates the DDS config from the model** and logs where
it wrote it (`<temp>/rti_cameo/<Project>_dds_generated.xml`), so the
configuration can't drift from the model. To run a hand-maintained XML
instead, start CAMEO with `-Dcom.rti.connext.cameo.xmlConfig=<path>`.

---

## Prerequisites

| Requirement | 2024x | 2026x |
|---|---|---|
| **RTI Connext DDS 7.x** | `NDDSHOME` must be set | same |
| **CAMEO / Magic Systems of Systems Architect** | `CAMEO_HOME` — 2024x install root | `CAMEO_HOME` — 2026x (Magic Cyber Systems Engineer) install root |
| **JDK** | 17+ (`RTIJDKHOME`, or `javac` 17+ on PATH) | 21+ (`RTIJDKHOME`, or `javac` 21+ on PATH) — 2026x's own bundled `jre\` has no compiler |

---

## Quick start

1. Copy `setup_rti_config.template.bat` to `setup_rti_config.bat` (git-ignored)
   and edit the paths under "Core environment variables" for your machine.
2. Run `setup_rti_config.bat 2024x` (or `2026x`, or double-click it and type
   the version). It opens an Administrator command window with everything set.
3. In that window: `build.bat`, then `install.bat`, then `run_cameo.bat`.

The sections below describe each step for doing it by hand.

---

## Build

```bat
set NDDSHOME=C:\Program Files\rti_connext_dds-7.5.0
set CAMEO_HOME=C:\Program Files\Magic Systems of Systems Architect
set RTIJDKHOME=<path to a JDK 17+, or 21+ for 2026x>
cd cameo_plugin
build.bat 2024x
```

For 2026x, point `CAMEO_HOME` at the 2026x (Magic Cyber Systems Engineer)
install and run `build.bat 2026x`. The script copies
`%NDDSHOME%\lib\java\nddsjava.jar` into `build\<version>\`, compiles the shared
`src/` sources plus that version's `src24x/`/`src26x/` override, and packages
`build\<version>\RTIConnextPlugin.jar`. Add `debug` (e.g. `build.bat 2026x
debug`) to link against `nddsjavad.jar` instead. If `CAMEO_VERSION` is set in
the window (the setup script does that), the version can be left out.

---

## Native library setup (required)

The Connext Java API (`nddsjava.jar`) needs its native DLLs
(`%NDDSHOME%\lib\<arch>\`, e.g. `x64Win64VS2017`) findable before CAMEO
starts:

```bat
set PATH=%NDDSHOME%\lib\x64Win64VS2017;%PATH%
```

...or add `-Djava.library.path=<that path>` to `<CAMEO_HOME>\bin\*.vmoptions`.
`setup_rti_config.bat` does this (and sets `RTI_LICENSE_FILE`) for the
window it opens.

---

## Install

Requires **Administrator privileges** (writes under `Program Files`).

```bat
set CAMEO_HOME=<install root of the CAMEO version you built for>
install.bat 2024x        :: or install.bat 2026x
```

Copies `plugin\<version>\plugin.xml` and `build\<version>\*.jar` into
`%CAMEO_HOME%\plugins\com.rti.connext.cameo\`. `uninstall.bat` removes that
folder entirely; safe to re-run.

`run_cameo.bat` launches whichever of `bin\msosa.exe` (2024x) or
`bin\mcse.exe` (2026x) exists under `%CAMEO_HOME%`, for convenience.

---

## Required model shape

The model needs a `«DDS_Domain»` package containing `«DDS_Topic»`-stereotyped
Signals, each typed by a Block whose properties become the DDS struct's
members (`«DDS_Member»` stereotype for key/max-length tags), and Blocks
wired via Ports/InterfaceBlocks/FlowProperties to mark which Block
publishes/subscribes to which topic. **See [`docs/architecture.md`](docs/architecture.md)'s "Required
SysML/DDS profile shape" section for the full, exact stereotype/tag
reference** — this is the part most worth reading before modeling a new
service against this plugin.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Tools → RTI Connext DDS` not visible | Plugin not installed for the CAMEO version you're running | Confirm you ran `install.bat 2024x` or `install.bat 2026x` to match the CAMEO you start |
| `NDDSHOME is not set` / `CAMEO_HOME is not set` | Env var missing at build time | Set both before running `build.bat`, or use `setup_rti_config.bat` |
| `UnsatisfiedLinkError: nddsjava` | Native DLLs not found | Add `%NDDSHOME%\lib\x64Win64VS2017` (or your arch) to PATH before starting CAMEO |
| `RTI Connext DDS No source for License information` / `Failed to create DomainParticipant` | `RTI_LICENSE_FILE` not set for the process | Set `RTI_LICENSE_FILE` before starting CAMEO (or the standalone tool hitting this) |
| `class file has wrong version` during build | Wrong JDK for the CAMEO version | 2024x needs a JDK 17+ compiler, 2026x needs 21+ — set `RTIJDKHOME` explicitly |
| Generated XML fails to load / `RTIXMLObject_addChild` duplicate-name error | A payload Block shares its name with its Signal | See `docs/architecture.md`'s `DdsXmlGenerator.java` section — `register_type` names get a `_Type` suffix specifically to avoid this |

For anything not covered here — how a specific class works, why a design
decision was made, or the current list of known gaps — `docs/architecture.md` is
the maintained source of truth.
