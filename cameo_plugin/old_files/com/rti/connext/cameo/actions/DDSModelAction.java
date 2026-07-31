/*
 * DDSModelAction.java — common contract for the reusable modeling actions
 * (CreateJsonAction, AddStringKeyAction, PublishToDdsTopicAction).
 *
 * ============================== IMPORTANT ==============================
 * This interface is a PLACEHOLDER. I do not have your Cameo Simulation
 * Toolkit SDK jars, so I can't confirm the exact interface/abstract class
 * CST expects a Java-backed Opaque Behavior implementation to extend, or
 * the exact method signature it calls at simulation time (it varies by
 * CST/CAMEO version — commonly lives under
 * com.nomagic.magicdraw.simulation.* or
 * com.nomagic.magicdraw.experimental.simulation.* in various releases).
 *
 * What you need to do:
 *   1. Find CST's actual behavior-execution interface in your installed
 *      CAMEO_HOME\lib jars (or CST javadoc) — search for something like
 *      "ExecutableBehavior", "BehaviorExecutor", "OpaqueBehaviorHandler",
 *      or similar, in whatever CST jar defines Java-backed behavior hooks.
 *   2. Replace this interface's method signature (execute(...)) to match
 *      whatever CST actually calls, and have each action class below
 *      implement/extend that instead of this placeholder.
 *   3. Register each action class with CST the way it expects — usually
 *      via the Opaque Behavior's "Language" field set to something like
 *      "Java" and the class's fully-qualified name as the body, OR via a
 *      registration call in RTIConnextPlugin.init() — depends on your CST
 *      version's convention.
 *
 * Everything BELOW this interface boundary (JSON building, DDS publish
 * logic) is real, working code, not placeholder.
 * =========================================================================
 */
package com.rti.connext.cameo.actions;

import java.util.Map;

public interface DDSModelAction {

    /**
     * @param inputs  named input values as bound from the model (e.g. pin
     *                values / behavior parameters) — TODO: confirm how CST
     *                actually hands you parameter values (Map, individual
     *                typed getters, etc.) and adjust this signature.
     * @return named output values to bind back to the model — TODO: same
     *         caveat; confirm CST's actual return/output convention.
     */
    Map<String, Object> execute(Map<String, Object> inputs) throws Exception;
}
