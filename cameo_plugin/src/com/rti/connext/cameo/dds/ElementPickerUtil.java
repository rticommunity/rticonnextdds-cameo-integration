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
 * ElementPickerUtil — single-element picker on CAMEO's own
 * ElementSelectionDlg, used by the Generate DDS XML and Import QoS Profile
 * actions.
 *
 * ElementSelectionDlg browses the model tree rather than a list, so the
 * choice is restricted to the given candidates with a TypeFilter whose
 * accept() checks membership (TypeFilter is a general predicate despite its
 * name); the tree still shows the packages leading to each candidate. The
 * dialog's own "<none>" option is used for optional choices: OK with
 * "<none>" selected returns null, like Cancel.
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
     * Lets the user pick one of {@code candidates}. Returns the element,
     * or null if the dialog was cancelled or "<none>" was picked.
     *
     * @param root         where the tree starts; must contain every candidate
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
