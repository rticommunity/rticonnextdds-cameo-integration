# RTI Connext DDS — CAMEO Plugin

Makes a running SysML simulation a live node on an RTI Connext DDS bus:

- **Outbound:** when the model sends a `«DDS_Topic»` Signal, the plugin
  publishes it as a real DDS sample.
- **Inbound:** DDS samples from other applications are injected into the
  running simulation as Signals, through the model's own ports and connectors.
- **Configuration:** the DDS XML (types, topics, participants, QoS) is
  generated from the model at every simulation start, so it can't drift from
  the model.

Works with CAMEO **2024x** and **2026x**. How it works internally:
[`docs/architecture.md`](docs/architecture.md).

**What you need:** CAMEO 2024x or 2026x with Cameo Simulation Toolkit, and RTI
Connext DDS 7.x with a valid license (RTI offers evaluation licenses). Without
RTI Connext you can still read the code and open the models, but not build or
run the plugin.

---

## Models

[`DDS_Models/`](DDS_Models/) holds three CAMEO projects, each using the one
below it. Keep them in the same folder: CAMEO finds the used projects next to
the one you open.

| Model | What it is |
|---|---|
| **`DDS_Profile.mdzip`** | The language: the `«DDS_Topic»`, `«DDS_Domain»`, `«DDS_Member»` and QoS stereotypes, plus the reusable **DDS test harness** (`DDS_Simulation_Environment`). Every model that uses the plugin needs it. |
| **`DDS_Message_Library.mdzip`** | The interface (ICD) of the demo system: its topics, message types, enums and QoS profile, in a `«DDS_Domain»`. |
| **`SAGE.mdzip`** | An example service built against that interface — the Sensor Alignment and Gating Service — with a Test Context that runs it on the DDS bus. |

