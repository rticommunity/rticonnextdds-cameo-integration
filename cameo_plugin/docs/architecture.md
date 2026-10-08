# RTI Connext DDS — CAMEO Plugin Architecture

How the plugin is put together and why. For installing and using it, see the
[README](../README.md).

- [Overview](#overview)
- [Code layout](#code-layout)
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
- **Behavior** — while Cameo Simulation Toolkit (CST) runs the model, it
  turns sent Signals into DDS samples, and received DDS samples into Signals.

```
                 simulation start
  SysML model ──────────────────────► DDS XML config ──► RTI Connext
       │                              (generated)            ▲   │
       │  SendSignalAction                                   │   │
       └──► DdsEngineListener ──► DDSTopicPublisher ─────────┘   │
                                                                 │
  harness port ◄── DdsInboundInjector ◄── queue ◄── DDSTopicSubscriber
   │  (poll script)
   └─ IBD connector ──► subscribing Block
```

---

## Code layout

`src/` is shared by both CAMEO versions. `src24x/` and `src26x/` hold the three
classes that touch CST's fUML runtime types, which moved package between
2024x and 2026x; the two copies differ only in imports and header comments.
`build.bat <version>` compiles `src/` plus the matching override.

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
| `DDSTopicSubscriber` | One reader per subscribing Block; converts received samples to JSON and queues them. |
| `DdsSubscriptionQueue` | Thread hand-off between RTI's callback thread and the simulation, one queue per (Block, topic). |
| `GenerateDdsXmlAction` | Menu action: export the XML to a file. |
| `ImportQosProfileAction` | Menu action: create a `«DDS_QosProfile»` from an RTI QoS XML file (the plugin's only write to the model). |
| `ElementPickerUtil` | Element-selection dialog used by the menu actions. |
| **`src24x` / `src26x`** | |
| `DdsEngineListener` | CST listener: detects `«DDS_Topic»` sends and publishes them. |
| `DdsInboundInjector` | Subscribes in-scope Blocks and injects queued samples into the simulation. |
| `FumlValueBridge` | The only place that touches CST's internal fUML value types (see [decisions](#design-decisions)). |

Model-side glue lives in `groovy/` and is pasted into the model: see the
README's [model setup](../README.md#model-setup).

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
3. **`DdsInboundInjector.setSimulationScope(_session_.getElement())`** —
   resolves the simulated Block (directly, through a «SimulationConfig»'s
   `executionTarget`, or through an owning Block) and records it plus every
   Block typing one of its part properties, recursively.
4. **Clears stale inbound queues and subscribes** each in-scope subscribing
   Block.

`DDSTopicPublisher` uses `-Dcom.rti.connext.cameo.xmlConfig=<path>` if set,
otherwise the generated file. There is deliberately no "first XML file in a
folder" fallback.

Participants and readers are torn down when the simulation ends.

---

## Outbound: model to DDS

1. **`elementActivated`** fires for a `SendSignalAction` on a `«DDS_Topic»`
   Signal. The listener works out the publishing Block — from the scan's
   publishers (Port/FlowProperty wiring), falling back to the action's owning
   Block — and queues a pending publish for participant
   `DomainParticipantLibrary::<Block>`, writer `Publisher::<Topic>Writer`.
2. **`eventTriggered(SignalInstance)`** fires with the actual Signal. Its
   `data` value is read field by field through `FumlValueBridge` (CST's `ALH`
   helper) and serialized to JSON following the **declared** DDS types: a
   `string` field is quoted even if the runtime value is a number, arrays
   become JSON arrays, enums their literal name, nested Blocks nested objects.
3. **`DDSTopicPublisher.publishOnce`** writes the sample and frees its native
   memory.

Only running objects send Signals, so outbound traffic is naturally limited
to what is being simulated.

---

## Inbound: DDS to model

1. **`DDSTopicSubscriber`** (RTI's thread) drains the reader on every
   `on_data_available` and queues each sample as JSON under
   `<Block>::<Topic>`.
2. **`groovy/PollDdsSubscriptionAndInject.groovy`** runs on a repeating
   transition of the harness Block and calls `pollAndInjectAll(ALH,
   ALH.getContext())`, which drains every in-scope queue each cycle.
3. For each sample, **`DdsInboundInjector`** parses the JSON (Gson), creates
   the payload Block instance and fills it following the model's types —
   nested Blocks get their own instance, recursively — wraps it in the topic
   Signal, and sends it with `ALH.sendSignal(signal, harness, port)` through
   each harness port the scan found connected to that subscribing Block.

The poll interval only affects latency: queues are fully drained every
cycle, so a burst is delayed, not dropped.

---

## Required model shape

**`DDS_Profile`** (custom; must be present in the project):

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
  named after it.
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
- **One reader, queue and delivery path per subscribing Block**, so two
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
| `Set_Object_Value` | Whether fields set through the model's `Set_Object_Value` Groovy library read back with real values at publish time is still to be verified. |
| Profile | `DDS_Profile` is not shipped in this repository yet. |
| Platform | Build and install scripts are Windows batch files. The Java code itself is OS-neutral. |
| CST API | Depends on CST internal fUML types (isolated in `FumlValueBridge`); a CST upgrade can break it, as 2024x → 2026x did. |
