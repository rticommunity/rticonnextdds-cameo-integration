/*
 * DdsInboundInjector.java — CAMEO 2024x variant. See
 * src26x/.../DdsInboundInjector.java for the 2026x port (Object_/
 * SignalInstance moved from fUML.Semantics.* to
 * com.nomagic.magicdraw.simulation.fuml.* in that CST version). Picked up by
 * build.bat; build_26x.bat picks up the src26x/ copy instead.
 *
 * DdsInboundInjector.java — fully generic inbound DDS -> live-signal
 * injection, driven entirely by ModelTopicScanner's structural scan. No
 * topic/participant/reader/payload/port names are hardcoded anywhere in
 * this class or in the two Groovy scripts that call it
 * (RegisterDdsEngineListener.groovy / PollDdsSubscriptionAndInject.groovy).
 * Any «DDS_Topic»-stereotyped Signal wired as inbound (Port ->
 * «InterfaceBlock» -> «FlowProperty» direction=in, on any Block) is
 * subscribed to and injected automatically.
 *
 * ============================== IMPORTANT ==============================
 * WHY subscriberPorts (Block name -> Port name), not just subscriberBlockNames:
 * ALH has no sanctioned way to look up "the live instance of Block X" from
 * outside that instance's own running execution (confirmed via a full
 * javap -p dump of ALH's public API — no getObjectsOfType()/findByClassifier()
 * or similar exists). ALH.getContext() only ever resolves to "whichever
 * object is currently running this code" (ContextHelper.findNearestObject()).
 * So there is no way to know, in general, which live object corresponds to
 * a Block name discovered by the scanner.
 *
 * The workaround used here needs no such lookup: for a dequeued message,
 * try ALH.sendSignal() against EVERY Port name the scan recorded for that
 * topic (across every subscribing Block), using the CALLER's own target
 * (wherever this method was invoked from). ALH.sendSignal(signal, target,
 * portName) looks the named Port up ON the target object itself — a Port
 * name that belongs to a different Block simply isn't found there and
 * throws, caught and skipped here. Exactly one candidate is expected to
 * actually exist on a given target; the rest are for other placements and
 * fail harmlessly. This means a poll script still has to be physically
 * pasted somewhere with a repeating trigger (there's no way around that
 * either, same investigation), but it no longer needs to know which topics
 * or ports belong to it — every placement just tries everything the model
 * says exists, and only what's actually locally deliverable succeeds.
 *
 * WHY the caller's own ALH instance, not `new ALH(session)` here: ALH's
 * getContext()/createObject() etc. only resolve correctly when ALH is the
 * instance CST auto-injects into a running Opaque Behavior — an externally
 * constructed ALH's getContext() returns null (same finding documented in
 * FumlValueBridge.java). Both public methods below take the caller's own
 * `ALH alh` parameter (Groovy's auto-injected `ALH` variable, a plain
 * instance of this same public class) rather than constructing one, so
 * every ALH call here runs with the correct, already-resolved context.
 * =========================================================================
 */
package com.rti.connext.cameo.dds;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.simulation.utils.ALH;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import com.rti.connext.cameo.core.JsonPayloadBuilder;
import com.rti.connext.cameo.core.ModelTopicScanner;
import com.rti.connext.cameo.core.TopicModel;

import fUML.Semantics.Classes.Kernel.Object_;
import fUML.Semantics.CommonBehaviors.Communications.SignalInstance;

import java.util.Map;

public final class DdsInboundInjector {

    // Cached for the life of the current simulation run — the model's
    // DDS_Topic/Port wiring doesn't change mid-run, so re-scanning the
    // whole model on every poll cycle would be wasteful. Cleared by
    // shutdown(), same lifecycle as DDSTopicSubscriber/DDSTopicPublisher's
    // own caches.
    private static volatile TopicModel cachedModel;

    private DdsInboundInjector() {
    }

    /**
     * Subscribes to every «DDS_Topic»-stereotyped Signal the model wires as
     * inbound on any Block. Idempotent per topic (delegates to
     * DDSTopicSubscriber.subscribeOnce()) — safe, and expected, to call on
     * every poll cycle for self-healing, the same reason the old
     * per-topic-hardcoded version did. Does NOT touch any queued backlog —
     * see resetForNewRun() for that.
     */
    public static void subscribeAll() {
        TopicModel model = getOrScanTopicModel();
        for (TopicModel.Topic topic : model.topics.values()) {
            for (String blockName : topic.subscriberPorts.keySet()) {
                String participantConfigName = "DomainParticipantLibrary::" + blockName;
                String dataReaderName = "Subscriber::" + topic.topicName + "Reader";
                try {
                    DDSTopicSubscriber.subscribeOnce(participantConfigName, dataReaderName, topic.topicName);
                } catch (Exception ex) {
                    log("[DDS Inject] ERROR: subscribeOnce() failed for topic '" + topic.topicName
                            + "' (Block '" + blockName + "') -- " + ex);
                }
            }
        }
    }

