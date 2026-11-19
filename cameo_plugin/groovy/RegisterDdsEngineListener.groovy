// (c) Copyright, Real-Time Innovations, 2026.  All rights reserved.
// RTI grants Licensee a license to use, modify, compile, and create derivative
// works of the software solely for use with RTI Connext DDS. Licensee may
// redistribute copies of the software provided that all such copies are subject
// to this license. The software is provided "as is", with no warranty of any
// type, including any warranty for fitness for any purpose. RTI is under no
// obligation to maintain or support the software. RTI shall not be liable for
// any incidental or consequential damages arising out of the use or inability
// to use the software.
//
// RegisterDdsEngineListener.groovy
//
// Paste into a Groovy Opaque Behavior that fires EXACTLY ONCE at
// simulation start (e.g. the top-level Block's initial state entry
// action). Do NOT attach to anything that can fire more than once —
// each run registers another listener, causing duplicate DDS publications.
//
// Requires _session_ (a SimulationSession), auto-injected by CST into
// the Opaque Behavior's Groovy binding.
//
// Registers DdsEngineListener for automatic publication on outgoing DDS-topic
// Signal events and cleanup when simulation execution ends.
//
// Before that, generates the DDS XML config from the model for this run
// (DdsRuntimeConfig). After it, limits inbound DDS to the Blocks in the
// simulated Block's composition (setSimulationScope) and subscribes them
// (see PollDdsSubscriptionAndInject.groovy for the injection half).
// Nothing is hardcoded here. The Activity containing this script runs as
// its own short-lived execution that ends before the real simulation
// starts (confirmed live), which tears the subscriptions down again;
// PollDdsSubscriptionAndInject.groovy re-subscribes every cycle to recover.

def guiLog = com.nomagic.magicdraw.core.Application.getInstance().getGUILog()

// Generate the DDS XML config from the model for this run (see
// DdsRuntimeConfig) -- before anything creates a DDS participant.
if (!com.rti.connext.cameo.dds.DdsRuntimeConfig.prepare()) {
    throw new IllegalStateException("DDS configuration preparation failed; see the CAMEO log.")
}

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
        guiLog.log("[DDS Listener] DdsEngineListener registered for automatic DDS publication and simulation cleanup.")
    }
} catch (Exception e) {
    guiLog.log("[DDS Listener] ERROR: failed to register DdsEngineListener -- " + e)
}

try {
    // Reset DDS entities once per run, never in the repeating poll script.
    com.rti.connext.cameo.dds.DdsInboundInjector.setSimulationScope(ALH.getContext())
    com.rti.connext.cameo.dds.DdsInboundInjector.resetForNewRun()
    com.rti.connext.cameo.dds.DdsInboundInjector.subscribeAll()
    guiLog.log("[DDS Listener] Inbound readers ready for direct polling by PollDdsSubscriptionAndInject.groovy.")
} catch (Exception e) {
    guiLog.log("[DDS Listener] ERROR: failed to start inbound subscriptions -- " + e)
}