# RTI Connext DDS — CAMEO Plugin Architecture

How the plugin is put together and why. For installing and using it, see the
[README](../README.md).

- [Overview](#overview)
- [Code layout](#code-layout)
- [Models and the test harness](#models-and-the-test-harness)
- [Simulation start](#simulation-start)
- [Outbound: model to DDS](#outbound-model-to-dds)
- [Inbound: DDS to model](#inbound-dds-to-model)
- [Required model shape](#required-model-shape)
- [Design decisions](#design-decisions)
- [Known limitations](#known-limitations)

---

## Overview

The model is the single source of truth. The plugin reads it in two ways:

- **Structure** — `«DDS_Topic»` Signals, their payload Blocks, and which
  Blocks publish or subscribe (ports, InterfaceBlocks, FlowProperties). From
  this it generates the DDS XML configuration.
- **Behavior** — while Cameo Simulation Toolkit (CST) runs the model, outgoing
  DDS-topic Signal events write payloads directly to DDS, and polling turns
  received DDS samples into Signals.

```
                 simulation start
  SysML model ──────────────────────► DDS XML config ──► RTI Connext
       │                              (generated)            ▲   │
       │  SendSignalAction                                  │   │
       └──► DdsEngineListener ──► DDSTopicPublisher ─────────┘   │
                                                                 │
  harness port ◄── DdsInboundInjector ◄── DDSTopicSubscriber.poll()
   │  (poll script; all on the simulation thread)
   └─ IBD connector ──► subscribing Block
```

---

## Code layout

`src/` contains the shared runtime, including `DdsEngineListener` and
`DdsInboundInjector`. Only `FumlValueBridge` lives in `src24x/` and `src26x/`:
CST's fUML types moved packages between releases. This small adapter handles
typed value access, object/signal creation, sending, and the typed Signal
callback. Its nested `SignalListener` forwards callbacks to the shared listener
without reflection. `build.bat <version>` compiles `src/` plus the matching
adapter. Existing Groovy entry points and behaviors remain unchanged.

| Class | Role |
|---|---|
| **`com.rti.connext.cameo`** | |
| `RTIConnextPlugin` | Plugin entry point; tears DDS down when CAMEO closes. |
| `RTIConnextActionsConfigurator` | Adds the **Tools > RTI Connext DDS** menu. |
| **`core`** — model reading, no DDS dependency | |
| `ModelTopicScanner` | Walks the model and builds a `TopicModel`: topics, struct and enum types, publishers, subscribers, and the ports wired into each subscriber. Also reads `«DDS_Domain»` and `«DDS_QosProfile»` tags. |
| `TopicModel` | Plain data produced by the scan. |
| `SimulationScope` | Finds the Block being simulated and the Blocks in its composition tree. |
| `JsonPayloadBuilder` | Builds the JSON handed to RTI on publish (strings escaped with Gson). |
| `ScanModelForTopicsAction` | Menu action: log the scan result. |
| **`dds`** — RTI Connext | |
| `DdsRuntimeConfig` | At simulation start: scans the `«DDS_Domain»`, generates the XML, writes it to the temp folder, and shares the scan with the listener and injector. |
| `DdsXmlGenerator` | `TopicModel` + domain id + QoS profile → DDS XML Application Creation document. |
| `DDSTopicPublisher` | Resolves the XML config, creates and caches one participant per Block, writes samples. |
| `DDSTopicSubscriber` | Polls one reader per subscribing Block and synchronously passes each valid sample as JSON to the injector. |
| `GenerateDdsXmlAction` | Menu action: export the XML to a file. |
| `LoadQosXmlAction` | Owns the QoS submenu: load XML, select a library/profile, or return to model QoS without modifying the model. |
| `ElementPickerUtil` | Element-selection dialog used by the menu actions. |
| `DdsEngineListener` | Matches outgoing DDS-topic actions to their Signal callbacks, writes immediately, and cleans up at execution end. |
| `DdsInboundInjector` | Polls in-scope readers and injects samples directly into the simulation. |
| **`src24x` / `src26x`** | |
| `FumlValueBridge` | The only place that touches CST's internal fUML value types (see [decisions](#design-decisions)). |

The model side of the plugin — two Opaque Behaviors in the test harness —
is kept as source in `groovy/`; see the next section.

---

## Models and the test harness

The shipped models (`DDS_Models/`) are layered, each using the one below:

| Layer | Model | Role |
|---|---|---|
| Language | `DDS_Profile` | Stereotypes, plus the reusable test harness |
| Interface | `DDS_Message_Library` | A system's topics, types and QoS: its ICD, shared by every service on that bus |
| Service | `SAGE` | One service's executable specification, built against that interface |

**The harness.** `DDS_Simulation_Environment` (in `DDS_Profile`) owns an
untyped `SYSTEM_UNDER_TEST` part, two untyped ports (`dds-bus-listener`,
`dds-bus-sender`) and a state machine:

```
CONFIGURE DDS OUTGOING      entry: register_dds_engine_listener
        │                   (config generation, scope, listener, subscriptions)
        ▼
┌─ concurrent ───────────────────────────────────────────────────┐
│ Test                     do: runTest()                          │
│ CONFIGURE DDS INCOMING   entry: poll_dds_subscription_inject,   │
│                          re-entered every 5 s                   │
└────────────────────────────────────────────────────────────────┘
```

A test is a Block that specializes the harness, redefines
`SYSTEM_UNDER_TEST` with the service and connects the inherited ports to it.
Nothing in the harness names the service, so it is reused unchanged.

**Why `runTest` is an operation.** A specialized Block's own classifier
behavior *replaces* the inherited one (fUML starts the classifier behavior of
the object's own type only), so giving a Test Block its own state machine
would stop the harness. Instead, the harness calls the abstract operation
`runTest`, and each Test Block provides the method — the harness decides when
the test runs, the Test Block decides what it does.

**CST behaviors the harness depends on** (observed on CST 2024x):

- An entry action runs to completion before the state machine processes the
  next event, in every region. A test that waits for responses must therefore
  be a `do` activity, or it blocks DDS polling in the other region.
- A transition without a trigger leaving a state that has a `do` activity
  fires as soon as the state is entered, aborting the `do` activity. A
  transition out of the test state needs a trigger (e.g. a signal the test
  sends itself when it has finished).
- The scope is taken from the running object (`ALH.getContext()`), not from
  `SimulationSession.getElement()`: inside the inherited state machine, the
  executing behavior belongs to the harness, while the running object is the
  Test Block.

---

## Simulation start

`groovy/RegisterDdsEngineListener.groovy` runs once:

1. **`DdsRuntimeConfig.prepare()`** — scans the project's single
   `«DDS_Domain»`, picks the `«DDS_QosProfile»` marked `is_default` (or the
   only one), generates the XML, and writes
   `<java.io.tmpdir>/rti_cameo/<Project>_dds_generated.xml`. RTI reloads it
   when the first participant of the run is created, so model changes apply
   without restarting CAMEO.
2. **Registers `DdsEngineListener`** on the simulation session.
3. **`DdsInboundInjector.setSimulationScope(ALH.getContext())`** — takes the
   Block type of the **running object** (e.g. a Test Block that specializes a
   harness and inherits its state machine; the behavior being executed would
   point at the harness instead) and records it plus, recursively, every
   Block typing one of its part properties and every Block it specializes.
4. **Deletes previous-run DDS entities and subscribes** each in-scope subscribing
   Block. The lifecycle listener still tears entities down at execution end;
   each poll restores readers if the short-lived startup behavior ended.

`DDSTopicPublisher` uses `-Dcom.rti.connext.cameo.xmlConfig=<path>` if set,
otherwise the generated file. There is deliberately no "first XML file in a
folder" fallback.

Participants and readers are torn down when the simulation ends.

---

## Outbound: model to DDS

1. **`elementActivated`** fires for a `SendSignalAction` on a `«DDS_Topic»`
   Signal. The listener resolves its publishing Block and records the Signal
   identity, participant and writer names. This is callback-routing bookkeeping,
   not a buffer of sample data.
2. **`eventTriggered(SignalInstance)`** matches the outgoing action and reads
   its `data` payload field by field through `FumlValueBridge` (CST's `ALH`
   helper) and serialized to JSON following the **declared** DDS types: a
   `string` field is quoted even if the runtime value is a number, arrays
   become JSON arrays, enums their literal name, nested Blocks nested objects.
3. **`DDSTopicPublisher.publishOnce`** writes the sample and frees its native
   memory.

The write happens synchronously in the Signal callback, without outbound
polling. An incoming injected Signal without a matching outgoing action is
not republished. The existing two harness Groovy behaviors and state machine
remain unchanged; no additional publication behavior is required.

---

## Inbound: DDS to model

1. DDS retains received samples in each reader's history, subject to QoS.
   No data-available listener or plugin sample queue is used.
2. **`groovy/PollDdsSubscriptionAndInject.groovy`** runs on a repeating
   transition of the harness Block and calls `pollAndInjectAll(ALH,
   ALH.getContext())`. For each in-scope reader, `DDSTopicSubscriber.poll()`
   calls `take()` and passes valid samples directly to the injector as JSON.
3. For each sample, **`DdsInboundInjector`** parses the JSON (Gson), creates
   the payload Block instance and fills it following the model's types —
   nested Blocks get their own instance, recursively — wraps it in the topic
   Signal, and sends it with `ALH.sendSignal(signal, harness, port)` through
   each harness port the scan found connected to that subscribing Block.
   The DDS loan is returned in a `finally` block after batch consumption.

All conversion and injection runs on the calling simulation thread. The poll
interval affects latency and retention: DDS history depth and resource limits
must accommodate bursts between polls. KEEP_LAST can replace older samples
before they are taken; removing the plugin queue does not remove DDS buffering.

---

## Required model shape

**`DDS_Profile`** (shipped as `DDS_Models/DDS_Profile.mdzip`):

| Stereotype | Extends | Tags |
|---|---|---|
| `«DDS_Domain»` | Package | `domain_id` (Integer) |
| `«DDS_Topic»` | Signal | — |
| `«DDS_Member»` | Property | `key` (Boolean), `max` (Integer: bounded string length) — optional, only where needed |
| `«DDS_QosLibrary»` | Package | — (container for QoS profiles) |
| `«DDS_QosProfile»` | Class | `history_depth` (Integer), `is_default` (Boolean), `participant_name` (String, currently ignored: participants are named after their Block) |

**SysML** (built in): `«Block»`, `«InterfaceBlock»`, and `«FlowProperty»` with
its `direction` (`in` / `out` / `inout`).

Rules:

- A topic is a `«DDS_Topic»` Signal with an attribute named **`data`**, typed
  by a `«Block»`. Signals that don't follow this are skipped with a warning.
- Payload fields: `Integer` → `int32`, `Real` → `float64`, `Boolean` →
  `boolean`, `String` → `string` (anything else → `string`); a part property
  typed by a Block → nested struct; a UML Enumeration → enum; a fixed
  multiplicity such as `[2]` → a one-dimensional array.
- A Block **publishes** a topic when one of its ports is typed by an
  InterfaceBlock with an `out` FlowProperty typed by the topic Signal, and
  **subscribes** with an `in` one. Each such Block becomes a participant
  named after it; a Block that specializes it is treated as that Block.
- Inbound delivery follows the IBD connector from the subscriber's port (as a
  part of the harness) to a harness port, which may be untyped.

---

## Design decisions

- **Generate the config at simulation start.** A hand-managed XML file could
  go stale or be shadowed by another file; generating it from the same scan
  the runtime uses keeps the two identical.
- **Read the model's meaning, not its names.** Topics, publishers,
  subscribers and inbound ports come from stereotypes, types and connectors,
  so renaming elements doesn't break anything and nothing in the code is
  specific to one model.
- **Every DDS entity references the QoS profile explicitly.**
  `is_default_qos="true"` has no effect on entities declared in a
  `domain_participant_library` (confirmed live), and a profile section only
  applies to its own entity kind, so the history depth is emitted for both
  writers and readers. Without this, keyless topics kept only the latest
  sample and back-to-back samples were lost.
- **Participants are named after their Block**, not from the shared QoS
  profile, so they stay distinguishable in RTI tools.
- **Topic, field and type names are an interface contract.** RTI matches
  differently named types by structure only when type information is
  exchanged during discovery; when it isn't, the type names must match
  (RTI logs `type names for topic 'X' do not match`). Renaming a payload
  Block therefore changes the interface for every other application.
- **One reader and delivery path per subscribing Block**, so two
  modeled Blocks receiving the same topic each get exactly one copy — the
  same behavior as two separate DDS applications.
- **Inject through the harness, with an explicit target.**
  `ALH.sendSignal(signal, target, port)` delivers exactly as an internal
  `SendSignalAction` would, so the receiving model needs no extra operations.
  The harness's ports may stay untyped because only the service is the
  reviewed specification.
- **Contain the internal-API dependency.** Reading Signal field values needs
  CST's fUML value types, which are marked `@InternalApi`; no supported
  alternative exists. All such access is in `FumlValueBridge` and goes
  through CST's public `ALH` helper where possible.
- **`core` is not yet DDS-independent.** It holds no RTI imports, but
  `ModelTopicScanner` still looks for DDS-named stereotypes and QoS tags. A
  second transport (e.g. MQTT) would need those names parameterized first.

---

## Known limitations

| Area | Limitation |
|---|---|
| Domains | Exactly one `«DDS_Domain»` per project when generating at simulation start. |
| Instances | One instance per Block is assumed; participants are per Block type. |
| Scope | Multiplicity is ignored: `0..1` / `0..*` parts join the bus at simulation start, before a behavior creates them. |
| Timing | Inbound samples are injected in arrival order at the poll interval, not paced by the timestamps they carry. |
| Types | No variable-length sequences (`0..*` fields serialize as scalars), no multi-dimensional arrays, small primitive mapping. |
| QoS | Only history depth is modeled. Import reads the first `<qos_profile>` in a file and doesn't update an existing element. |
| Inbound arrays | Injecting array and nested-Block fields into the simulation has not been exercised live (the demo's inbound topic is flat). |
| Polling retention | Reader history and resource limits must be sized for samples arriving between polls. |
| Tests | Standalone Java tests cover XML/QoS and direct DDS polling. Live CAMEO model execution must also be checked after updating embedded behaviors. |
| Platform | Build and install scripts are Windows batch files. The Java code itself is OS-neutral. |
| CST API | Depends on CST internal fUML types (isolated in `FumlValueBridge`); a CST upgrade can break it, as 2024x → 2026x did. |
