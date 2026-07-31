/*
 * ModelDdsScanner.java — walks a SysML model and discovers:
 *   - Blocks (Class stereotyped «Block»)
 *   - Signals used as DDS Topics (a Signal's one attribute, typed by a Block,
 *     recursively expanded into a DDS struct type via that Block's value/
 *     part properties)
 *   - Which Blocks publish a topic (SendSignalAction referencing that Signal,
 *     inside an owned Activity/behavior) and which subscribe to it
 *     (SignalEvent trigger on a Transition in the Block's State Machine)
 *
 * ============================== CONFIDENCE NOTE ==============================
 * The INTERFACES used here (Signal, SignalEvent, ChangeEvent, Port,
 * SendSignalAction, StateMachine, Transition, Property, Class) are verified
 * against your actual installed jdocs (jdocs.nomagic.com/190 + confirmed
 * present) — not guessed.
 *
 * The SPECIFIC GETTER METHODS called on them below (getOwnedAttribute(),
 * getAggregation(), getTrigger(), getEvent(), getSignal(), getOnPort(),
 * getRegion(), getTransition(), getClassifierBehavior(), etc.) follow the
 * standard OMG UML2 metamodel naming convention that this API has used
 * unchanged for many versions — high confidence, but NOT individually
 * re-verified one-by-one against your exact jar the way the interfaces were.
 * If build.bat reports "cannot find symbol" on any specific method below,
 * that's the method to look up in your local
 * <CAMEO_HOME>\openapi\docs\com/nomagic/uml2/... javadoc (which matches your
 * exact installed version, unlike the /190/ snapshot used for interface
 * verification) — the interface itself is not in question, just the exact
 * method name/signature on it.
 * ===============================================================================
 */
package com.rti.connext.cameo.model;

import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.actions.mdbasicactions.SendSignalAction;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKindEnum;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Type;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.SignalEvent;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Trigger;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Region;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.StateMachine;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Transition;

import java.util.HashSet;
import java.util.Set;

public final class ModelDdsScanner {

    private static final String BLOCK_STEREOTYPE = "Block";
    // TODO: confirm this is really the stereotype name in your profile (a
    // custom stereotype named "Topic", extending the Signal metaclass —
    // same pattern as "Block" extending Class). Adjust the string if your
    // profile names it differently.
    private static final String TOPIC_STEREOTYPE = "Topic";
    // Per your convention: a Topic Signal's one payload attribute is always
    // named "data", typed by the Block that defines the payload structure.
    private static final String TOPIC_DATA_ATTRIBUTE_NAME = "data";

    private final TopicModel result = new TopicModel();
    private final Set<String> structsInProgress = new HashSet<>(); // cycle guard

    private ModelDdsScanner() {
    }

    /**
     * Scans everything owned (recursively) by the given root element —
     * typically the project's primary model — and returns the discovered
     * topics/types.
     *
     * @param root any Element to scan from; pass Project.getPrimaryModel()
     *             for a full-project scan (TODO: confirm exact accessor —
     *             it's Project.getPrimaryModel() in most versions, may be
     *             named differently in yours)
     */
    public static TopicModel scan(Element root) {
        ModelDdsScanner scanner = new ModelDdsScanner();
        scanner.visit(root);
        return scanner.result;
    }

    // -------------------------------------------------------------------------
    // Traversal
    // -------------------------------------------------------------------------

    private void visit(Element element) {
        if (element instanceof Class && isBlock((Class) element)) {
            Class block = (Class) element;
            handleBlockStateMachine(block);
            handleBlockSendSignalActions(block);
        }

        // TODO: confirm exact recursive-children accessor. Element.getOwnedElement()
        // is the standard UML2 name for "everything this element directly owns" —
        // combined with this recursive visit() call it walks the whole containment
        // tree. If your version names it differently, this is the line to fix.
        for (Element child : element.getOwnedElement()) {
            visit(child);
        }
    }