    /**
     * Discards any backlog queued for every inbound topic before this run
     * starts polling (see DdsSubscriptionQueue.clear()'s doc comment for
     * why that matters). Call exactly ONCE, from
     * RegisterDdsEngineListener.groovy at simulation bootstrap — NOT from
     * the repeating poll script, which would wipe out legitimately-queued-
     * but-not-yet-drained messages every cycle.
     */
    public static void resetForNewRun() {
        TopicModel model = getOrScanTopicModel();
        for (TopicModel.Topic topic : model.topics.values()) {
            if (!topic.subscriberPorts.isEmpty()) {
                DdsSubscriptionQueue.clear(topic.topicName);
            }
        }
    }

    /**
     * Drains every subscribed topic's queue and injects each pending sample
     * as a live signal targeting {@code target}. See this class's header
     * comment for why {@code alh} must be the caller's own auto-injected
     * instance, and why every candidate Port for a topic is tried.
     */
    public static void pollAndInjectAll(ALH alh, Object_ target) {
        if (target == null) {
            log("[DDS Inject] ERROR: target is null -- this script isn't running inside a live simulation execution.");
            return;
        }
        TopicModel model = getOrScanTopicModel();
        for (TopicModel.Topic topic : model.topics.values()) {
            if (topic.subscriberPorts.isEmpty()) {
                continue;
            }
            DdsSubscriptionQueue.PendingMessage pending = DdsSubscriptionQueue.pollNext(topic.topicName);
            int count = 0;
            while (pending != null) {
                count++;
                injectOne(alh, target, topic, pending);
                pending = DdsSubscriptionQueue.pollNext(topic.topicName);
            }
            if (count > 1) {
                log("[DDS Inject] Drained " + count + " queued '" + topic.topicName + "' messages this cycle.");
            }
        }
    }

    private static void injectOne(ALH alh, Object_ target, TopicModel.Topic topic,
                                   DdsSubscriptionQueue.PendingMessage pending) {
        Map<String, Object> fields;
        try {
            fields = JsonPayloadBuilder.parseFlat(pending.jsonPayload);
        } catch (Exception ex) {
            log("[DDS Inject] ERROR: failed to parse queued JSON payload for '" + topic.topicName + "' -- " + ex);
            return;
        }

        Object_ payloadObject;
        try {
            payloadObject = alh.createObject(topic.typeName);
        } catch (Exception ex) {
            log("[DDS Inject] ERROR: ALH.createObject('" + topic.typeName + "') threw -- " + ex);
            return;
        }
        if (payloadObject == null) {
            log("[DDS Inject] ERROR: ALH.createObject('" + topic.typeName
                    + "') returned null -- either the Block wasn't found, or ALH's context is invalid.");
            return;
        }

        for (Map.Entry<String, Object> field : fields.entrySet()) {
            try {
                alh.setValue(payloadObject, field.getKey(), field.getValue());
            } catch (Exception ex) {
                log("[DDS Inject]   ERROR setting field '" + field.getKey() + "' = " + field.getValue() + " -- " + ex);
            }
        }

        SignalInstance signalInstance;
        try {
            signalInstance = alh.createSignal(topic.topicName);
        } catch (Exception ex) {
            log("[DDS Inject] ERROR: ALH.createSignal('" + topic.topicName + "') threw -- " + ex);
            return;
        }
        if (signalInstance == null) {
            log("[DDS Inject] ERROR: ALH.createSignal('" + topic.topicName + "') returned null -- Signal not found by name.");
            return;
        }

        try {
            alh.setValue(signalInstance, ModelTopicScanner.TOPIC_DATA_ATTRIBUTE_NAME, payloadObject);
        } catch (Exception ex) {
            log("[DDS Inject] ERROR: ALH.setValue(signalInstance, \"data\", payloadObject) threw -- " + ex);
            return;
        }

        // "dds-bus-listener" is target's own known inbound bus port
        // (DDS_Simulation_Environment now has two separate ports instead of
        // one shared "dds-bus" -- "dds-bus-listener" for incoming DDS
        // traffic, matching a service's own "port_in", and
        // "dds-bus-sender" for outgoing, matching "port_out". This method
        // only ever injects INTO the simulation, so it only ever needs the
        // listener side.) Neither is wired with a per-topic «FlowProperty»
        // the way port_in/port_out are, so this can't be discovered via the
        // structural scan the way topic.subscriberPorts is. Known-
        // convention port name, not derived.
        try {
            alh.sendSignal(signalInstance, target, "dds-bus-listener");
            log("[DDS Inject] SUCCESS: sent '" + topic.topicName + "' via port 'dds-bus-listener'.");
        } catch (Exception ex) {
            log("[DDS Inject] ERROR: ALH.sendSignal(signalInstance, target, \"dds-bus-listener\") threw -- " + ex);
        }
    }

    /** Clears the cached scan so the next call re-scans the model. Called
     *  alongside every other DDS shutdown hook (RTIConnextPlugin.close(),
     *  DdsEngineListener's executionTerminated() overloads). */
    public static void shutdown() {
        cachedModel = null;
    }

    private static TopicModel getOrScanTopicModel() {
        TopicModel model = cachedModel;
        if (model == null) {
            Project project = Application.getInstance().getProject();
            Element root = project.getPrimaryModel();
            model = ModelTopicScanner.scan(root);
            cachedModel = model;
        }
        return model;
    }

    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {
            System.out.println(msg);
        }
    }
}
