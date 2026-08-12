package ru.apollot.pzsync.runtime;

/** Sanitized fail-closed rejection from the exact runtime adapter contract. */
public final class RuntimeAdapterException extends IllegalArgumentException {
    private final String reasonCode;

    public RuntimeAdapterException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
