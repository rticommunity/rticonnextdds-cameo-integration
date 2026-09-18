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
import com.nomagic.magicdraw.simulation.fuml.activities.intermediate.Token;
import com.nomagic.magicdraw.simulation.utils.ALH;
import com.nomagic.uml2.ext.magicdraw.commonbehaviors.mdcommunications.Signal;

import fUML.Semantics.Classes.Kernel.StructuredValue;
import fUML.Semantics.CommonBehaviors.Communications.SignalInstance;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

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
     * onto the internal StructuredValue.get(). {@code fieldName} is passed
     * through verbatim, so callers can experiment with dotted/compound keys
     * without this file needing to know what they mean.
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

    /**
     * Returns ALH.getContext() — when constructed via the 2-arg
     * ALH(SimulationSession) constructor (as here), its own explicit
     * `context` field is unset, so getContext() falls back to
     * ContextHelper.findNearestObject() internally to dynamically resolve
     * "the nearest currently-running object" from the session. May return
     * null (see ALH's own bytecode — falls through to null if session/
     * context resolution comes up empty).
     */
    static Object getContext(SimulationSession session) {
        return new ALH(session).getContext();
    }

    /**
     * Unwraps an elementActivated() Collection<?> entry (an ObjectToken/
     * ControlToken) to the fUML Value it carries, or null if
     * {@code tokenObj} isn't a Token or carries no value (e.g. a
     * ControlToken). This is the argument value as it existed WHEN THE
     * ACTION ACTIVATED — potentially earlier/different from whatever
     * eventTriggered(SignalInstance) later delivers, since UML signal-send
     * semantics typically copy argument values into a fresh SignalInstance,
     * and that copy may not replicate every internal storage path.
     */
    @SuppressWarnings("deprecation")
    static Object getTokenValue(Object tokenObj) {
        if (!(tokenObj instanceof Token)) {
            return null;
        }
        return ((Token) tokenObj).getValue();
    }

    /**
     * DIAGNOSTIC ONLY (PORT-DIAG experiment — see DdsEngineListener.java's
     * Port/Connector elementActivated() handling). Reflects over every
     * declared field on {@code obj}, walking the superclass chain up to
     * (not including) Object, entirely bypassing ALH.getValue()'s
     * lookup-by-name. The goal is seeing the object's true raw internal
     * state — testing whether Set_Object_Value-written data is present
     * under some other field/key than what ALH.getValue() looks for, versus
     * genuinely not being there at this vantage point either.
     *
     * Returns an empty list if {@code obj} isn't a StructuredValue (or is
     * null) — callers should treat that as "nothing to dump", not an error.
     * A single field's reflection failure (e.g. an inaccessible/exotic
     * field) is captured as its own line rather than aborting the rest of
     * the dump.
     */
    @SuppressWarnings("deprecation")
    static List<String> dumpFieldsViaReflection(Object obj) {
        List<String> lines = new ArrayList<>();
        if (!(obj instanceof StructuredValue)) {
            return lines;
        }
        Class<?> type = obj.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                String label = type.getSimpleName() + "." + field.getName()
                        + " (" + field.getType().getName() + ")";
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    lines.add(label + " = " + (value == null ? "null" : value.toString()));
                } catch (Exception ex) {
                    lines.add(label + " — " + ex.getClass().getName() + ": " + ex.getMessage());
                }
            }
            type = type.getSuperclass();
        }
        return lines;
    }
}