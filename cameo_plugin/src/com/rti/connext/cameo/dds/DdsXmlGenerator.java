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
 * DdsXmlGenerator — turns a TopicModel into an RTI Connext DDS XML
 * Application Creation document (validated against RTI's
 * rti_dds_profiles.xsd, 7.5.0).
 *
 * Emitted, in order:
 *   <types>                       enums first, then structs ordered so every
 *                                 nested struct is defined before its users
 *   <qos_library>                 only when a «DDS_QosProfile» is given
 *   <domain_library>              one <register_type> per struct and one
 *                                 <topic> per topic. Registered types get a
 *                                 "_Type" suffix: register_type and topic
 *                                 share one namespace, and a payload Block
 *                                 named like its Signal would otherwise make
 *                                 RTI reject the whole document.
 *   <domain_participant_library>  one participant per publishing or
 *                                 subscribing Block, with "<Topic>Writer" /
 *                                 "<Topic>Reader" entities in fixed
 *                                 "Publisher" / "Subscriber" entities.
 *
 * Pure string in, string out (no file I/O, no CAMEO dependency), so it can be
 * exercised with a hand-built TopicModel.
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
    // Container names are constants, not taken from model package names.
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
        appendDomainParticipantLibrary(xml, model.topics, qosProfile);

        xml.append("</dds>\n");
        return xml.toString();
    }

    // -------------------------------------------------------------------------
    // <qos_library><qos_profile> — only when a QoS profile is given.
    // -------------------------------------------------------------------------

    private static void appendQosLibrary(StringBuilder xml, ModelTopicScanner.QosProfileInfo qosProfile) {
        xml.append("    <qos_library name=\"").append(QOS_LIBRARY_NAME).append("\">\n");
        xml.append("      <qos_profile name=\"").append(escapeAttr(qosProfile.profileName)).append("\"");
        if (qosProfile.isDefault) {
            xml.append(" is_default_qos=\"true\"");
        }
        xml.append(">\n");

        // No participant_name here: the profile is shared configuration, and a
        // shared name would give every participant the same identity. Each
        // participant is named after its own Block instead — see
        // appendDomainParticipantLibrary().

        // A profile section only applies to its own entity kind: writers read
        // <datawriter_qos>, readers read <datareader_qos>. The depth has to be
        // in both, or writers silently keep RTI's default KEEP_LAST depth 1.
        if (qosProfile.historyDepth != null) {
            for (String section : new String[] {"datawriter_qos", "datareader_qos"}) {
                xml.append("        <").append(section).append(">\n");
                xml.append("          <history>\n");
                xml.append("            <depth>").append(qosProfile.historyDepth).append("</depth>\n");
                xml.append("          </history>\n");
                xml.append("        </").append(section).append(">\n");
            }
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
     * Enums go before structs (they depend on nothing). Values are emitted
     * explicitly, 0, 1, 2... in declaration order — the same numbering RTI
     * would default to — so the generated XML is unambiguous.
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
     * Member syntax, as in RTI's own example types (Color.xml, EnumType.xml,
     * arrays.xml):
     *   - nested struct: {@code type="<StructName>"}
     *   - enum: {@code type="nonBasic" nonBasicTypeName="<EnumName>"}
     *   - {@code arrayDimensions}, {@code stringMaxLength} and {@code key}
     *     combine with either form.
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
                                                         Map<String, TopicModel.Topic> topics,
                                                         ModelTopicScanner.QosProfileInfo qosProfile) {
        xml.append("    <domain_participant_library name=\"")
           .append(DOMAIN_PARTICIPANT_LIBRARY_NAME).append("\">\n");

        // Every entity — participant, writer, reader — references the
        // profile explicitly: is_default_qos="true" has no effect on entities
        // declared in a domain_participant_library (verified with RTI 7.5).
        // Without the reference they fall back to RTI's default KEEP_LAST
        // depth 1, and on a keyless topic a second sample written right
        // after the first overwrites it before any reader takes it.
        String qosProfileRef = qosProfile != null
                ? QOS_LIBRARY_NAME + "::" + qosProfile.profileName
                : null;

        for (String blockName : collectParticipantBlockNames(topics)) {
            xml.append("      <domain_participant name=\"").append(escapeAttr(blockName))
               .append("\" domain_ref=\"").append(DOMAIN_LIBRARY_NAME).append("::")
               .append(DOMAIN_NAME).append("\">\n");
            if (qosProfileRef != null) {
                xml.append("        <domain_participant_qos base_name=\"")
                   .append(escapeAttr(qosProfileRef)).append("\">\n");
                xml.append("          <participant_name>\n");
                xml.append("            <name>").append(escapeAttr(blockName)).append("</name>\n");
                xml.append("          </participant_name>\n");
                xml.append("        </domain_participant_qos>\n");
            }

            List<TopicModel.Topic> published = topicsWhere(topics, blockName, true);
            if (!published.isEmpty()) {
                xml.append("        <publisher name=\"Publisher\">\n");
                for (TopicModel.Topic topic : published) {
                    xml.append("          <data_writer name=\"").append(escapeAttr(topic.topicName))
                       .append("Writer\" topic_ref=\"").append(escapeAttr(topic.topicName)).append("\"");
                    if (qosProfileRef != null) {
                        xml.append(">\n");
                        xml.append("            <datawriter_qos base_name=\"")
                           .append(escapeAttr(qosProfileRef)).append("\"/>\n");
                        xml.append("          </data_writer>\n");
                    } else {
                        xml.append("/>\n");
                    }
                }
                xml.append("        </publisher>\n");
            }

            List<TopicModel.Topic> subscribed = topicsWhere(topics, blockName, false);
            if (!subscribed.isEmpty()) {
                xml.append("        <subscriber name=\"Subscriber\">\n");
                for (TopicModel.Topic topic : subscribed) {
                    xml.append("          <data_reader name=\"").append(escapeAttr(topic.topicName))
                       .append("Reader\" topic_ref=\"").append(escapeAttr(topic.topicName)).append("\"");
                    if (qosProfileRef != null) {
                        xml.append(">\n");
                        xml.append("            <datareader_qos base_name=\"")
                           .append(escapeAttr(qosProfileRef)).append("\"/>\n");
                        xml.append("          </data_reader>\n");
                    } else {
                        xml.append("/>\n");
                    }
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
    // The scanner already inserts nested types first, but a hand-built
    // TopicModel may not, so struct order is computed here (depth-first
    // topological sort) rather than taken from the map.
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
