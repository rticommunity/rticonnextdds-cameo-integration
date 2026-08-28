/*
 * DdsXmlGenerator.java — generates an RTI Connext DDS XML Application
 * Creation config (the format resources/ShapeType.xml is written in, and
 * that DDSRunner/DDSTopicPublisher consume at runtime) from a TopicModel
 * produced by ModelTopicScanner.scan().
 *
 * Structure emitted, in order:
 *   <types>                        — one <enum> per TopicModel.EnumType,
 *                                     emitted first (enums have no
 *                                     dependencies of their own), then one
 *                                     <struct> per TopicModel.StructType,
 *                                     ordered so nested struct types are
 *                                     defined before anything referencing them
 *   <qos_library><qos_profile>     — OMITTED entirely unless a QoS profile
 *                                     was chosen (see generate()'s qosProfile
 *                                     param); when present, one <qos_profile>
 *                                     matching the reference ShapeType.xml
 *                                     structure (domain_participant_qos/
 *                                     participant_name, datareader_qos/
 *                                     history/depth — each sub-block omitted
 *                                     when its source tag was unset)
 *   <domain_library><domain>       — one <register_type> per struct, one
 *                                     <topic> per TopicModel.Topic.
 *                                     register_type's name is the struct
 *                                     name with a "_Type" suffix (see
 *                                     registerTypeName()), NOT the bare
 *                                     struct name — register_type and topic
 *                                     objects share one naming namespace
 *                                     under <domain> in RTI's XML schema,
 *                                     and a model whose payload Block is
 *                                     named identically to its Signal (a
 *                                     natural, common modeling choice)
 *                                     would otherwise produce a
 *                                     register_type and a topic with the
 *                                     exact same name, which
 *                                     RTIXMLObject_addChild rejects as a
 *                                     duplicate — silently failing the
 *                                     ENTIRE profile document's parse, not
 *                                     just that one entry (confirmed live:
 *                                     "XML object with name
 *                                     '::DomainLibrary::Domain::X' already
 *                                     exists", every subsequent publish
 *                                     failing with RETCODE_ERROR because
 *                                     the whole XML never actually loaded)
 *   <domain_participant_library>   — one <domain_participant> per Block name
 *                                     that publishes or subscribes to any
 *                                     topic, with matching <data_writer>/
 *                                     <data_reader> entries (named
 *                                     "<topic>Writer"/"<topic>Reader" inside
 *                                     fixed "Publisher"/"Subscriber" blocks —
 *                                     the same convention DDSRunner and
 *                                     DDSTopicPublisher already assume)
 *
 * Pure String in/out — no file I/O, no MagicDraw dependency — same
 * "plain data in, plain data out" shape as TopicModel itself, so this can be
 * unit-tested with a hand-built TopicModel. The caller decides whether to
 * write the result to disk or wire it into a Tools-menu action.
 */
package com.rti.connext.cameo.dds;

