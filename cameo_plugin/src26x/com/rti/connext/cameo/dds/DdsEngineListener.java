/*
 * DdsEngineListener.java — CAMEO 2026x variant. See
 * src24x/.../DdsEngineListener.java for the 2024x original this was ported
 * from, and for the full investigation history behind this design (kept
 * there in full rather than duplicated here). Picked up by build_26x.bat;
 * build.bat picks up the src24x/ copy instead.
 *
 * ONLY DIFFERENCES FROM THE 2024x VERSION, both confirmed via `javap -p`
 * against CST 2026x Refresh1's simulation.toolkit.core jar (2026.1.0) vs
 * 2024x's (2024.3.0):
 *   1. fUML runtime value types (Object_, StructuredValue, FeatureValue,
 *      SignalInstance) moved out of the shared `fUML.Semantics.*` namespace
 *      into NoMagic's own `com.nomagic.magicdraw.simulation.fuml.*`
 *      namespace.
 *   2. SimulationExecutionListener.operationCalled()/behaviorCalled()'s
 *      `ParameterValueList` parameter type is GONE entirely in 2026x —
 *      replaced by `java.util.List<ParameterValue>` (a plain generic list of
 *      a differently-shaped ParameterValue type). Both overrides' signatures
 *      below are updated to match; the diagnostic-only bodies are unchanged.
 * No other logic, method names, or ALH usage changed between the two
 * versions.
 *
 * publishes to DDS whenever a SendSignalAction on a «DDS_Topic»-stereotyped
 * Signal fires during a Cameo Simulation Toolkit run.
 *
 * Registration: see scripts/RegisterDdsEngineListener.groovy — a Groovy
 * Opaque Behavior body that must run exactly ONCE at simulation start.
 *
 * DESIGN: elementActivated() fires first (with the Element/owning-Block
 * context, all public API) and queues a PendingPublish; eventTriggered(
 * SignalInstance) fires shortly after (with the actual field data, only
 * reachable via FumlValueBridge) and matches it back up by Signal identity,
 * then builds the JSON payload and calls DDSTopicPublisher.publishOnce().
 * participantConfigName/dataWriterName resolution is verified against
 * DdsXmlGenerator's actual output:
 *   participantConfigName = "DomainParticipantLibrary::<BlockName>"
 *   dataWriterName         = "Publisher::<TopicName>Writer"
 *
 * Field-value extraction: every route to the payload is @InternalApi+
 * @Deprecated — no supported alternative exists. Rather than drop payload
 * publishing entirely, this is the one deliberately-accepted internal-API
 * dependency in this plugin, isolated to FumlValueBridge.java (see that
 * file's header) — every other class here only ever touches plain
 * java.lang.Object/String/Number/Boolean and the public Signal type.
 * =========================================================================
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
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Operation;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdbasicbehaviors.Behavior;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.Connector;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdports.Port;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.State;

import com.rti.connext.cameo.core.JsonPayloadBuilder;
import com.rti.connext.cameo.core.ModelTopicScanner;
import com.rti.connext.cameo.core.TopicModel;

import com.nomagic.magicdraw.simulation.fuml.classes.FeatureValue;
import com.nomagic.magicdraw.simulation.fuml.classes.Object_;
import com.nomagic.magicdraw.simulation.fuml.classes.StructuredValue;
import com.nomagic.magicdraw.simulation.fuml.behaviors.ParameterValue;
import com.nomagic.magicdraw.simulation.fuml.behaviors.communications.SignalInstance;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

// @SuppressWarnings("deprecation") at the class level: every remaining
// warning below comes from SimulationExecutionListener's required override
// signatures (operationCalled, behaviorCalled, objectCreated, valueChange,
// busyStatusChange, objectStateActivated, beforeObjectDestroyed, ...) which
// must declare the exact fUML parameter types the base class uses, even
// though these overrides are diagnostic-only no-ops. This is a different,
// unavoidable category from FumlValueBridge's voluntary internal-API use —
// see that file's header for the real internal-API dependency this plugin
// accepts.
@SuppressWarnings("deprecation")
public class DdsEngineListener extends SimulationExecutionListener implements EngineListener {

    private static final String BLOCK_STEREOTYPE = "Block";

    /** Queued between elementActivated() (has the Element/Block/Signal
     *  identity, all public API) and eventTriggered(SignalInstance) (has the
     *  actual field data, only reachable via FumlValueBridge) — matched back
     *  up by Signal identity. */
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

    // Needed to construct ALH (com.nomagic.magicdraw.simulation.utils.ALH) —
    // NoMagic's own @OpenApiAll scripting helper, used by FumlValueBridge
    // instead of calling the internal StructuredValue.get() directly. Null
    // if constructed via the no-arg constructor (field extraction will fail
    // cleanly with a clear log line rather than NPE — see publish()).
    private final SimulationSession session;

    public DdsEngineListener() {
        this(null);
    }

    public DdsEngineListener(SimulationSession session) {
        this.session = session;
    }

    // -------------------------------------------------------------------
    // EngineListener (public, @OpenApiAll) — elementActivated's signature is
    // IDENTICAL to SimulationExecutionListener's own elementActivated(
    // Element, Collection<?>), so this single method also serves as that
    // override.
    // -------------------------------------------------------------------

    @Override
    public void elementActivated(Element element, Collection<?> values) {
        try {
            try {
                String className = element == null ? "null" : element.getClass().getName();
                String elementName = (element instanceof NamedElement) ? ((NamedElement) element).getName() : null;
                log("[DDS Listener DIAG] elementActivated: class=" + className
                        + " name=" + elementName + " values.size()=" + values.size());
            } catch (Exception diagEx) {
                log("[DDS Listener DIAG] diagnostic logging itself failed: " + diagEx);
            }

            // ---- TEMPORARY DIAGNOSTIC (PORT-DIAG experiment) — testing
            // whether observing Port/Connector activations (where a
            // SignalInstance actually crosses between Parts via an IBD
            // connector) sees fresher/different data than SendSignalAction-
            // level reading, which is blocked for Set_Object_Value-populated
            // fields (see FumlValueBridge.java). Purely additive — does not
            // affect the SendSignalAction pipeline below in any way, and
            // runs regardless of whether `element` turns out to be a
            // SendSignalAction.
            if (element instanceof Port || element instanceof Connector) {
                try {
                    String kind = (element instanceof Port) ? "Port" : "Connector";
                    String portElementName = (element instanceof NamedElement)
                            ? ((NamedElement) element).getName() : null;
                    log("[DDS Listener PORT-DIAG] " + kind + " activated: name=" + portElementName
                            + " values.size()=" + values.size());
                    int k = 0;
                    for (Object value : values) {
                        try {
                            log("[DDS Listener PORT-DIAG]   values[" + k + "] class="
                                    + (value == null ? "null" : value.getClass().getName())
                                    + " toString=" + value);
                        } catch (Exception ex) {
                            log("[DDS Listener PORT-DIAG]   values[" + k + "] — error reading value: " + ex);
                        }

                        Object tokenValue;
                        try {
                            tokenValue = FumlValueBridge.getTokenValue(value);
                        } catch (Exception ex) {
                            log("[DDS Listener PORT-DIAG]   values[" + k + "] getTokenValue() failed: " + ex);
                            k++;
                            continue;
                        }
                        if (tokenValue == null) {
                            k++;
                            continue;
                        }
                        try {
                            log("[DDS Listener PORT-DIAG]   values[" + k + "] token value: class="
                                    + tokenValue.getClass().getName() + " toString=" + tokenValue);
                        } catch (Exception ex) {
                            log("[DDS Listener PORT-DIAG]   values[" + k + "] token value — error reading: " + ex);
                        }

                        try {
                            List<String> reflectionDump = FumlValueBridge.dumpFieldsViaReflection(tokenValue);
                            if (reflectionDump.isEmpty()) {
                                log("[DDS Listener PORT-DIAG]   values[" + k
                                        + "] token value is not a StructuredValue — no reflection dump.");
                            } else {
                                log("[DDS Listener PORT-DIAG]   values[" + k + "] reflection dump ("
                                        + reflectionDump.size() + " fields):");
                                for (String line : reflectionDump) {
                                    log("[DDS Listener PORT-DIAG]     " + line);
                                }
                            }
                        } catch (Exception ex) {
                            log("[DDS Listener PORT-DIAG]   values[" + k + "] reflection dump failed: " + ex);
                        }
                        k++;
                    }
                } catch (Exception ex) {
                    log("[DDS Listener PORT-DIAG] Port/Connector diagnostic failed: " + ex);
                }
            }

            if (!(element instanceof SendSignalAction)) {
                return;
            }
            SendSignalAction action = (SendSignalAction) element;
            Signal signal = action.getSignal();
            if (signal == null) {
                return;
            }
            if (!ModelTopicScanner.isDdsTopic(signal)) {
                return;
            }

            log("[DDS Listener] SendSignalAction fired for Topic Signal '"
                    + signal.getName() + "' — values.size()=" + values.size());
            int i = 0;
            for (Object value : values) {
                try {
                    log("[DDS Listener]   values[" + i + "] class="
                            + (value == null ? "null" : value.getClass().getName())
                            + " toString=" + value);
                } catch (Exception diagEx) {
                    log("[DDS Listener]   values[" + i + "] — error reading value: " + diagEx);
                }
                i++;
            }

            // ---- TEMPORARY DIAGNOSTIC — testing whether the argument value
            // AS IT EXISTED WHEN THIS ACTION ACTIVATED (unwrapped from its
            // ObjectToken, before any copy-into-SignalInstance UML signal
            // sending may do) already carries the data Set_Object_Value
            // wrote, where eventTriggered(SignalInstance)'s post-copy value
            // came back empty for every key tried. See FumlValueBridge's
            // getTokenValue() header.
            if (session != null) {
                int j = 0;
                for (Object value : values) {
                    Object tokenValue = FumlValueBridge.getTokenValue(value);
                    if (tokenValue == null) {
                        j++;
                        continue;
                    }
                    log("[DDS Listener DIAG] values[" + j + "] token value: class="
                            + tokenValue.getClass().getName() + " toString=" + tokenValue);
                    try {
                        Object flat = FumlValueBridge.getFieldValue(session, tokenValue, "data.measurement_frame");
                        log("[DDS Listener DIAG] values[" + j + "] token value .get(\"data.measurement_frame\") = "
                                + (flat == null ? "null" : flat.getClass().getName() + " toString=" + flat));
                    } catch (Exception ex) {
                        log("[DDS Listener DIAG] values[" + j + "] token value flat-key probe failed: " + ex);
                    }
                    try {
                        Object direct = FumlValueBridge.getFieldValue(session, tokenValue, "measurement_frame");
                        log("[DDS Listener DIAG] values[" + j + "] token value .get(\"measurement_frame\") = "
                                + (direct == null ? "null" : direct.getClass().getName() + " toString=" + direct));
                    } catch (Exception ex) {
                        log("[DDS Listener DIAG] values[" + j + "] token value direct-key probe failed: " + ex);
                    }
                    j++;
                }
            }

            // The structurally-scanned publisher Block(s) ModelTopicScanner
            // derives from Port -> InterfaceBlock -> FlowProperty ->
            // direction -> Signal is the PRIMARY source — see
            // findPublishingBlockNameFromScan()'s own comment for why.
            // findOwningBlock()'s ownership-chain walk is a secondary
            // heuristic, used only when the structural scan can't resolve
            // one unambiguous publisher.
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
            log("[DDS Listener] Resolved participantConfigName=" + participantConfigName
                    + " dataWriterName=" + dataWriterName);

            // The actual field values aren't available here — only reachable
            // via the SignalInstance delivered to eventTriggered() below.
            // Queue what we know now (from public API) and match it up by
            // Signal identity when that callback fires.
            pendingPublishes.addLast(new PendingPublish(signal, participantConfigName, dataWriterName));
            log("[DDS Listener] Queued pending publish for '" + signal.getName()
                    + "' — waiting for matching eventTriggered(SignalInstance).");

        } catch (Exception ex) {
            log("[DDS Listener] elementActivated failed: " + ex);
        }
    }

    @Override
    public void elementDeactivated(Element element, Collection<?> values) {
        // no-op — shared signature, see header.
    }

    @Override
    public void eventTriggered(String event) {
        // no-op — EngineListener's own eventTriggered(String); see
        // eventTriggered(SignalInstance) below for the more promising hook.
    }

    @Override
    public void executionTerminated() {
        // Was a no-op — the original design deliberately let cached
        // participants outlive a single simulation run (see
        // RTIConnextPlugin.close() -> DDSTopicPublisher.shutdown() as the
        // only teardown point), on the assumption that outliving a run was
        // desirable to avoid re-paying expensive participant creation on
        // every re-run. Live testing showed the real cost of that: a
        // lingering participant keeps doing normal DDS background activity
        // (discovery, liveliness, ...) for as long as CAMEO stays open, and
        // once NDDS's internal logging started getting routed into this GUI
        // log (see DDSTopicPublisher.ensureLoggingConfigured(), added to
        // chase RETCODE_ERROR), that activity showed up as a message
        // repeating every few seconds — including well after the user had
        // already stopped the simulation. Tearing down here trades away
        // that reuse-across-runs optimization for the behavior a user
        // actually expects: DDS activity should stop when the simulation
        // does.
        log("[DDS Listener] Simulation execution terminated — tearing down cached DDS participants.");
        DDSTopicSubscriber.shutdown();
        DDSTopicPublisher.shutdown();
        DdsInboundInjector.shutdown();
    }

    // -------------------------------------------------------------------
    // SimulationExecutionListener (public, @OpenApiAll) — every method here
    // is DIAGNOSTIC-ONLY: log class/name/args and nothing else, until a live
    // run shows which one actually fires for SendSignalAction.
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
                // Not a DDS_Topic send we queued (or a duplicate/unrelated
                // eventTriggered call) — nothing to publish.
                return;
            }
            publish(pending, signalInstance);
        } catch (Exception ex) {
            log("[DDS Listener] eventTriggered(SignalInstance) failed: " + ex);
        }
    }

    // ---- TEMPORARY DIAGNOSTIC — isolating whether RETCODE_ERROR on publish
    // is caused by the (partially-null / possibly mistyped, e.g. the
    // anomalous_sensors list-as-string issue) extracted payload, or by
    // something wrong with the write mechanism/XML config/type schema
    // itself, independent of extraction. publish() builds a synthetic,
    // fully-populated, all-scalar JSON directly from the scanned
    // StructType schema (bypassing FumlValueBridge/ALH entirely) and
    // attempts a SEPARATE publishOnce() call with it, after the real one,
    // regardless of whether the real one succeeded or failed. If the
    // synthetic one succeeds where the real one failed, the problem is in
    // payload content. If it also fails with RETCODE_ERROR, the problem is
    // upstream of payload content (config/XML/schema/domain).
    private void publish(PendingPublish pending, Object signalInstance) {
        TopicModel.StructType resolvedType = null;
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
            resolvedType = type;
            if (type == null) {
                log("[DDS Listener] No scanned StructType '" + topic.typeName + "' for Topic '"
                        + topic.topicName + "' — cannot build payload.");
                return;
            }

            // The Signal itself only has one attribute (per ModelTopicScanner's
            // convention, named "data") typed by the payload Block — the
            // struct's own fields (declared_id, sensor_id, ...) live one level
            // down from the SignalInstance, not directly on it.
            Object payload = FumlValueBridge.getFieldValue(session, signalInstance, ModelTopicScanner.TOPIC_DATA_ATTRIBUTE_NAME);
            if (payload == null) {
                log("[DDS Listener] SignalInstance for '" + pending.signal.getName() + "' has no '"
                        + ModelTopicScanner.TOPIC_DATA_ATTRIBUTE_NAME + "' attribute value — cannot build payload.");
                return;
            }
            // ---- TEMPORARY DIAGNOSTIC — see what ALH.getValue("data") actually
            // returns before trusting per-field extraction off it.
            log("[DDS Listener DIAG] payload ('data' attribute): class=" + payload.getClass().getName()
                    + " toString=" + payload);

            // ---- TEMPORARY DIAGNOSTIC — testing whether the model's
            // Set_Object_Value("data.measurement_frame", ...) treats the
            // dotted string as ONE flat key (as opposed to two nested hops,
            // "data" then "measurement_frame") on this same payload object.
            try {
                Object flatKeyProbe = FumlValueBridge.getFieldValue(session, payload, "data.measurement_frame");
                log("[DDS Listener DIAG] flat-key probe payload.get(\"data.measurement_frame\") = "
                        + (flatKeyProbe == null ? "null" : flatKeyProbe.getClass().getName() + " toString=" + flatKeyProbe));
            } catch (Exception ex) {
                log("[DDS Listener DIAG] flat-key probe failed: " + ex);
            }

            // ---- TEMPORARY DIAGNOSTIC — both probes on `payload` came back
            // null, meaning Set_Object_Value isn't writing into the
            // SensorReport object's own structural features under either key
            // shape. Testing whether it's instead writing into the execution
            // CONTEXT (ALH.getContext(), which internally falls back to
            // ContextHelper.findNearestObject() when no explicit context was
            // passed to ALH's constructor) — matching how "the other service
            // may have an addStructural to add it to the context" was
            // described.
            try {
                Object context = FumlValueBridge.getContext(session);
                log("[DDS Listener DIAG] ALH.getContext() = "
                        + (context == null ? "null" : context.getClass().getName() + " toString=" + context));
                if (context != null) {
                    Object contextFlat = FumlValueBridge.getFieldValue(session, context, "data.measurement_frame");
                    log("[DDS Listener DIAG] context.get(\"data.measurement_frame\") = "
                            + (contextFlat == null ? "null" : contextFlat.getClass().getName() + " toString=" + contextFlat));
                    Object contextField = FumlValueBridge.getFieldValue(session, context, "measurement_frame");
                    log("[DDS Listener DIAG] context.get(\"measurement_frame\") = "
                            + (contextField == null ? "null" : contextField.getClass().getName() + " toString=" + contextField));
                }
            } catch (Exception ex) {
                log("[DDS Listener DIAG] ALH.getContext() probe failed: " + ex);
            }

            JsonPayloadBuilder builder = JsonPayloadBuilder.create();
            for (TopicModel.Field field : type.fields) {
                appendField(model, builder, field, payload);
            }
            String json = builder.build();

            log("[DDS Listener] Publishing " + pending.dataWriterName + " <- " + json);
            try {
                DDSTopicPublisher.publishOnce(pending.participantConfigName, pending.dataWriterName, json);
                log("[DDS Listener] Published to " + pending.dataWriterName + ".");
            } catch (Exception ex) {
                log("[DDS Listener] Publish failed for Signal '" + pending.signal.getName() + "': " + ex);
            }
        } catch (Exception ex) {
            log("[DDS Listener] Publish failed for Signal '" + pending.signal.getName() + "': " + ex);
        }

        // ---- TEMPORARY DIAGNOSTIC — synthetic test payload, see this
        // method's own header comment. Runs regardless of whether the real
        // publish attempt above succeeded or failed.
        if (resolvedType != null) {
            try {
                String syntheticJson = buildSyntheticTestPayload(resolvedType);
                log("[DDS Listener DIAG] Attempting synthetic test publish " + pending.dataWriterName
                        + " <- " + syntheticJson);
                DDSTopicPublisher.publishOnce(pending.participantConfigName, pending.dataWriterName, syntheticJson);
                log("[DDS Listener DIAG] Synthetic test publish to " + pending.dataWriterName + " SUCCEEDED.");
            } catch (Exception ex) {
                log("[DDS Listener DIAG] Synthetic test publish to " + pending.dataWriterName + " FAILED: " + ex);
            }
        }
    }

    /**
     * DIAGNOSTIC ONLY — builds a synthetic, fully-populated, all-scalar
     * JSON payload directly from the scanned StructType schema, bypassing
     * FumlValueBridge/ALH/extraction entirely. See publish()'s header
     * comment for why. Dummy values: "test" (truncated to maxLength if
     * set) for string, 0/0.0/false for numeric/boolean types, recursing
     * for nested structs.
     */
    private String buildSyntheticTestPayload(TopicModel.StructType type) {
        JsonPayloadBuilder builder = JsonPayloadBuilder.create();
        for (TopicModel.Field field : type.fields) {
            appendSyntheticField(builder, field);
        }
        return builder.build();
    }

    private void appendSyntheticField(JsonPayloadBuilder builder, TopicModel.Field field) {
        if (field.isNested()) {
            TopicModel model = getOrScanTopicModel();
            TopicModel.StructType nestedType = model.types.get(field.nestedStructName);
            if (nestedType == null) {
                builder.addRaw(field.name, "null");
                return;
            }
            JsonPayloadBuilder nested = JsonPayloadBuilder.create();
            for (TopicModel.Field nestedField : nestedType.fields) {
                appendSyntheticField(nested, nestedField);
            }
            builder.addRaw(field.name, nested.build());
            return;
        }
        String primitiveType = field.primitiveType == null ? "string" : field.primitiveType;
        switch (primitiveType) {
            case "int32":
                builder.addRaw(field.name, "0");
                break;
            case "float64":
                builder.addRaw(field.name, "0.0");
                break;
            case "boolean":
                builder.addRaw(field.name, "false");
                break;
            case "string":
            default:
                String value = "test";
                if (field.maxLength != null && field.maxLength > 0 && field.maxLength < value.length()) {
                    value = value.substring(0, field.maxLength);
                }
                builder.addString(field.name, value);
                break;
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
        // ---- TEMPORARY DIAGNOSTIC — log every extraction result, including
        // legitimate nulls, so a "get() returned null" case is distinguishable
        // from "get() threw" and from "the value just happens to be non-null
        // but of a shape we don't handle yet".
        log("[DDS Listener DIAG]   field '" + field.name + "' raw=" + (raw == null ? "null" : raw.getClass().getName() + " toString=" + raw));

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

        // CONFIRMED LIVE: GatedReport.system_time is declared "string" in the
        // model but ALH hands back a java.lang.Double (whatever numeric
        // literal the Opaque Behavior that set it produced, e.g.
        // System.currentTimeMillis()). The old dispatch below picked
        // serialization off raw's Java runtime class, not the DDS-declared
        // type, so a Double landed as a bare JSON number
        // ("system_time":1.789...E12) even though the receiving struct
        // expects a quoted string there -- RTI's
        // DDS_DynamicDataParser_parse_json_node rejected the whole payload
        // with RETCODE_BAD_PARAMETER, silently killing every publish for
        // this Signal. Checking field.primitiveType FIRST makes
        // serialization follow the schema instead of whatever type the
        // fUML runtime happened to box the value as.
        if (field.isArray()) {
            builder.addRaw(field.name, arrayToJson(field, raw));
            return;
        }

        if (raw == null) {
            builder.addRaw(field.name, "null");
        } else if ("string".equals(field.primitiveType)) {
            String stringValue = (raw instanceof EnumerationLiteral)
                    ? ((EnumerationLiteral) raw).getName()
                    : String.valueOf(raw);
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

    /**
     * CONFIRMED LIVE: a fixed-size array field (e.g. GatedReport.Position,
     * float64 arrayDimensions="2") comes back from FumlValueBridge as a
     * java.util.ArrayList, which the old dispatch above had no case for --
     * it fell into the "unexpected value type" branch and got serialized as
     * a single JSON STRING holding the list's toString() (e.g.
     * "\"[7.07, 7.07]\""), instead of a real JSON array ([7.07,7.07]).
     * RTI's parser expects an actual array for an arrayDimensions member;
     * the stringified form is exactly the second reason the live
     * GatedReport publish was rejected with RETCODE_BAD_PARAMETER (the
     * first, system_time, is fixed above -- this one would have been hit
     * next once that one was fixed).
     */
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
            return "\"" + escapeJsonString(((EnumerationLiteral) element).getName()) + "\"";
        } else {
            return "\"" + escapeJsonString(String.valueOf(element)) + "\"";
        }
    }

    private static String escapeJsonString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
        TopicModel model = cachedTopicModel;
        if (model == null) {
            Project project = Application.getInstance().getProject();
            Element root = project.getPrimaryModel();
            model = ModelTopicScanner.scan(root);
            cachedTopicModel = model;
        }
        return model;
    }

    // 2026x signature change: ParameterValueList -> List<ParameterValue>.
    @Override
    public void operationCalled(Operation operation, List<ParameterValue> parameters,
                                 Object_ target, Object_ caller, boolean synch) {
        try {
            log("[DDS Listener DIAG] operationCalled: name="
                    + (operation == null ? "null" : operation.getName()));
        } catch (Exception ex) {
            log("[DDS Listener DIAG] operationCalled diagnostic logging failed: " + ex);
        }
    }

    // 2026x signature change: ParameterValueList -> List<ParameterValue>.
    @Override
    public void behaviorCalled(Behavior behavior, List<ParameterValue> parameters,
                                Object_ target, Object_ caller, boolean synch) {
        try {
            log("[DDS Listener DIAG] behaviorCalled: name="
                    + (behavior == null ? "null" : behavior.getName()));
        } catch (Exception ex) {
            log("[DDS Listener DIAG] behaviorCalled diagnostic logging failed: " + ex);
        }
    }

    @Override
    public void objectCreated(Object_ object, Object_ context) {
        try {
            log("[DDS Listener DIAG] objectCreated: class="
                    + (object == null ? "null" : object.getClass().getName()));
        } catch (Exception ex) {
            log("[DDS Listener DIAG] objectCreated diagnostic logging failed: " + ex);
        }
    }

    @Override
    public void configLoaded(Element element, SimulationExecution execution) {
        try {
            String elementName = (element instanceof NamedElement) ? ((NamedElement) element).getName() : null;
            log("[DDS Listener DIAG] configLoaded: element=" + elementName);
        } catch (Exception ex) {
            log("[DDS Listener DIAG] configLoaded diagnostic logging failed: " + ex);
        }
    }

    @Override
    public void executionStarted(SimulationExecution execution) {
        log("[DDS Listener DIAG] executionStarted");
    }

    @Override
    public void executionTerminated(SimulationExecution execution) {
        log("[DDS Listener DIAG] executionTerminated(SimulationExecution)");
        // Same reasoning as the no-arg EngineListener.executionTerminated()
        // override above — calling both is safe: DDSTopicPublisher.shutdown()
        // clears its cache on first call, so a second call (whichever of the
        // two termination hooks fires second) is just a no-op loop.
        DDSTopicSubscriber.shutdown();
        DDSTopicPublisher.shutdown();
        DdsInboundInjector.shutdown();
    }

    @Override
    public void beforeContextInitialized(SimulationExecution execution) {
        log("[DDS Listener DIAG] beforeContextInitialized");
    }

    @Override
    public void contextInitialized(SimulationExecution execution) {
        log("[DDS Listener DIAG] contextInitialized");
    }

    @Override
    public void valueChange(StructuredValue value, FeatureValue featureValue, Object oldValue, Object newValue) {
        // ---- TEMPORARY DIAGNOSTIC — confirmed live (2024x) that this fires
        // for Set_Object_Value-driven writes, handing the new value directly
        // as a plain Object — no ALH/context resolution needed at all.
        // featureValue.feature is a plain public StructuralFeature (standard
        // UML2 type, not internal-marked), so reading its name here doesn't
        // touch any new internal-API surface. If DdsEngineListener starts
        // caching values here instead of calling ALH.getValue() at publish
        // time, revert this to silent; this WILL be extremely high-frequency.
        try {
            String featureName = featureValue == null || featureValue.feature == null
                    ? "?" : featureValue.feature.getName();
            log("[DDS Listener DIAG] valueChange: owner=" + (value == null ? "null" : value.getClass().getName())
                    + " feature=" + featureName + " oldValue=" + oldValue + " newValue=" + newValue);
        } catch (Exception ex) {
            log("[DDS Listener DIAG] valueChange diagnostic logging failed: " + ex);
        }
    }

    @Override
    public void busyStatusChange(StructuredValue value, Object oldStatus, Object newStatus) {
        // high-frequency — intentionally not logged to avoid flooding the GUI log.
    }

    @Override
    public void objectStateActivated(StructuredValue value, State state) {
        try {
            log("[DDS Listener DIAG] objectStateActivated: state="
                    + (state == null ? "null" : state.getName()));
        } catch (Exception ex) {
            log("[DDS Listener DIAG] objectStateActivated diagnostic logging failed: " + ex);
        }
    }

    @Override
    public void beforeObjectDestroyed(Object_ object) {
        // no-op — not relevant to SendSignalAction detection.
    }

    // ---- DIAGNOSTIC (kept, not removed) — SECONDARY heuristic, used only
    // when findPublishingBlockNameFromScan() below (the structural Port/
    // FlowProperty scan, the PRIMARY source) can't resolve one unambiguous
    // publisher. This walk can find *a* Block (the SendSignalAction's
    // transitive owner) that isn't necessarily who structurally publishes
    // the topic. Logging left in place since it's cheap and remains useful
    // for any future case neither source resolves.
    private static Class findOwningBlock(Element element) {
        String elementName = (element instanceof NamedElement) ? ((NamedElement) element).getName() : null;
        log("[DDS Listener DIAG] findOwningBlock: walking owner chain for element class="
                + (element == null ? "null" : element.getClass().getName()) + " name=" + elementName);
        Element owner = element.getOwner();
        int depth = 0;
        while (owner != null) {
            String ownerClassName = owner.getClass().getName();
            String ownerName = (owner instanceof NamedElement) ? ((NamedElement) owner).getName() : null;
            boolean isClass = owner instanceof Class;
            boolean hasBlockStereotype = isClass && StereotypesHelper.hasStereotype((Class) owner, BLOCK_STEREOTYPE);
            log("[DDS Listener DIAG] findOwningBlock:   depth=" + depth + " class=" + ownerClassName
                    + " name=" + ownerName + " instanceofClass=" + isClass
                    + " hasBlockStereotype=" + hasBlockStereotype);
            if (hasBlockStereotype) {
                log("[DDS Listener DIAG] findOwningBlock: MATCH at depth=" + depth + " name=" + ownerName);
                return (Class) owner;
            }
            owner = owner.getOwner();
            depth++;
        }
        log("[DDS Listener DIAG] findOwningBlock: no Block-stereotyped owner found after "
                + depth + " level(s) — chain exhausted (reached a null owner).");
        return null;
    }

    /**
     * PRIMARY source for "which Block publishes this Signal's topic" — the
     * already-scanned structural publisher Block(s) ModelTopicScanner
     * derives from Port -> InterfaceBlock -> FlowProperty -> direction ->
     * Signal. This MUST be primary, not a fallback: DdsXmlGenerator only
     * ever builds <domain_participant> entries from this exact same scan,
     * so any other source (e.g. findOwningBlock()'s ownership-chain walk)
     * can resolve a Block that simply doesn't exist as a participant in
     * whatever XML was actually generated. Returns the single publisher
     * Block name if there's exactly one unambiguous candidate; returns null
     * (logged, zero vs. ambiguous) rather than guessing if there are zero or
     * more than one — findOwningBlock() is tried next in that case.
     */
    private String findPublishingBlockNameFromScan(Signal signal) {
        TopicModel model = getOrScanTopicModel();
        TopicModel.Topic topic = model.topics.get(signal.getName());
        if (topic == null || topic.publisherBlockNames.isEmpty()) {
            log("[DDS Listener DIAG] findPublishingBlockNameFromScan: no structurally-scanned publisher Block found for Signal '"
                    + signal.getName() + "'.");
            return null;
        }
        if (topic.publisherBlockNames.size() > 1) {
            log("[DDS Listener DIAG] findPublishingBlockNameFromScan: ambiguous — "
                    + topic.publisherBlockNames.size() + " structurally-scanned publisher Blocks for Signal '"
                    + signal.getName() + "': " + topic.publisherBlockNames + " — refusing to guess.");
            return null;
        }
        String blockName = topic.publisherBlockNames.get(0);
        log("[DDS Listener DIAG] findPublishingBlockNameFromScan: resolved publisher Block '" + blockName
                + "' for Signal '" + signal.getName() + "' via structural Port/FlowProperty scan.");
        return blockName;
    }

    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {
            System.out.println(msg);
        }
    }
}
