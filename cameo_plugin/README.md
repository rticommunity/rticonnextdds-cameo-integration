# RTI Connext DDS — CAMEO Plugin

Makes a running SysML simulation a live node on an RTI Connext DDS bus:

- **Outbound:** when the model sends a `«DDS_Topic»` Signal, the plugin
  publishes it as a real DDS sample.
- **Inbound:** DDS samples received from other applications are injected
  into the running simulation as Signals, through the model's own ports and
  connectors.
- **Configuration:** the DDS XML (types, topics, participants, QoS) is
  generated from the model at every simulation start, so it can't drift from
  the model.

Works with CAMEO **2024x** and **2026x** (with Cameo Simulation Toolkit).
How it works internally: [`docs/architecture.md`](docs/architecture.md).

---

## Quick start (Windows)

1. Copy `setup_rti_config.template.bat` to `setup_rti_config.bat` (git-ignored)
   and edit the paths under "Core environment variables".
2. Run `setup_rti_config.bat 2024x` (or `2026x`; double-clicking asks). It
   opens an Administrator command window with the environment set.
3. In that window: `build.bat`, `install.bat`, then `run_cameo.bat`.
4. Set up your model as described in [Model setup](#model-setup).

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

### 1. Describe the topics

- Apply **`DDS_Profile`** to your project (see
  [architecture.md](docs/architecture.md#required-model-shape) for its
  stereotypes; it isn't shipped with this repository yet).
- Create one **`«DDS_Domain»`** package and set its `domain_id`.
- In it, model each topic as a **`«DDS_Topic»` Signal** with one attribute
  named **`data`**, typed by the **`«Block»`** that defines the message.
  That Block's value properties become the DDS fields; Blocks typing part
  properties become nested structs, `[N]` multiplicity a fixed array, and a
  UML Enumeration an enum.

### 2. Say who publishes and subscribes

Give each service Block a **port typed by an `«InterfaceBlock»`** whose
**`«FlowProperty»`s** are typed by topic Signals: `out` = publishes,
`in` = subscribes. Each such Block becomes a DDS participant named after it.

### 3. Connect a "bus" for inbound data

Add a harness Block (e.g. `DDS_Simulation_Environment`) that contains your
service as a part. Give the harness a port (it can stay untyped) and connect
it in an IBD to the service's subscribing port. Received samples are sent
out of that port, so they reach the service over the connector you drew.

### 4. Add the two Groovy scripts

| Script | Paste into | When it runs |
|---|---|---|
| [`groovy/RegisterDdsEngineListener.groovy`](groovy/RegisterDdsEngineListener.groovy) | An Opaque Behavior that runs **once** at simulation start (e.g. an entry action of the harness's initial state) | Generates the DDS config, limits DDS to the Blocks being simulated, starts the listener and subscriptions |
| [`groovy/PollDdsSubscriptionAndInject.groovy`](groovy/PollDdsSubscriptionAndInject.groovy) | The effect of a **repeating** transition on the harness Block (e.g. a Time Event self-transition) | Injects received samples; the interval sets the inbound latency |

Update these in your model whenever you update the plugin.

### 5. Run

Simulate the harness Block (right-click > Simulate, or a Simulation
Configuration). The Notification window shows:

```
[RTI Connext] Generated DDS config from the model (domain 'X', id 0): <temp>\rti_cameo\<Project>_dds_generated.xml
[DDS Inject] Simulating 'DDS_Simulation_Environment' -- DDS subscribers in this simulation: [SAGE]
[DDS Listener] Publishing Publisher::GatedReportWriter: {...}
[DDS Inject] Injected 'SensorReport' into 'SAGE' via port 'dds-bus-listener'.
```

Only Blocks that are part of the simulated Block's composition go on the
DDS bus. To run a hand-maintained XML instead of the generated one, start
CAMEO with `-Dcom.rti.connext.cameo.xmlConfig=<path>`.

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
| `UnsatisfiedLinkError: nddsjava` | Add `%NDDSHOME%\lib\x64Win64VS2017` to `PATH` before starting CAMEO (or use `run_cameo.bat` from the setup window). |
| `No source for License information` / participant creation fails | Set `RTI_LICENSE_FILE` before starting CAMEO. |
| `class file has wrong version` while building | Wrong JDK: 2024x needs 17+, 2026x needs 21+. Set `RTIJDKHOME`. |
| `found N «DDS_Domain» packages` | The generated config supports exactly one `«DDS_Domain»` per project. |
| `«DDS_Topic» Signal 'X' skipped: ...` | Give the Signal an attribute named `data` typed by a `«Block»`. |
| `nothing is connected to 'Block.port'` | Connect the subscriber's inbound port to a port on the harness Block in an IBD (step 3). |
| A Block joins the DDS bus when it isn't simulated | Update `RegisterDdsEngineListener.groovy` in your model; older copies don't set the simulation scope. |
| Two samples sent back to back, only one arrives | Topics without a DDS key keep only the latest sample by default. Import a QoS profile with a `history_depth` and mark it `is_default`. |
