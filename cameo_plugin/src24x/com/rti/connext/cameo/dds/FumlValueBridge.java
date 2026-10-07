/*
 * FumlValueBridge.java — CAMEO 2024x variant. See src26x/.../FumlValueBridge.java
 * for the 2026x port (fUML.Semantics.* moved to
 * com.nomagic.magicdraw.simulation.fuml.* between these two CST versions —
 * confirmed via javap against both installs' simulation.toolkit.core jars;
 * source-incompatible, hence two copies rather than one shared file). Picked
 * up by build.bat; build_26x.bat picks up the src26x/ copy instead. Every
 * other file in this package is fully shared between both builds.
 *
 * FumlValueBridge.java — the ONE deliberately-accepted internal-API-adjacent
 * dependency in this plugin.
 *
 * BACKGROUND: reading live field values out of a running CST simulation
 * requires touching NoMagic's fUML runtime value types
 * (fUML.Semantics.CommonBehaviors.Communications.SignalInstance,
 * fUML.Semantics.Classes.Kernel.StructuredValue, ...), all of which are
 * marked, at the class level, @InternalApi(reason="No Magic internal API.
 * This code can change without any notification.") + @Deprecated — confirmed
 * via `javap -v -p`. See DdsEngineListener.java's file header for the full
 * search history (SignalInstance, StructuredValue/CompoundValue/Value/
 * FeatureValue, ObjectToken/Token, ValuesHelper, StructuralFeatureListener —
 * all internal, no supported alternative for touching these TYPES directly).
 *
 * UPGRADE: com.nomagic.magicdraw.simulation.utils.ALH IS a fully public,
 * @OpenApiAll-marked class (confirmed via javap -v -p, no @Deprecated at the
 * class level, and no method-level override on the specific methods used
 * below) — it's NoMagic's own documented scripting helper, the same thing
 * available inside a Groovy Opaque Behavior. Its methods still take/return
 * the internal fUML types in their signatures (ALH.getValue(StructuredValue,
 * String) — the class boundary is public/supported even though the data
 * flowing through it is still fUML-shaped), so this file still needs to cast
 * to StructuredValue/SignalInstance to call it — but the METHOD CALLS
 * themselves (ALH.getValue(...), as opposed to StructuredValue.get(...)
 * directly) are now on a class NoMagic commits to keeping stable. The one
 * remaining direct internal-type touch is SignalInstance.type (a public but
 * individually @InternalApi+@Deprecated field — ALH has no equivalent
 * accessor for "what Signal is this SignalInstance for").
 *
 * SCOPE DECISION: isolate all of this — both the ALH usage and the one
 * remaining raw field read — to this one file. Every other class in the
 * plugin receives/passes plain java.lang.Object (or the public
 * com.nomagic.uml2...Signal type) and never imports fUML.Semantics.*, ALH,
 * or Token types directly.
 *
 * FAILURE MODE: every method here can throw. Callers MUST catch and treat
 * failure as "could not extract this field/signal" — never let a failure
 * here take down the simulation or the publish loop.
 */
package com.rti.connext.cameo.dds;

import com.nomagic.magicdraw.simulation.execution.session.SimulationSession;
import com.nomagic.magicdraw.simulation.utils.ALH;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;

import fUML.Semantics.Classes.Kernel.StructuredValue;
import fUML.Semantics.CommonBehaviors.Communications.SignalInstance;

final class FumlValueBridge {

    private FumlValueBridge() {
    }

    /**
     * Returns the UML Signal a SimulationExecutionListener.eventTriggered()
     * SignalInstance represents, or null if {@code signalInstanceObj} isn't
     * actually a SignalInstance (defensive — callers receive it as Object so
     * this class is the only one that imports the fUML type).
     */
    @SuppressWarnings("deprecation")
    static Signal getSignal(Object signalInstanceObj) {
        if (!(signalInstanceObj instanceof SignalInstance)) {
            return null;
        }
        return ((SignalInstance) signalInstanceObj).type;
    }

    /**
     * Reads one named field off a live simulation struct/signal value (a
     * SignalInstance, or a nested struct value reached from one), via
     * ALH.getValue() — NoMagic's own supported wrapper, not a direct call
     * onto the internal StructuredValue.get().
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
