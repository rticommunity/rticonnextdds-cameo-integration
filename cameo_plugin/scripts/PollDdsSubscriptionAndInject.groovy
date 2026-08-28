// PollDdsSubscriptionAndInject.groovy — SensorReport, receive + inject.
//
// This is the inbound mirror of RegisterDdsEngineListener.groovy's outbound
// pipeline: external DDS data -> DdsSubscriptionQueue (see
// DDSTopicSubscriber.java's DataReaderListener, running on RTI's native
// callback thread) -> THIS script, polled from inside the live simulation,
// which turns the queued JSON back into a real SensorReport signal and
// sends it via ALH.sendSignal() through the "dds-bus" port.
//
// Health has NO inbound handling here, deliberately — Health is outbound
// only (something the model sends, not something received back in).
//
// ============================== IMPORTANT ==============================
// DESIGN — explicit target + ALH.sendSignal(SignalInstance, Object_, String),
// NOT the 2-arg ALH.sendSignal(SignalInstance, String).
//
// The 2-arg overload resolves its target via ContextHelper.findNearestObject()
// -- "whichever object is currently running this code" (confirmed via javap
// against com.nomagic.magicdraw.simulation.fuml.o.a's bytecode). The 3-arg
// overload used below takes the target explicitly instead (confirmed against
// NoMagic's own CST docs: "Sending a signal instance to a specific target
// object" -- ALH.sendSignal(signal, object, portName)), so it doesn't matter
// where this script runs, AS LONG AS the target object passed in is correct.
//
// Here, target = ALH.getContext() directly (no role-name lookup needed) --
// confirmed the "dds-bus" port lives on DDS_Simulation_Environment itself,
// which is the same object whose execution this script already runs as part
// of (the "CONFIGURE DDS INCOMING" state's self-transition), so getContext()
// already resolves to the right object without an extra ALH.getValue() hop.
//
// FIELDS SET VIA ALH.setValue(), NOT ALH.addValue() -- the choice between
// them is per-FIELD, based on that field's own multiplicity, not on
// whether the owning object was just created. A REQUIRED field (1..1,
// which every SensorReport_data field is, confirmed via the live [DDS
// Scan] log) is auto-initialized with a default value the moment the
// object is created, so there's already something there to replace --
// setValue() is correct. addValue() is only needed for a genuinely
// OPTIONAL (0..1) field, which does NOT get auto-initialized and has
// nothing to replace yet. The signal's own "data" attribute is likewise
// required (1..1), so setValue() is correct there too.
//
// ENUM FIELDS -- CONFIRMED. measurement_frame/sensor_modality arrive off
// the queue as plain strings (e.g. "BEARING_RANGE_ESTIMATE") since that's
// how DynamicData's JSON serialization represents enum members.
// ALH.setValue() on an enum-typed feature correctly coerces a bare String
// into the matching EnumerationLiteral -- confirmed live, injected samples
// reach SAGE's own ValidateSensorReport/Object_Contains logic correctly.
//
// SELF-HEALING SUBSCRIPTION -- unchanged from before: subscribeOnce() is
// called at the top of every poll cycle (cheap no-op once already
// subscribed) since the bootstrap-only registration doesn't survive into
// the real simulation run.
//
// DRAINING, NOT JUST PEEKING, PER CYCLE -- unchanged from before: this
// script drains the ENTIRE queue every cycle, and on_data_available (see
// DDSTopicSubscriber.java) drains the DDS reader itself on every RTI
// callback, so nothing is lost at either hand-off regardless of burst size.
// =========================================================================

def guiLog = com.nomagic.magicdraw.core.Application.getInstance().getGUILog()

def TOPIC_NAME = "SensorReport"
def PARTICIPANT_CONFIG_NAME = "DomainParticipantLibrary::Sensor Alignment and Gating Service_Definition"
def DATA_READER_NAME = "Subscriber::SensorReportReader"
def PAYLOAD_BLOCK_NAME = "SensorReport_data"
def PORT_NAME = "dds-bus"

