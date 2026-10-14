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
 * DdsEngineListener — publishes a DDS sample whenever the running simulation
 * sends a «DDS_Topic» Signal (CAMEO 2024x; the src26x copy differs only in
 * the SignalInstance import, which moved package in CST 2026x).
 *
 * Registered once per run by groovy/RegisterDdsEngineListener.groovy on both
 * the EngineListener and the SimulationExecutionListener of the session.
 *
 * How a publish works (verified against CST 2024x and 2026x):
 *   1. elementActivated() fires for the SendSignalAction. It carries the model
 *      element (public API), from which the participant and writer names are
 *      resolved, and queues a PendingPublish.
 *   2. eventTriggered(SignalInstance) fires right after with the actual
 *      Signal data. It is matched to the pending entry by Signal identity, and
 *      the "data" attribute is serialized to JSON and written via
 *      DDSTopicPublisher.
 *
 * API choices:
 *   - Only @OpenApiAll listener APIs are used. CST's InternalEngineListener
 *     (actionInvoked) is @InternalApi and is deliberately avoided.
 *   - Reading Signal field values requires CST's fUML value types, which are
 *     @InternalApi with no public alternative; that access is isolated in
 *     FumlValueBridge.
 *
 * Names follow DdsXmlGenerator's output:
 *   participant = "DomainParticipantLibrary::<publishing Block>"
 *   writer      = "Publisher::<Topic>Writer"
 */
package com.rti.connext.cameo.dds;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.simulation.engine.EngineListener;
import com.nomagic.magicdraw.simulation.execution.SimulationExecution;
import com.nomagic.magicdraw.simulation.execution.SimulationExecutionListener;
import com.nomagic.magicdraw.simulation.execution.session.SimulationSession;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.actions.mdbasicactions.SendSignalAction;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.EnumerationLiteral;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;

import com.rti.connext.cameo.core.JsonPayloadBuilder;
import com.rti.connext.cameo.core.ModelTopicScanner;
import com.rti.connext.cameo.core.TopicModel;

import fUML.Semantics.CommonBehaviors.Communications.SignalInstance;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;

// The SignalInstance type in eventTriggered()'s required override signature
// is marked deprecated by CST; overriding it is unavoidable.
@SuppressWarnings("deprecation")
public class DdsEngineListener extends SimulationExecutionListener implements EngineListener {

    private static final String BLOCK_STEREOTYPE = "Block";

    /** A send seen in elementActivated(), waiting for its SignalInstance in
     *  eventTriggered(); matched by Signal identity. */
    private static final class PendingPublish {
        final Signal signal;
        final String participantConfigName;
        final String dataWriterName;

        PendingPublish(Signal signal, String participantConfigName, String dataWriterName) {
            this.signal = signal;
            this.participantConfigName = participantConfigName;
            this.dataWriterName = dataWriterName;
        }
    }

    private final ConcurrentLinkedDeque<PendingPublish> pendingPublishes = new ConcurrentLinkedDeque<>();
    private volatile TopicModel cachedTopicModel;

    // Needed by FumlValueBridge to read field values through CST's ALH helper.
    // Null only if constructed without a session, in which case publish()
    // logs a clear error instead of failing.
    private final SimulationSession session;

    public DdsEngineListener() {
        this(null);
    }

    public DdsEngineListener(SimulationSession session) {
        this.session = session;
    }

    // -------------------------------------------------------------------
    // EngineListener. elementActivated() has the same signature in
    // SimulationExecutionListener, so this one method serves both.
    // -------------------------------------------------------------------

    @Override
    public void elementActivated(Element element, Collection<?> values) {
        try {
            if (!(element instanceof SendSignalAction)) {
                return;
            }
            Signal signal = ((SendSignalAction) element).getSignal();
            if (signal == null || !ModelTopicScanner.isDdsTopic(signal)) {
                return;
            }

            // The publisher comes from the structural scan (ports and
            // FlowProperties), because that is the same scan the DDS XML's
            // participants are generated from. The action's owning Block is
            // only a fallback: an action in a shared library activity has no
            // owning Block, and an action in a harness state machine is owned
            // by the harness, which is not a participant.
            String ownerBlockName = findPublishingBlockNameFromScan(signal);
            if (ownerBlockName == null) {
                Class owningBlock = findOwningBlock(element);
                ownerBlockName = (owningBlock != null) ? owningBlock.getName() : null;
            }
            if (ownerBlockName == null) {
                log("[DDS Listener] Could not determine an owning Block for SendSignalAction on '"
                        + signal.getName() + "' via either the ownership-chain walk or the structural Port scan "
                        + "— cannot determine participant name.");
                return;
            }
            String participantConfigName = "DomainParticipantLibrary::" + ownerBlockName;
            String dataWriterName = "Publisher::" + signal.getName() + "Writer";

            // Field values are only available from the SignalInstance in
            // eventTriggered(); queue what is known now.
            pendingPublishes.addLast(new PendingPublish(signal, participantConfigName, dataWriterName));
        } catch (Exception ex) {
            log("[DDS Listener] elementActivated failed: " + ex);
        }
    }

