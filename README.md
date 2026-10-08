# RTI Connext DDS — CAMEO Integration

Integration components for using **RTI Connext DDS** with **CAMEO Systems Modeler**.

**Status**: Under development. Not ready for use.

---

## Components

| Directory | Description |
|---|---|
| [`cameo_plugin/`](cameo_plugin/) | CAMEO System Modeler 2024x plugin that runs a DDS `ShapeType` publisher/subscriber in-process, adding a "RTI Connext DDS" entry to the Tools menu. Includes its own build (`build.bat`) and deploy (`install.bat`) scripts. |
| [`wis/`](wis/) | Example integration using **RTI Web Integration Service (WIS)** to publish `ShapeType` samples to DDS via REST, driven from a CAMEO Groovy opaque behavior. |

Each component is self-contained with its own `README.md` covering prerequisites,
build/run instructions, and troubleshooting — start there for details.

---

## Prerequisites (common to both components)

- **RTI Connext DDS 7.x** (`NDDSHOME` set)
- **CAMEO Systems Modeler 2024x**

See each component's README for additional setup specific to that integration.

---

## License

You can find the license here [LICENSE](LICENSE).
