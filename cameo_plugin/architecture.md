# RTI Connext DDS — CAMEO Plugin Architecture

This document describes how the `cameo_plugin` codebase is put together: what
each package/class does, how design-time model scanning connects to DDS XML
generation, how runtime simulation is (partially) wired to live DDS
publishing, and where the known gaps are.

It reflects the code as it exists in `src/` today. `cameo_plugin/README.md`
covers building/installing/running; this document is the deeper reference —
see [Relationship to the README and `old_files/`](#relationship-to-the-readme-and-old_files)
for that split's history.

---

## Table of contents

1. [Purpose and design goals](#purpose-and-design-goals)
2. [Package layout](#package-layout)
3. [Plugin lifecycle — `com.rti.connext.cameo`](#plugin-lifecycle--comrticonnextcameo)
4. [Backend-agnostic core — `com.rti.connext.cameo.core`](#backend-agnostic-core--comrticonnextcameocore)
5. [DDS backend — `com.rti.connext.cameo.dds`](#dds-backend--comrticonnextcameodds)
6. [End-to-end flows](#end-to-end-flows)
7. [Required SysML/DDS profile shape](#required-sysmldds-profile-shape)
8. [Build, package, and deploy](#build-package-and-deploy)
9. [Inbound (subscribe) pipeline](#inbound-subscribe-pipeline--sensorreport-confirmed-working-end-to-end)
10. [Required SysML/DDS profile shape](#required-sysmldds-profile-shape)
11. [Build, package, and deploy](#build-package-and-deploy)
12. [Beyond the plugin — the full demo pipeline](#beyond-the-plugin--the-full-demo-pipeline-as-of-2026-09-14)
13. [Known gaps and TODOs](#known-gaps-and-todos)
14. [The "core is backend-agnostic" caveat](#the-core-is-backend-agnostic-caveat)
15. [Relationship to the README and `old_files/`](#relationship-to-the-readme-and-old_files)

---

## Purpose and design goals

The plugin adds a **"RTI Connext DDS"** entry to CAMEO System Modeler's
**Tools** menu with three actions:

- **Scan Model for DDS Topics** — a debug tool that scans the whole open
  project and logs every DDS Topic/type it finds.
- **Generate DDS XML** — the main feature: finds a `«DDS_Domain»`-stereotyped
  package, scans it for `«DDS_Topic»`-stereotyped Signals, optionally applies
  an imported `«DDS_QosProfile»`, and writes an RTI Connext DDS **XML
  Application Creation** config file the user can load into a running system
  (RTI Routing Service, Recording Service, or an `nddsjava`-based application
  via `DomainParticipantFactory`).
- **Import QoS Profile** — reads an RTI Connext QoS profile XML file and
  creates a corresponding `«DDS_QosProfile»` element in the model (inside a
  `«DDS_QosLibrary»` package, existing or newly created), so `Generate DDS
  XML` can later offer it in its QoS picker. **This is the plugin's first
  WRITE operation against the model** — every other action only reads. See
  [`ImportQosProfileAction.java`](#importqosprofileactionjava) below for the
  write-path mechanics and two deliberate deviations from the original spec.

There is also a **simulation-time** path (`DdsEngineListener`) intended to
publish live DDS samples while a Cameo Simulation Toolkit (CST) run is
active. The full pipeline — detect a `SendSignalAction` on a `«DDS_Topic»`
Signal, resolve its participant/writer names, extract the signal's field
values, build JSON, publish via `DDSTopicPublisher` — is implemented and
**confirmed working end-to-end live** for the `Health` topic, including a
real DDS `write()` actually succeeding. Two separate issues still block
full data fidelity: field values this project's models set via a custom
`Set_Object_Value` Groovy library (rather than a direct
`AddStructuralFeatureValueAction`) still extract as empty; and the
`SensorReport` topic specifically has no structural publisher declared
anywhere in the model, which is a modeling gap, not a plugin defect (see
[Known gaps](#known-gaps-and-todos) and the `DdsEngineListener.java` /
`FumlValueBridge.java` sections below for the full investigation).

**Design goal driving the package split:** the code was reorganized into
`core` (SysML/UML model scanning — reads Blocks, Signals, Ports,
FlowProperties) and `dds` (everything that actually talks RTI Connext DDS:
XML generation, the `nddsjava` publish path, the CST engine listener) in
preparation for a **possible future MQTT backend**. The intent is that a new
`com.rti.connext.cameo.mqtt` package could reuse `core`'s `TopicModel`/
`ModelTopicScanner` output without depending on anything DDS-specific. See
the caveat section below — this separation is currently physical/packaging
only, not yet a clean interface boundary.

---

## Package layout

```
src/com/rti/connext/cameo/
  RTIConnextPlugin.java                  Plugin entry point (extends Plugin)
  RTIConnextActionsConfigurator.java     Adds "RTI Connext DDS" to the Tools menu

  core/                                  Backend-agnostic SysML model scanning
    TopicModel.java                        Plain data: Topic, StructType, Field
    ModelTopicScanner.java                 Walks the model, builds a TopicModel
    ScanModelForTopicsAction.java          Tools-menu debug action
    JsonPayloadBuilder.java                Minimal flat-JSON builder (no external dep)

  dds/                                   RTI Connext DDS-specific backend
    DdsXmlGenerator.java                   TopicModel + domainId + QoS -> XML string
    ElementPickerUtil.java                 Shared native ElementSelectionDlg picker helper
    GenerateDdsXmlAction.java              Tools-menu action wiring core -> dds -> file
    ImportQosProfileAction.java            Tools-menu action; parses QoS XML, WRITES model elements
    DDSTopicPublisher.java                 nddsjava publish helper (DomainParticipant cache)
    FumlValueBridge.java                   Isolates the plugin's one internal-API-adjacent
                                            dependency (ALH / fUML types) behind a narrow,
                                            plain-Object-in/out surface
    DdsEngineListener.java                 CST EngineListener + SimulationExecutionListener
                                            (publish pipeline built; blocked — see Known gaps)
```

Supporting, non-Java files:

```
plugin.xml                    CAMEO plugin descriptor (id com.rti.connext.cameo)
build.bat / install.bat /     Build, deploy, remove the plugin under
  uninstall.bat                 <CAMEO_HOME>\plugins\com.rti.connext.cameo\
setup_rti_config.bat          Convenience script: sets NDDSHOME/CAMEO_HOME/RTIJDKHOME
                               env vars and opens a configured shell (self-elevates)
resources/ShapeType.xml       A *demo* XML config, unrelated to Generate DDS XML's
                               output — see Known gaps
scripts/RegisterDdsEngineListener.groovy
                               Groovy snippet the user pastes into a CST Opaque
                               Behavior to register DdsEngineListener at sim start
```

(`old_files/`, a pre-refactor snapshot, and `test_tools/`, the standalone
external DDS publishers used to test the inbound pipeline, have both been
moved out of this repository — see "Relationship to the README and
`old_files/`" and "Beyond the plugin" below.)

The old `com.rti.connext.cameo.model` and `com.rti.connext.cameo.actions`
packages are gone — every file that used to live there moved into `core/`
or `dds/`. The empty `model/` directory itself has been deleted;
`actions/` is still an empty directory on disk (harmless, but could be
deleted the same way if it's ever noticed).

---

## Plugin lifecycle — `com.rti.connext.cameo`

### `RTIConnextPlugin.java`

Extends CAMEO's `Plugin`. This is the class named in `plugin.xml`'s
`class="com.rti.connext.cameo.RTIConnextPlugin"` attribute — CAMEO
instantiates it on startup.

- **`init()`** — registers `RTIConnextActionsConfigurator` with
  `ActionsConfiguratorsManager`, then calls `loadNativeLibraries()`, then logs
  a ready message to the GUI log.
- **`loadNativeLibraries()`** — reads `NDDSHOME` from the environment, finds
  the `x64Win64*` architecture subdirectory under `%NDDSHOME%\lib\`, and
  attempts to prepend it to `java.library.path` via reflection (clearing
  `ClassLoader`'s cached `sys_paths` field) so the Connext JNI libraries
  (`nddsjava.dll`, `nddscore.dll`, …) can load. On JDK 9+ this reflection
  trick can be denied — the code catches that and logs a fallback message
  telling the user to add the path to the system `PATH` themselves, or to
  `<CAMEO_HOME>\bin\cameo.vmoptions` (`-Djava.library.path=...`).
- **`close()`** — calls `DDSTopicPublisher.shutdown()` to tear down any
  cached `DomainParticipant`s so the plugin doesn't leak DDS entities when
  CAMEO closes or the plugin is disabled.

### `RTIConnextActionsConfigurator.java`

Implements `AMConfigurator`. Its `configure()` finds (or falls back to the
root of) the **Tools** menu, creates a nested `ActionsCategory` named
**"RTI Connext DDS"**, and adds three actions to it, in this order:

1. `core.ScanModelForTopicsAction` (ID `RTI_SCAN_MODEL_DDS_TOPICS`)
2. `dds.GenerateDdsXmlAction` (ID `RTI_GENERATE_DDS_XML`)
3. `dds.ImportQosProfileAction` (ID `RTI_IMPORT_QOS_PROFILE`)

All three action instances are constructed once, as fields, when the
configurator itself is constructed (in `RTIConnextPlugin.init()`).

---

## Backend-agnostic core — `com.rti.connext.cameo.core`

### `TopicModel.java`

Pure data holders, no MagicDraw API dependency at all — easy to unit test,
and the shape any future backend (DDS today, maybe MQTT later) is meant to
consume.

```java
TopicModel
  Map<String, StructType> types    // keyed by Block name
  Map<String, Topic>      topics   // keyed by Signal (topic) name

TopicModel.StructType
  String name
  List<Field> fields

TopicModel.Field
  String name
  String primitiveType     // e.g. "int32", "string" — null if nested
  String nestedStructName  // name of a StructType — null if primitive
  boolean isKey            // «DDS_Member» stereotype applied AND its "key" tag is true
  Integer maxLength        // «DDS_Member»'s "max" tag — null if unset/not applicable
  boolean isNested()

TopicModel.Topic
  String topicName          // the Signal's name
  String typeName           // the StructType name (payload Block's name)
  List<String> publisherBlockNames
  List<String> subscriberBlockNames
```

### `ModelTopicScanner.java`

The engine that walks a SysML model (starting from either the whole project
or a single `«DDS_Domain»` package) and produces a `TopicModel`.

**Entry points:**

- `scan(Element root)` — full recursive, **single-pass** scan from any
  `Element`; used by `ScanModelForTopicsAction` for whole-project debugging.
  No domain boundary to respect, so there's nothing to filter.
- `scan(Package domainPackage)` — used by `GenerateDdsXmlAction`. **Two
  passes**, not a delegation to `scan(Element)`:
  1. **Pass 1 (domain-scoped)** — walks only `domainPackage`'s own
     containment tree (`scan(Element)`'s traversal, `visit()`), registering
     every `«DDS_Topic»` Signal found there as this domain's topics/types.
  2. **Pass 2 (project-wide, topic-filtered)** — separately walks the
     **whole project** (`Project.getPrimaryModel()`) via a dedicated
     `visitBlockPorts()` traversal, running `handleBlockPorts()`'s
     structural Port scan on every Block found anywhere — with an instance
     flag, `structuralScanRestrictedToExistingTopics`, set so
     `handleFlowProperty()` only *attaches* a publisher/subscriber Block to
     a topic Pass 1 already found (`result.topics.get(signal.getName())`)
     and never registers a new one.

  **Why two passes:** by this project's modeling convention, Blocks
  deliberately live *outside* the `«DDS_Domain»` package (only `«DDS_Topic»`
  Signals live inside it). A single-pass scan rooted at the domain package
  therefore never reaches any Block at all, so
  `publisherBlockNames`/`subscriberBlockNames` stayed empty for every
  domain — confirmed via a whole-project `scan(Element)` run that *did*
  find the correct publisher/subscriber Blocks, proving the Port →
  InterfaceBlock → FlowProperty → direction → Signal extraction logic
  itself was already correct; only the traversal *scope* was wrong.

  **Why Pass 2 is topic-filtered, not just "any Block anywhere":** Pass 2's
  `visitBlockPorts()` deliberately skips the Signal-registration branch and
  the behavioral trace (`handleBlockStateMachine()`/
  `handleBlockSendSignalActions()`) that `scan(Element)`'s `visit()` runs —
  those call `registerTopic()` unconditionally on whatever `«DDS_Topic»`
  Signal they encounter. Running them across the whole project during Pass
  2 would register *other domains'* topics into this domain's result,
  breaking the domain isolation that was verified working (each generated
  XML's `<domain_participant_library>` must only contain Blocks
  publishing/subscribing to *that* domain's own topics).
- `findDomainPackages(Element root)` — recursively finds every `Package`
  stereotyped `«DDS_Domain»`.
- `getDomainId(Package domainPackage)` — reads the Integer `domain_id`
  tagged value off a `«DDS_Domain»` package. Uses
  `StereotypesHelper.getStereotypePropertyValue()` (the raw-`List` accessor),
  **not** `getStereotypePropertyValueAsString()` — the latter was tried and
  found, via live testing, to silently return `"0"` instead of the actual
  set value for this plain Integer-typed tag (it's better suited to
  enum-backed tags). See the code comment on `getDomainId()`.
- `findQosLibraryPackages(Element root)` / `findQosProfiles(Element root)` —
  same `findDomainPackages()` pattern: recursively find every `«DDS_QosLibrary»`
  Package, or every `«DDS_QosProfile»`-stereotyped `Class` (filtered by
  `Class`, matching what `ImportQosProfileAction` actually creates), anywhere
  under root. `findQosProfiles()` is deliberately *not* restricted to living
  inside a `«DDS_QosLibrary»` package, in case a profile ends up elsewhere.
- `readQosProfileInfo(Class qosProfileElement)` — reads a `«DDS_QosProfile»`
  element's three tags into a `QosProfileInfo` (`profileName` — the
  element's own name; `participantName`, `historyDepth`, `isDefault`).
  `participant_name`/`is_default` use `getStereotypePropertyValueAsString()`
  (String/Boolean tags, same as `direction`/`key`); `history_depth`
  deliberately uses the **raw-value accessor** instead, same reasoning as
  `getDomainId()`/`max` — it's a plain Integer tag, and `AsString` is already
  known to silently return `"0"` for that shape of tag.
- `resolveStereotype(Element, profileName, stereotypeName)` — public wrapper
  around the private `lookupStereotype()` below, added so write-path callers
  (`ImportQosProfileAction`, which needs to resolve `«DDS_QosLibrary»`/
  `«DDS_QosProfile»` before applying them to newly created elements) don't
  keep their own separate, driftable copy — same rationale as `isDdsTopic()`.
  `DDS_PROFILE_NAME`, `QOS_LIBRARY_STEREOTYPE_NAME`,
  `QOS_PROFILE_STEREOTYPE_NAME`, and the three QoS tag-name constants are
  public for the same reason.

**Stereotype resolution** — every stereotype lookup is profile-scoped, not a
bare name match, via a shared `lookupStereotype(Element, profileName,
stereotypeName)` helper that resolves `StereotypesHelper.getProfile()` then
`StereotypesHelper.getStereotype(project, name, profile)`. Stereotypes used
so far, across both profiles:

| Profile | Stereotype | Meaning |
|---|---|---|
| `DDS_Profile` | `DDS_Topic` (extends Signal) | Marks a Signal as a DDS topic |
| `DDS_Profile` | `DDS_Domain` (extends Package, tag `domain_id`: Integer) | Marks a package as a DDS domain |
| `DDS_Profile` | `DDS_QosLibrary` (extends Package) | Container package for `DDS_QosProfile` elements |
| `DDS_Profile` | `DDS_QosProfile` (tags `participant_name`: String, `history_depth`: Integer, `is_default`: Boolean) | An imported RTI Connext QoS profile |
| `SysML` (built-in) | `InterfaceBlock` | Typed on a Block's Port |
| `SysML` (built-in) | `FlowProperty` (tag `direction`: enum `in`/`out`/`inout`) | Owned by an InterfaceBlock, typed by a Signal |
| (bare name) | `Block` | Plain SysML Block — intentionally **not** profile-scoped |

**Topic/type discovery — `registerTopic(Signal)` / `buildStructType(Class)`:**

Any `Signal` encountered anywhere during traversal that carries `«DDS_Topic»`
is registered as a topic *unconditionally* — this does **not** depend on
some Block also referencing it behaviorally. (This used to be the only path
in, and a purely structural `«DDS_Topic»` Signal with no behavioral wiring
was silently dropped — fixed after live testing confirmed the symptom; see
[Known gaps](#known-gaps-and-todos) for the debugging trail this came from.)
A topic's payload type comes from its one attribute named `data`, typed by a
Block; `buildStructType()` recursively expands that Block's owned
`Property`s into a `TopicModel.StructType`, distinguishing **part**
properties (`AggregationKindEnum.COMPOSITE` → nested struct, recursed —
never checked for `«DDS_Member»`, that only applies to value properties) from
**value** properties (mapped via `mapPrimitive()` — currently a small stub:
`Integer`→`int32`, `Real`→`float64`, `Boolean`→`boolean`, `String`→`string`,
anything else → `string` fallback).

**DDS_Member detection** — `«DDS_Member»` (extends Property, profile-scoped
to `DDS_Profile`) is a generalization of an earlier, narrower `«DDS_Key»`
stereotype: same target (Property), but covers more than one RTI XSD
`<member>` attribute. Only a Property that needs to declare something
special (it's the key, or it's a bounded-length string) gets it applied —
a plain field with no special semantics stays unstereotyped, same usage
pattern as before. Each value property in `buildStructType()` is checked
via `hasDdsMember(Property)`, then two tags are read independently (a
Property can have one, both, or effectively neither meaningfully set):

- **`key`** (Boolean) — `isKeyProperty(Property)`: true only if
  `«DDS_Member»` is applied **and** `key` reads as `true` via
  `StereotypesHelper.getStereotypePropertyValueAsString()` (same accessor
  as `FlowProperty`'s `direction`, appropriate here since this is also a
  Boolean/toggleable tag rather than a plain Integer like `domain_id`). Raw
  value logged first (`[DDS Scan] Property '...' stereotyped «DDS_Member»
  — raw 'key' tag: '...'`), same diagnostic discipline as `direction`.
- **`max`** (Integer, optional — bounded string length) —
  `readMaxLength(Property)`: unset/missing reads as "no bound" (`null`),
  not an error. Deliberately uses the **raw-value accessor**
  (`StereotypesHelper.getStereotypePropertyValue()`), *not*
  `getStereotypePropertyValueAsString()` — `max` is a plain Integer tag,
  the same shape as `domain_id`, and `getStereotypePropertyValueAsString()`
  was already found (see `getDomainId()` above) to silently return `"0"`
  instead of the real value for that kind of tag. Still logs the raw value
  read for the same diagnostic-discipline reasons. Only meaningful for a
  string-typed field — `readMaxLength()` doesn't check the field's type
  itself; `DdsXmlGenerator` is responsible for ignoring it on a non-string
  field (see below), not the scanner.

Both results become `TopicModel.Field.isKey`/`maxLength`, which
`DdsXmlGenerator` turns into `key="true"`/`stringMaxLength="..."` on the
matching `<member>`.

**Fixed-size array detection** (added for `GatedReport`'s `position`/
`velocity : Real[2]`) — `readArrayDimension(Property)`, called for every
value property in `buildStructType()`. `Property` implements UML's
`MultiplicityElement` interface (confirmed against the local CAMEO 2024.3
javadoc, `openapi/docs/md-javadoc-2024.3.0-...-javadoc.jar`), which exposes
plain `int getLower()`/`int getUpper()` directly on `Property` — no
`ValueSpecification` unwrapping needed, and no cast required since
`Property` already implements the interface. Only `upper == lower AND
upper > 1` counts as a genuine fixed-size array (`arrayDimension` set to
that value on `TopicModel.Field`); a variable-length sequence (`0..*`,
`1..*`, or any other `upper != lower` shape) is deliberately left alone
(`arrayDimension` stays `null`) — this scanner does not attempt to handle
sequences, only fixed-size arrays. Raw `lower`/`upper` values are logged
per property, same diagnostic discipline as `direction`/`key`/`max`.

**UML Enumeration detection** (added for `GatedReport`'s `sensor_modality :
SensorModalityEnum`) — a genuinely new third branch in `buildStructType()`,
alongside the existing composite-aggregation-Block (nested struct) and
primitive-value-property branches: when a value property's
`Property.getType()` is a `com.nomagic.uml2.ext.magicdraw.classes.mdkernel.
Enumeration` (confirmed via local javadoc: `public interface Enumeration
extends DataType`), `registerEnumType(Enumeration)` records it into a new
`TopicModel.enums` map — keyed by the Enumeration's name, only added once
even if multiple fields/structs reference the same enum, same
de-duplication shape as `TopicModel.types` for nested structs — and the
field's `TopicModel.Field.enumTypeName` is set to that name. Literal names
are read via `Enumeration.getOwnedLiteral()` (confirmed via javadoc:
returns `List<EnumerationLiteral>` in declaration order) and
`EnumerationLiteral.getName()` (inherited from `NamedElement`) — no
explicit integer values are read off the model; see `DdsXmlGenerator`
below for how those get assigned on emission.

**Publisher/subscriber discovery is STRUCTURAL, not behavioral** —
`handleBlockPorts(Class)` is the sole source of
`Topic.publisherBlockNames`/`subscriberBlockNames`:

```
Block
  └─ owned Port
       └─ Port.getType() → Class stereotyped «InterfaceBlock» (SysML)
            └─ owned Property stereotyped «FlowProperty» (SysML)
                 ├─ "direction" tag: in / out / inout
                 └─ Property.getType() → Signal
                      └─ if stereotyped «DDS_Topic»:
                           out/inout  → add Block to Topic.publisherBlockNames
                           in/inout   → add Block to Topic.subscriberBlockNames
```

`direction` is read via `StereotypesHelper.getStereotypePropertyValueAsString()`
(appropriate here since it's genuinely enum-backed), logged raw
(`[DDS Scan] FlowProperty '...' — raw direction value: '...'`) so the exact
string CAMEO returns can be verified against a real model, then defensively
normalized (`normalizeDirection()` strips a qualified-enum prefix like
`"FlowDirectionKind::out"` down to `"out"` if CAMEO ever returns that form)
and compared case-insensitively.

**A separate BEHAVIORAL trace also still runs** — `handleBlockStateMachine()`
→ `scanRegionForSubscriptions()` (State Machine → Transition →
`SignalEvent` trigger) and `handleBlockSendSignalActions()` (any owned
`SendSignalAction`, found by walking all descendants of the Block). Both
still call `registerTopic()` (so a topic reachable only behaviorally is
still discovered/typed), but **deliberately no longer write to**
`publisherBlockNames`/`subscriberBlockNames` — that responsibility now
belongs solely to the structural Port scan above. The behavioral trace is
kept, not deleted, for a backlogged future validation feature: cross-check
whether a Block's structural Port/FlowProperty direction (what it *can* do)
actually matches its behavioral wiring (what it *does* do) — e.g. flag a
Block whose Port says "publishes X" but whose state machine never sends X.

### `ScanModelForTopicsAction.java`

Tools-menu action (`Scan Model for DDS Topics`). Runs `ModelTopicScanner.scan()`
against `Project.getPrimaryModel()` (the whole open project) and logs every
discovered `Topic` (name, type, publishers, subscribers) and `StructType`
(name, fields) to the GUI log via `[DDS Scan]`-prefixed lines. Pure
read/log, no side effects — the fastest way to sanity-check a model without
running the full XML-generation flow.

### `JsonPayloadBuilder.java`

A tiny, dependency-free flat-JSON builder (`addString`/`addInt`/`addRaw`/
`build()`, plus `fromExisting()` to parse a flat `{"k":"v"}` string back into
a builder). Written to avoid relying on `groovy.json` or any JSON library,
since CAMEO's embedded script engine classpath may not include one. Now
wired into `DdsEngineListener.publish()`/`appendField()` — one field at a
time, recursing into a fresh builder for each nested struct field and
folding the result in via `addRaw()`.

---

## DDS backend — `com.rti.connext.cameo.dds`

### `DdsXmlGenerator.java`

Pure `String` in/out, no MagicDraw dependency — `generate(TopicModel model,
int domainId, ModelTopicScanner.QosProfileInfo qosProfile)` returns a
complete RTI Connext DDS XML Application Creation document as a string
(`qosProfile` may be `null`). Structure emitted, in order:

1. **`<types>`** — one `<enum>` per `TopicModel.EnumType` in `model.enums`
   (`appendEnums()`), emitted first — enums have no dependencies of their
   own, so unlike structs they need no topological ordering, just "before
   any struct that might reference one." Each `<enumerator>` gets an
   explicit, sequential `value="0"`, `"1"`, ... in declaration order
   (`ModelTopicScanner` doesn't read explicit integer values off the
   model — RTI's schema allows omitting `value` and defaults to the same
   sequential numbering, confirmed against the real, RTI-shipped example
   `Color.xml`, but emitting it explicitly removes any ambiguity). Then one
   `<struct>` per `StructType`, topologically sorted (DFS-based,
   `topologicallySortStructs()`) so nested struct types are defined before
   anything that references them — computed explicitly rather than trusting
   `TopicModel.types`' map-insertion order, since this generator is also
   meant to work against a hand-built `TopicModel` (e.g. in a unit test).

   Each `<member>`'s `type=` attribute (`appendMember()`) branches three
   ways, verified against real RTI Connext example XML shipped locally
   under `<RTI install>\resource\app\app_support\system_designer\srcJs\
   node_modules\@connextdev\rticonnextdds-typerepoutils\test\res\
   test_types\` (`Color.xml`, `EnumType.xml`, `arrays.xml`) rather than
   assumed, since the two forms below are **not** symmetric:
   - **Nested struct reference** — bare `type="<StructName>"`. Unchanged
     from before the array/enum work below, already proven correct at
     runtime (`Health`/`SensorReport` publish live in this project using
     exactly this form).
   - **Enum reference** (`field.isEnum()`) — **not** a bare
     `type="<EnumName>"` (the naive guess) — RTI's own shipped example XML
     uses `type="nonBasic" nonBasicTypeName="<EnumName>"` instead
     (confirmed in `Color.xml`'s consuming struct in `EnumType.xml`). Using
     the bare form here would have been an unverified assumption; this is
     the confirmed one.
   - **Primitive** (existing behavior) — bare `type="<primitiveType>"`
     (`int32`/`float64`/`string`/`boolean`).

   Up to three more attributes can be added independently, all omitted
   (not defaulted) when not applicable, and all confirmed (via `arrays.xml`)
   to coexist freely with each other and with either `type=` form above:
   `arrayDimensions="<N>"` when `field.arrayDimension` is set (a genuinely
   fixed-size array, e.g. `Real[2]` → `arrayDimensions="2"` — RTI's schema
   also supports comma-separated multi-dimensional arrays like
   `arrayDimensions="5,6"`, but `ModelTopicScanner` only ever produces a
   single dimension today, see Known gaps); `stringMaxLength="..."` when
   `field.maxLength` is set **and** `field.primitiveType` is `"string"` (a
   `«DDS_Member»` `max` tag on a non-string field is silently ignored here,
   deliberately — see the `ModelTopicScanner` section); and `key="true"`
   when `field.isKey` is set (the source Property was stereotyped
   `«DDS_Member»` with `key=true`). Matches the reference `ShapeType.xml`
   pattern (`type="string" stringMaxLength="128" key="true"` all on one
   `<member>`).
2. **`<qos_library><qos_profile>`** — **entirely omitted when `qosProfile`
   is `null`** (the default, zero-friction path — see
   `GenerateDdsXmlAction` below). When present: one `<qos_profile
   name="...">` inside a fixed-name `<qos_library name="QosLibrary">` (like
   `DOMAIN_LIBRARY_NAME`/`DOMAIN_NAME`, this is a constant, not derived from
   the model's actual `«DDS_QosLibrary»` package name — same existing
   convention). `is_default_qos="true"` only when `qosProfile.isDefault`;
   a `<domain_participant_qos><participant_name><name>` block only when
   `participantName` is set; a `<datareader_qos><history><depth>` block
   only when `historyDepth` is set — matches the reference `ShapeType.xml`
   structure.
3. **`<domain_library><domain domain_id="...">`** — one `<register_type>`
   per struct, one `<topic>` per `Topic`.
4. **`<domain_participant_library>`** — one `<domain_participant>` per
   distinct Block name appearing in any topic's publisher/subscriber list,
   with a `<publisher>`/`<data_writer>` and/or `<subscriber>`/`<data_reader>`
   inside, named `"<topic>Writer"`/`"<topic>Reader"` — the same naming
   convention `DDSTopicPublisher` and `DdsEngineListener` assume when
   resolving `dataWriterName` at runtime. When `qosProfile` is non-null,
   each generated `<data_writer>`/`<data_reader>` now also gets a nested
   `<datawriter_qos base_name="QosLibrary::<profileName>"/>` /
   `<datareader_qos base_name="...">` reference (added 2026-09-14) — see
   the "QoS profile default doesn't apply to XML-declared entities" gap
   below for why. The previous design assumed `is_default_qos="true"`
   alone was enough to auto-apply the profile to statically-declared
   `<data_writer>`/`<data_reader>` elements. **That's false, confirmed via
   live testing** — `is_default_qos` has no effect on entities declared
   this way; only an explicit `base_name` reference does. Without this
   fix, a model's own `«DDS_QosProfile»` (e.g. `history_depth`) was
   silently never actually applied to any generated reader/writer,
   regardless of `is_default`.

### `ElementPickerUtil.java`

Package-private shared helper — a single native, CAMEO-standard
`ElementSelectionDlg` picker used by `GenerateDdsXmlAction` (the
`«DDS_Domain»` package picker and the QoS profile picker) and
`ImportQosProfileAction` (the "Use Existing Package" `«DDS_QosLibrary»`
picker). Replaced what used to be three separate plain `JOptionPane`
dropdowns, for a picker UI consistent with the rest of CAMEO.

`pickOne(Element root, List<T> candidates, String title, boolean
allowNone, T preselected)` — `ElementSelectionDlg` is fundamentally a
**tree browser rooted at `root`**, not a picker over an arbitrary flat
Java list (there's no "just show these N objects" constructor). The
mechanism that restricts it to an exact candidate set is a custom
`TypeFilter`: despite the name, `TypeFilter extends ElementFilter`, whose
`accept(BaseElement, boolean)`/`accept(BaseElement)` decide per-element,
not just per-type — so `pickOne()`'s filter simply checks
`candidates.contains(obj)`, used as both the visible- and
selectable-elements filter passed to
`ElementSelectionDlgFactory.initSingle()`. The tree still shows the
containment path (ancestor Packages) needed to reach a candidate, since
that's required for any tree UI to be navigable — same as a real "select a
Block" CAMEO dialog letting you browse through unrelated Packages to find
one.

"None" is native too, not a workaround: `SelectElementInfo`'s `showNone`
flag (`pickOne()`'s `allowNone` param) adds a "<none>" node the dialog
shows; picking it still sets `isOkClicked()==true`, but
`getSelectedElement()` then returns `null` (confirmed in the javadoc) —
`pickOne()` returns `null` for that case and for outright cancellation
alike, since every caller here already treats "nothing picked" as one
outcome regardless of which of those two ways it happened.

### `GenerateDdsXmlAction.java`

Tools-menu action (`Generate DDS XML`) — the orchestrator that wires `core`
to `dds`. Still entirely **read-only** — the QoS profile it may apply must
already exist in the model (created separately by `ImportQosProfileAction`):

```
1. Application.getInstance().getProject() → error + stop if none open
2. ModelTopicScanner.findDomainPackages(primaryModel)
     0 found  → GUI-log error, stop
     1 found  → use it directly
     >1 found → ElementPickerUtil.pickOne(...) — native CAMEO picker,
                restricted to exactly these Package candidates
3. ModelTopicScanner.getDomainId(chosenPackage) → error + stop if unset
4. ModelTopicScanner.findQosProfiles(primaryModel)
     0 found  → proceed with no QoS — SILENT, no dialog (must stay the
                default zero-friction path)
     ≥1 found → chooseQosProfile(): ElementPickerUtil.pickOne(...) with
                allowNone=true (CAMEO's native "None" node, not a
                synthetic list entry); pre-selected to whichever profile
                has is_default=true if exactly one does, else nothing
                pre-selected — user can still pick "None" regardless
5. ModelTopicScanner.scan(chosenPackage) → TopicModel
6. DdsXmlGenerator.generate(model, domainId, qosProfile) → XML string
7. JFileChooser "Save As" (default filename "<domainPackageName>.xml")
8. Write the XML string to the chosen file (UTF-8)
9. Log success with the absolute file path, or a clear error — every step
   above is wrapped so a failure never crashes CAMEO
```

Note: the file this writes is **not automatically picked up** by
`DDSTopicPublisher` at runtime — see [Known gaps](#known-gaps-and-todos).

### `ImportQosProfileAction.java`

Tools-menu action (`Import QoS Profile`) — **the plugin's first WRITE
operation against the model.** Every other action reads; this one creates
persistent `Package`/`Class` elements, applies stereotypes, and sets tagged
values. All mutation happens inside a `SessionManager` session, per
NoMagic's OpenAPI contract:

```
1. Get current project → error + stop if none open
2. ModelTopicScanner.findQosLibraryPackages(primaryModel)
3. Dialog: "Use Existing Package" vs "Create New Package"
     Use Existing → ElementPickerUtil.pickOne(...) — native CAMEO picker,
                    restricted to exactly the found «DDS_QosLibrary»
                    packages
     Create New   → text-input dialog for a name, then (in a session):
                    ElementsFactory.createPackageInstance(), name it,
                    ModelElementsManager.addElement(pkg, primaryModel),
                    StereotypesHelper.addStereotype(pkg, «DDS_QosLibrary»)
4. JFileChooser "Open" (XML files) for the QoS profile file
5. Parse with javax.xml.parsers.DocumentBuilderFactory (standard JDK):
     <qos_profile name="...">              → profile name (falls back to
                                              the XML filename if the name
                                              attribute is missing)
     is_default_qos="true"/"false"         → isDefault
     domain_participant_qos/participant_name/name → participantName
     datareader_qos/history/depth          → historyDepth
   Any missing element is skipped, not an error.
6. In a session: ElementsFactory.createClassInstance(), name it from the
   parsed profile name, ModelElementsManager.addElement(cls, qosLibrary),
   StereotypesHelper.addStereotype(cls, «DDS_QosProfile»), then
   StereotypesHelper.setStereotypePropertyValue(...) for each tag that was
   actually parsed (participant_name/history_depth/is_default)
7. Log success (element name + package) or a clear error at every step
```

**Two deliberate deviations from how this was originally specified**, both
because the real javadoc surfaced something the spec didn't account for
(verified against local CAMEO 2024.3 javadoc before writing, same as every
other API call in this codebase):

1. **`ModelElementsManager.getInstance().addElement(element, parent)`**,
   not a raw `Element.setOwner(parent)` call, attaches a newly created
   element to its owner. Both exist; `addElement()` is the session-aware,
   purpose-built API for exactly this — its own javadoc cross-references
   `SessionManager` — and throws proper exceptions
   (`ReadOnlyElementException`, etc.).
2. **On failure inside a session this action opened, it calls
   `SessionManager.cancelSession(project)`, not `closeSession(project)`.**
   `closeSession()`'s javadoc says explicitly changes are *recorded in the
   command history* (i.e. **committed**); `cancelSession()`'s says they're
   *rolled back*. Always closing on error — as originally specified —
   would commit a half-created `Package`/`Class` into the user's real model
   on any failure partway through a session; `cancelSession()` is what
   actually undoes it. `closeSession()` is still used on the success path.

Each of the two write steps (package creation, profile creation) opens and
closes/cancels its **own** session, guarded by `isSessionCreated(project)`
first (so it doesn't try to nest a session inside one that's already open
for some other reason) — matching the step boundaries in the original spec
rather than wrapping the whole action in one long session.

QoS is otherwise explicitly limited in scope — only the three tags this
action parses (`participant_name`, `history_depth`, `is_default_qos`) are
read or written anywhere in this codebase; no other QoS policy is modeled.

### `DDSTopicPublisher.java`

A reusable, topic-agnostic publish helper — the only class in the plugin
that actually imports `com.rti.dds.*` (the Connext Java API / `nddsjava`).

- `publishOnce(participantConfigName, dataWriterName, jsonPayload)` —
  lazily creates (and caches, keyed by `participantConfigName`, in a
  `ConcurrentHashMap`) a `DomainParticipant` from the resolved XML config via
  `create_participant_from_config()`, looks up the named
  `DynamicDataWriter`, builds a `DynamicData` sample from the JSON payload
  via `sample.from_string(json, PrintFormatKind.JSON_PRINT_FORMAT)`, and
  writes it. `DomainParticipant` creation is expensive (triggers
  discovery/matching with every other participant on the domain), hence the
  cache — participants live for the plugin's lifetime and are only torn
  down by `shutdown()` (called from `RTIConnextPlugin.close()`).
- `resolveXmlUrl()` — resolves which XML config file to load participants
  from, in order: (1) system property
  `-Dcom.rti.connext.cameo.xmlConfig=<path>`, else (2) the first `*.xml`
  file, alphabetically, found in `<plugin-dir>/resources/` (derived from the
  running JAR's own `CodeSource` location). Cached after first resolution.
- `ensureLoggingConfigured()` — one-time setup (guarded by a `volatile
  boolean`, called at the top of `publishOnce()`) that routes NDDS's own
  internal diagnostic logging (`com.rti.ndds.config.Logger`) into this
  plugin's GUI log via a custom `LoggerDevice`, at `WARNING` verbosity.
  Added specifically because `RETCODE_ERROR` on `write()` was carrying no
  message — confirmed via decompiling `RETCODE_ERROR.check_return_codeI()`
  that it only attaches a message when the native
  `get_last_error_messages_and_clearI()` call returns non-null, which it
  wasn't for these failures — so the actual reason was only ever visible
  through NDDS's own `Logger` output, which by default goes nowhere this
  plugin could see it. This is what surfaced the real
  `RTIXMLObject_addChild: ... already exists` parse error (see
  `DdsXmlGenerator.java` below and Known Gaps) — the bare exception alone
  never would have.

### `DdsEngineListener.java`

`extends SimulationExecutionListener implements EngineListener`. Registered
manually by the user pasting `scripts/RegisterDdsEngineListener.groovy` into
a Groovy Opaque Behavior that must fire **exactly once** at simulation start
(registering it more than once means duplicate publishes). The script does
two registrations on the one listener instance:
`engine.addEngineListener(listener)` and
`_session_.getExecution().addSimulationListener(listener)` — and constructs
it as `new DdsEngineListener(_session_)`, since the listener needs a
`SimulationSession` to build an `ALH` (see `FumlValueBridge.java` below).

**Why both registrations, and why `SimulationExecutionListener` at all —**
this is the result of a real investigation trail, not an arbitrary choice:

1. The plain `EngineListener` (registered via `addEngineListener()` alone)
   fires `elementActivated` for ordinary `ActivityNode` activations
   (`ControlFlow`, `ActivityFinalNode`, …) but — confirmed via live testing
   with real `SendSignalAction`s executing — **never** for a
   `SendSignalAction` itself.
2. Decompiling `simulation.toolkit.core` (no javadoc for this internal
   engine) showed `SendSignalAction`'s runtime activation class,
   `InvocationActionActivation`, doesn't call `elementActivated` at all —
   it calls a separate `actionInvoked(Action, InvocationActionActivationInfo)`
   on a different interface, `InternalEngineListener`. This was implemented
   and live-tested — it **never fired either**, despite confirmed-correct
   registration. Independently of that negative result, `javap -v -p`
   confirmed `InternalEngineListener` (interface and each of its 3 methods)
   and `ExecutionEngine.addInternalEngineListener(...)` all carry
   `@InternalApi(reason="No Magic internal API. This code can change
   without any notification.") + @Deprecated`. **This path was removed
   entirely** — both unsupported and non-functional.
3. Continuing to search for a supported alternative surfaced
   `com.nomagic.magicdraw.simulation.execution.SimulationExecutionListener`
   — a public, `@OpenApiAll`-marked **class** (not interface) with 16
   overridable no-op methods, registered via
   `SimulationExecution.addSimulationListener(SimulationExecutionListener)`
   (itself reached via `SimulationSession.getExecution()`, both plain public
   methods, no internal annotation at any point in that chain). Live
   testing confirmed: `elementActivated(Element, Collection<?>)` — the
   *exact same signature* `EngineListener` also declares — **does** fire
   for `SendSignalAction` when registered through this path, where the
   `EngineListener`-only registration never did. The earlier "never fires"
   finding was specific to that one registration path, not a fundamental
   limitation of the callback.

Both registrations are kept: `EngineListener` for the ordinary
`ActivityNode` traffic (still logged diagnostically, useful context), and
`SimulationExecutionListener` for `SendSignalAction` detection and the real
publish pipeline.

**Pipeline — two callbacks, correlated by Signal identity:**

`elementActivated(Element, Collection<?>)`:
1. Ignores anything that isn't a `SendSignalAction`.
2. Ignores signals not stereotyped `«DDS_Topic»`, via
   `ModelTopicScanner.isDdsTopic(Signal)`.
3. Logs each value's runtime class + `toString()` (diagnostic).
4. Resolves `participantConfigName`/`dataWriterName` via
   `findOwningBlock()` (walks owners looking for the enclosing `«Block»`) —
   worked for `SensorReport` but returned null for `Health` in the same live
   run. Root-caused by comparing both owner chains, logged side by side:
   `SensorReport`'s `SendSignalAction` is embedded in a State's own
   classifier-behavior Activity (`Activity -> State -> Region ->
   StateMachine -> Class`, reaching the Block); `Health`'s sits inside a
   reusable/library Activity (`"Initialize SAGE"`) invoked via
   `CallBehaviorAction` from a Block's state machine but not *owned* by any
   Block at all — the owner chain dead-ends at a `Package`/`Model`, no
   `Class` anywhere. That's a genuine "no ownership relationship to walk"
   case, not a walk bug. **Fixed** by falling back, when `findOwningBlock()`
   returns null, to `findPublishingBlockNameFallback()` — the
   already-scanned structural publisher Block(s) `ModelTopicScanner` derives
   from Port -> InterfaceBlock -> FlowProperty -> direction -> Signal (the
   same mechanism `DdsXmlGenerator`'s own participant naming is built on).
   Returns the single publisher Block name if there's exactly one
   unambiguous candidate; refuses to guess (returns null, logged) if zero or
   more than one. `participantConfigName`/`dataWriterName` format itself —
   `"DomainParticipantLibrary::<BlockName>"` /
   `"Publisher::<TopicName>Writer"` — remains verified against
   `DdsXmlGenerator`'s actual output.
5. Queues a `PendingPublish` (Signal + resolved names — all public-API data)
   on a `ConcurrentLinkedDeque`, since the actual field data isn't available
   here yet — only reachable via `eventTriggered(SignalInstance)`, which
   fires shortly after.

`eventTriggered(SignalInstance)` (the `SimulationExecutionListener` overload
— richer than `EngineListener`'s own `eventTriggered(String)`):
1. Resolves the `Signal` via `FumlValueBridge.getSignal(signalInstance)`.
2. Pops the matching `PendingPublish` off the queue (matched by `Signal`
   identity — `takePending()`).
3. Reads the payload struct via
   `FumlValueBridge.getFieldValue(session, signalInstance,
   ModelTopicScanner.TOPIC_DATA_ATTRIBUTE_NAME)` — the Signal's one
   attribute, always named `"data"`, typed by the payload Block (per
   `ModelTopicScanner`'s convention — the struct's own fields, like
   `declared_id`/`sensor_id`, live one level down from the `SignalInstance`,
   not directly on it).
4. `appendField()` walks the scanned `TopicModel.StructType.fields`,
   reading each via `FumlValueBridge.getFieldValue()`, recursing into a
   fresh `JsonPayloadBuilder` for nested-struct fields, and dispatching
   String/Number/Boolean/`EnumerationLiteral` values into the right
   `JsonPayloadBuilder` call (unrecognized shapes fall back to
   `toString()` as a JSON string, logged as a warning rather than silently
   guessed at).
5. `DDSTopicPublisher.publishOnce(participantConfigName, dataWriterName,
   json)`.

`getOrScanTopicModel()` runs `ModelTopicScanner.scan(project.getPrimaryModel())`
once, lazily, and caches the result per listener instance (i.e. per
simulation run — a new listener is constructed each time the registration
script runs).

**Current blocker — field extraction returns empty for `Set_Object_Value`-set
fields.** Live testing found every field in a `SensorReport` sample coming
back `null`, even after confirming: the `"data"` hop resolves to the right
object; both a flat key (`"data.measurement_frame"`) and the plain field
name were tried; the object's own `toString()` (NoMagic's own rendering)
shows every field genuinely empty, not just invisible to this code. Tracing
further:

- The model's `Set_Object_Value` Opaque Behavior (Groovy body:
  `ALH.setValue(object_in, key, value); object_out = object_in;`) is what
  populates most `SensorReport` fields — a generic key/value helper built on
  `com.nomagic.magicdraw.simulation.utils.ALH`, not a direct
  `AddStructuralFeatureValueAction`.
- Fields set the *standard* way (e.g. `Health`'s `service_state`,
  `registered_sensor_count`, set via ordinary `AddStructuralFeatureValueAction`
  visible in the trace) **do** read back correctly through this exact same
  pipeline — proving the extraction mechanism itself is sound.
- Fields set via `Set_Object_Value`/`ALH.setValue()` **never** read back,
  tried every way reachable from outside the model: the post-copy
  `SignalInstance` (`eventTriggered`), the pre-copy `ObjectToken`'s value
  (`elementActivated`'s `values[]`, via `FumlValueBridge.getTokenValue()`),
  a flat dotted key, a split key, and `FumlValueBridge.getContext()`
  (`ALH.getContext()`, which returned `null` when called from this external
  listener).
- Working theory (unconfirmed): `ALH.setValue()`'s underlying write is
  scoped to the execution trace/call-stack of the Opaque Behavior it ran
  in. The `ALH` instance auto-injected into a running Opaque Behavior
  carries that trace; `new ALH(session)` constructed from outside (this
  listener has no way to reconstruct a real trace) doesn't — even though
  the 3-arg `setValue`/`getValue` overloads take the target object
  explicitly. This would also explain why the model's *own*
  `Get_Object_Value` (running inside the same execution) reads the value
  back fine — `Validate Sensor Report` clearly depends on that working.

**Proposed alternative, not yet implemented:** since the model's own Opaque
Behaviors can already read these values correctly, add one more Opaque
Behavior — right after the payload is built, before/at the
`SendSignalAction` — that reads each field the same way `Get_Object_Value`
does, builds the JSON itself in Groovy, and calls
`DDSTopicPublisher.publishOnce()` directly (it already takes plain
`String`s). This sidesteps the internal-API/context problem entirely by
reading from the one vantage point already proven to work, at the cost of
per-topic manual Groovy wiring instead of one global automatic listener.

**The `RETCODE_ERROR` on publish — root-caused and fixed, live-confirmed.**
The initial theory (a downstream symptom of publishing an all-`null`
payload) turned out to be wrong: a fully-populated synthetic test payload
failed identically. The real cause was invisible until NDDS's own internal
logging was routed into the GUI log (`DDSTopicPublisher.
ensureLoggingConfigured()`, a custom `LoggerDevice` at `WARNING`
verbosity — added specifically because `RETCODE_ERROR` itself carried no
message, confirmed via decompiling `RETCODE_ERROR.check_return_codeI()`):
the generated XML config failed to parse
(`RTIXMLObject_addChild: XML object with name
'::DomainLibrary::Domain::SensorReport' already exists`) because
`DdsXmlGenerator` gave `<register_type>` and `<topic>` the same `name`
whenever a payload Block shares its Signal's name — see
`DdsXmlGenerator.java`'s section above for the fix. Once fixed and the XML
regenerated, `Health` published successfully — both the real payload and a
synthetic fully-populated one.

**Participant resolution for `SensorReport` still fails, but for a
different, already-understood reason**: `findPublishingBlockNameFromScan()`
now correctly reports zero structural publishers for it (no Block's
Port/FlowProperty wiring declares an "out" direction for `SensorReport` —
confirmed by the generated XML itself, which has no `<data_writer>` for it
under any participant), so it falls back to `findOwningBlock()`'s
ownership-chain walk, which resolves a real Block that simply isn't a
participant in the XML. This is a modeling gap (no Port declares
`SensorReport` as published anywhere), not a code defect — see Known Gaps.

**Cached participants now torn down on simulation end** —
`executionTerminated()` (both the `EngineListener` and
`SimulationExecutionListener` overloads) calls `DDSTopicPublisher.
shutdown()`, reversing the original "let participants outlive a run" design
once live testing showed a lingering participant keeps generating DDS
discovery-layer log activity indefinitely — see Known Gaps.

### `FumlValueBridge.java`

The plugin's one deliberately-accepted internal-API-adjacent dependency,
isolated to this single file specifically so nothing else needs to import
`fUML.Semantics.*` or CST's internal token types.

**Why it's needed at all:** reading live field values out of a running CST
simulation requires touching NoMagic's fUML runtime value types. Every
distinct route checked is `@InternalApi(reason="No Magic internal API. This
code can change without any notification.") + @Deprecated` at the class
level, confirmed via `javap -v -p`:
`fUML.Semantics.CommonBehaviors.Communications.SignalInstance` (individually,
including its own `type` field), `fUML.Semantics.Classes.Kernel.
StructuredValue`/`CompoundValue`/`Value`/`FeatureValue`,
`com.nomagic.magicdraw.simulation.fuml.activities.intermediate.
ObjectToken`/`Token`, the generic `com.nomagic.magicdraw.simulation.values.
ValuesHelper` conversion helper, and the alternate "watch the source
Property instead of the outgoing signal" architecture
(`com.nomagic.magicdraw.simulation.StructuralFeatureListener`/
`StructuralFeatureListeners`). This isn't a gap to keep searching past — a
deliberate boundary in CST's public API surface, confirmed across every
structurally distinct angle tried.

**The one upgrade found:** `com.nomagic.magicdraw.simulation.utils.ALH` is
fully public — `@OpenApiAll` at the class level, no `@Deprecated` — NoMagic's
own scripting helper, the same thing auto-available inside a Groovy Opaque
Behavior (confirmed this is literally what the model's own
`Set_Object_Value`/`Get_Object_Value` behaviors call). Its methods still
take/return the internal fUML types in their signatures
(`ALH.getValue(StructuredValue, String)`), so this file still has to cast to
`StructuredValue`/`SignalInstance` — but the *method calls* themselves go
through a class NoMagic commits to keeping stable, rather than calling
`StructuredValue.get()` directly. The one remaining raw touch is
`SignalInstance.type` (a public but individually `@InternalApi`+
`@Deprecated` field) — `ALH` has no accessor for "what Signal is this
instance of."

**Methods:**
- `getSignal(Object)` — `SignalInstance.type`, defensively (returns `null`
  if not actually a `SignalInstance`).
- `getFieldValue(SimulationSession, Object, String)` — `new
  ALH(session).getValue((StructuredValue) obj, fieldName)`.
- `getContext(SimulationSession)` — `new ALH(session).getContext()`. Added
  as a diagnostic probe while investigating the `Set_Object_Value` blocker
  above (see that section); `ALH`'s own bytecode shows this falls back to
  `ContextHelper.findNearestObject()` when `ALH` was constructed without an
  explicit context, which is the case here.
- `getTokenValue(Object)` — unwraps an `elementActivated()` `Collection<?>`
  entry (`ObjectToken`/`ControlToken`) to the fUML `Value` it carries, or
  `null` for a token with no value. Added as a diagnostic probe to test
  whether the argument value *as it existed when the action activated* (
  potentially pre-dating any copy-into-`SignalInstance` UML signal-send
  semantics might do) already carried the `Set_Object_Value`-written data —
  it didn't (see the blocker writeup above).

Every method here can throw; callers must treat failure as "could not
extract this field/signal," never let it take down the simulation or the
publish loop.

---

## End-to-end flows

### Design-time: "Generate DDS XML"

```
User: Tools → RTI Connext DDS → Generate DDS XML
  GenerateDdsXmlAction.actionPerformed()
    → ModelTopicScanner.findDomainPackages(project.getPrimaryModel())
    → [dropdown if >1 «DDS_Domain» package]
    → ModelTopicScanner.getDomainId(chosen)
    → ModelTopicScanner.findQosProfiles(primaryModel)
    → [silent, no QoS, if none found; picker w/ "None" option if ≥1 found]
    → ModelTopicScanner.scan(chosen)             (core: pure SysML reading, TWO PASSES)

        Pass 1 — domain-scoped (visit(chosenDomainPackage)):
            Signal + «DDS_Topic» found inside the domain package
                → registerTopic() → TopicModel.topics/types
                  (this determines WHICH topics belong to this domain)

        Pass 2 — project-wide, topic-filtered (visitBlockPorts(primaryModel)):
            every Class + «Block» found ANYWHERE in the project
                → handleBlockPorts() (structural Port → InterfaceBlock →
                  FlowProperty → direction → Signal)
                  → attaches publisher/subscriber ONLY if that Signal's
                    topic was already found in Pass 1 — a Block wired to
                    some OTHER domain's topic is ignored here
            (behavioral trace + Signal-registration branch deliberately
             NOT run in this pass — see ModelTopicScanner.java section)

    → DdsXmlGenerator.generate(model, domainId, qosProfile)  (dds: pure string generation)
    → JFileChooser Save As → write file
```

The user then manually places the generated file where `DDSTopicPublisher`
or an external RTI tool (Routing Service, `nddsjava` app, Shapes Demo, etc.)
can find it — this hand-off is not automated (see Known gaps).

### Design-time: "Import QoS Profile" (the plugin's first model WRITE)

```
User: Tools → RTI Connext DDS → Import QoS Profile
  ImportQosProfileAction.actionPerformed()
    → ModelTopicScanner.findQosLibraryPackages(project.getPrimaryModel())
    → dialog: Use Existing (ElementPickerUtil.pickOne, native CAMEO picker)
              vs Create New (name input)
        Create New → SessionManager.createSession() → ElementsFactory
                      .createPackageInstance() → ModelElementsManager
                      .addElement() → StereotypesHelper.addStereotype()
                      → SessionManager.closeSession()  [or cancelSession()
                        on failure — see ImportQosProfileAction.java section]
    → JFileChooser Open (XML) → parseQosProfile() (javax.xml.parsers DOM)
    → SessionManager.createSession() → ElementsFactory.createClassInstance()
      → ModelElementsManager.addElement() → StereotypesHelper.addStereotype()
      → StereotypesHelper.setStereotypePropertyValue() × up to 3 tags
      → SessionManager.closeSession()  [or cancelSession() on failure]
```

The resulting `«DDS_QosProfile»` element is what `GenerateDdsXmlAction`'s
QoS picker (above) later finds via `findQosProfiles()`.

### Runtime: CST simulation → DDS publish (pipeline built; blocked — see Known gaps)

```
Simulation start (once):
  Groovy Opaque Behavior runs scripts/RegisterDdsEngineListener.groovy
    → listener = new dds.DdsEngineListener(_session_)
    → _session_.getEngine().addEngineListener(listener)
    → _session_.getExecution().addSimulationListener(listener)

Each time a SendSignalAction on a «DDS_Topic» Signal fires:
  DdsEngineListener.elementActivated(element, values)      [SimulationExecutionListener path]
    → resolve participantConfigName / dataWriterName (findOwningBlock)
    → pendingPublishes.addLast(new PendingPublish(signal, participantConfigName, dataWriterName))

Shortly after, for the matching signal instance:
  DdsEngineListener.eventTriggered(signalInstance)          [SimulationExecutionListener path]
    → signal = FumlValueBridge.getSignal(signalInstance)
    → pending = takePending(signal)                          (matched by Signal identity)
    → payload = FumlValueBridge.getFieldValue(session, signalInstance, "data")
    → for each TopicModel.Field in the scanned StructType:
         appendField() → FumlValueBridge.getFieldValue(session, payload, field.name)
                        → JsonPayloadBuilder (recurses for nested structs)
    → DDSTopicPublisher.publishOnce(pending.participantConfigName,
                                     pending.dataWriterName, json)
```

**Currently blocked:** every field extracted via `Set_Object_Value`-populated
data comes back empty (see `DdsEngineListener.java` above for the full
investigation) — fields set via ordinary `AddStructuralFeatureValueAction`
extract correctly through this exact pipeline, so the block is specific to
that one model convention, not the pipeline itself.

Plugin shutdown: `RTIConnextPlugin.close()` → `DDSTopicPublisher.shutdown()`
deletes every cached `DomainParticipant`.

---

## Inbound (subscribe) pipeline — SensorReport, confirmed working end-to-end

**Port name note:** everything below was investigated and written while the
model had a single shared `"dds-bus"` port on `DDS_Simulation_Environment`.
That port has since been split into two (`dds-bus-listener` for inbound,
`dds-bus-sender` for outbound) — see the "Hardcoded names" section further
down for the full story and the current, real port name used at runtime.
Every `"dds-bus"` reference in this section should be read as
`"dds-bus-listener"` today; left as originally written rather than
retroactively edited, since the mechanics/findings described are otherwise
unchanged.

The mirror image of the outbound pipeline above: external DDS data →
running simulation, instead of running simulation → DDS. The real target
topic is `SensorReport` → SAGE, injected via the `dds-bus` port on
`DDS_Simulation_Environment`. **Health has no inbound wiring at all** —
Health is outbound-only (something the model itself sends, never
something an external system sends back in). An earlier pass briefly
proved the injection mechanism out end-to-end using Health as the test
topic (including a `HealthReader`/`ReceiveHealth` Operation on SAGE, and
an `ALH.callOperation`-based design) — that was scaffolding for the proof,
not a real requirement, and has since been fully removed from both the
scripts and the model. Everything below describes the current,
SensorReport-based design.

**Model structure, confirmed from the actual StateMachine diagrams (not
assumed):** `DDS_Simulation_Environment` is a real Block that owns SAGE as
a Part. Its own StateMachine is a straight-line sequence —
`CONFIGURATION → INITIALIZE → STIMULATE → MONITOR` — with `CONFIGURATION`'s
entry action being `dds-sim-runner (register and poll)`, i.e. the Activity
that runs `RegisterDdsEngineListener.groovy`. Critically, there is **no
transition back to `CONFIGURATION`** — it's a one-time setup state (see
"Bugs found via live testing" below for why this matters).
`PollDdsSubscriptionAndInject.groovy` runs instead as the effect of a
self-transition on a dedicated state, `CONFIGURE DDS INCOMING`, on
`DDS_Simulation_Environment` — confirmed live to repeat on a real cadence
for the whole simulation run. `SensorReportReader` lives under `Sensor
Alignment and Gating Service_Definition` (confirmed against the generated
DDS XML) — SAGE's `_Implementation` Block is not involved in the inbound
path at all.

**Why this shape, not the obvious one.** The obvious approach — call some
sanctioned method that fires a UML signal event at a target object — was
checked exhaustively first (full `javap -v -p` decompile of
`ExecutionEngine`, `SimulationExecution`, `SimulationSession`, and
`ALH`, the same discipline as every other API surface in this project):

- `ExecutionEngine.triggerEvent(String)` — `@OpenApi`, clean — but its own
  bytecode shows it only loops over the engine's registered `EngineListener`s
  and calls `.eventTriggered(event)` on each; it never touches the fUML
  execution/activation machinery, so it can't actually deliver anything into
  a running simulation.
- `ExecutionEngine.activateElement(Element, ActivationInfo)`/
  `deactivateElement(Element, ActivationInfo)` — the overloads that actually
  reach into the session's internal execution context (bytecode confirmed:
  `session → (session/b) → .y() → execution/x`) are both
  `@InternalApi`+`@Deprecated`, and their distinguishing parameter type,
  `ActivationInfo`, is *itself* class-level `@InternalApi`+`@Deprecated` —
  the public `Collection<?>`-taking overloads just construct an
  `ActivationInfo` internally and delegate to the internal ones.
- `SimulationExecution`/`SimulationSession`'s full method lists (both
  `@OpenApiAll`) — nothing beyond listener registration and
  element/engine/execution/session-hierarchy accessors; no
  fire/inject/trigger-shaped method anywhere.
- `com.nomagic.magicdraw.simulation.utils.ALH` (`@OpenApiAll`, no method-
  level override on *any* of its 61 members — confirmed by grepping a full
  `javap -v -p` dump for `InternalApi`/`Deprecated`) — **does** have exactly
  what's needed: `sendSignal` (6 overloads, see below), `createSignal(String
  signalName)` (resolves a `Signal` by name and wraps it in a fresh
  `SignalInstance` via `EventHelper.createSignalInstance()`),
  `createObject(String className)`, and `setValue(Object, String, Object)`.
  None of these take an internal-marked parameter type in their public
  signature (contrast `FumlValueBridge.java`'s methods, which all must).

So the inbound path is entirely `ALH`-based, run from *inside* a live
Opaque Behavior — the same vantage point that already proves out for
`Get_Object_Value`/`Set_Object_Value` on the outbound side (see the
"Current blocker" section above). This is not a coincidence: `ALH.
createObject(String)`'s own bytecode calls `getContext()` first and returns
`null` immediately if it's null — the same `getContext()` that returns null
when `ALH` is constructed externally (`new ALH(session)`, as
`FumlValueBridge`/`DdsEngineListener` do) but resolves correctly when `ALH`
is the instance CST auto-injects into a running Opaque Behavior.

**`ALH.sendSignal` — six overloads, target-resolution semantics differ, and
this genuinely matters.** The 2-arg overloads
(`sendSignal(String, Object_)`/`sendSignal(SignalInstance, String)`, etc.)
resolve their target implicitly: decompiling the internal delegation chain
(`com.nomagic.magicdraw.simulation.fuml.o.a(SignalInstance, String,
session)`, itself `@NotApi`+`@Deprecated`, confirming it's pure internal
implementation only reachable through `ALH`'s public wrapper) shows it
resolves the target `Object_` via `session.v() -> Context`, then
`ContextHelper.findNearestObject(context) -> Object_` — **the exact same
call chain `ALH.getContext()` itself uses** — and only then looks up the
named Port *on that specific object*. That means a 2-arg
`sendSignal(signal, "somePort")` targets "whichever object is currently
running this code," which would have forced the poll script to run as
part of the receiving Block's own StateMachine execution specifically.

The overload actually used here sidesteps that constraint entirely:
**`sendSignal(SignalInstance, Object_ target, String portName)`** takes the
target explicitly (confirmed against NoMagic's own CST docs, "Sending a
signal instance to a specific target object" —
`ALH.sendSignal("play", "Player", "out2")`, where `"Player"` is an object
and `"out2"` a port name). With this overload it doesn't matter where the
poll script runs, as long as the target object passed in is correct. Here,
`target = ALH.getContext()` directly — confirmed the `dds-bus` port lives
on `DDS_Simulation_Environment` itself, the same object whose execution the
poll script already runs as part of (the `CONFIGURE DDS INCOMING` state's
self-transition), so `getContext()` already resolves to the right object
with no extra role-name lookup needed.

**`setValue()` vs `addValue()`:** the choice is per-FIELD, based on that
field's own UML multiplicity, not on whether the owning object was just
created. A REQUIRED field (`1..1`) is auto-initialized with a default
value the moment the object is created, so there's already something there
to replace — `setValue()` is correct. `addValue()` is only needed for a
genuinely OPTIONAL (`0..1`) field, which does NOT get auto-initialized and
has nothing to replace yet. Every `SensorReport_data` field is confirmed
`1..1` (required) via the live `[DDS Scan]` log, so `setValue()` is used
throughout, including for the signal's own `data` attribute.

**Pipeline, thread hand-off first:**

1. **`DdsSubscriptionQueue.java`** — a plain, dependency-free,
   `ConcurrentHashMap<String, ConcurrentLinkedQueue<PendingMessage>>`
   (one queue per topic name). `push(topicName, jsonPayload)` is called from
   RTI's own native callback thread; `pollNext(topicName)` is called from
   inside the simulation's own execution. This is the thread boundary — no
   fUML/CST/DDS types anywhere in this file, same discipline as
   `DDSTopicPublisher.java`.
2. **`DDSTopicSubscriber.java`** — mirrors `DDSTopicPublisher.java`'s shape.
   `subscribeOnce(participantConfigName, dataReaderName, topicName)` looks up
   the named `DynamicDataReader` (`DomainParticipant.
   lookup_datareader_by_name()`) and registers a `DataReaderAdapter`
   subclass overriding only `on_data_available()` (verified against the real
   `nddsjava.jar`: `DataReaderAdapter` is a no-op-everything abstract base
   implementing `DataReaderListener`, registered via `DataReaderImpl.
   set_listener(listener, StatusKind.DATA_AVAILABLE_STATUS)`). On each
   sample: `take_next_sample(DynamicData, SampleInfo)`, skip if
   `!info.valid_data` (a dispose/unregister instance-state-change, not real
   data), else `sample.to_string(new PrintFormatProperty(PrintFormatKind.
   JSON_PRINT_FORMAT))` — the read-side mirror of the outbound path's
   `sample.from_string(json, PrintFormatKind.JSON_PRINT_FORMAT)` — then
   `DdsSubscriptionQueue.push(topicName, json)`.

   **Participant cache is shared with `DDSTopicPublisher`**, not
   duplicated: `DDSTopicPublisher.getOrCreateParticipant()` was extracted
   from the top of `publishOnce()` into its own package-private method so
   `DDSTopicSubscriber.subscribeOnce()` can call it directly — a single
   `DomainParticipant` can hold both a `<publisher>` and a `<subscriber>`
   (SAGE's own generated XML entry already has both, since it publishes
   `SensorReport`-adjacent topics and now also subscribes to `Health`), so
   reusing the cache avoids creating a second participant for one that
   already exists.
3. **`scripts/PollDdsSubscriptionAndInject.groovy`** — pasted as the effect
   of a self-transition on `DDS_Simulation_Environment`'s `CONFIGURE DDS
   INCOMING` state, driven by a repeating relative Time Event. Each firing:
   1. `DDSTopicSubscriber.subscribeOnce(...)` — self-healing re-subscribe,
      see "Bugs found via live testing" below; cheap no-op once already
      subscribed.
   2. `DdsSubscriptionQueue.pollNext("SensorReport")` — returns immediately
      if nothing's queued (the common case; kept cheap since this runs on a
      timer). If something's queued, the script **drains the entire
      per-topic queue in a loop** (not just one message) before returning —
      see "Bugs found via live testing" below for why.
   3. Resolves the send target **once per drain cycle**: `target =
      ALH.getContext()` — no role-name lookup needed, since the `dds-bus`
      port lives directly on `DDS_Simulation_Environment`, the object this
      script already runs as part of.
   4. Per dequeued message: `JsonPayloadBuilder.parseFlat(json)` — added to
      the existing (already flat-JSON-only) `JsonPayloadBuilder` rather than
      writing a second parser; returns a `Map<String,Object>`
      (String/Long/Double/Boolean/null), the read-side counterpart to
      `build()`. Same flat-only limitation as `fromExisting()` — fine for
      `SensorReport_data` (scalar/enum fields only), would need extending
      for a nested-struct topic.
   5. `ALH.createObject("SensorReport_data")`, then
      `ALH.setValue(payloadObject, key, value)` per field.
   6. `ALH.createSignal("SensorReport")`, then
      `ALH.setValue(signalInstance, "data", payloadObject)` — `"data"`
      matches `ModelTopicScanner.TOPIC_DATA_ATTRIBUTE_NAME`, the same
      single attribute name the outbound side reads the payload back off
      of.
   7. `ALH.sendSignal(signalInstance, target, "dds-bus")` — delivers the
      signal directly, no wrapper Operation needed.

   Per-message steps run inside a Groovy closure (`injectOne`) so a `return`
   on failure only abandons that one message, not the rest of the drain
   loop — one bad/unparseable sample doesn't block the ones behind it.
   Every step is individually try/caught and logged to the GUI log
   (`[DDS Inject]` prefix), same diagnostic-first discipline as
   `DdsEngineListener`/`RegisterDdsEngineListener.groovy`.
4. **Bootstrap wiring** — folded into `RegisterDdsEngineListener.groovy`
   (not a separate script) rather than a separate one-time setup script,
   since it's the same "run exactly once at simulation start" shape as the
   `DdsEngineListener` registration already there:
   `DDSTopicSubscriber.subscribeOnce("DomainParticipantLibrary::Sensor
   Alignment and Gating Service_Definition", "Subscriber::SensorReportReader",
   "SensorReport")`. `dataReaderName`'s `"Subscriber::<Topic>Reader"` shape
   comes straight from `DdsXmlGenerator`'s own generation convention (see
   its `<data_reader name="...Reader">` output) — not invented separately.
   This call is no longer load-bearing on its own (see below) but is kept
   since it's harmless and establishes the subscription slightly earlier
   when it happens to survive.
5. **Shutdown** — `DDSTopicSubscriber.shutdown()` (clears the reader-name
   cache only; the readers themselves get deleted when
   `DDSTopicPublisher.shutdown()` calls `delete_contained_entities()` on
   their shared participant) is now called everywhere `DDSTopicPublisher.
   shutdown()` already was: `RTIConnextPlugin.close()` and both
   `DdsEngineListener.executionTerminated()` overloads.

### Bugs found via live testing (both fixed)

**1. Wrong participant name.** During the earlier Health-based proof pass,
the bootstrap script initially hardcoded the wrong participant
(`..._Definition` when the reader had actually been generated under
`..._Implementation`), so `subscribeOnce()` silently failed to find the
reader at startup. General lesson kept here since it applies to any
topic/reader pairing: always cross-check the hardcoded
`participantConfigName`/`dataReaderName` pair against the actual generated
`DDS_Schema.xml` rather than assuming which Block a reader landed under —
`subscribeOnce()` fails silently (a caught exception, logged, not a hard
crash) if the pairing is wrong. `SensorReportReader`'s actual location
(`Sensor Alignment and Gating Service_Definition`) is confirmed correct
against the generated XML.

**2. Subscription torn down before the real simulation starts.** Live
testing (twice, reproducibly) showed: `DDS_Simulation_Environment`'s
`CONFIGURATION` state's entry action (`register_dds_engine_listener` +
the poll script's first call, back-to-back) runs as its own short-lived
execution that finishes and fires `executionTerminated()` — which tears
down `DDSTopicSubscriber` — **before** the real simulation (visible in the
log as `dds-sim-runner`, going through `INITIALIZE → READY → STIMULATE →
ALIGNING → MONITOR`) actually starts. `Health` publishing kept working
throughout because `DDSTopicPublisher.publishOnce()` lazily recreates its
participant on every call; `DDSTopicSubscriber.subscribeOnce()` had no
equivalent per-call laziness — it registers a listener once and stays
registered until torn down, so the real simulation ran with **zero live
Health subscription the entire time**, even after fix #1 above. Root cause
not fully understood (why CAMEO treats this bootstrap Activity's execution
as separate from the main one) — rather than chase that, the fix is
self-healing: the poll script now calls `subscribeOnce()` at the top of
**every** poll cycle, not just once at bootstrap. It's a cheap no-op once
already subscribed (`DDSTopicSubscriber.subscribeOnce()`'s "already
subscribed" fast path was made silent — it previously logged on every
no-op call, which would have flooded the GUI log once called every poll
cycle). This self-heals regardless of when the premature teardown happens
— but only once the poll script is actually placed somewhere that fires
repeatedly during the real run (see the open placement question below;
this fix alone doesn't help if the script only ever fires once).

### Reliability: could messages be lost between DDS and the simulation?

Asked directly by the person testing this ("if I run this script every
5s, do I lose messages?") — traced through and found two real gaps, both
fixed:

- **RTI callback → `DdsSubscriptionQueue`:** `on_data_available()`
  originally called `take_next_sample()` **once** per callback. But
  `on_data_available` is an edge-triggered "go check the reader"
  notification, not "here is exactly one sample" — if multiple samples
  arrive before the callback runs, RTI can coalesce them into a single
  invocation, and anything past the first `take_next_sample()` call could
  be left unread. Fixed: loop `take_next_sample()` until it throws
  `RETCODE_NO_DATA` (confirmed via `javap` that `RETCODE_NO_DATA extends
  RETCODE_ERROR` and is how RTI signals "reader empty") — the standard RTI
  drain pattern, so every callback now fully empties the reader regardless
  of burst size.
- **`DdsSubscriptionQueue` → simulation:** the poll script originally
  pulled one message per cycle. If a topic ever arrived faster than once
  per poll interval, the backlog would never shrink and delivered data
  would lag further and further behind real time (not lost, but
  increasingly stale). Fixed: the poll script now drains the entire
  per-topic queue every cycle (loop until `pollNext()` returns `null`), so
  a backlog only ever costs extra loop iterations that cycle, not growing
  delay — confirmed live draining 5+ queued `SensorReport` samples in a
  single cycle.

With both fixes, the poll interval only controls latency (how stale a
`SensorReport` update can be before injection), not whether it arrives.

### Design decision: explicit-target `ALH.sendSignal`, not `ALH.callOperation`

An earlier pass (Health-based proof) used `ALH.callOperation(sageObject,
"ReceiveHealth", [payloadObject])` instead — a wrapper Operation on SAGE's
Block whose Activity body assigned the payload to a Part property, chosen
because the 2-arg `sendSignal` overloads resolve their target via
`ContextHelper.findNearestObject()` ("whichever object is currently
running this code"), which would have forced the poll script to run as
part of the receiving Block's own StateMachine execution — awkward, since
`DDS_Simulation_Environment`'s poll cycle and SAGE's operational lifecycle
are different running objects.

That constraint turned out to be avoidable: `ALH.sendSignal(SignalInstance,
Object_ target, String portName)` — a 3-arg overload — takes the target
explicitly (confirmed against NoMagic's own CST docs), so the poll script
can run anywhere and just pass in the correct target object. This is
strictly simpler than the `callOperation` design: no wrapper Operation
needed on SAGE at all, and the signal is delivered exactly the way a real
internal `SendSignalAction` would deliver it — confirmed live, the
injected signal triggers SAGE's own `ValidateSensorReport`/`Object_
Contains`/`Gate Sensor Report` logic identically to a signal from a real
internal source. The `callOperation`/`ReceiveHealth` design has been fully
removed.

### Hardcoded names — four of five eliminated; the port name is back to hardcoded (`DdsInboundInjector.java`)

The five names once hardcoded in `PollDdsSubscriptionAndInject.groovy`
(`TOPIC_NAME`, `PARTICIPANT_CONFIG_NAME`, `DATA_READER_NAME`,
`PAYLOAD_BLOCK_NAME`, `PORT_NAME`) were all eliminated by
`DdsInboundInjector.java` (new class), which drives everything from
`ModelTopicScanner.scan()` instead: any Signal with the «DDS_Topic»
stereotype, wired inbound (Port → «InterfaceBlock» → «FlowProperty»
direction=in) on any Block, is discovered, subscribed to, and injected
automatically — no per-topic configuration anywhere in either Groovy
script.

`participantConfigName`/`dataReaderName`/`payloadBlockName` are still fully
eliminated this way (same convention `DdsEngineListener` uses outbound:
`"DomainParticipantLibrary::" + blockName`, `"Subscriber::" + topicName +
"Reader"`, `topic.typeName`) — no new scanning needed, just wiring it up.

**`PORT_NAME` is a different story — TODO, revisit.** It briefly *was*
eliminated the same way: `ModelTopicScanner.handleBlockPorts()` already
walks every Block's real `Port` objects to decide in/out direction, so it
was extended to keep the Port's own name too
(`TopicModel.Topic.subscriberPorts`, a `Map<BlockName, PortName>`), and
`pollAndInjectAll()` tried every recorded candidate Port against the
current target, letting `ALH.sendSignal()`'s own "not found on this
object" exception silently rule out the wrong ones.

**Live testing showed this picked a real but non-functional port** — the
structural scan surfaces `port_in` (a name that lives on SAGE's own Block,
not on `DDS_Simulation_Environment`, but *did* exist somewhere reachable
from the target and so passed the "does sendSignal throw" test) instead of
`dds-bus`, the one port on `DDS_Simulation_Environment` that's actually
wired through a Connector into SAGE. Delivery "succeeded" (no exception)
but produced no downstream effect — the signal landed on a port with
nothing connected to it. Reverted to a single hardcoded, known-convention
name at the model owner's explicit direction, now `"dds-bus-listener"`
after `DDS_Simulation_Environment` was further split into separate
listener/sender ports (`dds-bus-listener` for inbound, matching a
service's own `port_in`; `dds-bus-sender` for outbound, matching
`port_out`).

**Not yet fixed — this port name should not be hardcoded.** The structural
scan's candidate-list approach was the right idea but the wrong selection
criterion ("does the target merely have a port by this name" isn't the
same as "is this port actually wired to something"). A real fix would need
the scan (or a live check) to also confirm Connector-level connectivity
from the candidate port through to wherever the topic's true subscribing
Block/Port lives, not just port-name existence on the immediate target —
unstarted design work, not a mechanical extension of the existing scan.

**What genuinely can't be eliminated, confirmed via a full `javap -p` dump
of `ALH`'s public API:** there is no sanctioned way to look up "the live
running instance of Block X" from outside that instance's own execution —
`ALH.getContext()` only ever resolves to "whichever object is currently
running this code." So a poll script still has to be physically pasted
somewhere with a repeating trigger (today: `DDS_Simulation_Environment`'s
`CONFIGURE DDS INCOMING` state) — that placement fact is inherent, not a
hardcoded string in the code. `ALH` itself is passed as a parameter into
`DdsInboundInjector`'s methods (the caller's own CST-auto-injected
instance) rather than constructed internally, for the same reason —an
externally-constructed `ALH`'s `getContext()` returns null (same finding
as `FumlValueBridge.java`).

One known, accepted limitation: `DDSTopicSubscriber`'s reader cache is
keyed by bare `dataReaderName` (`"Subscriber::<Topic>Reader"`), not
qualified by participant — if two *different* Blocks ever both subscribed
to the same topic, their reader names would collide in that cache. Not
fixed, since the current model has exactly one subscriber per topic and
this pre-dates the generalization work.

### Status — confirmed working end-to-end

**Confirmed live, full pipeline (hardcoded-names version):** an external
standalone publisher (`test_tools/sensor_report_publisher/`) publishes
real `SensorReport` samples over DDS → `DDSTopicSubscriber` receives and
queues them (`[RTI Connext] Received sample for topic 'SensorReport' ->
queued: ...`) → the poll script (at the time, still hardcoded per-topic)
drains the queue, builds a `SensorReport_data` object and a `SensorReport`
signal via `ALH.createObject`/`setValue`/`createSignal`, and delivers it
via `ALH.sendSignal(signalInstance, ALH.getContext(), "dds-bus")`
(`[DDS Inject] SUCCESS: sent 'SensorReport' via port 'dds-bus'`) → the
signal reaches SAGE and triggers its own real internal validation logic
(`ValidateSensorReport` → `Object_Contains` → `Gate Sensor Report`),
observable in the GUI log exactly as it would be for a signal from a real
internal source. This also confirmed the enum fields (`measurement_frame`,
`sensor_modality`) round-trip correctly through `ALH.setValue()` given a
bare String off the JSON queue.

**Since generalized, not yet independently live-tested:** the mechanism
above was refactored into the fully generic, scanner-driven
`DdsInboundInjector` described above (same `createObject`/`setValue`/
`createSignal`/`sendSignal` calls, same target resolution — just no
hardcoded topic/participant/reader/payload/port names anymore). The
underlying mechanism is the same one already confirmed live; the
generalization itself (multi-candidate-Port try loop, `subscribeAll()`
called from a driven scan instead of one hardcoded call) has not yet been
exercised against a real run.

**Data-content note, not a pipeline defect:** `Object_Contains` correctly
rejects any `sensor_id` not present in the model's own Registration Map
(`SENSOR_DATA[*].sensor_id`, registered test sensors `CAM-01`/`CAM-02`) —
this is the model's business logic working as intended, not a bug. The
external test publisher's `sensor_id` is set to `"CAM-01"` so a full,
un-gated pass through validation can be demonstrated.

---

## Required SysML/DDS profile shape

For the scanner to find anything, the open project must have applied:

- **`DDS_Profile`** (a custom profile, not shipped with CAMEO — must exist in
  the user's own project/model library) with:
  - `«DDS_Topic»` stereotype extending **Signal**
  - `«DDS_Domain»` stereotype extending **Package**, with an Integer tagged
    value named `domain_id`
  - `«DDS_Member»` stereotype extending **Property**, covering more than
    one RTI XSD `<member>` attribute (a generalization of an earlier,
    narrower `«DDS_Key»` stereotype). Only a Property that needs to declare
    something special gets it applied — a plain field with no special
    semantics stays unstereotyped:
    - Boolean tagged value `key` — marks a struct's value property as a
      DDS key field (emitted as `key="true"` on its `<member>`); the
      stereotype alone isn't enough, `key` must also read as `true`
    - Integer tagged value `max`, optional — a bounded string length
      (emitted as `stringMaxLength="..."` on its `<member>`); only
      meaningful on a string-typed field, silently ignored otherwise; a
      Property may set `key` alone, `max` alone, or both
  - `«DDS_QosLibrary»` stereotype extending **Package** — a container for
    `«DDS_QosProfile»` elements. **Added as a manual prerequisite, not by
    any code in this plugin** — someone edits the `DDS_Profile` diagram in
    CAMEO directly to add it, alongside the stereotypes above.
  - `«DDS_QosProfile»` stereotype (its own metaclass extension isn't
    asserted by this codebase — `ImportQosProfileAction` creates a `Class`
    and applies this stereotype to it, so it's expected to extend Class),
    with three tagged values, all optional/independent:
    - `participant_name` (String)
    - `history_depth` (Integer)
    - `is_default` (Boolean) — marks this as the profile Connext should
      auto-apply (`is_default_qos="true"` in the generated XML)
- **SysML** (built-in, ships with CAMEO as `profiles/SysML Profile.mdzip`),
  specifically:
  - `«InterfaceBlock»` stereotype (on the Class typing a Block's Port)
  - `«FlowProperty»` stereotype (on a Property owned by that InterfaceBlock),
    with its built-in `direction` tag (`FlowDirectionKind`: `in`/`out`/`inout`)
- Plain SysML **`«Block»`** (Class stereotyped Block — checked by bare name,
  intentionally not profile-scoped)
- **No stereotype needed** for the two type-mapping capabilities below —
  both are detected from plain UML/SysML shapes already native to the
  metamodel, not from any custom tag:
  - A **fixed-size array** field: a value Property whose multiplicity is set
    to an exact bound greater than 1 (e.g. `[2]`, or explicit `Lower=2
    Upper=2`) — NOT `0..*`/`1..*` (variable-length sequences aren't
    supported, see Known gaps below).
  - An **enum-typed** field: a value Property typed by a plain UML
    `Enumeration` (e.g. `SensorModalityEnum`) — any UML Enumeration works,
    no stereotype required; its literals (in declaration order) become the
    generated `<enum>`'s `<enumerator>`s.

A topic Signal's payload Block must have exactly one attribute named `data`,
typed by the payload Block.

**Not supported (fixed-size arrays / enums), so this isn't mistaken for
full type-system coverage:**
- **Variable-length sequences** (`0..*`, `1..*` multiplicity, or any
  `upper != lower` shape) — `ModelTopicScanner.readArrayDimension()`
  deliberately returns `null` (no array) for these; a value property with
  this multiplicity today serializes as an ordinary scalar `<member>`, not
  a `<sequence>`. This is also the still-open, pre-existing
  `anomalous_sensors` (`java.util.ArrayList`) gap in the *outbound* runtime
  publish pipeline (`DdsEngineListener.appendField()`) — unrelated code
  path, same underlying missing capability.
- **Multi-dimensional arrays** — RTI's own schema supports comma-separated
  dimensions (`arrayDimensions="5,6"`, confirmed in `arrays.xml`), but
  `ModelTopicScanner`/`TopicModel.Field.arrayDimension` only ever carries a
  single `Integer`, so only a single-dimension fixed array (`Real[2]`, not
  a `Real[2][3]`-shaped model construct) is detected.
  - Enum-valued arrays and array-typed nested-struct fields are architecturally
    possible (the `arrayDimensions` attribute is confirmed to coexist with
    either `type=` form in `DdsXmlGenerator`) but not exercised by
    `GatedReport` and not specifically tested.
- **Nested enums / enums as struct members' own further-nested types** —
  not a real UML concept (an `Enumeration`'s literals are always flat), so
  not applicable; noted here only because it was explicitly asked about as
  a boundary to document.
- **Runtime (outbound publish) serialization — fixed, 2026-09-10.**
  `DdsEngineListener.appendField()` originally had no case for an
  array-valued field (`java.util.ArrayList`, e.g. `GatedReport.Position`)
  and picked JSON serialization off the extracted value's raw Java runtime
  type rather than the field's DDS-declared type — both confirmed live to
  independently break a real `GatedReport` publish:
  `RETCODE_BAD_PARAMETER` from `DDS_DynamicDataParser_parse_json_node`,
  once for `Position` (an `ArrayList` fell into the "unexpected value type"
  branch and got sent as a single JSON *string* holding its `toString()`,
  e.g. `"Position":"[7.07, 7.07]"` instead of a real array), and once for
  `system_time` (declared `type="string"` but extracted as a
  `java.lang.Double`, serialized as a bare JSON number instead of a quoted
  string). `appendField()` now checks `field.isArray()` first (builds a
  real JSON array via a new `arrayToJson()`/`arrayElementToJson()` pair),
  and checks `field.primitiveType` before the runtime type for scalars (so
  a schema-declared `string` field is always quoted, regardless of what
  Java class the value came back as). Confirmed live: `GatedReport`
  (`Position`/`Velocity` arrays, `system_time` string) now publishes
  successfully end-to-end, feeding a real, working multi-service demo
  pipeline (sensor emulator → SAGE → Association → Display) — see this
  file's "Beyond the plugin" section near the end.

---

## Build, package, and deploy

- **`build.bat`** — requires `NDDSHOME` and `CAMEO_HOME` env vars (optionally
  `RTIJDKHOME` to pick a specific `javac`; CAMEO 2024x ships its own JDK 17 at
  `<CAMEO_HOME>\jre`). Copies `nddsjava.jar` into `build\`, compiles the
  explicit source list below against `%CAMEO_HOME%\lib\*` +
  `nddsjava.jar` + the Cameo Simulation Toolkit plugin's own `lib\`
  (needed for `DdsEngineListener`'s `com.nomagic.magicdraw.simulation.*`
  import), and packages `build\RTIConnextPlugin.jar`. The compile list is
  **explicit, not a wildcard** — any new source file must be added to it by
  hand:

  ```
  src\com\rti\connext\cameo\RTIConnextPlugin.java
  src\com\rti\connext\cameo\RTIConnextActionsConfigurator.java
  src\com\rti\connext\cameo\core\TopicModel.java
  src\com\rti\connext\cameo\core\ModelTopicScanner.java
  src\com\rti\connext\cameo\core\ScanModelForTopicsAction.java
  src\com\rti\connext\cameo\core\JsonPayloadBuilder.java
  src\com\rti\connext\cameo\dds\DDSTopicPublisher.java
  src\com\rti\connext\cameo\dds\DDSTopicSubscriber.java
  src\com\rti\connext\cameo\dds\DdsSubscriptionQueue.java
  src\com\rti\connext\cameo\dds\DdsXmlGenerator.java
  src\com\rti\connext\cameo\dds\ElementPickerUtil.java
  src\com\rti\connext\cameo\dds\GenerateDdsXmlAction.java
  src\com\rti\connext\cameo\dds\ImportQosProfileAction.java
  src\com\rti\connext\cameo\dds\FumlValueBridge.java
  src\com\rti\connext\cameo\dds\DdsEngineListener.java
  ```

- **`install.bat`** — requires `CAMEO_HOME` and a prior successful build;
  requires Administrator elevation (writes under `Program Files`). Copies
  `plugin.xml`, `build\RTIConnextPlugin.jar`, `build\nddsjava.jar`, and
  `resources\*` into `%CAMEO_HOME%\plugins\com.rti.connext.cameo\`.
- **`uninstall.bat`** — removes that plugin directory entirely; also needs
  elevation; safe to re-run.
- **`setup_rti_config.bat`** — a convenience double-click launcher: sets
  `NDDSHOME`/`CAMEO_HOME`/`RTIJDKHOME`/`RTI_LICENSE_FILE` and `PATH`, self-
  elevates via a `powershell -Verb RunAs` relaunch, prints sanity-check
  warnings (missing `javac.exe`, missing license file, missing native DLL
  directory), then drops into an interactive shell with the environment
  ready — this is a personal/local convenience script with hardcoded paths
  (`C:\Program Files\rti_connext_dds-7.5.0`, a specific
  `rti_workspace\7.5.0\rti_license.dat` path under this developer's user
  profile), not a portable part of the build.

A build/deploy caveat worth knowing: **`javac -d` never deletes stale
`.class` files for since-removed/moved source files.** `build.bat`'s own
step 5 handles this correctly by wiping `build\classes` before every
compile — but if you ever compile by hand (as this session did repeatedly
while iterating), you must delete `build\classes` yourself first, or a jar
can silently end up with both the old and new copies of a moved/renamed
class.

---

## Beyond the plugin — the full demo pipeline (as of 2026-09-14)

This document is scoped to `cameo_plugin/src`'s own code — everything above
is the plugin itself. But the plugin no longer stands alone: it's the first
stage of a full, confirmed-working, multi-service, multi-language demo
pipeline. **As of 2026-09-18, everything below lives OUTSIDE this
repository**, in a separate, untracked sibling folder
(`sage_demo_pipeline/` under this machine's Documents, alongside — not
inside — this git repo), specifically so this repo (pushed to RTI's
`rticommunity` remote) only contains the plugin itself, not this project's
own demo/test scaffolding. Each piece still has its own README covering its
own build/run/status detail, just not tracked here. Worth knowing this
exists before assuming the plugin is the whole story:

- **SAGE** ("Sensor Alignment and Gating Service") — the CAMEO model
  itself, driven by this plugin. Subscribes to `SensorReport`
  (`sage_demo_pipeline/test_tools/sensor_report_publisher/`, a standalone
  Java DDS publisher — two variants: `SensorReportPublisher.java`, one
  fixed sensor/position; `DualSensorTargetPublisher.java`, both registered
  sensors, `CAM-01`/`CAM-02`, independently observing one shared
  predetermined moving target from two different angles each cycle, using
  the model's real Registration Map values), gates/transforms/validates
  it, and publishes `GatedReport` back out over real DDS.
- **Association** (`sage_demo_pipeline/association/`) — a standalone C++ service (RTI Modern
  C++ API), deliberately **not** modeled in CAMEO — represents an
  already-implemented, already-deployed production system SAGE has to
  interoperate with on the real bus, not something this project owns the
  design of. Subscribes to `GatedReport`, does real nearest-neighbor track
  association (2.0m threshold, reusing SAGE's own spatial-gate bound), and
  publishes a new `Track` topic it defines and owns.
- **Display** (`sage_demo_pipeline/display/`) — a standalone Python tool (`rti.connextdds`,
  matplotlib), one live 4-panel figure: two independent naive per-sensor
  world-frame views (raw `SensorReport`, geometry-only transform, no
  gating — "what each sensor thinks"), SAGE's gated output
  (`GatedReport`, colored by source sensor), and Association's tracks
  (`Track`, colored by `track_id`). Each subscriber runs in its own
  thread pushing into a thread-safe queue the animation loop drains per
  frame. Confirmed working end-to-end, including seeing Association
  actually associate both sensors' reports of the shared target.
- **`sage_demo_pipeline/track_visualizer/`** — an earlier, simpler
  single-panel `Track`-only predecessor to `display/`'s Tracks panel; kept
  as-is, superseded in practice but not removed.
- **`sage_demo_pipeline/sage_tester/`** (added 2026-09-15) — a standalone
  Python tool that publishes known `SensorReport` inputs and asserts
  SAGE's (and, for one scenario, Association's) real output against
  expected results — automated requirements verification, since the
  model's own behavior was never written down as formal requirements
  before this. Running it regenerates `sage_tester/REQUIREMENTS.md`, a live traceability matrix
  derived directly from the scenarios actually being verified. Also the
  first place in this project either the write-side `rti.connextdds`
  Python API (`writer.create_data()`, dict-style field assignment) or its
  enum-setting convention (integer ordinal only, string names raise
  `ValueError` — confirmed live) got exercised; every other Python tool
  here was read-only.

**Two real, non-plugin-specific findings surfaced building this pipeline,
both relevant if this plugin's own generated DDS config is ever debugged
again:**
1. The QoS profile default/`base_name` fix documented in the Known Gaps
   table below (`DdsXmlGenerator.java`) was actually **found** via this
   pipeline (a hand-authored config exhibiting the identical bug), then
   traced back to and fixed in the plugin's own generator.
2. `GatedReport`'s `source_sensor_id` field (not `sensor_id`) and its lack
   of any `x_world`/`y_world` members (position is a `Position` float64[2]
   array, `Position[0]`/`Position[1]`) tripped up more than one downstream
   consumer expecting field names to match `SensorReport_data`'s or to be
   named like `Track`'s — worth remembering if another consumer of
   `GatedReport` gets built.

---

## Known gaps and TODOs

Collected from comments across the codebase and from live debugging in this
session — useful as a punch list for what's genuinely unfinished vs. what's
just undocumented:

| Area | Gap |
|---|---|
| **Runtime publish pipeline — overall status** | **Confirmed working end-to-end for `Health`, live.** Detect send → resolve participant/writer → extract fields → build JSON → `DDSTopicPublisher.publishOnce()` → actual DDS `write()` all succeeded, for both the real (partially-populated) payload and a fully-populated synthetic test payload. This is the first confirmed successful publish this project has produced. Two separate, still-open issues remain — see the next two rows — neither of which is a defect in this pipeline itself. |
| **Inbound (subscribe) pipeline — confirmed working end-to-end, live** | `DDSTopicSubscriber`/`DdsSubscriptionQueue`/`PollDdsSubscriptionAndInject.groovy` for `SensorReport` → SAGE — see the dedicated section below for full detail. An external standalone publisher (`test_tools/sensor_report_publisher/`) publishes real samples → received, queued, drained, converted into a real `SensorReport` signal via `ALH.createObject`/`setValue`/`createSignal`, and delivered via explicit-target `ALH.sendSignal(signalInstance, ALH.getContext(), "dds-bus")` — confirmed live, including triggering SAGE's own internal `ValidateSensorReport`/`Object_Contains`/`Gate Sensor Report` logic exactly as a real internal signal would. `Health` has no inbound wiring (outbound-only, deliberately); an earlier proof pass used it as scaffolding and has been fully removed. |
| **`DdsEngineListener` — field extraction blocked for `Set_Object_Value` fields** | The publish pipeline extracts real values correctly for fields set via ordinary `AddStructuralFeatureValueAction` (confirmed: `Health`'s `service_state`/`registered_sensor_count`) but fields the model populates via the `Set_Object_Value`/`ALH.setValue()` Groovy library always extract as empty, confirmed at every reachable point (pre-copy token value, post-copy SignalInstance, flat/split key, execution context, and a reflection dump at the Port/Connector activation level that never even ran, since Port/Connector `values` entries turned out to be bare `SignalInstance`s, not `Token`s, so there was nothing to unwrap). Working theory: `ALH.setValue()`'s write is scoped to the Opaque Behavior execution trace it ran under, which an externally-constructed `ALH` (this listener has no way to reconstruct one) can't see. See `DdsEngineListener.java`/`FumlValueBridge.java` sections above for the full trail. Proposed fix — not yet implemented — is a per-topic Groovy Opaque Behavior that reads values the same way `Get_Object_Value` does (proven to work) and calls `DDSTopicPublisher.publishOnce()` directly, sidestepping the external-listener approach entirely. This is now the single biggest remaining blocker to real data fidelity — `SensorReport`'s entire payload, and most of `Health`'s fields, are `Set_Object_Value`-driven. |
| **`SensorReport` has no structural publisher in the model — not a plugin bug** | Newly found once the two bugs below were fixed: `findPublishingBlockNameFromScan()` correctly reports zero structurally-scanned publisher Blocks for `SensorReport` — no Block's Port/FlowProperty wiring declares an "out" direction for it, confirmed by the generated XML itself (only a `SensorReportReader` exists, under `Sensor Alignment and Gating Service_Definition`'s `<subscriber>`; no `<data_writer>` for `SensorReport` exists anywhere, under any participant). The model fires a real `SendSignalAction` for it behaviorally, but that's disconnected from the structural Port wiring `ModelTopicScanner`/`DdsXmlGenerator` rely on. Needs a Port on the correct Block, typed by an InterfaceBlock with an "out" `FlowProperty` of type `SensorReport` — the same pattern already working for `Health` — a model change, not a code fix. |
| **`DdsEngineListener` — Block resolution for participant naming** | **Fixed, live-confirmed.** Two distinct bugs found via comparing owner-chain traces side by side. (1) `findOwningBlock()`'s ownership-chain walk returned null for `Health` (its `SendSignalAction` sits in a reusable/library Activity invoked via `CallBehaviorAction`, owned by a `Package` chain, not a Block — no ownership relationship to walk at all) — fixed by adding `findPublishingBlockNameFromScan()`, the already-scanned structural publisher Block(s) from `ModelTopicScanner` (Port → InterfaceBlock → FlowProperty), now the **primary** source. (2) For `SensorReport`, the ownership walk found a *real but wrong* Block (`DDS_Simulation_Environment`, the enclosing StateMachine's owning Class) that doesn't exist as a `<domain_participant>` in the generated XML at all — `DdsXmlGenerator` only ever builds participants from the structural scan, so that scan had to become primary, with the ownership walk demoted to a secondary heuristic tried only when the scan is empty/ambiguous. Live-confirmed: `Health`'s participant resolves correctly via the scan and publishes successfully. (`SensorReport` still fails, but only because of the separate modeling gap above — the resolution logic itself is now correct.) |
| **`RETCODE_ERROR` on publish** | **Root-caused and fixed, live-confirmed.** Was not a symptom of null/malformed payload content as first suspected (a fully-populated synthetic payload failed identically) — the real cause was invisible until NDDS's own internal logging was routed into the GUI log (see `DDSTopicPublisher.java` below): the generated XML itself failed to parse (`RTIXMLObject_addChild: XML object with name '::DomainLibrary::Domain::SensorReport' already exists`), because `DdsXmlGenerator` gave `<register_type>` and `<topic>` the same `name` whenever a model's payload Block is named identically to its Signal — see the `DdsXmlGenerator.java` section above for the fix (`register_type` now gets a `"_Type"`-suffixed name, guaranteed distinct from any topic name). Confirmed fixed live: after regenerating the XML, `Health` published successfully with **zero** parse errors. |
| **Cached `DomainParticipant`s outliving the simulation** | **Fixed.** The original design deliberately let participants outlive a single simulation run (only ever torn down in `RTIConnextPlugin.close()`), to avoid re-paying expensive participant creation on every re-run. Live testing showed the real cost: a lingering participant keeps doing normal DDS background activity (discovery, liveliness) for as long as CAMEO stays open, which — once NDDS logging started getting routed into the GUI log to chase `RETCODE_ERROR` above — surfaced as a message (a standard RTI discovery-layer "remote reader/writer has no addressable locators" warning, not a bug) repeating every few seconds, including after the user had stopped the simulation. `DdsEngineListener.executionTerminated()` (both the `EngineListener` and `SimulationExecutionListener` overloads) now calls `DDSTopicPublisher.shutdown()`, trading the reuse-across-runs optimization for stopping DDS activity when the simulation does. Note: this only affects participants created *after* the fix was deployed — a participant already alive in a running CAMEO process from an earlier test needs a full CAMEO restart to clear. |
| **List/array-valued outbound field serialization** | **Fixed, 2026-09-10, live-confirmed.** `appendField()` now handles `java.util.List`-shaped extracted values (a fixed-size array field, e.g. `GatedReport.Position`) via a proper JSON array, and checks `field.primitiveType` before the extracted value's raw Java runtime type for scalars (fixes a `string`-declared field like `system_time` coming back as a `java.lang.Double` and being serialized as a bare number). Confirmed live: `GatedReport` now publishes successfully with real `Position`/`Velocity` arrays and a real `system_time` string — see the "Runtime (outbound publish) serialization" note above for the full story. |
| **XML config resolution is fragile** | `DDSTopicPublisher.resolveXmlUrl()` picks whichever `.xml` file sorts alphabetically first in `<plugin-dir>/resources/`. Reinstalling the plugin re-copies the sample `resources/ShapeType.xml`, which can silently outrank a real generated config depending on filename (`"S"` sorts before lowercase names in plain ASCII order) — this has caused live `DomainParticipant` creation to fail mid-session. The `-Dcom.rti.connext.cameo.xmlConfig=<path>` system-property override bypasses this ambiguity entirely and is the more robust option once a config is finalized. |
| **Generated XML ↔ runtime config hand-off** | `GenerateDdsXmlAction` writes wherever the user picks via `JFileChooser`; `DDSTopicPublisher.resolveXmlUrl()` looks in `<plugin-dir>/resources/` (or the system property above). Nothing connects these automatically — the user must manually place the generated file (or set the property). `resources/ShapeType.xml` is an unrelated, hand-written demo config (Square/Circle/Triangle shapes), not something `Generate DDS XML` ever produces. |
| **Nested composite states** | `scanRegionForSubscriptions()` only scans one Region level; a `SignalEvent` trigger inside a nested composite state's sub-region isn't found by the behavioral trace. (Doesn't affect topic discovery/pub-sub anymore, since those are now structural — only affects the future behavioral-vs-structural validation feature.) |
| **Primitive type mapping** | `mapPrimitive()` is a small hardcoded stub (`Integer`/`Real`/`Boolean`/`String` → DDS primitives, everything else falls back to `"string"`). No real convention has been decided for a broader set of value types. |
| **Fixed-size arrays and UML Enumerations — added for `GatedReport`** | `ModelTopicScanner`/`TopicModel`/`DdsXmlGenerator` now detect a Property's fixed multiplicity (`upper==lower>1`, e.g. `Real[2]`) as `arrayDimensions="N"`, and a Property typed by a UML `Enumeration` as a new `<enum>` block + `type="nonBasic" nonBasicTypeName="..."` member — both verified against real APIs/XML (local CAMEO 2024.3 javadoc for `MultiplicityElement`/`Enumeration`; real RTI-shipped example XML for the `nonBasic` enum-reference syntax, which differs from the naive bare-`type=` guess) rather than assumed, and confirmed via a hand-built `TopicModel` exercising `DdsXmlGenerator` directly (no MagicDraw dependency needed for that half). **Not** covered: variable-length sequences (`0..*`/`1..*`), multi-dimensional arrays (`TopicModel.Field.arrayDimension` only carries one `Integer`), and — most notably — the *outbound runtime* publish pipeline (`DdsEngineListener.appendField()`), which was not touched by this change and would still hit the same "unexpected value type" fallback the pre-existing `anomalous_sensors` gap already documents if asked to actually publish an array/enum field live. |
| **QoS** | No longer entirely out of scope (see `ImportQosProfileAction`/QoS picker), but still very narrow — only `participant_name`, `history_depth`, and `is_default_qos` are read/written anywhere. No other Connext QoS policy (durability, reliability, deadline, partition, …) is modeled or emitted. |
| **QoS profile default doesn't apply to XML-declared entities — fixed, 2026-09-14** | `is_default_qos="true"` on the generated `<qos_profile>` has **no effect** on `<data_writer>`/`<data_reader>` elements statically declared in a `domain_participant_library` — confirmed via direct live testing (not assumed), after a real symptom traced back to it: none of `SensorReport_data.sensor_id`/`GatedReport.source_sensor_id`/`Track.track_id` is a DDS key, so each topic has one shared anonymous instance; combined with RTI's factory-default `HISTORY = KEEP_LAST depth=1` (silently in effect despite the model's own `«DDS_QosProfile»` specifying `history_depth=6`), a second sensor's sample written immediately after a first deterministically overwrote it before any reader read it. `DdsXmlGenerator.appendDomainParticipantLibrary()` now emits an explicit `<datawriter_qos base_name="QosLibrary::<profileName>"/>` / `<datareader_qos base_name="...">` on every generated reader/writer when a QoS profile is selected — this is what actually applies it. The four hand-authored DDS configs outside the plugin (`test_tools/sensor_report_publisher/SensorReport.xml`, `association/association_config.xml`, `display/display_config.xml`, `track_visualizer/track_visualizer_config.xml`) needed the identical fix, since none of them had ever referenced a profile explicitly either. The underlying "no DDS key on these structs" design choice itself is unchanged — a real key would be the more correct long-term fix but needs a CAMEO model change; the QoS fix sidesteps it without one. |
| **QoS profile import** | Only ever imports **one** `<qos_profile>` per XML file (the first one found), even if the file defines several. No update/re-import path either — running `Import QoS Profile` again against the same file creates a second `«DDS_QosProfile»` element rather than updating the first. |
| **`DdsInboundInjector` inbound delivery port is hardcoded** | `injectOne()` sends via a literal `"dds-bus-listener"` — reverted from a scan-derived candidate-list approach after live testing showed the scan picks a real-but-unconnected port (see the "Hardcoded names" section above for the full story). Fixing this for real needs the scan to confirm actual Connector-level wiring to the topic's subscribing Block, not just port-name existence on the target — unstarted design work. |
| **`old_files/`** | **Resolved, 2026-09-18** — moved out of this repository to `sage_demo_pipeline/cameo_plugin_old_files/` (never was part of the build; a pre-refactor snapshot kept only as a historical reference, not deleted outright). |
| **`cameo_plugin/README.md`** | Describes the old `ShapeTypePublisherAction`/`ShapeTypeSubscriberAction`/`DDSRunner` design, which no longer exists in `src/`. Stale — this `architecture.md` is the current source of truth; the README should eventually be rewritten or removed. |

---

## The "core is backend-agnostic" caveat

The `core`/`dds` package split accomplishes physical separation — `core/`
has zero imports of `com.rti.dds.*` or anything DDS-specific in its API
surface (`TopicModel`, its own scan methods). But **`ModelTopicScanner`
itself still hardcodes DDS-specific stereotype names as its scanning
target**: the constants `DDS_PROFILE_NAME = "DDS_Profile"`,
`TOPIC_STEREOTYPE_NAME = "DDS_Topic"`, `DOMAIN_STEREOTYPE_NAME =
"DDS_Domain"` live directly in `core/ModelTopicScanner.java`.

That's fine for today — this reorganization was explicitly scoped as a pure
move/rename with no new functionality — but it means a future MQTT backend
can't yet just add `com.rti.connext.cameo.mqtt/` and reuse
`ModelTopicScanner` unchanged, because "what counts as a topic" is still
DDS-vocabulary-specific (`DDS_Topic`, `DDS_Domain`). Making `core` truly
backend-agnostic would mean either:

- Generalizing the profile/stereotype names to something backend-neutral
  (e.g. a generic `Topic`/`MessageDomain` concept the DDS backend maps onto
  `DDS_Topic`/`DDS_Domain`), or
- Parameterizing `ModelTopicScanner` to accept the profile/stereotype names
  it should look for, rather than hardcoding them.

Neither is implemented — flagging it here so it's a conscious decision
later, not a surprise.

**This got measurably worse, not better, with the QoS work.**
`findQosLibraryPackages()`/`findQosProfiles()`/`readQosProfileInfo()` — and
the public `QOS_LIBRARY_STEREOTYPE_NAME`/`QOS_PROFILE_STEREOTYPE_NAME`/tag
constants they're built on — were added to `core/ModelTopicScanner.java`,
even though QoS (participant/datareader policy) is an inherently
DDS-specific concept with no obvious MQTT analogue. This followed the
existing (already-imperfect) precedent of putting DDS-vocabulary scanning
in `core` rather than inventing a new pattern, but it's worth being
explicit that a future MQTT backend would need to either ignore these
methods entirely or the two profiles would need to diverge further at that
point. Not fixed here — this reorganization boundary question was already
open before this change; the QoS work didn't create it, just added to it.

---

## Relationship to the README and `old_files/`

**Updated 2026-09-18: `cameo_plugin/README.md` has been rewritten and is
current** — it now describes the actual Tools-menu actions and build/
install/troubleshooting steps for both CST versions, and points here for
everything else. It previously described an earlier version of this plugin
built around `ShapeTypePublisherAction`, `ShapeTypeSubscriberAction`, and a
`DDSRunner` class running background publisher/subscriber threads for a demo
`ShapeType` — none of those three classes exist in `src/` anymore. That
old README content, plus `old_files/` (a pre-refactor snapshot of the same
era, never part of the build), have been moved out of this repository
entirely to `sage_demo_pipeline/cameo_plugin_old_files/` (a sibling folder
outside git's view — see "Beyond the plugin" above for why that split
exists) rather than deleted outright, kept only as a historical reference,
not as anything this plugin depends on.
