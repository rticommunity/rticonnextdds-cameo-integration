/*
 * ModelTopicScanner.java — walks a SysML model and discovers:
 *   - Blocks (Class stereotyped «Block»)
 *   - Signals used as DDS Topics — every Signal reachable from the scan root
 *     that carries the «DDS_Topic» stereotype is registered directly (not
 *     conditionally on being referenced by some Block's behavior); its one
 *     attribute, typed by a Block, is recursively expanded into a DDS struct
 *     type via that Block's value/part properties.
 *   - Which Blocks publish/subscribe to a topic — STRUCTURAL: each Block's
 *     Ports, typed by an «InterfaceBlock», with «FlowProperty» members whose
 *     "direction" tag says in/out/inout (see handleBlockPorts()). This is
 *     the sole source of Topic.publisherBlockNames/subscriberBlockNames.
 *     A separate BEHAVIORAL trace (SendSignalAction, SignalEvent triggers on
 *     a State Machine transition) is also still run, but deliberately does
 *     NOT write to those two fields anymore — see handleBlockSendSignalActions()/
 *     scanRegionForSubscriptions() for why it's retained.
 *
 *     IMPORTANT: by this project's modeling convention, Blocks deliberately
 *     live OUTSIDE the «DDS_Domain» package (only «DDS_Topic» Signals live
 *     inside it) — so scan(Package) is a TWO-PASS scan (domain-scoped topic
 *     discovery, then a project-wide, topic-filtered Block/Port scan), not
 *     a simple single-pass containment walk. See scan(Package)'s own
 *     javadoc below for why.
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
package com.rti.connext.cameo.core;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.actions.mdbasicactions.SendSignalAction;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKindEnum;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Enumeration;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.EnumerationLiteral;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Type;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.SignalEvent;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Trigger;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdports.Port;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Region;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.StateMachine;
import com.nomagic.uml2.ext.magicdraw.statemachines.mdbehaviorstatemachines.Transition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ModelTopicScanner {

    private static final String BLOCK_STEREOTYPE = "Block";
    // The real DDS profile applied to the model: "DDS_Topic" (extends
    // Signal) and "DDS_Domain" (extends Package, with an Integer tagged
    // value "domain_id") are both looked up scoped to this profile by name
    // — not via a bare global stereotype-name match — so a same-named
    // stereotype from an unrelated profile can't be mistaken for these.
    // Public: write-path callers (ImportQosProfileAction) resolving their
    // own stereotypes via resolveStereotype() below need this profile name
    // too — exposed rather than duplicating the literal separately.
    public static final String DDS_PROFILE_NAME = "DDS_Profile";
    private static final String TOPIC_STEREOTYPE_NAME = "DDS_Topic";
    private static final String DOMAIN_STEREOTYPE_NAME = "DDS_Domain";
    private static final String DOMAIN_ID_TAG_NAME = "domain_id";
    // "DDS_Member" (extends Property) — also DDS_Profile-scoped, same
    // pattern as DDS_Topic/DDS_Domain above. Covers more than one concern:
    // only a Property that needs to declare something special (it's the
    // key, or it's a bounded-length string) gets this stereotype applied;
    // a plain field with no special semantics stays unstereotyped.
    private static final String MEMBER_STEREOTYPE_NAME = "DDS_Member";
    private static final String MEMBER_KEY_TAG_NAME = "key";      // Boolean
    private static final String MEMBER_MAX_TAG_NAME = "max";      // Integer, optional — string length bound
    // "DDS_QosLibrary" (extends Package) and "DDS_QosProfile" — also
    // DDS_Profile-scoped. DDS_QosProfile's own metaclass isn't stated by
    // name here (findQosProfiles() below filters by Class, matching what
    // ImportQosProfileAction actually creates) since only the manually-added
    // stereotype (not this scanner) fixes that extension.
    // Public for the same reason as DDS_PROFILE_NAME — ImportQosProfileAction
    // (the write path) needs these exact names too, resolved via the shared
    // resolveStereotype() below rather than a private, driftable copy.
    public static final String QOS_LIBRARY_STEREOTYPE_NAME = "DDS_QosLibrary";
    public static final String QOS_PROFILE_STEREOTYPE_NAME = "DDS_QosProfile";
    // Public for the same reason — ImportQosProfileAction writes these tags.
    public static final String QOS_PARTICIPANT_NAME_TAG = "participant_name"; // String
    public static final String QOS_HISTORY_DEPTH_TAG = "history_depth";       // Integer
    public static final String QOS_IS_DEFAULT_TAG = "is_default";             // Boolean
    // SysML's own built-in profile (confirmed named exactly "SysML" in the
    // profile shipped with CAMEO) — NOT DDS_Profile — owns InterfaceBlock/
    // FlowProperty, used for the structural Port -> pub/sub scan below.
    private static final String SYSML_PROFILE_NAME = "SysML";
    private static final String INTERFACE_BLOCK_STEREOTYPE_NAME = "InterfaceBlock";
    private static final String FLOW_PROPERTY_STEREOTYPE_NAME = "FlowProperty";
    private static final String FLOW_DIRECTION_TAG_NAME = "direction";
    // Per your convention: a Topic Signal's one payload attribute is always
    // named "data", typed by the Block that defines the payload structure.
    // Public: DdsEngineListener needs this to read the payload struct back
    // off a live SignalInstance (signalInstance.get(TOPIC_DATA_ATTRIBUTE_NAME))
    // before it can read the struct's own fields.
    public static final String TOPIC_DATA_ATTRIBUTE_NAME = "data";

    private final TopicModel result = new TopicModel();
    private final Set<String> structsInProgress = new HashSet<>(); // cycle guard
    private Stereotype topicStereotype;
    private Stereotype interfaceBlockStereotype;
    private Stereotype flowPropertyStereotype;
    private Stereotype memberStereotype;
    // Set true only during scan(Package)'s Pass 2 (see below) — when true,
    // handleFlowProperty() attaches publisher/subscriber Blocks ONLY to a
    // topic Pass 1 already found, and never registers a new one, so a Block
    // near a DIFFERENT domain's «DDS_Topic» Signal can't leak that topic
    // into this scan's result.
    private boolean structuralScanRestrictedToExistingTopics;

    private ModelTopicScanner() {
    }

    /**
     * Scans everything owned (recursively) by the given root element —
     * typically the project's primary model — and returns the discovered
     * topics/types.
     *
     * @param root any Element to scan from; pass Project.getPrimaryModel()
     *             for a full-project scan
     */
    public static TopicModel scan(Element root) {
        ModelTopicScanner scanner = new ModelTopicScanner();
        scanner.topicStereotype = lookupStereotype(root, DDS_PROFILE_NAME, TOPIC_STEREOTYPE_NAME);
        scanner.interfaceBlockStereotype = lookupStereotype(root, SYSML_PROFILE_NAME, INTERFACE_BLOCK_STEREOTYPE_NAME);
        scanner.flowPropertyStereotype = lookupStereotype(root, SYSML_PROFILE_NAME, FLOW_PROPERTY_STEREOTYPE_NAME);
        scanner.memberStereotype = lookupStereotype(root, DDS_PROFILE_NAME, MEMBER_STEREOTYPE_NAME);
        scanner.visit(root);
        return scanner.result;
    }

    /**
     * Scans a «DDS_Domain» package for the topics/types that belong to it,
     * THEN separately scans the whole project for the Blocks that publish
     * or subscribe to those specific topics — the scope used by DDS XML
     * generation, as opposed to {@link #scan(Element)}'s single-pass
     * whole-model scan.
     *
     * TWO PASSES, because Blocks deliberately live OUTSIDE the «DDS_Domain»
     * package by this project's modeling convention (only «DDS_Topic»
     * Signals live inside it): a single-pass scan rooted at the domain
     * package alone (the old behavior — this used to just delegate to
     * {@link #scan(Element)}) never reached any Block at all, so
     * Topic.publisherBlockNames/subscriberBlockNames stayed empty for
     * every domain. Confirmed via a whole-project {@link #scan(Element)}
     * run that DID find the correct publisher/subscriber Blocks, proving
     * the Port -> InterfaceBlock -> FlowProperty -> direction -> Signal
     * extraction logic itself was already correct — only the traversal
     * SCOPE was wrong.
     *
     * Pass 1 (domain-scoped): walks domainPackage's own containment tree —
     * exactly what scan(Element) does — registering «DDS_Topic» Signals
     * found there as this domain's topics/types.
     *
     * Pass 2 (project-wide, topic-filtered): walks the WHOLE project
     * looking for Blocks, running the STRUCTURAL Port scan on each one, but
     * with structuralScanRestrictedToExistingTopics set so it only attaches
     * a Block to a topic Pass 1 already found — never registers a new one.
     * Without that restriction, a Block that happens to publish/subscribe
     * to some OTHER domain's topic would leak that topic into this domain's
     * result, breaking the domain isolation already verified working.
     */
    public static TopicModel scan(Package domainPackage) {
        ModelTopicScanner scanner = new ModelTopicScanner();
        scanner.topicStereotype = lookupStereotype(domainPackage, DDS_PROFILE_NAME, TOPIC_STEREOTYPE_NAME);
        scanner.interfaceBlockStereotype = lookupStereotype(domainPackage, SYSML_PROFILE_NAME, INTERFACE_BLOCK_STEREOTYPE_NAME);
        scanner.flowPropertyStereotype = lookupStereotype(domainPackage, SYSML_PROFILE_NAME, FLOW_PROPERTY_STEREOTYPE_NAME);
        scanner.memberStereotype = lookupStereotype(domainPackage, DDS_PROFILE_NAME, MEMBER_STEREOTYPE_NAME);

        // Pass 1 — domain-scoped: which topics/types belong to this domain.
        scanner.visit(domainPackage);

        // Pass 2 — project-wide, filtered to Pass 1's topics.
        Project project = Project.getProject(domainPackage);
        Element projectRoot = project != null ? project.getPrimaryModel() : null;
        if (projectRoot != null) {
            scanner.structuralScanRestrictedToExistingTopics = true;
            scanner.visitBlockPorts(projectRoot);
        }

        return scanner.result;
    }

    // -------------------------------------------------------------------------
    // DDS_Domain package discovery
    // -------------------------------------------------------------------------

    /** Finds every Package stereotyped «DDS_Domain» (scoped to DDS_Profile), anywhere under root. */
    public static List<Package> findDomainPackages(Element root) {
        Stereotype domainStereotype = lookupStereotype(root, DDS_PROFILE_NAME, DOMAIN_STEREOTYPE_NAME);
        List<Package> domains = new ArrayList<>();
        collectDomainPackages(root, domainStereotype, domains);
        return domains;
    }

    private static void collectDomainPackages(Element element, Stereotype domainStereotype, List<Package> out) {
        if (domainStereotype != null && element instanceof Package
                && StereotypesHelper.hasStereotype(element, domainStereotype)) {
            out.add((Package) element);
        }
        for (Element child : element.getOwnedElement()) {
            collectDomainPackages(child, domainStereotype, out);
        }
    }

    /** Reads the Integer "domain_id" tagged value off a «DDS_Domain»-stereotyped Package, or null if unset. */
    public static Integer getDomainId(Package domainPackage) {
        Stereotype domainStereotype = lookupStereotype(domainPackage, DDS_PROFILE_NAME, DOMAIN_STEREOTYPE_NAME);
        if (domainStereotype == null) {
            return null;
        }
        // NOTE: getStereotypePropertyValueAsString() was tried here first (it's
        // the right call for an enum-backed tag like FlowProperty's "direction"
        // below) but for this plain Integer-typed tag it silently returned "0"
        // instead of the actually-set value in real testing — reverted to the
        // raw-value accessor, which reads the real Integer/Number correctly.
        List<?> values = StereotypesHelper.getStereotypePropertyValue(domainPackage, domainStereotype, DOMAIN_ID_TAG_NAME);
        if (values == null || values.isEmpty()) {
            return null;
        }
        Object value = values.get(0);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    /**
     * Profile-scoped «DDS_Topic» check (DDS_Profile), same lookupStereotype()
     * resolution every other stereotype check in this class uses. Exposed
     * publicly so callers outside this class (DdsEngineListener, in
     * particular — it needs to recognize a «DDS_Topic» Signal at simulation
     * time) resolve the stereotype exactly the same way scanning does,
     * rather than maintaining their own separate, driftable copy of this
     * lookup.
     */
    public static boolean isDdsTopic(Signal signal) {
        Stereotype stereotype = lookupStereotype(signal, DDS_PROFILE_NAME, TOPIC_STEREOTYPE_NAME);
        return stereotype != null && StereotypesHelper.hasStereotype(signal, stereotype);
    }

    // -------------------------------------------------------------------------
    // DDS_QosLibrary / DDS_QosProfile discovery (read-only — see
    // ImportQosProfileAction for the write-path counterpart that creates
    // these elements)
    // -------------------------------------------------------------------------

    /** Finds every Package stereotyped «DDS_QosLibrary» (scoped to DDS_Profile), anywhere under root. */
    public static List<Package> findQosLibraryPackages(Element root) {
        Stereotype qosLibraryStereotype = lookupStereotype(root, DDS_PROFILE_NAME, QOS_LIBRARY_STEREOTYPE_NAME);
        List<Package> libraries = new ArrayList<>();
        collectQosLibraryPackages(root, qosLibraryStereotype, libraries);
        return libraries;
    }

    private static void collectQosLibraryPackages(Element element, Stereotype qosLibraryStereotype, List<Package> out) {
        if (qosLibraryStereotype != null && element instanceof Package
                && StereotypesHelper.hasStereotype(element, qosLibraryStereotype)) {
            out.add((Package) element);
        }
        for (Element child : element.getOwnedElement()) {
            collectQosLibraryPackages(child, qosLibraryStereotype, out);
        }
    }

    /**
     * Finds every element stereotyped «DDS_QosProfile» (scoped to
     * DDS_Profile) anywhere under root — deliberately NOT restricted to
     * living inside a «DDS_QosLibrary» package, in case a profile ends up
     * elsewhere in the model. Filtered to Class instances, matching what
     * ImportQosProfileAction actually creates.
     */
    public static List<Class> findQosProfiles(Element root) {
        Stereotype qosProfileStereotype = lookupStereotype(root, DDS_PROFILE_NAME, QOS_PROFILE_STEREOTYPE_NAME);
        List<Class> profiles = new ArrayList<>();
        collectQosProfiles(root, qosProfileStereotype, profiles);
        return profiles;
    }

    private static void collectQosProfiles(Element element, Stereotype qosProfileStereotype, List<Class> out) {
        if (qosProfileStereotype != null && element instanceof Class
                && StereotypesHelper.hasStereotype(element, qosProfileStereotype)) {
            out.add((Class) element);
        }
        for (Element child : element.getOwnedElement()) {
            collectQosProfiles(child, qosProfileStereotype, out);
        }
    }

    /** Plain data holder for a «DDS_QosProfile» element's tagged values — see readQosProfileInfo(). */
    public static final class QosProfileInfo {
        public final String profileName;       // the element's own name
        public final String participantName;   // "participant_name" tag — null if unset
        public final Integer historyDepth;      // "history_depth" tag — null if unset
        public final boolean isDefault;         // "is_default" tag — false if unset

        public QosProfileInfo(String profileName, String participantName, Integer historyDepth, boolean isDefault) {
            this.profileName = profileName;
            this.participantName = participantName;
            this.historyDepth = historyDepth;
            this.isDefault = isDefault;
        }
    }

    /** Reads a «DDS_QosProfile» element's participant_name/history_depth/is_default tagged values. */
    public static QosProfileInfo readQosProfileInfo(Class qosProfileElement) {
        Stereotype qosProfileStereotype = lookupStereotype(qosProfileElement, DDS_PROFILE_NAME, QOS_PROFILE_STEREOTYPE_NAME);
        String participantName = null;
        Integer historyDepth = null;
        boolean isDefault = false;

        if (qosProfileStereotype != null) {
            List<String> participantValues = StereotypesHelper.getStereotypePropertyValueAsString(
                    qosProfileElement, qosProfileStereotype, QOS_PARTICIPANT_NAME_TAG, false);
            participantName = (participantValues == null || participantValues.isEmpty()) ? null : participantValues.get(0);
            log("QosProfile '" + qosProfileElement.getName() + "' — raw 'participant_name' tag: '" + participantName + "'");

            // NOTE: raw-value accessor, not getStereotypePropertyValueAsString() —
            // "history_depth" is a plain Integer tag, same shape as domain_id/max,
            // and AsString was already found (see getDomainId()) to silently
            // return "0" instead of the real value for that kind of tag.
            List<?> depthValues = StereotypesHelper.getStereotypePropertyValue(
                    qosProfileElement, qosProfileStereotype, QOS_HISTORY_DEPTH_TAG);
            Object rawDepth = (depthValues == null || depthValues.isEmpty()) ? null : depthValues.get(0);
            log("QosProfile '" + qosProfileElement.getName() + "' — raw 'history_depth' value: '" + rawDepth + "'");
            historyDepth = rawDepth instanceof Number ? ((Number) rawDepth).intValue() : null;

            List<String> defaultValues = StereotypesHelper.getStereotypePropertyValueAsString(
                    qosProfileElement, qosProfileStereotype, QOS_IS_DEFAULT_TAG, false);
            String rawDefault = (defaultValues == null || defaultValues.isEmpty()) ? null : defaultValues.get(0);
            log("QosProfile '" + qosProfileElement.getName() + "' — raw 'is_default' tag: '" + rawDefault + "'");
            isDefault = rawDefault != null && "true".equalsIgnoreCase(rawDefault.trim());
        }

        return new QosProfileInfo(qosProfileElement.getName(), participantName, historyDepth, isDefault);
    }

    /**
     * Public, generic profile-scoped stereotype resolution — same
     * lookupStereotype() pattern used internally throughout this class,
     * exposed so write-path callers (ImportQosProfileAction, in particular —
     * it needs to resolve «DDS_QosLibrary»/«DDS_QosProfile» before applying
     * them to newly created elements) don't keep their own separate,
     * driftable copy of this lookup — same rationale as isDdsTopic() above.
     */
    public static Stereotype resolveStereotype(Element contextElement, String profileName, String stereotypeName) {
        return lookupStereotype(contextElement, profileName, stereotypeName);
    }

    private static Stereotype lookupStereotype(Element contextElement, String profileName, String stereotypeName) {
        Project project = Project.getProject(contextElement);
        Profile profile = StereotypesHelper.getProfile(project, profileName);
        return StereotypesHelper.getStereotype(project, stereotypeName, profile);
    }

    // -------------------------------------------------------------------------
    // Traversal
    // -------------------------------------------------------------------------

    private void visit(Element element) {
        if (element instanceof Signal) {
            // A Signal is a DDS Topic candidate purely by being reachable
            // here and stereotyped «DDS_Topic» — registration must NOT
            // depend on some Block also referencing it behaviorally (that
            // used to be the only path in; a structurally-declared Signal
            // with no SendSignalAction/SignalEvent wiring was silently
            // dropped — see handleBlockSendSignalActions()/
            // scanRegionForSubscriptions() below for the behavioral trace,
            // which no longer owns topic discovery).
            registerTopic((Signal) element);
        } else if (element instanceof Class && isBlock((Class) element)) {
            Class block = (Class) element;
            handleBlockStateMachine(block);
            handleBlockSendSignalActions(block);
            handleBlockPorts(block);
        }

        // TODO: confirm exact recursive-children accessor. Element.getOwnedElement()
        // is the standard UML2 name for "everything this element directly owns" —
        // combined with this recursive visit() call it walks the whole containment
        // tree. If your version names it differently, this is the line to fix.
        for (Element child : element.getOwnedElement()) {
            visit(child);
        }
    }

    /**
     * Pass-2 traversal for {@link #scan(Package)}: finds every Block
     * anywhere under the given root and runs the STRUCTURAL Port scan on it
     * (handleBlockPorts()) — but, unlike visit(), does NOT treat a Signal as
     * a topic candidate and does NOT run the BEHAVIORAL trace
     * (handleBlockStateMachine()/handleBlockSendSignalActions()). Both of
     * those call registerTopic() unconditionally, which would register
     * whatever «DDS_Topic» Signal they happen to find anywhere in the whole
     * project — exactly the cross-domain leakage
     * structuralScanRestrictedToExistingTopics (checked inside
     * handleFlowProperty()) is meant to prevent. Only ever called with that
     * flag set.
     */
    private void visitBlockPorts(Element element) {
        if (element instanceof Class && isBlock((Class) element)) {
            handleBlockPorts((Class) element);
        }
        for (Element child : element.getOwnedElement()) {
            visitBlockPorts(child);
        }
    }

    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log("[DDS Scan] " + msg);
        } catch (Exception ignored) {
            System.out.println("[DDS Scan] " + msg);
        }
    }

    private boolean isBlock(Class clazz) {
        // hasStereotype(Element, String) is StereotypesHelper's convenience
        // overload for checking by stereotype NAME (vs. passing a Stereotype
        // object) — matches the "Block" name from your SysML profile.
        return StereotypesHelper.hasStereotype(clazz, BLOCK_STEREOTYPE);
    }

    // -------------------------------------------------------------------------
    // BEHAVIORAL trace (kept, not deleted): Block's State Machine ->
    // Transition -> SignalEvent trigger. Retained for a future, backlogged
    // validation feature that cross-checks this behavioral wiring against
    // the STRUCTURAL scan (handleBlockPorts() below) — e.g. flagging a Block
    // whose Port/FlowProperty direction says it publishes a topic but whose
    // state machine never actually sends it, or vice versa. Does NOT write
    // to Topic.subscriberBlockNames/publisherBlockNames anymore — the
    // structural scan is the sole source for those two fields now.
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
                        // Detection only, deliberately discarded beyond this —
                        // see the class comment above for why.
                        registerTopic(signal);
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
    // BEHAVIORAL trace (kept, not deleted): Block's owned Activities ->
    // SendSignalAction. Same retention rationale as scanRegionForSubscriptions()
    // above — no longer writes to Topic.publisherBlockNames.
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
                    // Detection only, deliberately discarded beyond this —
                    // see the class comment above for why.
                    registerTopic(signal);
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
    // STRUCTURAL pub/sub: Block's Ports -> Port type («InterfaceBlock») ->
    // owned Properties («FlowProperty») -> "direction" tag -> FlowProperty's
    // type (a Signal, hopefully «DDS_Topic»). This is the sole source of
    // Topic.publisherBlockNames/subscriberBlockNames — see the class comment
    // and the BEHAVIORAL-trace sections above for why the old behavioral
    // path no longer writes to those fields.
    // -------------------------------------------------------------------------

    private void handleBlockPorts(Class block) {
        if (interfaceBlockStereotype == null || flowPropertyStereotype == null) {
            return;
        }
        for (Port port : block.getOwnedPort()) {
            Type portType = port.getType();
            if (!(portType instanceof Class) || !StereotypesHelper.hasStereotype(portType, interfaceBlockStereotype)) {
                continue;
            }
            Class interfaceBlock = (Class) portType;
            for (Property prop : interfaceBlock.getOwnedAttribute()) {
                if (!StereotypesHelper.hasStereotype(prop, flowPropertyStereotype)) {
                    continue;
                }
                handleFlowProperty(block, port, prop);
            }
        }
    }

    private void handleFlowProperty(Class block, Port port, Property flowProperty) {
        List<String> directionValues = StereotypesHelper.getStereotypePropertyValueAsString(
                flowProperty, flowPropertyStereotype, FLOW_DIRECTION_TAG_NAME, false);
        String rawDirection = (directionValues == null || directionValues.isEmpty()) ? null : directionValues.get(0);
        log("FlowProperty '" + flowProperty.getName() + "' on Block '" + block.getName()
                + "' — raw direction value: '" + rawDirection + "'");

        Type flowType = flowProperty.getType();
        if (!(flowType instanceof Signal)) {
            return;
        }
        Signal signal = (Signal) flowType;
        if (topicStereotype == null || !StereotypesHelper.hasStereotype(signal, topicStereotype)) {
            return;
        }

        TopicModel.Topic topic;
        if (structuralScanRestrictedToExistingTopics) {
            // Pass 2 of scan(Package): only attach pub/sub to a topic Pass 1
            // already found INSIDE this domain — never register a new one
            // here, or a Block near a DIFFERENT domain's «DDS_Topic» Signal
            // would leak that topic into this domain's result.
            topic = result.topics.get(signal.getName());
            if (topic == null) {
                return;
            }
        } else {
            topic = registerTopic(signal);
            if (topic == null) {
                return;
            }
        }

        String direction = normalizeDirection(rawDirection);
        boolean isOut = "out".equalsIgnoreCase(direction) || "inout".equalsIgnoreCase(direction);
        boolean isIn = "in".equalsIgnoreCase(direction) || "inout".equalsIgnoreCase(direction);
        if (isOut && !topic.publisherBlockNames.contains(block.getName())) {
            topic.publisherBlockNames.add(block.getName());
        }
        if (isIn && !topic.subscriberBlockNames.contains(block.getName())) {
            topic.subscriberBlockNames.add(block.getName());
            topic.subscriberPorts.put(block.getName(), port.getName());
        }
    }

    /** Guards against a qualified enum literal (e.g. "FlowDirectionKind::out") instead of the bare literal name. */
    private static String normalizeDirection(String raw) {
        if (raw == null) {
            return null;
        }
        int idx = raw.lastIndexOf("::");
        return idx >= 0 ? raw.substring(idx + 2) : raw;
    }

    // -------------------------------------------------------------------------
    // Topic / type registration
    // -------------------------------------------------------------------------

    /**
     * Registers the given Signal as a Topic, but ONLY if it has the
     * «DDS_Topic» stereotype (from DDS_Profile) applied — a plain Signal
     * with no stereotype is just a regular UML signal, not a DDS topic, and
     * is skipped (returns null).
     */
    private TopicModel.Topic registerTopic(Signal signal) {
        if (topicStereotype == null || !StereotypesHelper.hasStereotype(signal, topicStereotype)) {
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
                // DDS_Member and fixed-size-array detection don't apply to
                // nested struct references — only to simple value properties,
                // per the property's own class.
                struct.fields.add(new TopicModel.Field(fieldName, null, nestedBlock.getName(), false, null, null, null));
            } else if (propType instanceof Enumeration) {
                // Value property typed by a UML Enumeration (e.g.
                // SensorModalityEnum) — a genuinely new case, distinct from
                // both the nested-struct branch above and the primitive
                // fallback below. Registered once into result.enums (shared/
                // de-duplicated across fields/structs, same pattern as
                // result.types), referenced from the Field by name.
                Enumeration enumType = (Enumeration) propType;
                registerEnumType(enumType);
                struct.fields.add(new TopicModel.Field(fieldName, null, null,
                        isKeyProperty(prop), readMaxLength(prop), readArrayDimension(prop), enumType.getName()));
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
                struct.fields.add(new TopicModel.Field(fieldName, mapPrimitive(typeName), null,
                        isKeyProperty(prop), readMaxLength(prop), readArrayDimension(prop), null));
            }
        }

        result.types.put(name, struct);
        structsInProgress.remove(name);
    }

    private boolean hasDdsMember(Property prop) {
        return memberStereotype != null && StereotypesHelper.hasStereotype(prop, memberStereotype);
    }

    /**
     * True only if prop is stereotyped «DDS_Member» AND its "key" tagged
     * value reads as true — the stereotype alone isn't enough, since it's a
     * toggleable tag rather than a presence-only marker (a «DDS_Member»
     * Property with key=false must NOT be treated as a key).
     */
    private boolean isKeyProperty(Property prop) {
        if (!hasDdsMember(prop)) {
            return false;
        }
        List<String> values = StereotypesHelper.getStereotypePropertyValueAsString(
                prop, memberStereotype, MEMBER_KEY_TAG_NAME, false);
        String rawValue = (values == null || values.isEmpty()) ? null : values.get(0);
        log("Property '" + prop.getName() + "' stereotyped «DDS_Member» — raw 'key' tag: '" + rawValue + "'");
        return rawValue != null && "true".equalsIgnoreCase(rawValue.trim());
    }

    /**
     * Reads the optional Integer "max" tagged value (bounded string length)
     * off a «DDS_Member» Property — a Property may have DDS_Member applied
     * for its key setting alone with no "max" set, or vice versa, so
     * missing/unset reads as "no bound" (null), not an error. Only
     * meaningful for string-typed fields; DdsXmlGenerator is responsible for
     * ignoring this on a non-string field rather than this method trying to
     * know that here.
     *
     * NOTE: uses the raw-value accessor (getStereotypePropertyValue), NOT
     * getStereotypePropertyValueAsString() — "max" is a plain Integer tag,
     * same shape as domain_id, and getStereotypePropertyValueAsString() was
     * already found (see getDomainId()) to silently return "0" instead of
     * the actually-set value for that kind of tag in live testing. Still
     * logs the raw value read, same diagnostic discipline as everything else.
     */
    private Integer readMaxLength(Property prop) {
        if (!hasDdsMember(prop)) {
            return null;
        }
        List<?> values = StereotypesHelper.getStereotypePropertyValue(prop, memberStereotype, MEMBER_MAX_TAG_NAME);
        Object rawValue = (values == null || values.isEmpty()) ? null : values.get(0);
        log("Property '" + prop.getName() + "' stereotyped «DDS_Member» — raw 'max' value: '" + rawValue + "'");
        return rawValue instanceof Number ? ((Number) rawValue).intValue() : null;
    }

    /**
     * Detects a genuinely FIXED-size array via the Property's own
     * multiplicity (Property implements MultiplicityElement — confirmed via
     * local javadoc: {@code int getLower()}/{@code int getUpper()}, both
     * plain ints, no ValueSpecification unwrapping needed). Only
     * upper == lower AND upper > 1 counts (e.g. a [2] or [3] multiplicity on
     * a Real/Integer/etc. value property) — a variable-length sequence
     * (0..*, 1..*, or any other upper != lower shape) is deliberately left
     * alone (returns null) and is NOT the same thing; this scanner doesn't
     * attempt to handle sequences. Ordinary scalar properties (typically
     * lower=upper=1) also return null. Logs the raw lower/upper read, same
     * diagnostic discipline as domain_id/direction/key/max.
     */
    private Integer readArrayDimension(Property prop) {
        int lower = prop.getLower();
        int upper = prop.getUpper();
        log("Property '" + prop.getName() + "' multiplicity — lower=" + lower + " upper=" + upper);
        if (upper == lower && upper > 1) {
            return upper;
        }
        return null;
    }

    /**
     * Registers a UML Enumeration into result.enums the first time it's
     * referenced by any Field — subsequent references to the same
     * Enumeration (by name) are no-ops, same de-duplication shape as
     * result.types for nested structs. Literal names are read via
     * Enumeration.getOwnedLiteral() (confirmed via local javadoc: returns
     * List&lt;EnumerationLiteral&gt; in declaration order) and
     * EnumerationLiteral.getName() (inherited from NamedElement) — no
     * explicit integer values are read from the model; DdsXmlGenerator
     * assigns sequential ones on emission.
     */
    private void registerEnumType(Enumeration enumType) {
        String name = enumType.getName();
        if (result.enums.containsKey(name)) {
            return;
        }
        List<String> literalNames = new ArrayList<>();
        for (EnumerationLiteral literal : enumType.getOwnedLiteral()) {
            literalNames.add(literal.getName());
        }
        log("Enumeration '" + name + "' — literals: " + literalNames);
        result.enums.put(name, new TopicModel.EnumType(name, literalNames));
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
