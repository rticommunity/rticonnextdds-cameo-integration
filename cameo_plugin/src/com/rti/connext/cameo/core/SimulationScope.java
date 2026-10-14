/*
 * SimulationScope.java — works out which Blocks are part of the simulation
 * actually being run, so DDS participants are only started for those, not
 * for every DDS Block that happens to exist somewhere in the project.
 *
 * "Part of the simulation" = the simulated Block plus, recursively, the type
 * of every part property (composite aggregation, owned or inherited) of a
 * Block in scope — the composition tree CST instantiates — plus every Block
 * that a Block in scope specializes.
 *
 * v1 limitations, both deliberate:
 *   - Multiplicity is ignored. CST creates parts with lower bound >= 1
 *     automatically, but 0..1 / 0..* parts only when a behavior creates
 *     them; they are still in scope here, so their participant is on the DDS
 *     bus from simulation start.
 *   - One instance per Block is assumed (participants are per Block type).
 */
package com.rti.connext.cameo.core;

import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.AggregationKindEnum;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Class;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Classifier;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.InstanceSpecification;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Property;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SimulationScope {

    private static final String BLOCK_STEREOTYPE = "Block";
    // From CST's own SimulationProfile (profiles/SimulationProfile.mdzip).
    private static final String SIMULATION_CONFIG_STEREOTYPE = "SimulationConfig";
    private static final String EXECUTION_TARGET_TAG = "executionTarget";

    private SimulationScope() {
    }

    /**
     * Returns the names of the Blocks in the simulation started on
     * {@code simulated} — what SimulationSession.getElement() returns: a
     * Block (right-click > Simulate), a «SimulationConfig» (its
     * executionTarget is used), an InstanceSpecification, or a behavior or
     * other element owned by a Block. Returns an empty set if no Block can
     * be resolved.
     */
    public static Set<String> blockNamesIn(Element simulated) {
        Set<String> names = new LinkedHashSet<>();
        Class root = resolveRootBlock(simulated);
        if (root != null) {
            collect(root, names);
        }
        return names;
    }

    /** The Block a simulation run is rooted at, or null if none can be found. */
    public static Class resolveRootBlock(Element element) {
        if (element == null) {
            return null;
        }
        Stereotype config = StereotypesHelper.getAppliedStereotypeByString(element, SIMULATION_CONFIG_STEREOTYPE);
        if (config != null) {
            List<?> targets = StereotypesHelper.getStereotypePropertyValue(element, config, EXECUTION_TARGET_TAG);
            Object target = (targets == null || targets.isEmpty()) ? null : targets.get(0);
            return (target instanceof Element) ? resolveRootBlock((Element) target) : null;
        }
        if (element instanceof InstanceSpecification) {
            for (Classifier classifier : ((InstanceSpecification) element).getClassifier()) {
                if (isBlock(classifier)) {
                    return (Class) classifier;
                }
            }
            return null;
        }
        // A Block itself, or something owned by one (e.g. its classifier behavior).
        for (Element e = element; e != null; e = e.getOwner()) {
            if (isBlock(e)) {
                return (Class) e;
            }
        }
        return null;
    }

    private static void collect(Class block, Set<String> names) {
        if (!names.add(block.getName())) {
            return; // already visited — also stops a Block that (indirectly) contains itself
        }
        // An instance of a specialized Block is also an instance of every
        // Block it specializes. ModelTopicScanner records pub/sub under the
        // Block that DECLARES the port (e.g. a "Definition" Block), while the
        // part may be typed by a specialization (e.g. "..._Implementation"),
        // so the generals have to be in scope too.
        for (Classifier general : block.getGeneral()) {
            if (isBlock(general)) {
                collect((Class) general, names);
            }
        }
        for (Property property : attributesIncludingInherited(block)) {
            if (property.getAggregation() == AggregationKindEnum.COMPOSITE && isBlock(property.getType())) {
                collect((Class) property.getType(), names);
            }
        }
    }

    /** {@code classifier}'s own attributes plus those of everything it specializes. */
    private static Set<Property> attributesIncludingInherited(Classifier classifier) {
        Set<Property> attributes = new LinkedHashSet<>();
        Set<Classifier> visited = new LinkedHashSet<>();
        addAttributes(classifier, attributes, visited);
        return attributes;
    }

    private static void addAttributes(Classifier classifier, Set<Property> attributes, Set<Classifier> visited) {
        if (!visited.add(classifier)) {
            return;
        }
        attributes.addAll(classifier.getAttribute());
        for (Classifier general : classifier.getGeneral()) {
            addAttributes(general, attributes, visited);
        }
    }

    private static boolean isBlock(Element element) {
        return element instanceof Class && StereotypesHelper.hasStereotype(element, BLOCK_STEREOTYPE);
    }
}