    @Override
    public void elementDeactivated(Element element, Collection<?> values) {
        // not needed
    }

    @Override
    public void eventTriggered(String event) {
        // not needed; eventTriggered(SignalInstance) below carries the data
    }

    @Override
    public void executionTerminated() {
        // DDS entities are torn down with the run, not kept for the next one:
        // a lingering participant keeps discovering and asserting liveliness
        // for as long as CAMEO is open, which users read as DDS still running.
        log("[DDS Listener] Simulation execution terminated — tearing down cached DDS participants.");
        DDSTopicSubscriber.shutdown();
        DDSTopicPublisher.shutdown();
        DdsInboundInjector.shutdown();
    }

    // -------------------------------------------------------------------
    // SimulationExecutionListener. Its callbacks default to no-ops; only the
    // ones the plugin needs are overridden.
    // -------------------------------------------------------------------

    @Override
    @SuppressWarnings("deprecation")
    public void eventTriggered(SignalInstance signalInstance) {
        try {
            Signal signal = FumlValueBridge.getSignal(signalInstance);
            if (signal == null) {
                return;
            }
            PendingPublish pending = takePending(signal);
            if (pending == null) {
                return; // not a «DDS_Topic» send queued by elementActivated()
            }
            publish(pending, signalInstance);
        } catch (Exception ex) {
            log("[DDS Listener] eventTriggered(SignalInstance) failed: " + ex);
        }
    }

    /**
     * Builds the JSON payload for a queued «DDS_Topic» send from the
     * SignalInstance's "data" attribute, then writes it to the topic's
     * DataWriter. Any failure is logged; the simulation keeps running.
     */
    private void publish(PendingPublish pending, Object signalInstance) {
        try {
            if (session == null) {
                log("[DDS Listener] DdsEngineListener was constructed without a SimulationSession — "
                        + "cannot use ALH to read field values. Update RegisterDdsEngineListener.groovy to pass "
                        + "_session_ into the constructor. Publish skipped.");
                return;
            }
            TopicModel model = getOrScanTopicModel();
            TopicModel.Topic topic = model.topics.get(pending.signal.getName());
            if (topic == null) {
                log("[DDS Listener] No scanned Topic found for Signal '" + pending.signal.getName()
                        + "' — cannot build payload. (Model may have changed since the listener was registered.)");
                return;
            }
            TopicModel.StructType type = model.types.get(topic.typeName);
            if (type == null) {
                log("[DDS Listener] No scanned StructType '" + topic.typeName + "' for Topic '"
                        + topic.topicName + "' — cannot build payload.");
                return;
            }

            // The payload's fields live on the Signal's "data" attribute, one
            // level below the SignalInstance.
            Object payload = FumlValueBridge.getFieldValue(session, signalInstance, ModelTopicScanner.TOPIC_DATA_ATTRIBUTE_NAME);
            if (payload == null) {
                log("[DDS Listener] SignalInstance for '" + pending.signal.getName() + "' has no '"
                        + ModelTopicScanner.TOPIC_DATA_ATTRIBUTE_NAME + "' attribute value — cannot build payload.");
                return;
            }

            JsonPayloadBuilder builder = JsonPayloadBuilder.create();
            for (TopicModel.Field field : type.fields) {
                appendField(model, builder, field, payload);
            }
            String json = builder.build();

            log("[DDS Listener] Publishing " + pending.dataWriterName + ": " + json);
            DDSTopicPublisher.publishOnce(pending.participantConfigName, pending.dataWriterName, json);
            log("[DDS Listener] Published to " + pending.dataWriterName + ".");
        } catch (Exception ex) {
            log("[DDS Listener] Publish failed for Signal '" + pending.signal.getName() + "' ("
                    + pending.participantConfigName + " / " + pending.dataWriterName + "): " + ex);
        }
    }

    private void appendField(TopicModel model, JsonPayloadBuilder builder, TopicModel.Field field, Object owner) {
        Object raw;
        try {
            raw = FumlValueBridge.getFieldValue(session, owner, field.name);
        } catch (Exception ex) {
            log("[DDS Listener]   field '" + field.name + "' extraction failed: " + ex);
            return;
        }
        if (field.isNested()) {
            TopicModel.StructType nestedType = model.types.get(field.nestedStructName);
            if (nestedType == null || raw == null) {
                log("[DDS Listener]   nested field '" + field.name + "' — no StructType or null value, skipped.");
                return;
            }
            JsonPayloadBuilder nested = JsonPayloadBuilder.create();
            for (TopicModel.Field nestedField : nestedType.fields) {
                appendField(model, nested, nestedField, raw);
            }
            builder.addRaw(field.name, nested.build());
            return;
        }

        // Serialization follows the field's declared DDS type, not the Java
        // type CST happens to return: a value set from a number into a
        // string-typed property comes back as a Double, and RTI rejects the
        // whole sample (RETCODE_BAD_PARAMETER) if a string member arrives as a
        // JSON number. Fixed-size arrays come back as java.util.List.
        if (field.isArray()) {
            builder.addRaw(field.name, arrayToJson(field, raw));
            return;
        }

        if (raw == null) {
            builder.addRaw(field.name, "null");
        } else if ("string".equals(field.primitiveType)) {
            String stringValue = (raw instanceof EnumerationLiteral)
                    ? ((EnumerationLiteral) raw).getName()
                    : (raw instanceof Number) ? plainNumber((Number) raw) : String.valueOf(raw);
            builder.addString(field.name, stringValue);
        } else if (raw instanceof String) {
            builder.addString(field.name, (String) raw);
        } else if (raw instanceof Boolean || raw instanceof Number) {
            builder.addRaw(field.name, raw.toString());
        } else if (raw instanceof EnumerationLiteral) {
            builder.addString(field.name, ((EnumerationLiteral) raw).getName());
        } else {
            log("[DDS Listener]   field '" + field.name + "' has unexpected value type "
                    + raw.getClass().getName() + " — sending toString() as a JSON string.");
            builder.addString(field.name, String.valueOf(raw));
        }
    }

