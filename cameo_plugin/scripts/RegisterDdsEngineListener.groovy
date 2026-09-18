// RegisterDdsEngineListener.groovy
//
// Paste into a Groovy Opaque Behavior that fires EXACTLY ONCE at
// simulation start (e.g. the top-level Block's initial state entry
// action). Do NOT attach to anything that can fire more than once —
// each run registers another listener, so duplicate registrations mean
// duplicate DDS publishes.
//
// Requires _session_ (a SimulationSession), auto-injected by CST into
// the Opaque Behavior's Groovy binding.
//
// Registers DdsEngineListener on both EngineListener (generic
// ActivityNode activations — doesn't fire for SendSignalAction) and
// SimulationExecutionListener (elementActivated()/eventTriggered() do
// fire for SendSignalAction — this is the path that actually reads field
// values via FumlValueBridge/ALH and publishes to DDS). See
// DdsEngineListener.java for the rest of that listener's logic.
//
// Also subscribes to every inbound «DDS_Topic» the model wires up (see
// PollDdsSubscriptionAndInject.groovy for the injection half). Fully
// generic — DdsInboundInjector.subscribeAll() finds every topic itself via
// ModelTopicScanner; nothing is hardcoded here. subscribeOnce() (called
// per topic inside it) is idempotent, so this is safe to call from here
// even though — confirmed live — the Activity containing this script runs
// as its own short-lived execution that tears itself down before the real
// simulation starts; PollDdsSubscriptionAndInject.groovy re-subscribes
// every poll cycle to self-heal from that.

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
    // resetForNewRun() FIRST -- discards any backlog left over from before
    // this run started (DDS reception has no simulation-run scoping of its
    // own). Must only happen here, once, not in the repeating poll script.
    com.rti.connext.cameo.dds.DdsInboundInjector.resetForNewRun()
    com.rti.connext.cameo.dds.DdsInboundInjector.subscribeAll()
    guiLog.log("[DDS Listener] Inbound subscriptions started for every «DDS_Topic» the model wires up (receive-and-queue here; PollDdsSubscriptionAndInject.groovy does the actual injection).")
} catch (Exception e) {
    guiLog.log("[DDS Listener] ERROR: failed to start inbound subscriptions -- " + e)
}