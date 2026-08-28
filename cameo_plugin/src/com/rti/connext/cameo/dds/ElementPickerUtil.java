/*
 * ElementPickerUtil.java — shared single-element picker used by
 * GenerateDdsXmlAction (DDS_Domain package, QoS profile) and
 * ImportQosProfileAction (DDS_QosLibrary package), replacing what used to
 * be three separate plain JOptionPane dropdowns with CAMEO's own
 * ElementSelectionDlg, for a consistent, standard-CAMEO picker UI.
 *
 * ============================== CONFIDENCE NOTE ==============================
 * Every class/method used below (ElementSelectionDlgFactory.create()/
 * initSingle(), ElementSelectionDlg.show()/isOkClicked()/getSelectedElement(),
 * MDDialogParentProvider.getProvider().getDialogOwner(), SelectElementInfo's
 * 4-arg constructor, TypeFilter) was individually confirmed against the
 * local CAMEO 2024.3 javadoc before writing this file — not guessed.
 *
 * KEY FINDING that shapes this whole file: ElementSelectionDlg is
 * fundamentally a TREE BROWSER of the model (SelectElementInfo.root is
 * where the tree starts), not a picker over an arbitrary flat Java list —
 * there's no "just show these N objects" constructor. The mechanism that
 * DOES let this restrict selection to an exact candidate set is TypeFilter:
 * despite the name, it's a general predicate
 * (TypeFilter extends ElementFilter, whose accept(BaseElement, boolean)
 * decides per-element, not just per-type) — so a custom TypeFilter whose
 * accept() simply checks `candidates.contains(obj)` restricts BOTH which
 * nodes are visible/selectable to exactly the given list, same effective
 * result as a closed candidate set, implemented via the dialog's native
 * filtering rather than a hand-rolled one. The tree still shows the
 * containment PATH (ancestor Packages) needed to reach a candidate, since
 * that's required for any tree UI to be navigable at all — same as how a
 * real "select a Block" CAMEO dialog lets you browse through unrelated
 * Packages to find one.
 *
 * "None" is native too: SelectElementInfo's showNone flag adds a "<none>"
 * node the dialog shows; picking it still sets isOkClicked()==true, but
 * getSelectedElement() then returns null (confirmed
 * @CheckForNull/"Can be null" in the javadoc) — exactly what
 * ImportQosProfileAction (task caller decides "abort" vs "proceed without")
 * and GenerateDdsXmlAction (QoS optional) need, no synthetic model element
 * required as a workaround.
 * ===============================================================================
 */
package com.rti.connext.cameo.dds;

import com.nomagic.magicdraw.ui.dialogs.MDDialogParentProvider;
import com.nomagic.magicdraw.ui.dialogs.SelectElementInfo;
import com.nomagic.magicdraw.ui.dialogs.selection.ElementSelectionDlg;
import com.nomagic.magicdraw.ui.dialogs.selection.ElementSelectionDlgFactory;
import com.nomagic.magicdraw.ui.dialogs.selection.TypeFilter;
import com.nomagic.magicdraw.uml.BaseElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;

import java.awt.Window;
import java.util.Collection;
import java.util.List;

final class ElementPickerUtil {

    private ElementPickerUtil() {
    }

    /**
     * Shows a native, single-selection ElementSelectionDlg restricted to
     * exactly {@code candidates} (via a custom TypeFilter — see file
     * header). Returns the picked element, or {@code null} if the dialog
     * was cancelled, or (when {@code allowNone} is true) the user
     * explicitly picked "None" — both cases collapse to the same null
     * return, since every caller here already treats "nothing picked" as
     * one outcome regardless of which of those two ways it happened.
     *
     * @param root         where the browsable tree starts (e.g. the
     *                     project's primary model) — candidates elsewhere
     *                     in the project would simply be unreachable, so
     *                     this should be an ancestor of every candidate
     * @param candidates   the closed set of elements the user may pick
     * @param title        dialog title
     * @param allowNone    whether to show CAMEO's native "None" option
     * @param preselected  initially-selected element, or null for none
     */
    @SuppressWarnings("unchecked")
    static <T extends Element> T pickOne(Element root, List<T> candidates, String title,
                                          boolean allowNone, T preselected) {
        Window dialogParent = MDDialogParentProvider.getProvider().getDialogOwner();
        ElementSelectionDlg dlg = ElementSelectionDlgFactory.create(dialogParent, title, null);

        TypeFilter candidateFilter = new TypeFilter() {
            @Override
            public Collection<?> getTypes() {
                return null; // no type-based pre-filtering — accept() below is the real, instance-level filter
            }

            @Override
            public boolean accept(BaseElement obj, boolean checkType) {
                return candidates.contains(obj);
            }

            @Override
            public boolean accept(BaseElement obj) {
                return candidates.contains(obj);
            }
        };

        SelectElementInfo info = new SelectElementInfo(allowNone, false, root, false);
        ElementSelectionDlgFactory.initSingle(dlg, info, candidateFilter, candidateFilter, null, preselected);
        dlg.show();

        if (!dlg.isOkClicked()) {
            return null;
        }
        BaseElement selected = dlg.getSelectedElement();
        return candidates.contains(selected) ? (T) selected : null;
    }
}
