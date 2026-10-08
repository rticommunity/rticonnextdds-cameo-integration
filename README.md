# RTI Connext DDS — CAMEO Integration

Connect SysML models in CAMEO to real RTI Connext DDS traffic.

**Status**: Under development. Not ready for use.

| Directory | What it is |
|---|---|
| [`cameo_plugin/`](cameo_plugin/) | CAMEO plugin that lets a running SysML simulation (Cameo Simulation Toolkit) publish and receive real DDS samples. The DDS configuration is generated from the model. Supports CAMEO 2024x and 2026x. |
| [`wis/`](wis/) | Example: publishing `ShapeType` samples to DDS through RTI Web Integration Service (REST), driven from a CAMEO Groovy behavior. |

Each directory has its own README with setup instructions.

## Demo

A full multi-service demo built on the plugin — a sensor emulator, a SysML
service model running in CAMEO, a C++ track-association service, and a live
Python display, all talking over DDS — lives in a separate repository:
[sage-demo-pipeline](https://gitlab.com/notesandnotches-group/sage-demo-pipeline).

## Prerequisites

- RTI Connext DDS 7.x
- CAMEO 2024x or 2026x with Cameo Simulation Toolkit (for `cameo_plugin/`)

## License

See [LICENSE](LICENSE).
