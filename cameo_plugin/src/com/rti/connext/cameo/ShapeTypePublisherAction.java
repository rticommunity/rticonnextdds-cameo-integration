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
 * ShapeTypePublisherAction.java — CAMEO menu action that starts or stops the
 * DDS ShapeType publisher in a background thread.
 *
 * The action label toggles between:
 *   "Start Shape Publisher"  — when the publisher is not running
 *   "Stop Shape Publisher"   — when the publisher is active
 */
package com.rti.connext.cameo;

import com.nomagic.magicdraw.actions.MDAction;
import com.nomagic.magicdraw.core.Application;

import java.awt.event.ActionEvent;

public class ShapeTypePublisherAction extends MDAction {

    private static final String ID           = "RTI_START_STOP_PUBLISHER";
    private static final String NAME_START   = "Start Shape Publisher";
    private static final String NAME_STOP    = "Stop Shape Publisher";

    public ShapeTypePublisherAction() {
        super(ID, NAME_START, null, null);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (DDSRunner.isPublisherRunning()) {
            DDSRunner.stopPublisher();
            setName(NAME_START);
            Application.getInstance().getGUILog()
                    .log("[RTI Connext] Shape publisher stopped.");
        } else {
            try {
                DDSRunner.startPublisher();
                setName(NAME_STOP);
                Application.getInstance().getGUILog()
                        .log("[RTI Connext] Shape publisher started \u2014 "
                                + "writing RED squares on domain 0 (Square topic).");
            } catch (Exception ex) {
                Application.getInstance().getGUILog()
                        .log("[RTI Connext] Failed to start publisher: "
                                + ex.getMessage());
            }
        }
    }
}
