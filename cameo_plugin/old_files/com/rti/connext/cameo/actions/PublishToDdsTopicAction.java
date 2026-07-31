/*
 * PublishToDdsTopicAction.java — modeling Action: "Publish to DDS Topic (topic)".
 *
 * Intended model-facing behavior: takes a JSON payload string and a topic
 * name, publishes one sample to that topic via DDSTopicPublisher.
 *
 * IMPORTANT — topic name vs. writer/participant name mismatch to resolve:
 * the existing plugin hardcodes "DomainParticipantLibrary::SquareParticipant"
 * and "Publisher::SquareWriter" (see DDSRunner). A user-facing "topic" input
 * (e.g. "Square", "Circle", "Triangle") needs to map to the right
 * participant-config-name/writer-name pair defined in your XML Application
 * Creation file. Two ways to handle this, pick one:
 *
 *   (a) Require the model author to supply the full writer name directly
 *       (e.g. "Publisher::CircleWriter") — simplest, no mapping table,
 *       but less friendly as a "topic" concept.
 *   (b) Add a small lookup table here (topic name -> participant config +
 *       writer name) that you maintain alongside your XML config. Stubbed
 *       below as TOPIC_TO_WRITER — currently only has Square, matching
 *       what's proven working; add Circle/Triangle entries once your XML
 *       config actually defines those writers (per the WIS README's
 *       REST URL structure table, those three exist as
 *       SquareWriter/CircleWriter/TriangleWriter conventionally).
 *
 * TODO: see DDSModelAction.java for the CST wiring caveat.
 */
package com.rti.connext.cameo.actions;

import java.util.LinkedHashMap;
import java.util.Map;

public class PublishToDdsTopicAction implements DDSModelAction {

    public static final String INPUT_JSON  = "json";
    public static final String INPUT_TOPIC = "topic";

    private static final String PARTICIPANT_CONFIG = "DomainParticipantLibrary::SquareParticipant";

    // TODO: fill in once your XML config defines writers for these topics.
    // Key = topic name as the model author types it; value = full data
    // writer name as registered in the XML config (matches
    // "<publisher-name>::<writer-name>" convention seen in DDSRunner).
    private static final Map<String, String> TOPIC_TO_WRITER = Map.of(
            "Square", "Publisher::SquareWriter"
            // "Circle",   "Publisher::CircleWriter",
            // "Triangle", "Publisher::TriangleWriter"
    );

    @Override
    public Map<String, Object> execute(Map<String, Object> inputs) throws Exception {
        String json = inputs != null && inputs.get(INPUT_JSON) != null
                ? inputs.get(INPUT_JSON).toString() : null;
        String topic = inputs != null && inputs.get(INPUT_TOPIC) != null
                ? inputs.get(INPUT_TOPIC).toString() : null;

        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("PublishToDdsTopicAction requires a 'json' input.");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("PublishToDdsTopicAction requires a 'topic' input.");
        }

        String writerName = TOPIC_TO_WRITER.get(topic);
        if (writerName == null) {
            throw new IllegalArgumentException(
                    "Unknown topic '" + topic + "'. Known topics: " + TOPIC_TO_WRITER.keySet()
                            + " — add an entry to TOPIC_TO_WRITER if your XML config defines more.");
        }

        DDSTopicPublisher.publishOnce(PARTICIPANT_CONFIG, writerName, json);

        // No meaningful output beyond success (exception thrown on failure) —
        // return empty map for a consistent DDSModelAction contract.
        return new LinkedHashMap<>();
    }
}