import com.rti.connext.cameo.core.ModelTopicScanner;
import com.rti.connext.cameo.core.TopicModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DdsXmlGenerator {

    private static final String DOMAIN_LIBRARY_NAME = "DomainLibrary";
    private static final String DOMAIN_NAME = "Domain";
    private static final String DOMAIN_PARTICIPANT_LIBRARY_NAME = "DomainParticipantLibrary";
    // Fixed, like DOMAIN_LIBRARY_NAME/DOMAIN_NAME above — not derived from
    // the model's «DDS_QosLibrary» package name, matching the existing
    // convention that generated container names are constants, not
    // model-name-driven (the domain package's own name is similarly ignored
    // in favor of the fixed DOMAIN_NAME today).
    private static final String QOS_LIBRARY_NAME = "QosLibrary";

    private DdsXmlGenerator() {
    }

    /** @param qosProfile the chosen «DDS_QosProfile» to emit as a <qos_library> block, or null to omit it entirely */
    public static String generate(TopicModel model, int domainId, ModelTopicScanner.QosProfileInfo qosProfile) {
        List<TopicModel.StructType> orderedStructs = topologicallySortStructs(model);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<dds xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
           .append("xsi:noNamespaceSchemaLocation=\"http://community.rti.com/schema/7.5.0/rti_routing_service.xsd\" ")
           .append("version=\"7.5.0\">\n");

        appendTypes(xml, orderedStructs, model.enums);
        if (qosProfile != null) {
            appendQosLibrary(xml, qosProfile);
        }
        appendDomainLibrary(xml, orderedStructs, model.topics, domainId);
        appendDomainParticipantLibrary(xml, model.topics);

        xml.append("</dds>\n");
        return xml.toString();
    }

    // -------------------------------------------------------------------------
    // <qos_library><qos_profile> — omitted entirely by generate() when
    // qosProfile is null; see ImportQosProfileAction for how a
    // «DDS_QosProfile» element gets created in the first place.
    // -------------------------------------------------------------------------

    private static void appendQosLibrary(StringBuilder xml, ModelTopicScanner.QosProfileInfo qosProfile) {
        xml.append("    <qos_library name=\"").append(QOS_LIBRARY_NAME).append("\">\n");
        xml.append("      <qos_profile name=\"").append(escapeAttr(qosProfile.profileName)).append("\"");
        if (qosProfile.isDefault) {
            xml.append(" is_default_qos=\"true\"");
        }
        xml.append(">\n");

        if (qosProfile.participantName != null) {
            xml.append("        <domain_participant_qos>\n");
            xml.append("          <participant_name>\n");
            xml.append("            <name>").append(escapeAttr(qosProfile.participantName)).append("</name>\n");
            xml.append("          </participant_name>\n");
            xml.append("        </domain_participant_qos>\n");
        }

        if (qosProfile.historyDepth != null) {
            xml.append("        <datareader_qos>\n");
            xml.append("          <history>\n");
            xml.append("            <depth>").append(qosProfile.historyDepth).append("</depth>\n");
            xml.append("          </history>\n");
            xml.append("        </datareader_qos>\n");
        }

        xml.append("      </qos_profile>\n");
        xml.append("    </qos_library>\n\n");
    }

    // -------------------------------------------------------------------------
    // <types>
    // -------------------------------------------------------------------------

    private static void appendTypes(StringBuilder xml, List<TopicModel.StructType> orderedStructs,
                                     Map<String, TopicModel.EnumType> enums) {
        xml.append("    <types>\n");
        appendEnums(xml, enums);
        for (TopicModel.StructType struct : orderedStructs) {
            xml.append("      <struct name=\"").append(escapeAttr(struct.name))
               .append("\" extensibility=\"appendable\">\n");
            for (TopicModel.Field field : struct.fields) {
                appendMember(xml, field);
            }
            xml.append("      </struct>\n");
        }
        xml.append("    </types>\n\n");
    }

    /**
     * Emitted first, ahead of any <struct> — enums have no dependencies of
     * their own, so unlike structs they don't need topological ordering.
     * Literal values are always emitted explicitly, sequentially from 0 in
     * declaration order (ModelTopicScanner doesn't read explicit integer
     * values off the model) — RTI's own schema allows omitting {@code value}
     * entirely and defaults to the same sequential numbering (confirmed
     * against the real, RTI-shipped example
     * {@code Color.xml}: {@code <enumerator name="RED"/>} with no value),
     * but emitting it explicitly here removes any ambiguity.
     */
    private static void appendEnums(StringBuilder xml, Map<String, TopicModel.EnumType> enums) {
        for (TopicModel.EnumType enumType : enums.values()) {
            xml.append("      <enum name=\"").append(escapeAttr(enumType.name)).append("\">\n");
            int value = 0;
            for (String literalName : enumType.literalNames) {
                xml.append("        <enumerator name=\"").append(escapeAttr(literalName))
                   .append("\" value=\"").append(value).append("\"/>\n");
                value++;
            }
            xml.append("      </enum>\n");
        }
    }

    /**
     * type= handling, verified against real RTI Connext example XML shipped
     * under {@code <RTI install>\resource\app\...\test_types\} (Color.xml,
     * EnumType.xml, arrays.xml) rather than assumed:
     *   - a nested struct reference is a bare {@code type="<StructName>"} —
     *     unchanged from before this change, already proven correct at
     *     runtime (Health/SensorReport publish live in this project using
     *     exactly this form).
     *   - an ENUM reference is NOT a bare {@code type="<EnumName>"} — RTI's
     *     own example XML uses {@code type="nonBasic"
     *     nonBasicTypeName="<EnumName>"} instead (confirmed in Color.xml's
     *     consuming struct, EnumType.xml). Using the unverified bare form
     *     here would have been a guess; this is the confirmed one.
     *   - arrayDimensions is a separate, independent attribute that coexists
     *     with either form above (confirmed in arrays.xml: e.g.
     *     {@code type="int16" arrayDimensions="10,20"}), and with
     *     stringMaxLength/key (also confirmed in arrays.xml:
     *     {@code stringMaxLength="233" type="string" arrayDimensions="6,8"}).
     */
    private static void appendMember(StringBuilder xml, TopicModel.Field field) {
        xml.append("        <member name=\"").append(escapeAttr(field.name)).append("\"");
        if (field.isEnum()) {
            xml.append(" type=\"nonBasic\" nonBasicTypeName=\"").append(escapeAttr(field.enumTypeName)).append("\"");
        } else {
            String type = field.isNested() ? field.nestedStructName : field.primitiveType;
            xml.append(" type=\"").append(escapeAttr(type)).append("\"");
        }
        if (field.arrayDimension != null) {
            xml.append(" arrayDimensions=\"").append(field.arrayDimension).append("\"");
        }
        if (field.maxLength != null && "string".equals(field.primitiveType)) {
            xml.append(" stringMaxLength=\"").append(field.maxLength).append("\"");
        }
        if (field.isKey) {
            xml.append(" key=\"true\"");
        }
        xml.append("/>\n");
    }

    // -------------------------------------------------------------------------
    // <domain_library><domain>
    // -------------------------------------------------------------------------

    private static void appendDomainLibrary(StringBuilder xml,
                                             List<TopicModel.StructType> orderedStructs,
                                             Map<String, TopicModel.Topic> topics,
                                             int domainId) {
        xml.append("    <domain_library name=\"").append(DOMAIN_LIBRARY_NAME).append("\">\n");
        xml.append("      <domain name=\"").append(DOMAIN_NAME).append("\" domain_id=\"")
           .append(domainId).append("\">\n");
        for (TopicModel.StructType struct : orderedStructs) {
            xml.append("        <register_type name=\"").append(escapeAttr(registerTypeName(struct.name)))
               .append("\" type_ref=\"").append(escapeAttr(struct.name)).append("\"/>\n");
        }
        for (TopicModel.Topic topic : topics.values()) {
            xml.append("        <topic name=\"").append(escapeAttr(topic.topicName))
               .append("\" register_type_ref=\"").append(escapeAttr(registerTypeName(topic.typeName))).append("\"/>\n");
        }
        xml.append("      </domain>\n");
        xml.append("    </domain_library>\n\n");
    }

    // -------------------------------------------------------------------------
    // <domain_participant_library>
    // -------------------------------------------------------------------------

    private static void appendDomainParticipantLibrary(StringBuilder xml,
                                                         Map<String, TopicModel.Topic> topics) {
        xml.append("    <domain_participant_library name=\"")
           .append(DOMAIN_PARTICIPANT_LIBRARY_NAME).append("\">\n");

        for (String blockName : collectParticipantBlockNames(topics)) {
            xml.append("      <domain_participant name=\"").append(escapeAttr(blockName))
               .append("\" domain_ref=\"").append(DOMAIN_LIBRARY_NAME).append("::")
               .append(DOMAIN_NAME).append("\">\n");

            List<TopicModel.Topic> published = topicsWhere(topics, blockName, true);
            if (!published.isEmpty()) {
                xml.append("        <publisher name=\"Publisher\">\n");
                for (TopicModel.Topic topic : published) {
                    xml.append("          <data_writer name=\"").append(escapeAttr(topic.topicName))
                       .append("Writer\" topic_ref=\"").append(escapeAttr(topic.topicName)).append("\"/>\n");
                }
                xml.append("        </publisher>\n");
            }

            List<TopicModel.Topic> subscribed = topicsWhere(topics, blockName, false);
            if (!subscribed.isEmpty()) {
                xml.append("        <subscriber name=\"Subscriber\">\n");
                for (TopicModel.Topic topic : subscribed) {
                    xml.append("          <data_reader name=\"").append(escapeAttr(topic.topicName))
                       .append("Reader\" topic_ref=\"").append(escapeAttr(topic.topicName)).append("\"/>\n");
                }
                xml.append("        </subscriber>\n");
            }

            xml.append("      </domain_participant>\n");
        }

        xml.append("    </domain_participant_library>\n");
    }

    private static Set<String> collectParticipantBlockNames(Map<String, TopicModel.Topic> topics) {
        Set<String> names = new LinkedHashSet<>();
        for (TopicModel.Topic topic : topics.values()) {
            names.addAll(topic.publisherBlockNames);
            names.addAll(topic.subscriberBlockNames);
        }
        return names;
    }

    private static List<TopicModel.Topic> topicsWhere(Map<String, TopicModel.Topic> topics,
                                                        String blockName, boolean asPublisher) {
        List<TopicModel.Topic> result = new ArrayList<>();
        for (TopicModel.Topic topic : topics.values()) {
            List<String> names = asPublisher ? topic.publisherBlockNames : topic.subscriberBlockNames;
            if (names.contains(blockName)) {
                result.add(topic);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Struct dependency ordering
    //
    // ModelTopicScanner.buildStructType() happens to insert nested types into
    // TopicModel.types before the structs that reference them (it recurses
    // into the nested Block first), so TopicModel.types' LinkedHashMap order
    // already works for scanner-produced models. But this generator is
    // explicitly meant to also work against a hand-built TopicModel (e.g. in
    // a unit test), which isn't guaranteed to respect that insertion order —
    // so ordering is computed explicitly here via a DFS-based topological
    // sort instead of trusting the map's iteration order.
    // -------------------------------------------------------------------------

    private static List<TopicModel.StructType> topologicallySortStructs(TopicModel model) {
        List<TopicModel.StructType> ordered = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String name : model.types.keySet()) {
            visitStruct(name, model, visited, visiting, ordered);
        }
        return ordered;
    }

    private static void visitStruct(String name, TopicModel model, Set<String> visited,
                                     Set<String> visiting, List<TopicModel.StructType> ordered) {
        if (visited.contains(name)) return;
        if (visiting.contains(name)) {
            throw new IllegalStateException(
                    "Cycle detected in struct type dependencies: "
                            + String.join(" -> ", visiting) + " -> " + name);
        }
        TopicModel.StructType struct = model.types.get(name);
        if (struct == null) {
            throw new IllegalArgumentException(
                    "TopicModel references struct type '" + name
                            + "' which is not present in model.types.");
        }
        visiting.add(name);
        for (TopicModel.Field field : struct.fields) {
            if (field.isNested()) {
                visitStruct(field.nestedStructName, model, visited, visiting, ordered);
            }
        }
        visiting.remove(name);
        visited.add(name);
        ordered.add(struct);
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * register_type's name, guaranteed distinct from any topic name (which
     * bare struct.name is NOT, whenever a model's payload Block shares its
     * Signal's name) — see this file's header comment for why that
     * matters. This name is purely an internal XML alias (referenced only
     * by topic's own register_type_ref attribute, never by
     * DdsEngineListener/DDSTopicPublisher, which only ever look up
     * participants/writers/topics by the OTHER names in this file), so any
     * deterministic, collision-free suffix is safe to use here.
     */
    private static String registerTypeName(String structName) {
        return structName + "_Type";
    }
}
