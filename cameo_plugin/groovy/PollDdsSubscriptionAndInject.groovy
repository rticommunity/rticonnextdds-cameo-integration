// PollDdsSubscriptionAndInject.groovy — generic inbound DDS injection.
// Paste into a repeating self-transition's effect (e.g. a Time Event
// self-transition on whatever Block owns the inbound Port(s) this
// placement is meant to serve — currently DDS_Simulation_Environment's
// "CONFIGURE DDS INCOMING" state).
//
// No topic/participant/reader/payload/port names hardcoded here or in
// DdsInboundInjector — every inbound «DDS_Topic» of the Blocks being
// simulated is subscribed and injected automatically, out of the port on
// this Block that the model connects to each subscriber. See
// DdsInboundInjector.java's header comment for the full design (why ALH
// itself is passed in rather than reconstructed, and why a script has to
// be placed somewhere with a repeating trigger).
try {
    com.rti.connext.cameo.dds.DdsInboundInjector.subscribeAll()
    com.rti.connext.cameo.dds.DdsInboundInjector.pollAndInjectAll(ALH, ALH.getContext())
} catch (Exception outer) {
    com.nomagic.magicdraw.core.Application.getInstance().getGUILog()
            .log("[DDS Inject] ERROR: unhandled exception in polling script -- " + outer)
}
