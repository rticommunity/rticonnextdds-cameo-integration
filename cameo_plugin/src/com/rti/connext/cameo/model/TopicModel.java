/*
 * TopicModel.java — plain data classes describing what ModelDdsScanner
 * finds in the SysML model: a Signal acting as a DDS Topic, its type
 * structure (recursively built from the typing Block's value/part
 * properties), and which Blocks publish/subscribe to it.
 *
 * These are pure data holders — no MagicDraw API dependency — so they're
 * easy to unit test and easy for DdsXmlGenerator to consume later.
 */
package com.rti.connext.cameo.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TopicModel {

    /** One member of a DDS struct type — either a primitive or a nested struct. */
    public static final class Field {
        public final String name;
        public final String primitiveType;   // e.g. "string", "int32", "float64" — null if nested
        public final String nestedStructName; // name of the nested StructType — null if primitive

        public Field(String name, String primitiveType, String nestedStructName) {
            this.name = name;
            this.primitiveType = primitiveType;
            this.nestedStructName = nestedStructName;
        }

        public boolean isNested() {
            return nestedStructName != null;
        }
    }

    /** A DDS struct type, built recursively from a Block's value/part properties. */
    public static final class StructType {
        public final String name; // Block name
        public final List<Field> fields = new ArrayList<>();

        public StructType(String name) {
            this.name = name;
        }
    }

    /** A Signal acting as a Topic: its name, its type (the Block typing its one
     *  attribute), and which Blocks publish/subscribe to it (by name, plus the
     *  Port used for publishing where known). */
    public static final class Topic {
        public final String topicName;      // Signal name
        public final String typeName;       // name of the StructType (the Block typing the Signal's attribute)
        public final List<String> publisherBlockNames = new ArrayList<>();
        public final List<String> subscriberBlockNames = new ArrayList<>();

        public Topic(String topicName, String typeName) {
            this.topicName = topicName;
            this.typeName = typeName;
        }
    }

    /** Full scan result: every struct type discovered (keyed by name, since
     *  nested types are shared/de-duplicated across topics), and every topic. */
    public final Map<String, StructType> types = new LinkedHashMap<>();
    public final Map<String, Topic> topics = new LinkedHashMap<>();
}
