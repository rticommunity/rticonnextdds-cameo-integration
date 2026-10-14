# RTI Connext DDS — CAMEO Integration

Connect SysML models in CAMEO to real RTI Connext DDS traffic: a model running
in Cameo Simulation Toolkit publishes and receives real DDS samples, with the
DDS configuration generated from the model itself. A model of a service can
then be exercised against the actual applications on the bus, as an executable
specification.

**Status**: Under development. Not ready for use.

| Directory | What it is |
|---|---|
| [`cameo_plugin/`](cameo_plugin/) | The CAMEO plugin (2024x and 2026x), the DDS profile and test harness, and an example service model. |
| [`wis/`](wis/) | Example: publishing `ShapeType` samples to DDS through RTI Web Integration Service (REST), driven from a CAMEO Groovy behavior. |

Each directory has its own README with setup instructions.

## Demo

A full multi-service demo built on the plugin — a sensor emulator, a SysML
service model running in CAMEO, a C++ track-association service, and a live
Python display, all talking over DDS. The service model is in
[`cameo_plugin/DDS_Models/`](cameo_plugin/DDS_Models/); the other applications
are in [sage-demo-pipeline](https://gitlab.com/notesandnotches-group/sage-demo-pipeline).

## Prerequisites

- RTI Connext DDS 7.x, with a valid license (RTI offers evaluation licenses)
- CAMEO 2024x or 2026x with Cameo Simulation Toolkit

Without RTI Connext you can still read the code and open the models, but not
build or run the plugin.

## Known limitations

One `«DDS_Domain»` per project, one instance per Block, no DDS sequences,
Windows build scripts only. The full list is in
[`cameo_plugin/docs/architecture.md`](cameo_plugin/docs/architecture.md#known-limitations).

## License

See [LICENSE](LICENSE).
