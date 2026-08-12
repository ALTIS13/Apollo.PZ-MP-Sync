package ru.apollot.pzsync.geometry;

public enum DecisionCode {
    ACCEPT_REWIND,
    ACCEPT_CURRENT,
    REJECT_IDENTITY,
    REJECT_FLOOR,
    REJECT_RANGE,
    REJECT_CONE,
    REJECT_LOS,
    REJECT_DIVERGENCE,
    REJECT_UNLOADED,
    REJECT_STALE,
    REJECT_NATIVE
}
