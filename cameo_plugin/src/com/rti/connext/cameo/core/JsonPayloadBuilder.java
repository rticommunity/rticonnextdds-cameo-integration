/*
 * JsonPayloadBuilder.java — minimal JSON object builder, no external
 * dependency (mirrors the "no groovy.json available" constraint noted in
 * the WIS Groovy script — CAMEO's embedded script engine classpath doesn't
 * include it, so this plugin avoids relying on any JSON library too, in
 * case the same classpath restriction applies here).
 *
 * Deliberately supports only flat string/int/double keys — matches
 * ShapeType's actual fields (color: string, x/y/shapesize: int32). Extend
 * if a future type needs nested objects or arrays.
 *
 * TODO (chaining question you said you'd decide later): this class is the
 * answer to "how does data pass between Create JSON -> Add String Key ->
 * Publish to DDS Topic" IF you decide those are meant to build up one JSON
 * object incrementally. If instead each action should be fully independent
 * (no pin-based chaining), this class can just be used internally by a
 * single action instead of being the hand-off object between three actions.
 */
package com.rti.connext.cameo.core;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonPayloadBuilder {

    private final Map<String, String> rawFields = new LinkedHashMap<>();

    public static JsonPayloadBuilder create() {
        return new JsonPayloadBuilder();
    }

    /** Parses an existing JSON object string back into a builder, so a chain
     *  of actions can each add one field without re-specifying the whole
     *  payload. TODO: this is a minimal parser for FLAT {"k":"v","k2":1}
     *  objects only — no nested objects/arrays, no escaped quotes inside
     *  string values. Good enough for ShapeType; revisit if a future type
     *  needs more.
     */
    public static JsonPayloadBuilder fromExisting(String json) {
        JsonPayloadBuilder builder = new JsonPayloadBuilder();
        if (json == null || json.isBlank()) {
            return builder;
        }
        String body = json.trim();
        if (body.startsWith("{")) body = body.substring(1);
        if (body.endsWith("}")) body = body.substring(0, body.length() - 1);
        if (body.isBlank()) return builder;

        for (String pair : body.split(",")) {
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            String key = stripQuotes(pair.substring(0, colon).trim());
            String value = pair.substring(colon + 1).trim();
            builder.rawFields.put(key, value); // keep raw (quoted-or-not) form as-is
        }
        return builder;
    }

    /** Parses a flat {"k":"v","k2":1} JSON object into a name -> value map
     *  (String for quoted values, Long/Double for bare numbers, Boolean for
     *  true/false, null for null) — the read-side counterpart to build().
     *  Same flat-only limitation as fromExisting(): no nested objects/arrays,
     *  no escaped quotes inside string values. Used by the inbound DDS
     *  subscription path (PollDdsSubscriptionAndInject.groovy) to turn a
     *  received sample's JSON back into field values before injecting them
     *  via ALH.setValue(). */
    public static Map<String, Object> parseFlat(String json) {
        JsonPayloadBuilder raw = fromExisting(json);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.rawFields.entrySet()) {
            result.put(e.getKey(), coerce(e.getValue()));
        }
        return result;
    }

    private static Object coerce(String rawValue) {
        if (rawValue.length() >= 2 && rawValue.startsWith("\"") && rawValue.endsWith("\"")) {
            return stripQuotes(rawValue).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if ("true".equals(rawValue)) return Boolean.TRUE;
        if ("false".equals(rawValue)) return Boolean.FALSE;
        if ("null".equals(rawValue)) return null;
        try {
            return rawValue.contains(".") ? (Object) Double.valueOf(rawValue) : (Object) Long.valueOf(rawValue);
        } catch (NumberFormatException ex) {
            return rawValue; // not a recognized number shape — hand back the raw text
        }
    }

    public JsonPayloadBuilder addString(String key, String value) {
        rawFields.put(key, "\"" + escape(value) + "\"");
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

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
