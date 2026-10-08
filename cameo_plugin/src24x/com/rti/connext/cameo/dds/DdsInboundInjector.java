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
 * WHICH PORT TO SEND THROUGH: ALH has no sanctioned way to look up "the live
 * instance of Block X" from outside that instance's own running execution
 * (confirmed via a full javap -p dump of ALH's public API — no
 * getObjectsOfType()/findByClassifier() or similar exists). ALH.getContext()
 * only ever resolves to "whichever object is currently running this code"
 * (ContextHelper.findNearestObject()) — in practice the DDS bus / test
 * environment Block the poll script is placed on, not the subscriber.
 *
 * So injection goes the way a real signal would: out of that environment
 * Block's own Port and along the modeled IBD connector into the subscriber.
 * The Port names come from ModelTopicScanner (Topic.injectionPorts — the far
 * end of each connector wired into a subscriber's inbound Port from
 * outside), so nothing is hardcoded and the environment's Ports can be named
 * anything and left untyped: only the subscriber's side is part of the
 * reviewed spec.
 *
 * ONE PATH PER SUBSCRIBING BLOCK: each Block that subscribes to a topic has
 * its own DataReader (in its own participant) feeding its own queue
 * (DdsSubscriptionQueue.key(block, topic)), and its samples are sent only
 * through the Port(s) connected to THAT Block. Two modeled Blocks receiving
 * the same topic therefore each get exactly one copy of every sample, and
 * each shows up on the DDS bus as its own subscriber — the same behavior as
 * two separate DDS applications (e.g. an external display) subscribing. The poll script still has to be physically placed on that
 * environment Block with a repeating trigger (no way around that, same
 * investigation).
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
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import com.rti.connext.cameo.core.ModelTopicScanner;
import com.rti.connext.cameo.core.SimulationScope;
import com.rti.connext.cameo.core.TopicModel;

