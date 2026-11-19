package com.rti.connext.cameo.dds;

import com.nomagic.magicdraw.simulation.execution.SimulationExecutionListener;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exercises the installed CST's actual callback signature without starting a model. */
public final class CstAdapterTest {
    public static void main(String[] args) throws Exception {
        Method callback = Arrays.stream(SimulationExecutionListener.class.getMethods())
                .filter(method -> method.getName().equals("eventTriggered")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].getSimpleName().equals("SignalInstance"))
                .findFirst().orElseThrow(() -> new AssertionError("CST Signal callback is missing"));
        Object first = callback.getParameterTypes()[0].getConstructor().newInstance();
        Object second = callback.getParameterTypes()[0].getConstructor().newInstance();
        RecordingListener listener = new RecordingListener();
        callback.invoke(listener, first);
        listener.eventTriggered("not a Signal");
        callback.invoke(listener, second);
        if (listener.signals.size() != 2
                || listener.signals.get(0) != first || listener.signals.get(1) != second) {
            throw new AssertionError("Typed CST callbacks must reach shared logic once, in order, unchanged");
        }
        System.out.println("PASS: typed CST Signal callbacks reach the shared listener; String events do not");
    }

    private static final class RecordingListener extends DdsEngineListener {
        private final List<Object> signals = new ArrayList<>();

        @Override
        protected void onSignalTriggered(Object signalInstance) {
            signals.add(signalInstance);
        }
    }
}
