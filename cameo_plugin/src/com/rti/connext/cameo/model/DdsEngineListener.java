/*
 * DdsEngineListener.java — EngineListener implementation that publishes to
 * DDS whenever a SendSignalAction on a <<Topic>>-stereotyped Signal fires
 * during a Cameo Simulation Toolkit run.
 *
 * Registration: see scripts/RegisterDdsEngineListener.groovy — a Groovy
 * Opaque Behavior body that calls
 *   _session_.getEngine().addEngineListener(new DdsEngineListener())
 * and must run exactly ONCE at simulation start.
 *
 * ============================== IMPORTANT ==============================
 * elementActivated()'s field-extraction logic is DELIBERATELY NOT
 * IMPLEMENTED YET. The runtime shape of the `values` Collection<?> for a
 * SendSignalAction activation has not been verified against a real running
 * simulation — only diagnostic logging (values.size(), each item's
 * getClass().getName() and toString()) is implemented below.
 *
 * Next step: attach a Send Signal Action to a <<Topic>>-stereotyped Signal,
 * run the simulation, trigger it, and read the "[DDS Listener] values[...]"
 * lines this prints to the GUI log. Only once that output is captured
 * should the real extraction logic (pulling field name -> value pairs out
 * of `values`, matched against the Signal's typing Block's fields — see
 * ModelDdsScanner.buildStructType()) be written in place of the
 * "not yet implemented" block below.
 *
 * participantConfigName / dataWriterName ARE fully wired up already and
 * verified against DdsXmlGenerator's actual output (not guessed):
 *   participantConfigName = "DomainParticipantLibrary::<BlockName>"
 *   dataWriterName         = "Publisher::<TopicName>Writer"
 * =========================================================================
 */
package com.rti.connext.cameo.model;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.simulation.engine.EngineListener;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.actions.mdbasicactions.SendSignalAction;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;

import java.util.Collection;

public class DdsEngineListener implements EngineListener {

    private static final String BLOCK_STEREOTYPE = "Block";
    private static final String TOPIC_STEREOTYPE = "Topic";

    @Override
    public void elementActivated(Element element, Collection<?> values) {
        try {
            if (!(element instanceof SendSignalAction)) {
                return;
            }
            SendSignalAction action = (SendSignalAction) element;
            Signal signal = action.getSignal();
            if (signal == null) {
                return;
            }
            if (!StereotypesHelper.hasStereotype(signal, TOPIC_STEREOTYPE)) {
                return;
            }

            // ---- DIAGNOSTIC — see file header. Capture this output first. ----
            log("[DDS Listener] SendSignalAction fired for Topic Signal '"
                    + signal.getName() + "' — values.size()=" + values.size());
            int i = 0;
            for (Object value : values) {
                try {
                    log("[DDS Listener]   values[" + i + "] class="
                            + (value == null ? "null" : value.getClass().getName())
                            + " toString=" + value);
                } catch (Exception diagEx) {
                    log("[DDS Listener]   values[" + i + "] — error reading value: " + diagEx);
                }
                i++;
            }

            Class owningBlock = findOwningBlock(element);
            if (owningBlock == null) {
                log("[DDS Listener] Could not find an owning <<Block>> for SendSignalAction on '"
                        + signal.getName() + "' — cannot determine participant name.");
                return;
            }
            String participantConfigName = "DomainParticipantLibrary::" + owningBlock.getName();
            String dataWriterName = "Publisher::" + signal.getName() + "Writer";
            log("[DDS Listener] Resolved participantConfigName=" + participantConfigName
                    + " dataWriterName=" + dataWriterName);

            // ---- TODO: field extraction — NOT YET IMPLEMENTED, see file header ----
            // Once the diagnostic output above has been captured against a real
            // simulation run, replace this block with:
            //   1. pull field name -> value pairs out of `values`
            //   2. build JSON via com.rti.connext.cameo.actions.JsonPayloadBuilder
            //   3. com.rti.connext.cameo.actions.DDSTopicPublisher.publishOnce(
            //          participantConfigName, dataWriterName, json)
            log("[DDS Listener] Field extraction not yet implemented — publish skipped. "
                    + "See DdsEngineListener.java header for next steps.");

        } catch (Exception ex) {
            log("[DDS Listener] elementActivated failed: " + ex);
        }
    }

    @Override
    public void elementDeactivated(Element element, Collection<?> values) {
        // no-op
    }

    @Override
    public void eventTriggered(String event) {
        // no-op
    }

    @Override
    public void executionTerminated() {
        // no-op — plugin-level shutdown (RTIConnextPlugin.close() ->
        // DDSTopicPublisher.shutdown()) already handles cached-participant
        // cleanup; tearing down per-simulation-run would be premature since
        // DDSTopicPublisher's cache is meant to outlive a single simulation.
    }

    private static Class findOwningBlock(Element element) {
        Element owner = element.getOwner();
        while (owner != null) {
            if (owner instanceof Class && StereotypesHelper.hasStereotype((Class) owner, BLOCK_STEREOTYPE)) {
                return (Class) owner;
            }
            owner = owner.getOwner();
        }
        return null;
    }

    private static void log(String msg) {
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {
            System.out.println(msg);
        }
    }
}
