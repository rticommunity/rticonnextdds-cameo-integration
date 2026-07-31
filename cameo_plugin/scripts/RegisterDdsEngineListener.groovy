// RegisterDdsEngineListener.groovy
//
// Paste this into the body of a Groovy Opaque Behavior attached to an
// entry/initial action that fires EXACTLY ONCE at the start of simulation
// (e.g. the top-level Block's initial state entry action).
//
// Do NOT attach this to anything that can fire more than once (a repeating
// transition, a loop, a do-activity that re-enters, etc.) — each execution
// registers another listener instance, so duplicate registrations mean
// DdsEngineListener.elementActivated() fires multiple times per
// SendSignalAction and you'll get duplicate DDS publishes once publishing
// is wired up.
//
// Requires: _session_ is auto-injected by CST into the Opaque Behavior's
// Groovy binding at simulation runtime; _session_.getEngine() returns the
// running ExecutionEngine for this simulation session.

try {
    def engine = _session_.getEngine()
    if (engine == null) {
        println "[DDS Listener] ERROR: _session_.getEngine() returned null -- " +
                "cannot register DdsEngineListener. Check your CST version against " +
                "com.nomagic.magicdraw.simulation.execution.session.SimulationSession."
    } else {
        engine.addEngineListener(new com.rti.connext.cameo.model.DdsEngineListener())
        println "[DDS Listener] DdsEngineListener registered on the running ExecutionEngine."
    }
} catch (Exception e) {
    println "[DDS Listener] ERROR: failed to register DdsEngineListener -- " + e
}
