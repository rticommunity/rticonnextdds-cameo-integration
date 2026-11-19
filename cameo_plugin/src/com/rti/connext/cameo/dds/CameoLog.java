package com.rti.connext.cameo.dds;

import com.nomagic.magicdraw.core.Application;

import java.util.function.Supplier;

public final class CameoLog {

    private static volatile boolean verbose = Boolean.getBoolean("com.rti.connext.cameo.verbose");

    private CameoLog() {
    }

    public static boolean isVerbose() {
        return verbose;
    }

    public static void setVerbose(boolean enabled) {
        verbose = enabled;
    }

    public static void log(String message) {
        try {
            Application.getInstance().getGUILog().log(message, false);
        } catch (Exception ignored) {
            System.out.println(message);
        }
    }

    public static void trace(Supplier<String> message) {
        if (verbose) {
            log(message.get());
        }
    }
}