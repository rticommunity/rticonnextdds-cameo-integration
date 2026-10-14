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
 * FumlValueBridge — the plugin's only access to Cameo Simulation Toolkit's
 * fUML runtime value types (CAMEO 2024x; the src26x copy differs only in the
 * imports, because these types moved package in CST 2026x).
 *
 * Reading field values out of a running simulation requires CST's fUML value
 * types (SignalInstance, StructuredValue, Object_), which CST marks
 * @InternalApi + @Deprecated; there is no public alternative. To keep that
 * dependency contained:
 *   - field values are read through ALH, CST's public (@OpenApiAll)
 *     scripting helper, rather than through the internal types' own methods;
 *   - the remaining direct uses (SignalInstance.type, Object_.getTypes()) are
 *     in this class only;
 *   - every other class passes plain Objects and never imports these types.
 *
 * Every method can throw; callers treat a failure as "could not read this
 * value" and must not let it stop the simulation.
 */
package com.rti.connext.cameo.dds;

import com.nomagic.magicdraw.simulation.execution.session.SimulationSession;
import com.nomagic.magicdraw.simulation.utils.ALH;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;

import fUML.Semantics.Classes.Kernel.Object_;
import fUML.Semantics.Classes.Kernel.StructuredValue;
import fUML.Semantics.CommonBehaviors.Communications.SignalInstance;

final class FumlValueBridge {

    private FumlValueBridge() {
    }

    /**
     * The UML Signal a SignalInstance (from
     * SimulationExecutionListener.eventTriggered()) is an instance of, or null
     * if {@code signalInstanceObj} isn't a SignalInstance.
     */
    @SuppressWarnings("deprecation")
    static Signal getSignal(Object signalInstanceObj) {
        if (!(signalInstanceObj instanceof SignalInstance)) {
            return null;
        }
        return ((SignalInstance) signalInstanceObj).type;
    }

    /**
     * Returns the «Block» a running simulation object (e.g. ALH.getContext())
     * is an instance of, or null if {@code obj} isn't a simulation object or
     * has no Block type.
     */
    @SuppressWarnings("deprecation")
    static Class getBlockType(Object obj) {
        if (!(obj instanceof Object_)) {
            return null;
        }
        for (Classifier type : ((Object_) obj).getTypes()) {
            if (type instanceof Class && StereotypesHelper.hasStereotype(type, "Block")) {
                return (Class) type;
            }
        }
        return null;
    }

    /**
     * Reads one named field of a running simulation value (a SignalInstance,
     * or a nested struct value reached from one) through ALH.getValue().
     *
     * @throws IllegalArgumentException if {@code structuredValueObj} isn't a
     *                                   StructuredValue
     */
    @SuppressWarnings("deprecation")
    static Object getFieldValue(SimulationSession session, Object structuredValueObj, String fieldName) {
        if (!(structuredValueObj instanceof StructuredValue)) {
            throw new IllegalArgumentException("Not a StructuredValue: "
                    + (structuredValueObj == null ? "null" : structuredValueObj.getClass().getName()));
        }
        ALH alh = new ALH(session);
        return alh.getValue((StructuredValue) structuredValueObj, fieldName);
    }
}
