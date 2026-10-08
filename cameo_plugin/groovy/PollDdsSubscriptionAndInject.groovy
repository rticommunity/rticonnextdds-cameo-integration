// PollDdsSubscriptionAndInject.groovy — generic inbound DDS injection.
// Paste into a repeating self-transition's effect (e.g. a Time Event
// self-transition on whatever Block owns the inbound Port(s) this
// placement is meant to serve — currently DDS_Simulation_Environment's
// "CONFIGURE DDS INCOMING" state).
//
// No topic/participant/reader/payload/port names hardcoded here or in
// DdsInboundInjector — every «DDS_Topic» the model wires up as inbound is
// discovered, subscribed, and injected automatically. See
// DdsInboundInjector.java's header comment for the full design (why
// ALH itself is passed in rather than reconstructed, why every candidate
// Port for a topic is tried against this placement's target, and why a
// script still has to be physically pasted somewhere with a repeating
// trigger — there's no way around that part).
try {
    com.rti.connext.cameo.dds.DdsInboundInjector.subscribeAll()
    com.rti.connext.cameo.dds.DdsInboundInjector.pollAndInjectAll(ALH, ALH.getContext())
} catch (Exception outer) {
    com.nomagic.magicdraw.core.Application.getInstance().getGUILog()
            .log("[DDS Inject] ERROR: unhandled exception in polling script -- " + outer)
}
