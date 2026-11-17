# RTI Connext DDS — CAMEO Plugin

Makes a running SysML simulation a live node on an RTI Connext DDS bus:

- **Outbound:** when the model sends a `«DDS_Topic»` Signal, the plugin
  publishes it as a real DDS sample.
- **Inbound:** DDS samples from other applications are injected into the
  running simulation as Signals, through the model's own ports and connectors.
- **Configuration:** DDS types, topics and participants are generated from
  the model at every simulation start. QoS comes from the model or a selected
  XML profile.

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
  and set `NDDSHOME` in your environment. Set `CAMEO_HOME` if its default
  does not match your installation. Setup always sets `RTI_LICENSE_FILE`
  to `%NDDSHOME%\rti_license.dat`; place your license file there.
  Ensure `javac` is available on `PATH`; no JDK home variable is required.
2. Run `setup_rti_config.bat 2024x` (or `2026x`; double-clicking asks). It
  checks the required paths, companion scripts, and JDK version before
  requesting administrator privileges. Errors stop setup in the original
  window. Once checks pass, it opens an Administrator Command Prompt with
  the environment set.
3. In that window: `build.bat`, `install.bat`, then `run_cameo.bat`.
4. Open `DDS_Models/SAGE.mdzip` and run the Simulation Configuration
   `Test for Test`, or set up your own model as described in
   [Model setup](#model-setup).

### Prerequisites

| | 2024x | 2026x |
|---|---|---|
| RTI Connext DDS 7.x | `NDDSHOME`, `RTI_LICENSE_FILE` | same |
| CAMEO install root | `CAMEO_HOME` | `CAMEO_HOME` |
| Compiler on `PATH` | `javac` 17+ | `javac` **21+** |

### Building and installing by hand

```bat
set NDDSHOME=C:\Program Files\rti_connext_dds-7.5.0
set CAMEO_HOME=C:\Program Files\Magic Systems of Systems Architect
javac -version            :: must be available on PATH
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

If the project does not appear, select **From Filesystem** and browse for
`DDS_Profile.mdzip` in the `DDS_Models/` folder.

### 2. Describe the interface

Define the interface in your model, typically in its own message-library
project, like `DDS_Message_Library.mdzip`.

1. **Create the DDS domain:** in the Containment tree, right-click the owner
   and choose **Create Element > Package**. Open the package's
   **Specification > Applied Stereotype** and select `DDS_Domain` from the
   DDS profile. Under **Tags > DDS_Domain > domain_id**, enter an integer
   such as `0`, matching your external DDS applications.
2. **Create each DDS topic:** right-click the domain package and choose
   **Create Element > Signal** (use **More** if Signal is not listed).
   Name it after the topic, then open **Specification > Applied Stereotype**
   and select `DDS_Topic`. This stereotype applies to a Signal, not a Block.
3. **Define the topic type:** reuse an existing payload **Block**, or create
  one in the domain package with value properties for the message fields.
  Right-click the topic Signal in the Containment tree and open
  **Specification**. Find **Attributes / Owned Attribute** (enable
  **Expert / All properties** if hidden), then use **Create / +** to add
  an attribute named exactly `data` and set its **Type** to the payload
  Block. **The attribute must be named `data` (lowercase); this name is
  required by the plugin.** The Block's value properties
   become DDS fields; Blocks typing part properties become nested structs,
   an `[N]` multiplicity a fixed-size array, and a UML Enumeration an enum.
4. **Set DDS keys and string lengths:** select the payload Block's value
  property, open **Specification > Applied Stereotype**, and add `DDS_Member`.
  Under **Tags > DDS_Member**, set **`key = true`** for a key field. Applying
  the stereotype alone does not make the field a key.
  **For a string key, also set `max` to its maximum string length**, matching
  the type used by the other DDS applications. For example, on
  `ShapeType.color`, keep **Type = String** and set **`key = true`** and
  **`max = 128`**. Set these tags on `color`, not on the Block or the Signal's
  `data` attribute. The `max` tag sets the string length, not an array
  multiplicity. The generated member for this example is:

  ```xml
  <member name="color" type="string" stringMaxLength="128" key="true"/>
  ```

  Stop and restart the simulation after changing these tags so the generated
  DDS configuration and entities use the updated type.
5. **Optional QoS:** use **Tools > RTI Connext DDS > QoS > Load QoS XML...**
  to select a file and profile. Change the library/profile from the same
  **QoS** submenu. Types still come from the model. Existing model-defined
  `«DDS_QosProfile»` elements remain supported through **Use Model QoS**;
  see [Tools menu](#tools-menu).

The topic, field and type names are the contract with every other application
on the bus: renaming one in the model is an interface change.

### 3. Say who publishes and subscribes

The **service Block** represents the application that publishes and subscribes
to DDS topics. A **BDD (Block Definition Diagram)** is the diagram in which you
create and display Blocks. To create your service Block, open your BDD, select
**Block** in the diagram palette, click an empty area of the diagram, and name
the new Block, for example, `ShapeApplication`. You can also reuse an existing
Block representing your application. Use the Block that owns your model
and its activity diagram.

The **Interface Block** describes the topics that application sends and receives.

1. Create one **Interface Block** per endpoint (DataReaders and DataWriters)
  and open each interface's **Specification > Properties**.
2. Add one **Flow Property** to each Interface Block for the corresponding topic.
  Set its **Type** to the topic Signal, not the type Block, and its
  **Direction** to `out` for publishing or `in` for subscribing.
3. **Create the service ports:** in the Containment tree, right-click the
  service **Block** (for example, `ShapeApplication`) and choose
  **Create Element > Proxy Port** (use **More** if needed). Create one port per
  Interface Block, with a descriptive name such as `SquareInput` or
  `CircleOutput`. Open each port's **Specification** and set its **Type** to
  the corresponding Interface Block.

Each such service Block becomes a DDS participant named after it.
A specialized Block (e.g. an implementation of a definition Block) is treated
as the Block it specializes.

To connect an existing Activity to DDS, see
[Adapt an existing Activity to receive and send DDS data](#adapt-an-existing-activity-to-receive-and-send-dds-data).

### 4. Create a Test Context

1. **Create the Test Context:** create a Block named, for example, `TestContext`
  in your system (containment tree top-level), navigate to **DDS_Profile > DDS Simulation
  > DDS_Simulation_Environment** and drag that existing Block onto the BDD.
  Use **Generalization** to draw a relationship from `TestContext` to
  `DDS_Simulation_Environment`, with the hollow triangle pointing to
  `DDS_Simulation_Environment`. Your Test Context now specializes it and
  inherits the harness state machine, which generates the DDS configuration,
  starts DDS and injects received samples every 5 seconds.
2. **Redefine `SYSTEM_UNDER_TEST`:** open your `TestContext` Block's
  **Specification > Properties**, select the inherited `SYSTEM_UNDER_TEST`
  property, and click **Redefine**. Set its **Type** to the service Block that
  owns your model (for example, `ShapeApplication`).
  The Interface Blocks are selected as the **Types of that Block's ports**.
3. **Connect the ports:** right-click `TestContext` and choose **Create Diagram
  > SysML Internal Block Diagram (IBD)**. Display `SYSTEM_UNDER_TEST` with its
  service ports and the inherited bus ports on the diagram frame. Use
  **Connector**, starting at `dds-bus-listener` and finishing at the part's
  subscribing port (for example, `SquareInput`). For publishing, **start at
  `dds-bus-sender` on the diagram frame and finish at the part's publishing
  port** (for example, `CircleOutput`). This drawing order matches SAGE and
  avoids the observed `Incompatible Port directions` error. Keep the bus
  ports inherited and untyped; keep the service ports unconjugated.
4. **Implement `runTest` (required for the supplied harness):** redefine the
  inherited `DDS_Simulation_Environment::runTest` operation in your Test Context
  and assign an executable **Method**. The base operation has no method; leaving
  it unimplemented can prevent the harness from continuing DDS polling.
  Test inputs and assertions are optional, but the method is not. For a minimal
  implementation, create an **Opaque Behavior** named `RunTestNoOp` under your
  Test Context, set **Language = Groovy** and **Body = `return`**, then select it
  as the redefined operation's **Method**. Leave its parameters empty.
  **Keep the Test Context's Classifier Behavior locally unset** so it uses the
  inherited harness. If creating the no-op assigns it as Classifier Behavior,
  clear that reference without deleting the behavior or its Method assignment.
5. **Optional polling interval:** to change the polling period, open your
  Testbench's **Specification > Properties**, redefine `ddsPollingPeriodMs`
  and set its **Default Value** in milliseconds (for example, `1000` for one
  second). Use a positive integer and restart the simulation to apply it.

The Groovy bodies of the harness's two Opaque Behaviors are kept in
[`groovy/`](groovy/): `register_dds_engine_listener` ←
[`RegisterDdsEngineListener.groovy`](groovy/RegisterDdsEngineListener.groovy),
`poll_dds_subscription_inject` ←
[`PollDdsSubscriptionAndInject.groovy`](groovy/PollDdsSubscriptionAndInject.groovy).
They are already in `DDS_Profile.mdzip`; update them there whenever these
files change.

### 5. Configure the Simulation (optional)

Create a top-level package named `simconfig`. Right-click the package and choose
**Create Diagram > Simulation Configuration Diagram**. Add your `simConfig`
(**SimulationConfig**) to this diagram. Drag and drop `TestContext` from the
Containment tree onto the same diagram.

Open `simConfig`'s **Specification > Simulation Configuration** and set:

| Property | Value |
|---|---|
| `executionTarget` | Your Test Context Block, for example `Testbench` |
| `animationSpeed` | `95` |
| `autoConvertUnits` | `true` |
| `autoStart` | `true` |
| `automaticParametricRecalculation` | `true` |
| `cloneReferences` | `false` |
| `numberOfRuns` | `1` |
| `openSimulationPane` | `true` |
| `runForksInParallel` | `true` |
| `showActiveStateImages` | `both` |
| `showRuntimeValuesOnPortLabels` | `true` |
| `silent` | `false` |
| `timeVariableName` | `"simtime"` |
| `treatAllClassifiersAsActive` | `true` |

### 6. Run

Run the **Simulation Configuration**:

- **SAGE example:** in `SAGE.mdzip`, run the Simulation Configuration
  **`Test for Test`** (its execution target is the `Test` Block).
- **Your own model:** run `simConfig` from the `simconfig` package configured
  in step 5.

Right-click > Simulate on the Test Context works too. Start the other DDS
applications (e.g. the sensor emulator) before or during the run.

Runtime messages are recorded without opening the Notification window.
Per-sample receive, inject and publish messages are off by default. Toggle
**Tools > RTI Connext DDS > Verbose DDS Logging** at any time, including during
simulation; no restart is needed. The setting applies to the current CAMEO
session. The startup option `-Dcom.rti.connext.cameo.verbose=true` sets its
initial state to enabled. Startup messages, warnings and errors remain enabled.
With verbose tracing, the Notification window includes:

```
[RTI Connext] Generated DDS config from the model (domain 'DDS_Schema', id 0): <temp>\rti_cameo\SAGE_dds_generated.xml
[DDS Inject] Simulating 'Test' -- DDS subscribers in this simulation: [Sensor Alignment and Gating Service]
[DDS Inject] Injected 'SensorReport' into 'Sensor Alignment and Gating Service' via port 'dds-bus-listener'.
[DDS Listener] Publishing Publisher::GatedReportWriter: {...}
```

Only the Blocks in the simulated Block's composition go on the DDS bus. To run
a hand-maintained XML instead of the generated one, start CAMEO with
`-Dcom.rti.connext.cameo.xmlConfig=<path>`. This replaces the whole configuration
and cannot be combined with the QoS-only file selection.

---

## Tools menu

**Tools > RTI Connext DDS**:

- **Scan Model for DDS Topics** — logs every topic and type the plugin finds.
- **Generate DDS XML** — exports the config for a `«DDS_Domain»` to a file,
  e.g. to hand to another team. This model export does not apply the runtime
  QoS-file override. Not needed for simulation.
- **QoS > Load QoS XML...** — select an XML file and a `Library::Profile` to use its
  QoS policies instead of model QoS. Only QoS libraries are loaded; types,
  topics and endpoints still come from the model.
  Generated publisher, subscriber, writer and reader names take precedence
  over imported names so the plugin can still locate its endpoints.
- **QoS > Library > Profile** — switch between profiles in the loaded
  XML file. The active profile and its library are checked; no file picker
  is needed.
- **QoS > Use Model QoS** — clears the QoS-file override. Existing model QoS
  profiles remain supported; currently only their history depth is used.
- **Verbose DDS Logging** — check to log each received, injected and published
  sample; uncheck to stop sample tracing immediately.

QoS selections last for the current CAMEO session and take effect after stopping
and restarting the simulation. The file is reread at each simulation start.
The selected profile is referenced by participant, publisher, subscriber, writer
and reader QoS. Generated topics have no `topic_qos` references. Use a
self-contained file with all required QoS libraries; inheritance from RTI
built-in profiles is supported. External XML includes and per-topic
`topic_filter` selection are not supported.

Generated XML uses the Connext version from `NDDSHOME/rti_versions.xml` for its
`version` attribute and `https://community.rti.com/schema/<version>/rti_dds_profiles.xsd`
schema URL. `NDDSHOME` must point to a readable Connext installation.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| No **Tools > RTI Connext DDS** menu | Run `install.bat` for the CAMEO version you start (`2024x` vs `2026x`), then restart CAMEO. |
| `No such property: com for class: Script…` | The plugin isn't loaded in this CAMEO session: run `install.bat` and restart CAMEO. |
| `UnsatisfiedLinkError: nddsjava` / `no nddscore in java.library.path` / `Could not initialize class com.rti.ndds.config.Logger` | Add `%NDDSHOME%\lib\x64Win64VS2017` to `PATH`, then fully restart CAMEO (or use `run_cameo.bat` from the setup window). |
| `No source for License information` / participant creation fails | Set `RTI_LICENSE_FILE` before starting CAMEO. |
| `class file has wrong version` while building | Wrong compiler: 2024x needs `javac` 17+, 2026x needs 21+. Update `PATH`. |
| `found N «DDS_Domain» packages` | The generated config supports exactly one `«DDS_Domain»` per project. |
| Selected QoS file moved or profile removed | Use **QoS > Load QoS XML...** to reselect the file/profile, or choose **QoS > Use Model QoS**, then restart the simulation. |
| `«DDS_Topic» Signal 'X' skipped: ...` | Give the Signal an attribute named `data` typed by a `«Block»`. |
| `DDS subscribers in this simulation: none` | The simulated Block's composition contains no subscribing Block: check that `SYSTEM_UNDER_TEST` is redefined and typed by your service. |
| `nothing is connected to 'Block.port'` | Connect `dds-bus-listener` to the service's subscribing port in the Test Context's IBD (step 4). |
| `Incompatible Port directions` on the sender connector | Delete the connector, keeping both ports, then redraw it **starting at `dds-bus-sender` and finishing at the publishing port on `SYSTEM_UNDER_TEST`** (for example, `Circle` in `shape_demo`). Keep the publishing Flow Property set to `out` and rerun validation. |
| Another application doesn't receive a topic | Its type name must match the model's: RTI's discovery log shows `type names for topic 'X' do not match`. |
| Two samples sent back to back, only one arrives | Topics without a DDS key keep only the latest sample by default. Use a QoS profile with a `history_depth`, marked `is_default`. |

---

## Adapt an existing Activity to receive and send DDS data

An Activity input parameter does not receive Signals automatically. To turn an
existing transformation Activity into a DDS-driven behavior, put the receiving,
processing and sending actions in that same Activity. No wrapper Activity such
as `CallCopyData` is needed once these actions are in place.

This example starts with `CopyData`, owned by the service Block
`ShapeApplicationBlock`. Its existing Groovy **Opaque Action** has an input pin
`src` and an output pin `dest`, both typed by `ShapeType`. The topic Signals are
`Square` and `Circle`, each with a `data:ShapeType` attribute. Substitute your
own names when adapting another model.

1. **Open the existing Activity diagram.** Keep the transformation action,
  its Groovy body, and its `src` and `dest` pins. Save a backup before changing
  the Activity's parameter interface; callers using that interface will need
  updating.
2. **Remove the old Activity parameters.** Delete the boundary **Activity
  Parameter Nodes** `inSq` and `outCirc` and their connecting Object Flows.
  Also remove the corresponding owned **Parameters** from the Activity's
  Specification, not just their diagram symbols. Keep the transformation
  action's `src` and `dest` pins.
3. **Add the receiver.** From the diagram palette, add an **Accept Event
  Action** named `ReceivingSquare`. In its Specification, configure a
  **Trigger** with a **Signal Event** whose **Signal = Square**. Set
  **Is Unmarshall = true**. Ensure its result/output pin is named `data` and
  has **Type = ShapeType**. Unmarshalling outputs the Signal's `data` payload,
  rather than the whole `Square` Signal instance.
4. **Add the sender.** Add a **Send Signal Action** with **Signal = Circle**.
  Set **On Port** to the service Block's publishing port, for example
  `CircleOutput` (or `Circle` if that is your port's name), not the Test
  Context's `dds-bus-sender`. Ensure the action's `data` argument/input pin
  has **Type = ShapeType**.
5. **Connect the pins with Object Flows**, not Control Flows:

  ```text
  ReceivingSquare.data --> transformation.src
  transformation.dest --> Send Circle.data
  ```

  Leave `ReceivingSquare` **without incoming control edges** so it remains
  enabled for further Signals while the Activity runs. This chain needs no
  Initial Node, Final Node, Merge Node or loop-back edge. Remove any such
  nodes added solely for the earlier loop experiment. The inherited harness
  polls DDS; it does not restart the transformation Activity for each sample.
6. **Assign the service behavior.** Open **ShapeApplicationBlock >
  Specification > Classifier Behavior** and select the `CopyData` **Activity**,
  not its diagram. Keep the **Test Context's** Classifier Behavior locally
  unset and retain its executable `runTest` Method, as described in
  [Create a Test Context](#4-create-a-test-context). If `CallCopyData` exists,
  delete that wrapper only after replacing its receive/send functionality and
  changing the service's Classifier Behavior reference. Do not delete
  `CopyData` itself.
7. **Run through the Test Context.** Restart its Simulation Configuration,
  rather than simulating `CopyData` by itself. Publish several external
  `Square` samples and verify that the transformation runs for successive
  samples and that an external DDS reader receives the resulting `Circle`
  samples. Entity creation alone does not verify this path.
