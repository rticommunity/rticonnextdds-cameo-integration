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
 * ShapeTypeSubscriberAction.java — CAMEO menu action that starts or stops the
 * DDS ShapeType subscriber in a background thread.
 *
 * The action label toggles between:
 *   "Start Shape Subscriber"  — when the subscriber is not running
 *   "Stop Shape Subscriber"   — when the subscriber is active
 */
package com.rti.connext.cameo;

import com.nomagic.magicdraw.actions.MDAction;
import com.nomagic.magicdraw.core.Application;

import java.awt.event.ActionEvent;

public class ShapeTypeSubscriberAction extends MDAction {

    private static final String ID           = "RTI_START_STOP_SUBSCRIBER";
    private static final String NAME_START   = "Start Shape Subscriber";
    private static final String NAME_STOP    = "Stop Shape Subscriber";

    public ShapeTypeSubscriberAction() {
        super(ID, NAME_START, null, null);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (DDSRunner.isSubscriberRunning()) {
            DDSRunner.stopSubscriber();
            setName(NAME_START);
            Application.getInstance().getGUILog()
                    .log("[RTI Connext] Shape subscriber stopped.");
        } else {
            try {
                DDSRunner.startSubscriber();
                setName(NAME_STOP);
                Application.getInstance().getGUILog()
                        .log("[RTI Connext] Shape subscriber started \u2014 "
                                + "listening on domain 0 (Square topic). "
                                + "Received samples appear in this log.");
            } catch (Exception ex) {
                Application.getInstance().getGUILog()
                        .log("[RTI Connext] Failed to start subscriber: "
                                + ex.getMessage());
            }
        }
    }
}