    private boolean isBlock(Class clazz) {
        // hasStereotype(Element, String) is StereotypesHelper's convenience
        // overload for checking by stereotype NAME (vs. passing a Stereotype
        // object) — matches the "Block" name from your SysML profile.
        return StereotypesHelper.hasStereotype(clazz, BLOCK_STEREOTYPE);
    }

    // -------------------------------------------------------------------------
    // Subscriptions: Block's State Machine -> Transition -> SignalEvent trigger
    // -------------------------------------------------------------------------

    private void handleBlockStateMachine(Class block) {
        // TODO: confirm exact accessor for "this Block's classifier behavior,
        // if it's a StateMachine". Class.getClassifierBehavior() is the
        // standard UML2 name (classifier behavior = the behavior that runs
        // for the lifetime of an instance) — commonly a StateMachine for a
        // Block in this kind of design.
        Object classifierBehavior = block.getClassifierBehavior();
        if (!(classifierBehavior instanceof StateMachine)) {
            return;
        }
        StateMachine sm = (StateMachine) classifierBehavior;

        for (Region region : sm.getRegion()) {
            scanRegionForSubscriptions(region, block);
        }
    }

    private void scanRegionForSubscriptions(Region region, Class subscriberBlock) {
        for (Transition transition : region.getTransition()) {
            for (Trigger trigger : transition.getTrigger()) {
                Object event = trigger.getEvent();
                if (event instanceof SignalEvent) {
                    Signal signal = ((SignalEvent) event).getSignal();
                    if (signal != null) {
                        TopicModel.Topic topic = registerTopic(signal);
                        if (topic != null && !topic.subscriberBlockNames.contains(subscriberBlock.getName())) {
                            topic.subscriberBlockNames.add(subscriberBlock.getName());
                        }
                    }
                }
                // ChangeEvent triggers deliberately ignored here — per your
                // design, those represent internal-only logic, not DDS traffic.
            }
        }
        // TODO: Regions can nest (composite states contain sub-regions via
        // their own owned States). This only scans one level — recurse into
        // State.getRegion() for each State in this region if your models use
        // nested composite states for subscription logic.
    }

    // -------------------------------------------------------------------------
    // Publications: Block's owned Activities -> SendSignalAction
    // -------------------------------------------------------------------------

    private void handleBlockSendSignalActions(Class block) {
        // TODO: this walks every owned element under the Block looking for
        // SendSignalAction, rather than specifically navigating "owned
        // Activities" — simpler and catches SendSignalActions wherever they
        // live (entry/exit/do activities, owned Activity diagrams, etc.),
        // at the cost of being less precise about *which* activity it's in.
        for (Element descendant : allDescendants(block)) {
            if (descendant instanceof SendSignalAction) {
                SendSignalAction action = (SendSignalAction) descendant;
                Signal signal = action.getSignal();
                if (signal != null) {
                    TopicModel.Topic topic = registerTopic(signal);
                    if (topic != null && !topic.publisherBlockNames.contains(block.getName())) {
                        topic.publisherBlockNames.add(block.getName());
                    }
                    // TODO: action.getOnPort() gives the Port used to send —
                    // useful later for validating the Port's type/direction
                    // matches the topic, or for XML generation if ports need
                    // to map to specific DDS entities. Not used yet.
                }
            }
        }
    }

    private Iterable<Element> allDescendants(Element root) {
        java.util.List<Element> out = new java.util.ArrayList<>();
        collectDescendants(root, out);
        return out;
    }

    private void collectDescendants(Element element, java.util.List<Element> out) {
        for (Element child : element.getOwnedElement()) {
            out.add(child);
            collectDescendants(child, out);
        }
    }

    // -------------------------------------------------------------------------
    // Topic / type registration
    // -------------------------------------------------------------------------