// Injects one already-dequeued message: builds a SensorReport_data object,
// wraps it in a fresh SensorReport signal, sends it to "dds-bus" targeting
// the explicitly-passed object. Uses `return` (not `continue`) on
// per-message failure so one bad/unparseable message doesn't abort the
// rest of this cycle's drain loop -- Groovy closures treat `return` as
// "stop processing THIS message," equivalent to a loop's `continue`.
def injectOne = { pending, target, log ->
    def fields
    try {
        fields = com.rti.connext.cameo.core.JsonPayloadBuilder.parseFlat(pending.jsonPayload)
    } catch (Exception ex) {
        log.log("[DDS Inject] ERROR: failed to parse queued JSON payload -- " + ex)
        return
    }

    def payloadObject
    try {
        payloadObject = ALH.createObject(PAYLOAD_BLOCK_NAME)
    } catch (Exception ex) {
        log.log("[DDS Inject] ERROR: ALH.createObject('" + PAYLOAD_BLOCK_NAME + "') threw -- " + ex)
        return
    }
    if (payloadObject == null) {
        log.log("[DDS Inject] ERROR: ALH.createObject('" + PAYLOAD_BLOCK_NAME
                + "') returned null -- either the Block wasn't found, or ALH.getContext() is null "
                + "(check this script is actually running as part of a live simulation execution).")
        return
    }

    fields.each { key, value ->
        try {
            ALH.setValue(payloadObject, key, value)
        } catch (Exception ex) {
            log.log("[DDS Inject]   ERROR setting field '" + key + "' = " + value + " -- " + ex)
        }
    }

    def signalInstance
    try {
        signalInstance = ALH.createSignal(TOPIC_NAME)
    } catch (Exception ex) {
        log.log("[DDS Inject] ERROR: ALH.createSignal('" + TOPIC_NAME + "') threw -- " + ex)
        return
    }
    if (signalInstance == null) {
        log.log("[DDS Inject] ERROR: ALH.createSignal('" + TOPIC_NAME + "') returned null -- "
                + "Signal not found by name.")
        return
    }

    try {
        // "data" matches ModelTopicScanner.TOPIC_DATA_ATTRIBUTE_NAME -- the
        // same single attribute name the outbound side reads the payload
        // back off of.
        ALH.setValue(signalInstance, com.rti.connext.cameo.core.ModelTopicScanner.TOPIC_DATA_ATTRIBUTE_NAME, payloadObject)
    } catch (Exception ex) {
        log.log("[DDS Inject] ERROR: ALH.setValue(signalInstance, \"data\", payloadObject) threw -- " + ex)
        return
    }

    try {
        ALH.sendSignal(signalInstance, target, PORT_NAME)
        log.log("[DDS Inject] SUCCESS: sent '" + TOPIC_NAME + "' via port '" + PORT_NAME + "'.")
    } catch (Exception ex) {
        log.log("[DDS Inject] ERROR: ALH.sendSignal(signalInstance, target, \"" + PORT_NAME + "\") threw -- " + ex)
    }
}

try {
    // Self-healing -- see header comment. Cheap no-op once already
    // subscribed; only does real work the first time this actually runs
    // inside the real simulation.
    try {
        com.rti.connext.cameo.dds.DDSTopicSubscriber.subscribeOnce(PARTICIPANT_CONFIG_NAME, DATA_READER_NAME, TOPIC_NAME)
    } catch (Exception ex) {
        guiLog.log("[DDS Inject] ERROR: subscribeOnce() failed -- " + ex)
        return
    }

    def firstPending = com.rti.connext.cameo.dds.DdsSubscriptionQueue.pollNext(TOPIC_NAME)
    if (firstPending == null) {
        return // nothing queued this cycle — keep the no-op path cheap
    }

    // Resolved once per drain cycle (not per message) -- doesn't change
    // mid-cycle. See header comment for why this is DDS_Simulation_
    // Environment itself, not a fetched SAGE reference.
    def target = ALH.getContext()
    if (target == null) {
        guiLog.log("[DDS Inject] ERROR: ALH.getContext() is null -- this script isn't running inside a "
                + "live simulation execution; cannot determine the sendSignal target.")
        return
    }

    def pending = firstPending
    def count = 0
    while (pending != null) {
        count++
        injectOne(pending, target, guiLog)
        pending = com.rti.connext.cameo.dds.DdsSubscriptionQueue.pollNext(TOPIC_NAME)
    }
    if (count > 1) {
        guiLog.log("[DDS Inject] Drained " + count + " queued '" + TOPIC_NAME + "' messages this cycle.")
    }

} catch (Exception outer) {
    guiLog.log("[DDS Inject] ERROR: unhandled exception in polling script -- " + outer)
}
