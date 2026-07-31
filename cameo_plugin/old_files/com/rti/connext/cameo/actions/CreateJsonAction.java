/*
 * CreateJsonAction.java — modeling Action: "Create JSON (inputs)".
 *
 * Intended model-facing behavior: takes a set of key/value inputs and
 * produces a JSON object string, e.g. inputs {color: "BLUE", x: 100} ->
 * output json = {"color":"BLUE","x":100}.
 *
 * Input handling below is written generically (String -> addString,
 * Integer -> addInt, anything else -> addRaw via toString()) so it isn't
 * hardcoded to ShapeType's four fields — works for any flat key/value set.
 *
 * TODO: see DDSModelAction.java for the CST wiring caveat — the
 * execute(Map inputs) signature here is a guess at how CST will hand you
 * parameter values; confirm and adjust.
 */
package com.rti.connext.cameo.actions;

import java.util.LinkedHashMap;
import java.util.Map;

public class CreateJsonAction implements DDSModelAction {

    public static final String OUTPUT_KEY = "json";

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs) {
        JsonPayloadBuilder builder = JsonPayloadBuilder.create();

        if (inputs != null) {
            for (Map.Entry<String, Object> entry : inputs.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof String) {
                    builder.addString(key, (String) value);
                } else if (value instanceof Integer) {
                    builder.addInt(key, (Integer) value);
                } else if (value != null) {
                    // Fallback: treat as raw (numbers/booleans print fine as toString();
                    // anything else probably needs a dedicated addX() method added above)
                    builder.addRaw(key, value.toString());
                }
            }
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put(OUTPUT_KEY, builder.build());
        return outputs;
    }
}
