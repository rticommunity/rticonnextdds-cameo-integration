# SensorReport External Publisher (standalone test tool)

Standalone DDS publisher for the `SensorReport` topic, used to test the
CAMEO plugin's full inbound (subscribe → inject) pipeline —
`DDSTopicSubscriber` + `DdsSubscriptionQueue` +
`PollDdsSubscriptionAndInject.groovy` — against a real external publisher,
outside CAMEO entirely. Modeled on an RTI engineer's own reference example
(`MessagePublisher.java`), shaped for `SensorReport_data`'s real fields.

**Confirmed working end-to-end:** running this and watching CAMEO's live
simulation shows `[RTI Connext] Received sample for topic 'SensorReport'
-> queued: ...`, then `[DDS Inject] SUCCESS: sent 'SensorReport' via port
'dds-bus'`, then SAGE's own internal validation logic
(`ValidateSensorReport` / `Object_Contains` / `Gate Sensor Report`)
processing the injected signal exactly as it would a real internal one.

`sensor_id` is set to `"CAM-01"`, a sensor actually registered in the
model's Registration Map — required for the report to pass
`Object_Contains` validation instead of being gated as unregistered.

## Files

- `SensorReport.xml` — defines `MeasurementFrameEnum`, `SensorModalityEnum`,
  the `SensorReport_data` type, the `SensorReport` topic, and a
  publisher-only participant (XML Application Creation) — field-for-field
  and literal-for-literal matched against CAMEO's own generated
  `DDS_Schema.xml`
- `SensorReportPublisher.java` — loads `SensorReport.xml`, looks up the
  writer by name, and publishes one `DynamicData` sample every 30 seconds
  (paced for demo purposes)
- `build.sh` / `run.sh` / `clean.sh` — same shape as the deleted
  `listeners_java/` reference example

## Process

1. Source the RTI Connext DDS environment setup script for your platform
   (optional — `build.sh`/`run.sh` default `NDDSHOME`/`RTI_LICENSE_FILE` to
   this machine's known install paths if not already set):
   ```bash
   source $NDDSHOME/resource/scripts/rtisetenv_<arch>.zsh
   ```
2. Compile: `./build.sh`
3. Make sure CAMEO already has a live simulation running (the inbound
   subscription/poll cycle only exists inside a running simulation).
4. Run: `./run.sh` (Ctrl+C to stop)
5. Watch CAMEO's GUI Notification log for `[RTI Connext] Received sample
   for topic 'SensorReport' -> queued: ...` followed by `[DDS Inject]
   SUCCESS: sent 'SensorReport' via port 'dds-bus'`.
6. `./clean.sh` when done.
