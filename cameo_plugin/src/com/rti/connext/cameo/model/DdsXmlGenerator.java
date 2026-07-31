/*
 * DdsXmlGenerator.java — generates an RTI Connext DDS XML Application
 * Creation config (the format resources/ShapeType.xml is written in, and
 * that DDSRunner/DDSTopicPublisher consume at runtime) from a TopicModel
 * produced by ModelDdsScanner.scan().
 *
 * Structure emitted, in order:
 *   <types>                        — one <struct> per TopicModel.StructType,
 *                                     ordered so nested struct types are
 *                                     defined before anything referencing them
 *   <domain_library><domain>       — one <register_type> per struct, one
 *                                     <topic> per TopicModel.Topic
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
package com.rti.connext.cameo.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DdsXmlGenerator {

    private static final String DOMAIN_LIBRARY_NAME = "DomainLibrary";
    private static final String DOMAIN_NAME = "Domain";
    private static final String DOMAIN_PARTICIPANT_LIBRARY_NAME = "DomainParticipantLibrary";

    private DdsXmlGenerator() {
    }

    public static String generate(TopicModel model) {
        List<TopicModel.StructType> orderedStructs = topologicallySortStructs(model);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<dds xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
           .append("xsi:noNamespaceSchemaLocation=\"http://community.rti.com/schema/7.5.0/rti_routing_service.xsd\" ")
           .append("version=\"7.5.0\">\n");

        appendTypes(xml, orderedStructs);
        appendDomainLibrary(xml, orderedStructs, model.topics);
        appendDomainParticipantLibrary(xml, model.topics);

        xml.append("</dds>\n");
        return xml.toString();
    }

    // -------------------------------------------------------------------------
    // <types>
    // -------------------------------------------------------------------------

    private static void appendTypes(StringBuilder xml, List<TopicModel.StructType> orderedStructs) {
        xml.append("    <types>\n");
        for (TopicModel.StructType struct : orderedStructs) {
            xml.append("      <struct name=\"").append(escapeAttr(struct.name))
               .append("\" extensibility=\"appendable\">\n");
            for (TopicModel.Field field : struct.fields) {
                String type = field.isNested() ? field.nestedStructName : field.primitiveType;
                xml.append("        <member name=\"").append(escapeAttr(field.name))
                   .append("\" type=\"").append(escapeAttr(type)).append("\"/>\n");
            }
            xml.append("      </struct>\n");
        }
        xml.append("    </types>\n\n");
    }

    // -------------------------------------------------------------------------
    // <domain_library><domain>
    // -------------------------------------------------------------------------

    private static void appendDomainLibrary(StringBuilder xml,
                                             List<TopicModel.StructType> orderedStructs,
                                             Map<String, TopicModel.Topic> topics) {
        xml.append("    <domain_library name=\"").append(DOMAIN_LIBRARY_NAME).append("\">\n");
        xml.append("      <domain name=\"").append(DOMAIN_NAME).append("\">\n");
        for (TopicModel.StructType struct : orderedStructs) {
            xml.append("        <register_type name=\"").append(escapeAttr(struct.name))
               .append("\" type_ref=\"").append(escapeAttr(struct.name)).append("\"/>\n");
        }
        for (TopicModel.Topic topic : topics.values()) {
            xml.append("        <topic name=\"").append(escapeAttr(topic.topicName))
               .append("\" register_type_ref=\"").append(escapeAttr(topic.typeName)).append("\"/>\n");
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
    // ModelDdsScanner.buildStructType() happens to insert nested types into
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
}
