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
 * ModelTopicScanner — reads the DDS interface out of a SysML model into a
 * TopicModel:
 *   - topics: every «DDS_Topic» Signal in scope, with its "data" attribute's
 *     «Block» expanded into a DDS struct (nested Blocks become nested structs,
 *     Enumerations become enums, fixed multiplicities become arrays);
 *   - publishers and subscribers, structurally: a Block whose port is typed by
 *     an «InterfaceBlock» with a «FlowProperty» typed by a topic Signal
 *     publishes it ("out"), subscribes to it ("in"), or both ("inout");
 *   - for each subscriber, the ports connected to its inbound port from
 *     outside (where DdsInboundInjector delivers samples).
 *
 * Blocks normally live outside the «DDS_Domain» package that holds the
 * topics, so scan(Package) runs in two passes: topics from the domain, then
 * a project-wide port scan limited to those topics.
 *
 * Stereotypes are resolved by profile and name (DDS_Profile, SysML), so a
 * same-named stereotype from another profile is not mistaken for these.
 */
package com.rti.connext.cameo.core;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKindEnum;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Enumeration;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.EnumerationLiteral;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Package;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Type;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectableElement;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.Connector;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectorEnd;
import com.nomagic.uml2.ext.magicdraw.compositestructures.mdports.Port;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Profile;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ModelTopicScanner {

    private static final String BLOCK_STEREOTYPE = "Block";
    // DDS_Profile stereotypes and tags. Public where ImportQosProfileAction
    // (which creates QoS elements) needs the same names.
    public static final String DDS_PROFILE_NAME = "DDS_Profile";
    private static final String TOPIC_STEREOTYPE_NAME = "DDS_Topic";
    private static final String DOMAIN_STEREOTYPE_NAME = "DDS_Domain";
    private static final String DOMAIN_ID_TAG_NAME = "domain_id";
    // «DDS_Member» is applied only to a Property that is a key or a bounded
    // string; ordinary fields carry no stereotype.
    private static final String MEMBER_STEREOTYPE_NAME = "DDS_Member";
    private static final String MEMBER_KEY_TAG_NAME = "key";      // Boolean
    private static final String MEMBER_MAX_TAG_NAME = "max";      // Integer, optional — string length bound
    // «DDS_QosProfile» elements are Classes (as ImportQosProfileAction
    // creates them), usually inside a «DDS_QosLibrary» package.
    public static final String QOS_LIBRARY_STEREOTYPE_NAME = "DDS_QosLibrary";
    public static final String QOS_PROFILE_STEREOTYPE_NAME = "DDS_QosProfile";
    public static final String QOS_PARTICIPANT_NAME_TAG = "participant_name"; // String
    public static final String QOS_HISTORY_DEPTH_TAG = "history_depth";       // Integer
    public static final String QOS_IS_DEFAULT_TAG = "is_default";             // Boolean
    // InterfaceBlock and FlowProperty come from CAMEO's built-in "SysML"
    // profile.
    private static final String SYSML_PROFILE_NAME = "SysML";
    private static final String INTERFACE_BLOCK_STEREOTYPE_NAME = "InterfaceBlock";
    private static final String FLOW_PROPERTY_STEREOTYPE_NAME = "FlowProperty";
    private static final String FLOW_DIRECTION_TAG_NAME = "direction";
    // A «DDS_Topic» Signal's payload is its attribute named "data", typed by
    // the «Block» that defines the message. Public: the listener and injector
    // read and write the payload through it.
    public static final String TOPIC_DATA_ATTRIBUTE_NAME = "data";

    private final TopicModel result = new TopicModel();
    private final Set<String> structsInProgress = new HashSet<>(); // cycle guard
    private Stereotype topicStereotype;
    private Stereotype interfaceBlockStereotype;
    private Stereotype flowPropertyStereotype;
    private Stereotype memberStereotype;
    // True during scan(Package)'s second pass: ports then only attach
    // publishers/subscribers to topics the first pass found in the domain,
    // so another domain's topics can't leak into the result.
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
     * Scans one «DDS_Domain»: the scope used to generate DDS XML.
     *
     * Pass 1 registers the «DDS_Topic» Signals inside the domain package.
     * Pass 2 scans the ports of every Block in the project — Blocks usually
     * live outside the domain package — attaching publishers and subscribers
     * only to Pass 1's topics, so another domain's topics never enter the
     * result.
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
        // Integer tags are read with the raw-value accessor:
        // getStereotypePropertyValueAsString() returns "0" for them regardless
        // of the value set (observed on CAMEO 2024x).
        List<?> values = StereotypesHelper.getStereotypePropertyValue(domainPackage, domainStereotype, DOMAIN_ID_TAG_NAME);
        if (values == null || values.isEmpty()) {
            return null;
        }
        Object value = values.get(0);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    /**
     * Whether {@code signal} is a «DDS_Topic», resolved exactly as the scan
     * resolves it (used by DdsEngineListener at simulation time).
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
     * Finds every Class stereotyped «DDS_QosProfile» anywhere under root,
     * inside a «DDS_QosLibrary» or not.
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

            // Integer tag: raw-value accessor (see getDomainId()).
            List<?> depthValues = StereotypesHelper.getStereotypePropertyValue(
                    qosProfileElement, qosProfileStereotype, QOS_HISTORY_DEPTH_TAG);
            Object rawDepth = (depthValues == null || depthValues.isEmpty()) ? null : depthValues.get(0);
            historyDepth = rawDepth instanceof Number ? ((Number) rawDepth).intValue() : null;

            List<String> defaultValues = StereotypesHelper.getStereotypePropertyValueAsString(
                    qosProfileElement, qosProfileStereotype, QOS_IS_DEFAULT_TAG, false);
            String rawDefault = (defaultValues == null || defaultValues.isEmpty()) ? null : defaultValues.get(0);
            isDefault = rawDefault != null && "true".equalsIgnoreCase(rawDefault.trim());
        }

        return new QosProfileInfo(qosProfileElement.getName(), participantName, historyDepth, isDefault);
    }

    /**
     * Resolves a stereotype by profile and name, the same way the scan does
     * (used by ImportQosProfileAction before applying stereotypes).
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
            // Every reachable «DDS_Topic» Signal is a topic, whether or not
            // any behavior uses it.
            registerTopic((Signal) element);
        } else if (element instanceof Class && isBlock((Class) element)) {
            handleBlockPorts((Class) element);
        }

        for (Element child : element.getOwnedElement()) {
            visit(child);
        }
    }

    /**
     * Pass 2 of {@link #scan(Package)}: runs the port scan on every Block
     * under {@code element}, without registering any Signal it passes as a
     * topic (only the domain's own topics count).
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
        return StereotypesHelper.hasStereotype(clazz, BLOCK_STEREOTYPE);
    }

    // -------------------------------------------------------------------------
    // Publishers and subscribers: Block port -> «InterfaceBlock» ->
    // «FlowProperty» (direction) -> «DDS_Topic» Signal.
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
            // scan(Package) pass 2: only topics the domain already has.
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
        if (isIn) {
            collectInjectionPorts(block, port, topic);
        }
    }

    /**
     * Records the port at the far end of every connector wired into a
     * subscriber's inbound port from outside (typically the harness Block's
     * port, which may be untyped). DdsInboundInjector sends through it, so an
     * injected signal travels the modeled connector into the subscriber.
     *
     * "From outside" means the subscriber's connector end has a partWithPort
     * (the port is reached through a part typed by the subscriber). Delegation
     * connectors inside the subscriber have none and are skipped.
     */
    private void collectInjectionPorts(Class subscriberBlock, Port subscriberPort, TopicModel.Topic topic) {
        for (ConnectorEnd end : subscriberPort.getEnd()) {
            if (end.getPartWithPort() == null) {
                continue;
            }
            Connector connector = end.get_connectorOfEnd();
            if (connector == null) {
                continue;
            }
            for (ConnectorEnd farEnd : connector.getEnd()) {
                ConnectableElement farRole = farEnd.getRole();
                if (farEnd != end && farRole instanceof Port) {
                    topic.injectionPorts
                            .computeIfAbsent(subscriberBlock.getName(), k -> new LinkedHashSet<>())
                            .add(farRole.getName());
                }
            }
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
     * Registers {@code signal} as a topic if it is a «DDS_Topic» with a valid
     * payload; returns null otherwise (plain Signals are not topics).
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

        // Convention: the Signal has an attribute named "data", typed by the
        // «Block» that defines the DDS payload type. Looking it up BY NAME (not
        // just "the first attribute") catches modeling mistakes instead of
        // silently grabbing the wrong attribute. A Signal that doesn't follow
        // the convention is skipped with a warning rather than registered:
        // a topic with no valid type would make RTI reject the entire
        // generated XML, taking every other topic down with it.
        Type payloadType = null;
        for (Property attr : signal.getOwnedAttribute()) {
            if (TOPIC_DATA_ATTRIBUTE_NAME.equals(attr.getName())) {
                payloadType = attr.getType();
                break;
            }
        }

        if (payloadType == null) {
            log("WARNING: «DDS_Topic» Signal '" + topicName + "' skipped: it needs an attribute named '"
                    + TOPIC_DATA_ATTRIBUTE_NAME + "', typed by the «Block» that defines its payload.");
            return null;
        }

        if (!StereotypesHelper.hasStereotype(payloadType, BLOCK_STEREOTYPE)) {
            log("WARNING: «DDS_Topic» Signal '" + topicName + "' skipped: its '" + TOPIC_DATA_ATTRIBUTE_NAME
                    + "' attribute is typed by '" + payloadType.getName() + "', which is not a «Block».");
            return null;
        }

        Class typeBlock = (Class) payloadType;
        buildStructType(typeBlock); // ensures result.types has this entry

        TopicModel.Topic topic = new TopicModel.Topic(topicName, typeBlock.getName());
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

        for (Property prop : block.getOwnedAttribute()) {
            String fieldName = prop.getName();
            Type propType = prop.getType();

            // Composite aggregation = part property (nested struct);
            // otherwise a value property.
            boolean isPart = prop.getAggregation() == AggregationKindEnum.COMPOSITE;

            if (isPart && propType instanceof Class && isBlock((Class) propType)) {
                Class nestedBlock = (Class) propType;
                buildStructType(nestedBlock); // recurse
                // Key, bound and array detection apply to value properties
                // only.
                struct.fields.add(new TopicModel.Field(fieldName, null, nestedBlock.getName(), false, null, null, null));
            } else if (propType instanceof Enumeration) {
                // Enumeration-typed value property: the enum is registered
                // once and referenced by name.
                Enumeration enumType = (Enumeration) propType;
                registerEnumType(enumType);
                struct.fields.add(new TopicModel.Field(fieldName, null, null,
                        isKeyProperty(prop), readMaxLength(prop), readArrayDimension(prop), enumType.getName()));
            } else {
                // Primitive value property; see mapPrimitive().
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
     * True if {@code prop} is a «DDS_Member» whose "key" tag is true (the
     * stereotype alone doesn't make a key).
     */
    private boolean isKeyProperty(Property prop) {
        if (!hasDdsMember(prop)) {
            return false;
        }
        List<String> values = StereotypesHelper.getStereotypePropertyValueAsString(
                prop, memberStereotype, MEMBER_KEY_TAG_NAME, false);
        String rawValue = (values == null || values.isEmpty()) ? null : values.get(0);
        return rawValue != null && "true".equalsIgnoreCase(rawValue.trim());
    }

    /**
     * The «DDS_Member» "max" tag (bounded string length), or null if unset.
     * DdsXmlGenerator ignores it on non-string fields. Integer tag: raw-value
     * accessor (see getDomainId()).
     */
    private Integer readMaxLength(Property prop) {
        if (!hasDdsMember(prop)) {
            return null;
        }
        List<?> values = StereotypesHelper.getStereotypePropertyValue(prop, memberStereotype, MEMBER_MAX_TAG_NAME);
        Object rawValue = (values == null || values.isEmpty()) ? null : values.get(0);
        return rawValue instanceof Number ? ((Number) rawValue).intValue() : null;
    }

    /**
     * The length of a fixed-size array ([N] multiplicity with N > 1), or
     * null. Variable-length multiplicities (0..*, 1..*) are not supported as
     * DDS sequences and are treated as single values.
     */
    private Integer readArrayDimension(Property prop) {
        int lower = prop.getLower();
        int upper = prop.getUpper();
        if (upper == lower && upper > 1) {
            return upper;
        }
        return null;
    }

    /**
     * Registers an Enumeration once, with its literal names in declaration
     * order; DdsXmlGenerator numbers them sequentially.
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
        result.enums.put(name, new TopicModel.EnumType(name, literalNames));
    }

    /**
     * Maps a model primitive type to a DDS type. Only the four UML
     * primitives are mapped; any other value type (e.g. a SysML value type
     * with units) is sent as a string — a known limitation.
     */
    private String mapPrimitive(String modelTypeName) {
        switch (modelTypeName) {
            case "Integer": return "int32";
            case "Real":    return "float64";
            case "Boolean": return "boolean";
            case "String":  return "string";
            default:        return "string";
        }
    }
}
