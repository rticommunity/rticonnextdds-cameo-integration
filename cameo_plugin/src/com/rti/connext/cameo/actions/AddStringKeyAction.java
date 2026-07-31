/*
 * AddStringKeyAction.java — modeling Action: "Add String Key".
 *
 * Intended model-facing behavior: takes an existing JSON object string plus
 * a key and a string value, returns a new JSON string with that key added
 * (or overwritten if it already existed) — the chaining piece you said
 * we'd decide later. As written, this expects three named inputs:
 *   "json"  — the existing JSON object string (e.g. from CreateJsonAction,
 *             or "{}" / empty to start fresh)
 *   "key"   — the field name to add
 *   "value" — the string value to assign
 *
 * TODO: see DDSModelAction.java for the CST wiring caveat.
 * TODO: confirm these are really the three input names/keys CST will hand
 * you — I picked "json"/"key"/"value" to match the button description
 * ("add string key") but these aren't validated against any real pin
 * definitions yet since none exist.
 */
package com.rti.connext.cameo.actions;

import java.util.LinkedHashMap;
import java.util.Map;

public class AddStringKeyAction implements DDSModelAction {

    public static final String INPUT_JSON  = "json";
    public static final String INPUT_KEY   = "key";
    public static final String INPUT_VALUE = "value";
    public static final String OUTPUT_KEY  = "json";

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs) {
        String existingJson = inputs != null && inputs.get(INPUT_JSON) != null
                ? inputs.get(INPUT_JSON).toString() : "{}";
        String key = inputs != null && inputs.get(INPUT_KEY) != null
                ? inputs.get(INPUT_KEY).toString() : null;
        String value = inputs != null && inputs.get(INPUT_VALUE) != null
                ? inputs.get(INPUT_VALUE).toString() : null;

        if (key == null) {
            throw new IllegalArgumentException("AddStringKeyAction requires a 'key' input.");
        }

        String result = JsonPayloadBuilder.fromExisting(existingJson)
                .addString(key, value == null ? "" : value)
                .build();

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put(OUTPUT_KEY, result);
        return outputs;
    }
}
