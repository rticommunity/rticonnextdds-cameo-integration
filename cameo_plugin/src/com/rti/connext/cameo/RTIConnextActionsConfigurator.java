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
 * RTIConnextActionsConfigurator — adds the "RTI Connext DDS" sub-menu
 * to the CAMEO Tools menu.
 *
 * Menu structure:
 *   Tools
 *     └─ RTI Connext DDS
 *           ├─ Scan Model for DDS Topics
 *           ├─ Generate DDS XML
 *           ├─ QoS (Load QoS XML..., Use Model QoS, Library > Profile)
 *           └─ Verbose DDS Logging
 */
package com.rti.connext.cameo;

import com.nomagic.actions.AMConfigurator;
import com.nomagic.actions.ActionsCategory;
import com.nomagic.actions.ActionsManager;
import com.nomagic.magicdraw.actions.MDStateAction;

import com.rti.connext.cameo.core.ScanModelForTopicsAction;
import com.rti.connext.cameo.dds.CameoLog;
import com.rti.connext.cameo.dds.GenerateDdsXmlAction;
import com.rti.connext.cameo.dds.LoadQosXmlAction;

import java.awt.event.ActionEvent;

public class RTIConnextActionsConfigurator implements AMConfigurator {

    static final String TOOLS_MENU_ID = "TOOLS";
    static final String RTI_MENU_ID   = "RTI_CONNEXT_DDS_MENU";
    static final String RTI_MENU_NAME = "RTI Connext DDS";

    private final ScanModelForTopicsAction scanModelAction = new ScanModelForTopicsAction();
    private final GenerateDdsXmlAction generateDdsXmlAction = new GenerateDdsXmlAction();
    private final LoadQosXmlAction loadQosXmlAction = new LoadQosXmlAction();
    private final VerboseDdsLoggingAction verboseDdsLoggingAction = new VerboseDdsLoggingAction();

    @Override
    public void configure(ActionsManager manager) {
        ActionsCategory toolsMenu =
                (ActionsCategory) manager.getActionFor(TOOLS_MENU_ID);
        if (toolsMenu == null) {
            // Fallback: add to the root manager if Tools menu not found
            ActionsCategory root = manager.getCategory(null);
            if (root != null) {
                toolsMenu = root;
            } else {
                return;
            }
        }

        ActionsCategory rtiMenu = new ActionsCategory(RTI_MENU_ID, RTI_MENU_NAME);
        rtiMenu.setNested(true);
        rtiMenu.addAction(scanModelAction);
        rtiMenu.addAction(generateDdsXmlAction);
        rtiMenu.addAction(loadQosXmlAction.getProfileMenu());
        rtiMenu.addAction(verboseDdsLoggingAction);

        toolsMenu.addAction(rtiMenu);
    }

    @Override
    public int getPriority() {
        return AMConfigurator.MEDIUM_PRIORITY;
    }

    private static final class VerboseDdsLoggingAction extends MDStateAction {

        private VerboseDdsLoggingAction() {
            super("RTI_VERBOSE_DDS_LOGGING", "Verbose DDS Logging", null, null);
            setState(CameoLog.isVerbose());
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            CameoLog.setVerbose(!CameoLog.isVerbose());
            setState(CameoLog.isVerbose());
        }

        @Override
        public void updateState() {
            setState(CameoLog.isVerbose());
        }
    }
}
