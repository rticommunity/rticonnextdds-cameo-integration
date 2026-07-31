/*
 * TestDdsActionsMenuAction.java — TEMPORARY test-only menu action.
 *
 * Not part of the final feature. This exists purely so the new action
 * classes (CreateJsonAction, AddStringKeyAction, PublishToDdsTopicAction)
 * can be exercised end-to-end from CAMEO's Tools menu, without needing
 * Cameo Simulation Toolkit wired up yet.
 *
 * What it does when clicked:
 *   1. CreateJsonAction:      builds {"color":"BLUE","x":100,"y":200,"shapesize":30}
 *   2. AddStringKeyAction:    adds a "note" field to that JSON
 *   3. PublishToDdsTopicAction: publishes the result to the "Square" topic
 *
 * Requires the Shape Publisher/Subscriber to already work (i.e. NDDSHOME,
 * RTI_LICENSE_FILE, native lib PATH all set up as in prior sessions) —
 * this reuses the same DomainParticipant/XML config resolution path.
 *
 * DELETE THIS FILE (and its one line of registration in
 * RTIConnextActionsConfigurator) once CST wiring replaces it.
 */
package com.rti.connext.cameo.actions;

import com.nomagic.magicdraw.actions.MDAction;
import com.nomagic.magicdraw.core.Application;

import java.awt.event.ActionEvent;
import java.util.LinkedHashMap;
import java.util.Map;

public class TestDdsActionsMenuAction extends MDAction {

    private static final String ID   = "RTI_TEST_DDS_ACTIONS";
    private static final String NAME = "Test: Create JSON -> Add Key -> Publish (Square)";

    public TestDdsActionsMenuAction() {
        super(ID, NAME, null, null);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        try {
            // ---- 1. Create JSON ----
            Map<String, Object> createInputs = new LinkedHashMap<>();
            createInputs.put("color", "BLUE");
            createInputs.put("x", 100);
            createInputs.put("y", 200);
            createInputs.put("shapesize", 30);
            Map<String, Object> createOutputs = new CreateJsonAction().execute(createInputs);
            String jsonAfterCreate = (String) createOutputs.get(CreateJsonAction.OUTPUT_KEY);
            log("[Test] CreateJsonAction -> " + jsonAfterCreate);

            // ---- 2. Add String Key ----
            Map<String, Object> addKeyInputs = new LinkedHashMap<>();
            addKeyInputs.put(AddStringKeyAction.INPUT_JSON, jsonAfterCreate);
            addKeyInputs.put(AddStringKeyAction.INPUT_KEY, "note");
            addKeyInputs.put(AddStringKeyAction.INPUT_VALUE, "sent from CAMEO test action");
            Map<String, Object> addKeyOutputs = new AddStringKeyAction().execute(addKeyInputs);
            String jsonAfterAddKey = (String) addKeyOutputs.get(AddStringKeyAction.OUTPUT_KEY);
            log("[Test] AddStringKeyAction -> " + jsonAfterAddKey);

            // ---- 3. Publish to DDS Topic ----
            // Deliberately publishing jsonAfterCreate (the clean 4-field
            // payload), NOT jsonAfterAddKey. ShapeType only defines
            // color/x/y/shapesize — DDSTopicPublisher does NOT strip unknown
            // fields the way the WIS Groovy script does, so publishing
            // jsonAfterAddKey (which has the extra "note" field) will likely
            // throw from DynamicData.from_string(). Left both steps in above
            // so you can see AddStringKeyAction's output either way; swap
            // this line to jsonAfterAddKey if you want to see that failure
            // mode, or extend JsonPayloadBuilder with allowlist filtering
            // (like the Groovy script's field-stripping) if you want chaining
            // to "just work" against a fixed-schema type like ShapeType.
            Map<String, Object> publishInputs = new LinkedHashMap<>();
            publishInputs.put(PublishToDdsTopicAction.INPUT_JSON, jsonAfterCreate);
            publishInputs.put(PublishToDdsTopicAction.INPUT_TOPIC, "Square");
            new PublishToDdsTopicAction().execute(publishInputs);
            log("[Test] PublishToDdsTopicAction -> published successfully to Square topic.");

        } catch (Exception ex) {
            log("[Test] FAILED: " + ex);
        }
    }

    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {
            System.out.println(msg);
        }
    }
}
