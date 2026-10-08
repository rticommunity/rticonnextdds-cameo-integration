/*
 * DdsEngineListener.java — CAMEO 2026x variant. See
 * src24x/.../DdsEngineListener.java for the 2024x original this was ported
 * from, and for the full investigation history behind this design (kept
 * there in full rather than duplicated here). Picked up by build_26x.bat;
 * build.bat picks up the src24x/ copy instead.
 *
 * ONLY DIFFERENCE FROM THE 2024x VERSION, confirmed via `javap -p` against
 * CST 2026x Refresh1's simulation.toolkit.core jar (2026.1.0) vs 2024x's
 * (2024.3.0): the fUML runtime value types (SignalInstance, among others)
 * moved out of the shared `fUML.Semantics.*` namespace into NoMagic's own
 * `com.nomagic.magicdraw.simulation.fuml.*` namespace — so only the
 * SignalInstance import below differs. No logic, method names, or ALH usage
 * changed between the two versions.
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
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;

import com.rti.connext.cameo.core.JsonPayloadBuilder;
import com.rti.connext.cameo.core.ModelTopicScanner;
import com.rti.connext.cameo.core.TopicModel;

import com.nomagic.magicdraw.simulation.fuml.behaviors.communications.SignalInstance;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;

// @SuppressWarnings("deprecation") at the class level: the fUML
// SignalInstance type in eventTriggered(SignalInstance)'s required override
// signature is itself marked deprecated. That is unavoidable and separate
// from FumlValueBridge's deliberate internal-API use — see that file's
// header.
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
    // EngineListener (public, @OpenApiAll) — kept from the original
    // implementation. elementActivated's signature is IDENTICAL to
    // SimulationExecutionListener's own elementActivated(Element,
    // Collection<?>), so this single method also serves as that override —
    // see the src24x copy's header, point 5.
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

            // The structurally-scanned publisher Block(s) ModelTopicScanner
            // derives from Port -> InterfaceBlock -> FlowProperty ->
            // direction -> Signal is the PRIMARY source — confirmed live
            // that it must be, because DdsXmlGenerator ONLY ever builds
            // <domain_participant> entries from this exact same scan.
            // findOwningBlock()'s ownership-chain walk is a secondary
            // heuristic, used only when the structural scan can't resolve
            // one unambiguous publisher — it can find *a* Block (the
            // SendSignalAction's transitive owner via State -> Region ->
            // StateMachine -> Class) that isn't necessarily who actually
            // publishes the topic structurally. Live-confirmed both ways:
            // for 'Health' the ownership walk found nothing (action lives in
            // a reusable/library Activity, owned by a Package, not a Block)
            // and the structural-scan fallback correctly resolved it; for
            // 'SensorReport' the ownership walk found a REAL but WRONG
            // Block ('DDS_Simulation_Environment', the enclosing
            // StateMachine's owning Class) that doesn't exist as a
            // <domain_participant> in the generated XML at all — trusting
            // it produced "Profile library ... not found" on every publish
            // attempt, even though the structural scan would have resolved
            // the correct participant ('Sensor Alignment and Gating
            // Service_Definition') on its own.
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

            // The actual field values aren't available here — only reachable
            // via the SignalInstance delivered to eventTriggered() below, see
            // the src24x copy's header, point 7. Queue what we know now (from public API)
            // and match it up by Signal identity when that callback fires.
            pendingPublishes.addLast(new PendingPublish(signal, participantConfigName, dataWriterName));
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
    // SimulationExecutionListener (public, @OpenApiAll) — a base class whose
    // callbacks all default to no-ops, so only the ones this plugin needs
    // are overridden here. See the src24x copy's header, points 5-6.
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

            JsonPayloadBuilder builder = JsonPayloadBuilder.create();
            for (TopicModel.Field field : type.fields) {
                appendField(model, builder, field, payload);
            }
            String json = builder.build();

            log("[DDS Listener] Publishing " + pending.dataWriterName + " <- " + json);
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
            return JsonPayloadBuilder.quote(((EnumerationLiteral) element).getName());
        } else {
            return JsonPayloadBuilder.quote(String.valueOf(element));
        }
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
        // Same reasoning as the no-arg EngineListener.executionTerminated()
        // override above — calling both is safe: each shutdown() clears its
        // cache on first call, so whichever termination hook fires second is
        // just a no-op.
        DDSTopicSubscriber.shutdown();
        DDSTopicPublisher.shutdown();
        DdsInboundInjector.shutdown();
    }

    // SECONDARY heuristic, used only when findPublishingBlockNameFromScan()
    // below (the structural Port/FlowProperty scan, the PRIMARY source — see
    // elementActivated()'s own comment for why) can't resolve one
    // unambiguous publisher. This walk can find *a* Block (the
    // SendSignalAction's transitive owner) that isn't necessarily who
    // structurally publishes the topic — confirmed live it found a real but
    // WRONG Block for 'SensorReport' ('DDS_Simulation_Environment', not a
    // real <domain_participant> in the generated XML). It also returns null
    // entirely for 'Health' (action lives in a reusable/library Activity
    // invoked via CallBehaviorAction, owned by a Package chain, not a Block).
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
     * PRIMARY source for "which Block publishes this Signal's topic" — the
     * already-scanned structural publisher Block(s) ModelTopicScanner
     * derives from Port -> InterfaceBlock -> FlowProperty -> direction ->
     * Signal. This MUST be primary, not a fallback: DdsXmlGenerator only
     * ever builds <domain_participant> entries from this exact same scan,
     * so any other source (e.g. findOwningBlock()'s ownership-chain walk)
     * can resolve a Block that simply doesn't exist as a participant in
     * whatever XML was actually generated — confirmed live for
     * 'SensorReport' ("Profile library ... not found" on every attempt
     * while this method was only consulted as a fallback). Returns the
     * single publisher Block name if there's exactly one unambiguous
     * candidate; returns null (logged, zero vs. ambiguous) rather than
     * guessing if there are zero or more than one — findOwningBlock() is
     * tried next in that case.
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