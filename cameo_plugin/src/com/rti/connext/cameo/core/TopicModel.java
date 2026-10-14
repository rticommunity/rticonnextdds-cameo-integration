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
 * TopicModel — what ModelTopicScanner finds in a model: topics, their struct
 * and enum types, and which Blocks publish or subscribe to each. Plain data,
 * no CAMEO dependency.
 */
package com.rti.connext.cameo.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     *  "data" attribute), and which Blocks publish and subscribe to it. */
    public static final class Topic {
        public final String topicName;      // Signal name
        public final String typeName;       // name of the StructType (the Block typing the Signal's attribute)
        public final List<String> publisherBlockNames = new ArrayList<>();
        public final List<String> subscriberBlockNames = new ArrayList<>();

        // Subscriber Block name -> its inbound port's name.
        public final Map<String, String> subscriberPorts = new LinkedHashMap<>();

        // Subscriber Block name -> the ports connected to its inbound port
        // from outside (usually the harness's), which DdsInboundInjector
        // sends that Block's samples through.
        public final Map<String, Set<String>> injectionPorts = new LinkedHashMap<>();

        public Topic(String topicName, String typeName) {
            this.topicName = topicName;
            this.typeName = typeName;
        }
    }

    /** Every struct type, enum and topic found, keyed by name (types are
     *  shared between topics). */
    public final Map<String, StructType> types = new LinkedHashMap<>();
    public final Map<String, EnumType> enums = new LinkedHashMap<>();
    public final Map<String, Topic> topics = new LinkedHashMap<>();
}