    /** A fixed-size array field (a java.util.List from CST) as a JSON array;
     *  RTI requires a real array, not a stringified list, for an
     *  arrayDimensions member. */
    private String arrayToJson(TopicModel.Field field, Object raw) {
        if (!(raw instanceof java.util.List)) {
            if (raw != null) {
                log("[DDS Listener]   array field '" + field.name + "' has unexpected value type "
                        + raw.getClass().getName() + " — expected a List, sending empty array.");
            }
            return "[]";
        }
        java.util.List<?> list = (java.util.List<?>) raw;
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) json.append(",");
            json.append(arrayElementToJson(list.get(i)));
        }
        json.append("]");
        return json.toString();
    }

    private String arrayElementToJson(Object element) {
        if (element == null) {
            return "null";
        } else if (element instanceof Boolean || element instanceof Number) {
            return element.toString();
        } else if (element instanceof EnumerationLiteral) {
            return JsonPayloadBuilder.quote(((EnumerationLiteral) element).getName());
        } else {
            return JsonPayloadBuilder.quote(String.valueOf(element));
        }
    }

    /**
     * Text for a number going into a DDS string field, without Java's
     * scientific notation: a timestamp that reaches us as the Double
     * 1.791952739611E12 is written "1791952739611", and 0.25 stays "0.25".
     */
    private static String plainNumber(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return String.valueOf(d);
            }
            return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return n.toString();
    }

    private PendingPublish takePending(Signal signal) {
        Iterator<PendingPublish> it = pendingPublishes.iterator();
        while (it.hasNext()) {
            PendingPublish pending = it.next();
            if (pending.signal == signal) {
                it.remove();
                return pending;
            }
        }
        return null;
    }

    private TopicModel getOrScanTopicModel() {
        TopicModel generated = DdsRuntimeConfig.currentModel();
        if (generated != null) {
            return generated; // the same scan this run's DDS XML was generated from
        }
        TopicModel model = cachedTopicModel;
        if (model == null) {
            Project project = Application.getInstance().getProject();
            Element root = project.getPrimaryModel();
            model = ModelTopicScanner.scan(root);
            cachedTopicModel = model;
        }
        return model;
    }

    @Override
    public void executionTerminated(SimulationExecution execution) {
        // Both termination callbacks can fire; each shutdown() is idempotent.
        DDSTopicSubscriber.shutdown();
        DDSTopicPublisher.shutdown();
        DdsInboundInjector.shutdown();
    }

    /** Fallback publisher lookup: the nearest «Block» owning the action. See
     *  elementActivated() for why the structural scan comes first. */
    private static Class findOwningBlock(Element element) {
        Element owner = element.getOwner();
        while (owner != null) {
            if (owner instanceof Class && StereotypesHelper.hasStereotype((Class) owner, BLOCK_STEREOTYPE)) {
                return (Class) owner;
            }
            owner = owner.getOwner();
        }
        return null;
    }

    /**
     * The Block that structurally publishes {@code signal}'s topic (an "out"
     * FlowProperty on one of its ports), or null if there is none or more than
     * one. This is the primary source because the generated DDS XML defines
     * participants from the same scan.
     */
    private String findPublishingBlockNameFromScan(Signal signal) {
        TopicModel model = getOrScanTopicModel();
        TopicModel.Topic topic = model.topics.get(signal.getName());
        if (topic == null || topic.publisherBlockNames.isEmpty()) {
            log("[DDS Listener] No Port/FlowProperty publisher found for Signal '" + signal.getName()
                    + "' — falling back to the SendSignalAction's owning Block.");
            return null;
        }
        if (topic.publisherBlockNames.size() > 1) {
            log("[DDS Listener] WARNING: " + topic.publisherBlockNames.size() + " Blocks publish Signal '"
                    + signal.getName() + "' " + topic.publisherBlockNames
                    + " — can't tell which one sent it; falling back to the SendSignalAction's owning Block.");
            return null;
        }
        return topic.publisherBlockNames.get(0);
    }

    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {
            System.out.println(msg);
        }
    }
}
