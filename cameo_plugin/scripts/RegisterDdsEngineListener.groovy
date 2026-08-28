// RegisterDdsEngineListener.groovy
//
// Paste this into the body of a Groovy Opaque Behavior attached to an
// entry/initial action that fires EXACTLY ONCE at the start of simulation
// (e.g. the top-level Block's initial state entry action).
//
// Do NOT attach this to anything that can fire more than once (a repeating
// transition, a loop, a do-activity that re-enters, etc.) — each execution
// registers another listener instance, so duplicate registrations mean
// DdsEngineListener's callbacks fire multiple times per SendSignalAction
// and you'll get duplicate DDS publishes once publishing is wired up.
//
// Requires: _session_ is auto-injected by CST into the Opaque Behavior's
// Groovy binding at simulation runtime; _session_ is a SimulationSession.
//
// IMPORTANT: logs go to CAMEO's GUI Notification log (Application.getGUILog()),
// the SAME place DdsEngineListener's own [DDS Listener]/[DDS Listener DIAG]
// lines appear. If you don't see the "[DDS Listener] registered" line below
// in the GUI log after starting a simulation, registration itself is the
// problem -- look no further into DdsEngineListener's own logic until this
// line shows.
//
// Registers on BOTH (both public/@OpenApiAll, confirmed via javap -v -p --
// see DdsEngineListener.java's file header for the full investigation
// history, including why an earlier InternalEngineListener-based attempt was
// removed: confirmed @InternalApi + @Deprecated, and never fired anyway):
//   - EngineListener (engine.addEngineListener) -- fires for generic
//     ActivityNode activations (ControlFlow, ActivityFinalNode, ...) but NOT
//     for SendSignalAction.
//   - SimulationExecutionListener (_session_.getExecution().addSimulationListener)
//     -- elementActivated() on THIS registration path DOES fire for
//     SendSignalAction (confirmed live), giving the Topic/Block identity via
//     public API. eventTriggered(SignalInstance), also on this path, is where
//     the actual field values are read (via FumlValueBridge, using
//     com.nomagic.magicdraw.simulation.utils.ALH -- NoMagic's own public
//     @OpenApiAll scripting helper -- see FumlValueBridge.java's header) and
//     published to DDS. The other 14 hooks on this listener remain
//     diagnostic-only no-ops.
//
// _session_ is now passed into DdsEngineListener's constructor (not just
// used locally here) because ALH needs a SimulationSession to construct.
//
// ALSO starts the INBOUND subscription for SensorReport -> SAGE — see
// scripts/PollDdsSubscriptionAndInject.groovy for the other half of this
// pipeline (the actual DDS-sample-to-SignalInstance injection, which must
// be pasted into an Opaque Behavior running as part of SAGE's own
// StateMachine, NOT here). Folded into this bootstrap script rather than a
// separate one-time setup script since it's the same "run exactly once at
// simulation start" shape as the listener registration above.
// subscribeOnce() is itself idempotent (checks its own cache), so this is
// safe even if this bootstrap script accidentally runs more than once.
//
// Health has NO inbound wiring, deliberately: Health is outbound-only —
// something the model itself sends, never something an external system
// sends back in — so there is no HealthReader to subscribe to. (An earlier
// version of this pipeline briefly proved the inbound mechanism out
// end-to-end using Health as the test topic, including a HealthReader/
// ReceiveHealth Operation on SAGE — that was scaffolding for the proof,
// not a real requirement, and has since been removed from both this
// script and the model.)
//
// LIVE TESTING FOUND: the Activity containing this script's own
// registration call runs as its own short-lived execution that finishes
// and fires executionTerminated() (tearing down DDSTopicSubscriber) BEFORE
// the real simulation actually starts -- so this call alone is not
// sufficient. PollDdsSubscriptionAndInject.groovy now also calls
// subscribeOnce() at the top of every poll cycle (cheap no-op once already
// subscribed) to self-heal from this. Kept here too since it's harmless
// and establishes the subscription slightly earlier when it does happen to
// survive.

def guiLog = com.nomagic.magicdraw.core.Application.getInstance().getGUILog()

try {
    def engine = _session_.getEngine()
    def execution = _session_.getExecution()
    if (engine == null || execution == null) {
        guiLog.log("[DDS Listener] ERROR: _session_.getEngine()=" + engine +
                " _session_.getExecution()=" + execution +
                " -- cannot register DdsEngineListener. Check your CST version against " +
                "com.nomagic.magicdraw.simulation.execution.session.SimulationSession.")
    } else {
        def listener = new com.rti.connext.cameo.dds.DdsEngineListener(_session_)
        engine.addEngineListener(listener)
        execution.addSimulationListener(listener)
        guiLog.log("[DDS Listener] DdsEngineListener registered (EngineListener + SimulationExecutionListener) on the running simulation.")
    }
} catch (Exception e) {
    guiLog.log("[DDS Listener] ERROR: failed to register DdsEngineListener -- " + e)
}

try {
    // SensorReportReader lives under "_Definition" -- confirmed against the
    // generated DDS_Schema.xml: SAGE_Definition already has
    // <subscriber><data_reader name="SensorReportReader" topic_ref=
    // "SensorReport"/></subscriber>. This call here only establishes the
    // subscription early; PollDdsSubscriptionAndInject.groovy (pasted into
    // an Opaque Behavior running as part of SAGE's own StateMachine) is what
    // actually drains the queue and injects each sample as a real
    // SensorReport signal via ALH.sendSignal -- confirmed working live
    // end-to-end against test_tools/sensor_report_publisher/, including
    // triggering SAGE's own ValidateSensorReport/Gate Sensor Report logic.
    // Same self-healing caveat as above applies -- this bootstrap-only call
    // alone won't survive into the real simulation run, which is why
    // PollDdsSubscriptionAndInject.groovy also re-calls subscribeOnce()
    // every cycle.
    com.rti.connext.cameo.dds.DDSTopicSubscriber.subscribeOnce(
            "DomainParticipantLibrary::Sensor Alignment and Gating Service_Definition",
            "Subscriber::SensorReportReader",
            "SensorReport")
    guiLog.log("[DDS Listener] Inbound subscription started for 'SensorReport' -> SAGE (receive-and-queue here; PollDdsSubscriptionAndInject.groovy does the actual injection).")
} catch (Exception e) {
    guiLog.log("[DDS Listener] ERROR: failed to start inbound 'SensorReport' subscription -- " + e)
}