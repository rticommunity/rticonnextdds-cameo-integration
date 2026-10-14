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
 * JsonPayloadBuilder — builds the JSON object handed to RTI's
 * DynamicData.from_string() when publishing. Values are added as JSON
 * (addRaw) or as strings (addString, escaped by Gson); nested objects and
 * arrays are built by the caller and added raw.
 *
 * Parsing received samples is done in DdsInboundInjector with Gson, which
 * CAMEO 2024x and 2026x both ship on their runtime classpath.
 */
package com.rti.connext.cameo.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonPayloadBuilder {

    // HTML escaping off: it would turn '<' into \u003c, valid JSON but noisy in logs.
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Map<String, String> rawFields = new LinkedHashMap<>();

    public static JsonPayloadBuilder create() {
        return new JsonPayloadBuilder();
    }

    public JsonPayloadBuilder addString(String key, String value) {
        rawFields.put(key, quote(value));
        return this;
    }

    public JsonPayloadBuilder addInt(String key, int value) {
        rawFields.put(key, String.valueOf(value));
        return this;
    }

    public JsonPayloadBuilder addRaw(String key, String rawJsonValue) {
        rawFields.put(key, rawJsonValue);
        return this;
    }

    public String build() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : rawFields.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /** Returns {@code s} as a quoted JSON string, with every character that
     *  JSON requires escaped (quotes, backslashes, control characters). */
    public static String quote(String s) {
        return GSON.toJson(s);
    }
}
