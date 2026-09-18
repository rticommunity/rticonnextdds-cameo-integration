/*
 * TopicModel.java — plain data classes describing what ModelTopicScanner
 * finds in the SysML model: a Signal acting as a DDS Topic, its type
 * structure (recursively built from the typing Block's value/part
 * properties), and which Blocks publish/subscribe to it.
 *
 * These are pure data holders — no MagicDraw API dependency — so they're
 * easy to unit test and easy for DdsXmlGenerator to consume later.
 */
package com.rti.connext.cameo.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TopicModel {

    /** One member of a DDS struct type — a primitive, a nested struct, or an
     *  enum reference. */
    public static final class Field {
        public final String name;
        public final String primitiveType;   // e.g. "string", "int32", "float64" — null if nested/enum
        public final String nestedStructName; // name of the nested StructType — null if not a nested struct
        public final boolean isKey;           // true if the source Property is stereotyped «DDS_Member» with key=true
        public final Integer maxLength;       // «DDS_Member»'s "max" tag (bounded string length) — null if unset/not applicable
        public final Integer arrayDimension;  // fixed-size array length (Property multiplicity, upper==lower>1) — null if not an array
        public final String enumTypeName;     // name of the EnumType this field references — null if not an enum

        public Field(String name, String primitiveType, String nestedStructName, boolean isKey, Integer maxLength,
                      Integer arrayDimension, String enumTypeName) {
            this.name = name;
            this.primitiveType = primitiveType;
            this.nestedStructName = nestedStructName;
            this.isKey = isKey;
            this.maxLength = maxLength;
            this.arrayDimension = arrayDimension;
            this.enumTypeName = enumTypeName;
        }

        public boolean isNested() {
            return nestedStructName != null;
        }

        public boolean isEnum() {
            return enumTypeName != null;
        }

        public boolean isArray() {
            return arrayDimension != null;
        }
    }

    /** A UML Enumeration referenced by one or more Fields — literal names
     *  only (no explicit integer values; DdsXmlGenerator assigns sequential
     *  ones on emission), in declaration order. */
    public static final class EnumType {
        public final String name;
        public final List<String> literalNames;

        public EnumType(String name, List<String> literalNames) {
            this.name = name;
            this.literalNames = literalNames;
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

        // Block name -> the actual inbound Port's own name on that Block.
        // Same discovery pass as subscriberBlockNames (handleBlockPorts()),
        // just also keeping what that method used to discard. Lets a fully
        // generic inbound injector (DdsInboundInjector) resolve which Port
        // to ALH.sendSignal() through for a given (Block, Topic) pairing
        // without any per-topic hardcoding.
        public final Map<String, String> subscriberPorts = new LinkedHashMap<>();

        public Topic(String topicName, String typeName) {
            this.topicName = topicName;
            this.typeName = typeName;
        }
    }

    /** Full scan result: every struct type discovered (keyed by name, since
     *  nested types are shared/de-duplicated across topics), every enum
     *  discovered (same de-duplication, keyed by Enumeration name), and
     *  every topic. */
    public final Map<String, StructType> types = new LinkedHashMap<>();
    public final Map<String, EnumType> enums = new LinkedHashMap<>();
    public final Map<String, Topic> topics = new LinkedHashMap<>();
}