    /**
     * Registers the given Signal as a Topic, but ONLY if it has the «Topic»
     * stereotype applied — a plain Signal with no stereotype is just a
     * regular UML signal, not a DDS topic, and is skipped (returns null).
     */
    private TopicModel.Topic registerTopic(Signal signal) {
        if (!StereotypesHelper.hasStereotype(signal, TOPIC_STEREOTYPE)) {
            return null;
        }

        String topicName = signal.getName();
        TopicModel.Topic existing = result.topics.get(topicName);
        if (existing != null) {
            return existing;
        }

        // Per your convention: the Signal has exactly one attribute, named
        // "data", typed by a Block. Looking it up BY NAME (not just "the
        // first attribute") catches modeling mistakes early — if no
        // attribute is named "data", payloadType stays null and typeName
        // falls through to "UnknownType" below, which is a visible signal
        // something's wrong rather than silently grabbing the wrong attribute.
        // TODO: confirm Signal.getOwnedAttribute() — standard UML2 name for
        // a Signal's attribute list (Signal extends Classifier, which owns
        // attributes the same way a Class does).
        Type payloadType = null;
        for (Property attr : signal.getOwnedAttribute()) {
            if (TOPIC_DATA_ATTRIBUTE_NAME.equals(attr.getName())) {
                payloadType = attr.getType();
                break;
            }
        }

        String typeName = "UnknownType";
        if (payloadType instanceof Class) {
            Class typeBlock = (Class) payloadType;
            typeName = typeBlock.getName();
            buildStructType(typeBlock); // ensures result.types has this entry
        }

        TopicModel.Topic topic = new TopicModel.Topic(topicName, typeName);
        result.topics.put(topicName, topic);
        return topic;
    }

    /** Recursively builds a DDS struct type from a Block's value/part properties. */
    private void buildStructType(Class block) {
        String name = block.getName();
        if (result.types.containsKey(name) || structsInProgress.contains(name)) {
            return; // already built, or cycle guard (block references itself)
        }
        structsInProgress.add(name);

        TopicModel.StructType struct = new TopicModel.StructType(name);

        // TODO: confirm Class.getOwnedAttribute() — standard UML2 name for
        // "properties owned directly by this classifier" (value + part
        // properties both come through this one accessor; they're
        // distinguished by aggregation kind below).
        for (Property prop : block.getOwnedAttribute()) {
            String fieldName = prop.getName();
            Type propType = prop.getType();

            // TODO: confirm Property.getAggregation() — standard UML2 name.
            // COMPOSITE aggregation = part property (nested Block, complex
            // type); NONE = value property (simple/primitive type).
            boolean isPart = prop.getAggregation() == AggregationKindEnum.COMPOSITE;

            if (isPart && propType instanceof Class && isBlock((Class) propType)) {
                Class nestedBlock = (Class) propType;
                buildStructType(nestedBlock); // recurse
                struct.fields.add(new TopicModel.Field(fieldName, null, nestedBlock.getName()));
            } else {
                // Simple/value property — map its type name to a DDS primitive.
                // TODO: this primitive mapping is a real design decision, not
                // yet made: how do model types like "Integer", "String",
                // "Real" map to DDS "int32"/"string"/"float64"? Currently a
                // placeholder passthrough (uses the model type's name
                // directly) — needs a real mapping table once you decide the
                // convention (e.g. primitive UML types vs. a custom value
                // type profile).
                String typeName = propType != null ? propType.getName() : "string";
                struct.fields.add(new TopicModel.Field(fieldName, mapPrimitive(typeName), null));
            }
        }

        result.types.put(name, struct);
        structsInProgress.remove(name);
    }

    /**
     * PLACEHOLDER mapping from a UML/model type name to a DDS primitive type
     * name. TODO: this is a stub, not a real mapping — fill in once you've
     * decided the modeling convention for primitive value properties (plain
     * UML primitives like Integer/String/Boolean/Real, or a custom SysML
     * value type profile, etc.).
     */
    private String mapPrimitive(String modelTypeName) {
        switch (modelTypeName) {
            case "Integer": return "int32";
            case "Real":    return "float64";
            case "Boolean": return "boolean";
            case "String":  return "string";
            default:        return "string"; // unmapped fallback — flag these
        }
    }
}
