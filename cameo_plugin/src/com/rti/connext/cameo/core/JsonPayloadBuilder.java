/*
 * JsonPayloadBuilder.java — builds the flat-or-nested JSON object handed to
 * RTI's DynamicData.from_string() on the outbound path. Values are added
 * already in JSON form (addRaw) or as strings (addString, quoted/escaped by
 * Gson), so nested objects and arrays are built by the caller and added raw.
 *
 * Parsing (the inbound direction) is NOT done here: DdsInboundInjector uses
 * Gson's JsonParser, which CAMEO 2024x and 2026x both ship on their runtime
 * classpath (lib/gson-*.jar, listed in lib/classpath.jar's manifest).
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
