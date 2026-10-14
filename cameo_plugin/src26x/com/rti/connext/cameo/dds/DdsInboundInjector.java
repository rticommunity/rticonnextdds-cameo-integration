/*
 * (c) Copyright, Real-Time Innovations, 2026.  All rights reserved.
 * RTI grants Licensee a license to use, modify, compile, and create derivative
 * works of the software solely for use with RTI Connext DDS. Licensee may
 * redistribute copies of the software provided that all such copies are subject
 * to this license. The software is provided "as is", with no warranty of any
 * type, including any warranty for fitness for any purpose. RTI is under no
 * obligation to maintain or support the software. RTI shall not be liable for
 * any incidental or consequential damages arising out of the use or inability
 * to use the software.
 */

/*
 * DdsInboundInjector — injects DDS samples received for the running
 * simulation into it as Signals (CAMEO 2026x; the src24x copy differs only in
 * the fUML imports, which moved package in CST 2026x).
 *
 * Driven entirely by ModelTopicScanner: every «DDS_Topic» Signal that a Block
 * in the simulation receives (an "in" FlowProperty on one of its ports) is
 * subscribed and injected; no topic, participant, reader, type or port name
 * is hardcoded here or in the Groovy scripts that call this class.
 *
 * Delivery path. CST's ALH has no public way to find "the live instance of
 * Block X" from outside it, and ALH.getContext() is the object running the
 * calling script: the harness Block the poll script runs on. So a sample is
 * delivered the way a real signal would be: sent out of the harness port that
 * the model connects (IBD connector) to the subscriber's inbound port. The
 * harness port may be untyped and named anything.
 *
 * One path per subscribing Block. Each subscribing Block has its own
 * DataReader (in its own participant) feeding its own queue
 * (DdsSubscriptionQueue.key(block, topic)), injected only through the ports
 * connected to that Block — so two modeled Blocks receiving the same topic
 * each get exactly one copy, like two separate DDS applications would.
 *
 * The caller passes its own ALH: only the instance CST injects into a running
 * Opaque Behavior resolves getContext()/createObject() correctly; a
 * separately constructed ALH returns null for getContext().
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

import com.nomagic.magicdraw.simulation.fuml.classes.Object_;
import com.nomagic.magicdraw.simulation.fuml.behaviors.communications.SignalInstance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DdsInboundInjector {

    // Fallback scan, cached for the run (the model doesn't change mid-run).
    // Normally DdsRuntimeConfig's scan is used instead; see
    // getOrScanTopicModel().
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
     * Restricts inbound DDS to the Blocks in the running simulation. Call once
     * at simulation bootstrap, before resetForNewRun()/subscribeAll(), from
     * RegisterDdsEngineListener.groovy, passing ALH.getContext(): the running
     * object, whose Block type is the root of the simulation. That has to be
     * the running object, not the behavior being executed — a state machine
     * inherited from a harness Block is owned by the harness, while the object
     * running it is the specialized Test Block.
     *
     * Also accepts a model element (SimulationSession.getElement(): a Block,
     * a «SimulationConfig», ...), as older copies of the script pass.
     */
    public static void setSimulationScope(Object simulated) {
        Class root = (simulated instanceof Element)
                ? SimulationScope.resolveRootBlock((Element) simulated)
                : FumlValueBridge.getBlockType(simulated);
        if (root == null) {
            simulationScope = null;
            log("[DDS Inject] WARNING: could not find the Block being simulated (from '"
                    + simulated + "') -- subscribing for every DDS Block in the project.");
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
     * Subscribes every in-scope Block to each «DDS_Topic» it receives.
     * Idempotent (DDSTopicSubscriber.subscribeOnce()), and called on every
     * poll cycle on purpose: the registration script's own short-lived
     * execution tears subscriptions down right after creating them, and the
     * next cycle restores them. Doesn't touch queued samples; see
     * resetForNewRun().
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
     * Discards samples queued before this run (see
     * DdsSubscriptionQueue.clear()). Call once, from the registration script —
     * not from the poll script, which would drop samples still waiting to be
     * injected.
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
     * Drains every in-scope queue and injects each sample as a Signal sent
     * from {@code target} (the harness object). See the class comment for why
     * {@code alh} must be the caller's own instance.
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

    /** Clears the cached scan. Called with the other DDS shutdown hooks
     *  (RTIConnextPlugin.close(), DdsEngineListener.executionTerminated()). */
    public static void shutdown() {
        cachedModel = null;
        // simulationScope is deliberately kept: the registration script's own
        // short-lived execution ends (and triggers this) right after setting
        // it, before the poll script re-subscribes. The next run's
        // setSimulationScope() replaces it.
    }

    private static TopicModel getOrScanTopicModel() {
        TopicModel generated = DdsRuntimeConfig.currentModel();
        if (generated != null) {
            return generated; // the same scan this run's DDS XML was generated from
        }
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
