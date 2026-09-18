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
 * RTIConnextPlugin.java — CAMEO System Modeler 2024x plugin entry point.
 *
 * Lifecycle:
 *   init()  — registers the RTI Connext menu under Tools, then attempts to
 *              load the Connext native libraries from %NDDSHOME%\lib\<arch>\.
 *   close() — tears down any cached DDS DomainParticipants/DataReaders
 *              (DDSTopicPublisher, DDSTopicSubscriber).
 */
package com.rti.connext.cameo;

import com.nomagic.magicdraw.actions.ActionsConfiguratorsManager;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.plugins.Plugin;

import com.rti.connext.cameo.dds.DDSTopicPublisher;
import com.rti.connext.cameo.dds.DDSTopicSubscriber;
import com.rti.connext.cameo.dds.DdsInboundInjector;

import java.io.File;

public class RTIConnextPlugin extends Plugin {

    private static RTIConnextPlugin instance;

    public static RTIConnextPlugin getInstance() {
        return instance;
    }

    // -------------------------------------------------------------------------
    // Plugin lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void init() {
        instance = this;

        // Register the "RTI Connext DDS" sub-menu under Tools
        ActionsConfiguratorsManager.getInstance()
                .addMainMenuConfigurator(new RTIConnextActionsConfigurator());

        // Attempt to add the Connext native library directory to the JVM
        // search path so that nddsjava.jar can load its JNI counterpart.
        loadNativeLibraries();

        Application.getInstance().getGUILog()
                .log("[RTI Connext] Plugin initialised. "
                        + "Use Tools → RTI Connext DDS to scan the model for DDS topics.");
    }

    @Override
    public boolean close() {
        DDSTopicSubscriber.shutdown();
        DDSTopicPublisher.shutdown();
        DdsInboundInjector.shutdown();
        return true;
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    // -------------------------------------------------------------------------
    // Native library loading
    // -------------------------------------------------------------------------

    /**
     * Adds %NDDSHOME%\lib\<arch>\ to java.library.path at runtime so that
     * the Connext JNI layer (nddsjava.dll, nddscore.dll, …) can be found.
     *
     * On Windows the JVM also searches directories on the PATH, so an
     * alternative is to add %NDDSHOME%\lib\x64Win64VS2017 to the system PATH
     * before launching CAMEO (see README.md for details).
     */
    private void loadNativeLibraries() {
        String nddshome = System.getenv("NDDSHOME");
        if (nddshome == null || nddshome.isEmpty()) {
            Application.getInstance().getGUILog()
                    .log("[RTI Connext] NDDSHOME environment variable is not set. "
                            + "Native library loading may fail. "
                            + "Set NDDSHOME before starting CAMEO.");
            return;
        }

        // Detect the Windows architecture sub-folder (x64Win64VS2017, etc.)
        File libDir = new File(nddshome, "lib");
        String archDir = detectWindowsArchDir(libDir);
        if (archDir == null) {
            Application.getInstance().getGUILog()
                    .log("[RTI Connext] Could not find a Windows architecture "
                            + "directory under " + libDir.getAbsolutePath()
                            + ". Add the correct path to PATH manually.");
            return;
        }

        // Prepend the architecture directory to java.library.path
        String nativeLibPath = new File(libDir, archDir).getAbsolutePath();
        String current = System.getProperty("java.library.path", "");
        if (!current.contains(nativeLibPath)) {
            System.setProperty("java.library.path", nativeLibPath + File.pathSeparator + current);
            // Invalidate the ClassLoader's cached library path
            try {
                java.lang.reflect.Field sysPathsField =
                        ClassLoader.class.getDeclaredField("sys_paths");
                sysPathsField.setAccessible(true);
                sysPathsField.set(null, null);
            } catch (Exception e) {
                // Java 17+ may deny this; fallback is the PATH environment variable
                Application.getInstance().getGUILog()
                        .log("[RTI Connext] Could not update java.library.path "
                                + "programmatically (Java 9+ restriction). "
                                + "Ensure " + nativeLibPath + " is on the system PATH.");
            }
        }

        Application.getInstance().getGUILog()
                .log("[RTI Connext] Native library path: " + nativeLibPath);
    }

    /**
     * Returns the name of the first directory under {@code libDir} whose name
     * starts with "x64Win64" (e.g. x64Win64VS2017, x64Win64VS2019).
     */
    private String detectWindowsArchDir(File libDir) {
        if (!libDir.isDirectory()) return null;
        File[] entries = libDir.listFiles(f -> f.isDirectory()
                && f.getName().startsWith("x64Win64"));
        if (entries != null && entries.length > 0) {
            return entries[0].getName();
        }
        return null;
    }
}