import fUML.Semantics.Classes.Kernel.Object_;
import fUML.Semantics.CommonBehaviors.Communications.SignalInstance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DdsInboundInjector {

    // Cached for the life of the current simulation run — the model's
    // DDS_Topic/Port wiring doesn't change mid-run, so re-scanning the
    // whole model on every poll cycle would be wasteful. Cleared by
    // shutdown(), same lifecycle as DDSTopicSubscriber/DDSTopicPublisher's
    // own caches.
    private static volatile TopicModel cachedModel;

    // Names of the Blocks in the running simulation (see SimulationScope),
    // set once per run by setSimulationScope(). Inbound subscriptions are
    // only started for these, so a DDS Block elsewhere in the project that
    // isn't being simulated stays off the bus. Null = no scope was set (an
    // older registration script): every subscribing Block is used.
    private static volatile Set<String> simulationScope;

    private DdsInboundInjector() {
    }

    /**
     * Restricts inbound DDS to the Blocks in the simulation started on
     * {@code simulatedElement} (SimulationSession.getElement() — a Block, a
     * «SimulationConfig», ...). Call once at simulation bootstrap, before
     * resetForNewRun()/subscribeAll(), from RegisterDdsEngineListener.groovy.
     */
    public static void setSimulationScope(Element simulatedElement) {
        Class root = SimulationScope.resolveRootBlock(simulatedElement);
        if (root == null) {
            simulationScope = null;
            log("[DDS Inject] WARNING: could not find the Block being simulated (from '"
                    + simulatedElement + "') -- subscribing for every DDS Block in the project.");
            return;
        }
        Set<String> scope = SimulationScope.blockNamesIn(root);
        simulationScope = scope;

        Set<String> subscribersInScope = new LinkedHashSet<>();
        for (TopicModel.Topic topic : getOrScanTopicModel().topics.values()) {
            for (String blockName : topic.subscriberPorts.keySet()) {
                if (scope.contains(blockName)) {
                    subscribersInScope.add(blockName);
                }
            }
        }
        log("[DDS Inject] Simulating '" + root.getName() + "' -- DDS subscribers in this simulation: "
                + (subscribersInScope.isEmpty() ? "none" : subscribersInScope));
    }

    private static boolean inScope(String blockName) {
        Set<String> scope = simulationScope;
        return scope == null || scope.contains(blockName);
    }

    /**
     * Subscribes to every «DDS_Topic»-stereotyped Signal the model wires as
     * inbound on a Block in the simulation scope. Idempotent per topic (delegates to
     * DDSTopicSubscriber.subscribeOnce()) — safe, and expected, to call on
     * every poll cycle for self-healing, the same reason the old
     * per-topic-hardcoded version did. Does NOT touch any queued backlog —
     * see resetForNewRun() for that.
     */
    public static void subscribeAll() {
        TopicModel model = getOrScanTopicModel();
        for (TopicModel.Topic topic : model.topics.values()) {
            for (String blockName : topic.subscriberPorts.keySet()) {
                if (!inScope(blockName)) {
                    continue;
                }
                String participantConfigName = "DomainParticipantLibrary::" + blockName;
                String dataReaderName = "Subscriber::" + topic.topicName + "Reader";
                try {
                    DDSTopicSubscriber.subscribeOnce(participantConfigName, dataReaderName, topic.topicName,
                            DdsSubscriptionQueue.key(blockName, topic.topicName));
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
            for (String blockName : topic.subscriberPorts.keySet()) {
                if (!inScope(blockName)) {
                    continue;
                }
                DdsSubscriptionQueue.clear(DdsSubscriptionQueue.key(blockName, topic.topicName));
            }
        }
    }

    /**
     * Drains every subscribed topic's queue and injects each pending sample
     * as a live signal targeting {@code target}. See this class's header
     * comment for why {@code alh} must be the caller's own auto-injected
     * instance, and which Port each topic is sent through.
     */
    public static void pollAndInjectAll(ALH alh, Object_ target) {
        if (target == null) {
            log("[DDS Inject] ERROR: target is null -- this script isn't running inside a live simulation execution.");
            return;
        }
        TopicModel model = getOrScanTopicModel();
        for (TopicModel.Topic topic : model.topics.values()) {
            for (String blockName : topic.subscriberPorts.keySet()) {
                if (!inScope(blockName)) {
                    continue;
                }
                String queueKey = DdsSubscriptionQueue.key(blockName, topic.topicName);
                DdsSubscriptionQueue.PendingMessage pending = DdsSubscriptionQueue.pollNext(queueKey);
                int count = 0;
                while (pending != null) {
                    count++;
                    injectOne(alh, target, model, topic, blockName, pending);
                    pending = DdsSubscriptionQueue.pollNext(queueKey);
                }
                if (count > 1) {
                    log("[DDS Inject] Drained " + count + " queued '" + queueKey + "' messages this cycle.");
                }
            }
        }
    }

    private static void injectOne(ALH alh, Object_ target, TopicModel model, TopicModel.Topic topic,
                                   String blockName, DdsSubscriptionQueue.PendingMessage pending) {
        Set<String> ports = topic.injectionPorts.get(blockName);
        if (ports == null || ports.isEmpty()) {
            log("[DDS Inject] ERROR: nothing is connected to '" + blockName + "." + topic.subscriberPorts.get(blockName)
                    + "' (receives '" + topic.topicName + "') -- connect it, in an IBD, to a Port on the Block"
                    + " running the poll script.");
            return;
        }
        TopicModel.StructType type = model.types.get(topic.typeName);
        if (type == null) {
            log("[DDS Inject] ERROR: no scanned type '" + topic.typeName + "' for topic '" + topic.topicName + "'.");
            return;
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(pending.jsonPayload).getAsJsonObject();
        } catch (Exception ex) {
            log("[DDS Inject] ERROR: failed to parse queued JSON payload for '" + topic.topicName + "' -- " + ex);
            return;
        }

        for (String portName : ports) {
            SignalInstance signalInstance = buildSignal(alh, model, topic, type, json);
            if (signalInstance == null) {
                return; // already logged; would fail the same way for every port
            }
            try {
                alh.sendSignal(signalInstance, target, portName);
                log("[DDS Inject] Injected '" + topic.topicName + "' into '" + blockName + "' via port '" + portName + "'.");
            } catch (Exception ex) {
                log("[DDS Inject] ERROR: sending '" + topic.topicName + "' via port '" + portName
                        + "' failed -- is the poll script running on the Block that owns this Port? " + ex);
            }
        }
    }

    /**
     * Builds a fresh SignalInstance for {@code topic} whose "data" attribute
     * holds a new payload object filled from {@code json}. Returns null
     * (after logging why) if any step fails. Called once per send so that
     * each Port gets its own copy rather than sharing one object (a Block
     * normally has one connected Port, but nothing stops a model wiring two).
     */
    private static SignalInstance buildSignal(ALH alh, TopicModel model, TopicModel.Topic topic,
                                              TopicModel.StructType type, JsonObject json) {
        Object_ payloadObject = createAndFill(alh, model, type, json);
        if (payloadObject == null) {
            return null;
        }

        SignalInstance signalInstance;
        try {
            signalInstance = alh.createSignal(topic.topicName);
        } catch (Exception ex) {
            log("[DDS Inject] ERROR: ALH.createSignal('" + topic.topicName + "') threw -- " + ex);
            return null;
        }
        if (signalInstance == null) {
            log("[DDS Inject] ERROR: ALH.createSignal('" + topic.topicName + "') returned null -- Signal not found by name.");
            return null;
        }

        try {
            alh.setValue(signalInstance, ModelTopicScanner.TOPIC_DATA_ATTRIBUTE_NAME, payloadObject);
        } catch (Exception ex) {
            log("[DDS Inject] ERROR: ALH.setValue(signalInstance, \"data\", payloadObject) threw -- " + ex);
            return null;
        }
        return signalInstance;
    }

    /**
     * Creates an instance of the Block behind {@code type} and sets each of
     * its fields from {@code json}. A Block-typed (nested) field needs its
     * own instance first — toSimValue() builds it by calling back into this
     * method — so any nesting depth works. Returns null (after logging) if
     * the instance itself can't be created; a single bad field is logged and
     * skipped.
     */
    private static Object_ createAndFill(ALH alh, TopicModel model, TopicModel.StructType type, JsonObject json) {
        Object_ instance;
        try {
            instance = alh.createObject(type.name);
        } catch (Exception ex) {
            log("[DDS Inject] ERROR: ALH.createObject('" + type.name + "') threw -- " + ex);
            return null;
        }
        if (instance == null) {
            log("[DDS Inject] ERROR: ALH.createObject('" + type.name
                    + "') returned null -- either the Block wasn't found, or ALH's context is invalid.");
            return null;
        }

        for (TopicModel.Field field : type.fields) {
            JsonElement value = json.get(field.name);
            if (value == null || value.isJsonNull()) {
                continue;
            }
            try {
                alh.setValue(instance, field.name, toSimValue(alh, model, field, value));
            } catch (Exception ex) {
                log("[DDS Inject]   ERROR setting " + type.name + "." + field.name + " = " + value + " -- " + ex);
            }
        }
        return instance;
    }

    /** Converts one JSON value to what ALH.setValue() expects for {@code
     *  field}, following the model's declared type rather than the JSON's
     *  shape (e.g. a float64 sent as 7 still becomes 7.0). An array field
     *  becomes a List of converted elements. */
    private static Object toSimValue(ALH alh, TopicModel model, TopicModel.Field field, JsonElement value) {
        if (field.isArray()) {
            List<Object> items = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) {
                items.add(toSingleValue(alh, model, field, item));
            }
            return items;
        }
        return toSingleValue(alh, model, field, value);
    }

    private static Object toSingleValue(ALH alh, TopicModel model, TopicModel.Field field, JsonElement value) {
        if (field.isNested()) {
            TopicModel.StructType nestedType = model.types.get(field.nestedStructName);
            if (nestedType == null) {
                throw new IllegalStateException("no scanned type '" + field.nestedStructName + "'");
            }
            Object_ nested = createAndFill(alh, model, nestedType, value.getAsJsonObject());
            if (nested == null) {
                throw new IllegalStateException("could not create '" + field.nestedStructName + "'");
            }
            return nested;
        }
        if (field.isEnum()) {
            return value.getAsString(); // RTI's JSON writes enums as their literal name
        }
        switch (field.primitiveType == null ? "string" : field.primitiveType) {
            case "int32":
                return value.getAsLong();
            case "float64":
                return value.getAsDouble();
            case "boolean":
                return value.getAsBoolean();
            default:
                return value.getAsString();
        }
    }

    /** Clears the cached scan so the next call re-scans the model. Called
     *  alongside every other DDS shutdown hook (RTIConnextPlugin.close(),
     *  DdsEngineListener's executionTerminated() overloads). */
    public static void shutdown() {
        cachedModel = null;
        simulationScope = null;
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
