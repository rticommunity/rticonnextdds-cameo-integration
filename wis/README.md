# WIS Example — RTI Web Integration Service + CAMEO

Publishes `ShapeType` samples to DDS via the **RTI Web Integration Service (WIS)** REST API, driven by a CAMEO Groovy opaque behavior.

---

## Files

| File | Purpose |
|---|---|
| `wis_config.xml` | WIS configuration: type definition, QoS, domain, participant, writers/readers |
| `ShapeTypeWISWriter.groovy` | CAMEO Groovy behavior that POSTs a ShapeType JSON payload to WIS |

---

## Prerequisites

- RTI Connext DDS 7.x with Web Integration Service (`rtiwebintegrationservice` on PATH)
- Cameo Systems Modeler with Groovy behavior support

---

## Step 1 — Start WIS

```bash
# Default port is 8080; use -port to override
rtiwebintegrationservice -cfgFile wis_config.xml -cfgName ShapeWIS
```

WIS listens on **http://localhost:8080** by default.

> **Note:** WIS auto-loads `RTI_WEB_INTEGRATION_SERVICE.xml` which already defines `ShapeType`.
> The `wis_config.xml` therefore does **not** redefine the type — it only adds the domain,
> topics (Square, Circle, Triangle), and the REST application wiring.

---

## Step 2 — Verify with curl (optional)

```bash
# Publish a Blue square
curl -X POST \
  "http://localhost:8080/dds/rest1/applications/ShapeApplication/domain_participants/ShapeParticipant/publishers/Publisher/data_writers/SquareWriter" \
  -H "Content-Type: application/dds-web+json" \
  -d '{"color":"BLUE","x":100,"y":200,"shapesize":30}'
```

Open RTI Shapes Demo on domain 0 to see the shape appear.

---

## Step 3 — Wire the Groovy script in CAMEO

1. Create an **Opaque Behavior** on a Block or Activity.
2. Set **Language** = `Groovy`.
3. Paste the contents of `ShapeTypeWISWriter.groovy` as the body.
4. Add a **String-typed Parameter** named `jsonBody` to the behavior.  
   CAMEO binds the parameter value to the local variable at runtime.

At runtime, supply a JSON string such as:

```json
{"color":"BLUE","x":100,"y":200,"shapesize":30}
```

The script POSTs it to WIS and prints the HTTP status. If the call fails, a `RuntimeException` is thrown (visible in the CAMEO execution log).

---

## REST URL structure

```
http://<WIS_HOST>:<WIS_PORT>/dds/rest1/applications/<session>/domain_participants/<dp>/publishers/<pub>/data_writers/<dw>
```

| Segment | Value in this example |
|---|---|
| `session` | `ShapeApplication` |
| `dp` | `ShapeParticipant` |
| `pub` | `Publisher` |
| `dw` | `SquareWriter`, `CircleWriter`, or `TriangleWriter` |

Change the `DW_NAME` constant in the Groovy script to target a different topic.

---

## ShapeType fields

| Field | Type | Key? |
|---|---|---|
| `color` | string (max 128) | yes |
| `x` | int32 | no |
| `y` | int32 | no |
| `shapesize` | int32 | no |
