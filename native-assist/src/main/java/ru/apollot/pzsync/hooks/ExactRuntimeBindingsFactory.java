package ru.apollot.pzsync.hooks;

import ru.apollot.pzsync.gate.RuntimeFingerprint;
import ru.apollot.pzsync.runtime.ExactRuntimeAdapterBindings;
import ru.apollot.pzsync.runtime.RuntimeAdapterSpec;

/** The sole packaged entry point for exact, fingerprint-described native bindings. */
public final class ExactRuntimeBindingsFactory {
    private ExactRuntimeBindingsFactory() {}

    public static HookInstaller.ProductionBindings create(RuntimeFingerprint fingerprint) {
        RuntimeAdapterSpec.validateSelection(fingerprint);
        ClassLoader targetLoader = Thread.currentThread().getContextClassLoader();
        if (targetLoader == null) {
            throw new IllegalArgumentException("target loader is absent");
        }
        if (RuntimeAdapterSpec.isDeclared(fingerprint)) {
            return ExactRuntimeAdapterBindings.create(fingerprint, targetLoader);
        }
        return ExactRuntimeBindingsSupport.create(fingerprint, targetLoader);
    }
}