The other applications of the demo (sensor emulator, track association
service, live display) are in a separate repository:
[sage-demo-pipeline](https://gitlab.com/notesandnotches-group/sage-demo-pipeline).

---

## Quick start (Windows)

1. Copy `setup_rti_config.template.bat` to `setup_rti_config.bat` (git-ignored)
   and edit the paths under "Core environment variables".
2. Run `setup_rti_config.bat 2024x` (or `2026x`; double-clicking asks). It
   opens an Administrator command window with the environment set.
3. In that window: `build.bat`, `install.bat`, then `run_cameo.bat`.
4. Open `DDS_Models/SAGE.mdzip` and run the Simulation Configuration
   `Test for Test`, or set up your own model as described in
   [Model setup](#model-setup).

### Prerequisites

| | 2024x | 2026x |
|---|---|---|
| RTI Connext DDS 7.x | `NDDSHOME`, `RTI_LICENSE_FILE` | same |
| CAMEO install root | `CAMEO_HOME` | `CAMEO_HOME` |
| JDK for building | 17+ (CAMEO's bundled `jre\` works) | **21+** (CAMEO's `jre\` has no compiler) — set `RTIJDKHOME` |

### Building and installing by hand

```bat
set NDDSHOME=C:\Program Files\rti_connext_dds-7.5.0
set CAMEO_HOME=C:\Program Files\Magic Systems of Systems Architect
set RTIJDKHOME=<JDK root>
build.bat 2024x            :: add "debug" to link nddsjavad.jar
install.bat 2024x          :: needs an Administrator prompt
```

The build goes to `build\<version>\`; `install.bat` copies it and
`plugin\<version>\plugin.xml` to `%CAMEO_HOME%\plugins\com.rti.connext.cameo\`.
`uninstall.bat` removes that folder.

Before starting CAMEO, RTI's native libraries must be on `PATH`
(`%NDDSHOME%\lib\x64Win64VS2017`) and `RTI_LICENSE_FILE` must be set — the
setup window does both, and `run_cameo.bat` launches CAMEO from it.

---

## Model setup

### 1. Use the profile

In your project, use **`DDS_Profile.mdzip`** (File > Use Project). It brings
the stereotypes and the test harness.

### 2. Describe the interface

Typically in its own message-library project, like `DDS_Message_Library.mdzip`:

- Create one **`«DDS_Domain»`** package and set its `domain_id`.
- Model each topic as a **`«DDS_Topic»` Signal** with one attribute named
  **`data`**, typed by the **`«Block»`** that defines the message. Its value
  properties become the DDS fields; Blocks typing part properties become
  nested structs, an `[N]` multiplicity a fixed-size array, and a UML
  Enumeration an enum.
- Optionally, add a `«DDS_QosProfile»` (Tools > RTI Connext DDS > Import QoS
  Profile) and mark it `is_default`.

The topic, field and type names are the contract with every other application
on the bus: renaming one in the model is an interface change.

### 3. Say who publishes and subscribes

Give each service Block a **port typed by an `«InterfaceBlock»`** whose
**`«FlowProperty»`s** are typed by topic Signals: `out` = publishes,
`in` = subscribes. Each such Block becomes a DDS participant named after it.
A specialized Block (e.g. an implementation of a definition Block) is treated
as the Block it specializes.

### 4. Create a Test Context

1. Create a Block that **specializes `DDS_Simulation_Environment`** from the
   profile. It inherits the harness state machine, which generates the DDS
   configuration, starts DDS and injects received samples every 5 seconds.
2. **Redefine `SYSTEM_UNDER_TEST`** and type it by your service Block.
3. In an IBD of the Test Context, **connect the inherited `dds-bus-listener`
   port to your service's subscribing port**. Received samples are sent out of
   `dds-bus-listener`, so they reach the service over that connector.
   (`dds-bus-sender` can be connected to the service's publishing port for
   readability; the plugin publishes directly from the service.)
4. Optionally, **implement `runTest`**: the harness calls this operation in
   parallel with DDS polling, and your Test Context's method for it drives the
   test (for example, sending inputs to the system under test and checking
   what it publishes).

The Groovy bodies of the harness's two Opaque Behaviors are kept in
[`groovy/`](groovy/): `register_dds_engine_listener` ←
[`RegisterDdsEngineListener.groovy`](groovy/RegisterDdsEngineListener.groovy),
`poll_dds_subscription_inject` ←
[`PollDdsSubscriptionAndInject.groovy`](groovy/PollDdsSubscriptionAndInject.groovy).
They are already in `DDS_Profile.mdzip`; update them there whenever these
files change.

### 5. Run

Run the Test Context through a **Simulation Configuration**:

- **SAGE example:** in `SAGE.mdzip`, run the Simulation Configuration
  **`Test for Test`** (its execution target is the `Test` Block).
- **Your own model:** copy **`DDS Simulation Instance`** from the profile's
  `DDS Simulation` package into your project and set its **execution target**
  to your Test Context. Run as-is, it simulates the bare harness, whose
  `SYSTEM_UNDER_TEST` has no type, so nothing goes on the DDS bus. Keep its
  settings — notably *Treat All Classifiers As Active*, which runs the system
  under test's own behavior, and *Auto Start*.

Right-click > Simulate on the Test Context works too. Start the other DDS
applications (e.g. the sensor emulator) before or during the run. The
Notification window shows:

```
[RTI Connext] Generated DDS config from the model (domain 'DDS_Schema', id 0): <temp>\rti_cameo\SAGE_dds_generated.xml
[DDS Inject] Simulating 'Test' -- DDS subscribers in this simulation: [Sensor Alignment and Gating Service]
[DDS Inject] Injected 'SensorReport' into 'Sensor Alignment and Gating Service' via port 'dds-bus-listener'.
[DDS Listener] Publishing Publisher::GatedReportWriter: {...}
```

Only the Blocks in the simulated Block's composition go on the DDS bus. To run
a hand-maintained XML instead of the generated one, start CAMEO with
`-Dcom.rti.connext.cameo.xmlConfig=<path>`.

---

## Tools menu

**Tools > RTI Connext DDS**:

- **Scan Model for DDS Topics** — logs every topic and type the plugin finds.
- **Generate DDS XML** — exports the config for a `«DDS_Domain»` to a file,
  e.g. to hand to another team. Not needed for simulation.
- **Import QoS Profile** — creates a `«DDS_QosProfile»` element from an RTI
  QoS XML file.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| No **Tools > RTI Connext DDS** menu | Run `install.bat` for the CAMEO version you start (`2024x` vs `2026x`), then restart CAMEO. |
| `No such property: com for class: Script…` | The plugin isn't loaded in this CAMEO session: run `install.bat` and restart CAMEO. |
| `UnsatisfiedLinkError: nddsjava` | Add `%NDDSHOME%\lib\x64Win64VS2017` to `PATH` before starting CAMEO (or use `run_cameo.bat` from the setup window). |
| `No source for License information` / participant creation fails | Set `RTI_LICENSE_FILE` before starting CAMEO. |
| `class file has wrong version` while building | Wrong JDK: 2024x needs 17+, 2026x needs 21+. Set `RTIJDKHOME`. |
| `found N «DDS_Domain» packages` | The generated config supports exactly one `«DDS_Domain»` per project. |
| `«DDS_Topic» Signal 'X' skipped: ...` | Give the Signal an attribute named `data` typed by a `«Block»`. |
| `DDS subscribers in this simulation: none` | The simulated Block's composition contains no subscribing Block: check that `SYSTEM_UNDER_TEST` is redefined and typed by your service. |
| `nothing is connected to 'Block.port'` | Connect `dds-bus-listener` to the service's subscribing port in the Test Context's IBD (step 4). |
| Another application doesn't receive a topic | Its type name must match the model's: RTI's discovery log shows `type names for topic 'X' do not match`. |
| Two samples sent back to back, only one arrives | Topics without a DDS key keep only the latest sample by default. Use a QoS profile with a `history_depth`, marked `is_default`. |
