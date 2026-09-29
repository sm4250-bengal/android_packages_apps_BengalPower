package com.bengal.power;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Explicit unwired boundary, never a product/property-to-readiness conversion. */
final class UnavailablePowerReadiness implements AccPowerCoordinator.Participants,
        AccPowerCoordinator.DeepCapability {
    static final String REASON = "selected kernel retained-wake status provider missing";

    @Override public CompletionStage<Boolean> prepare(AccPowerCoordinator.Cycle cycle) {
        return CompletableFuture.completedFuture(false);
    }

    @Override public void cancel(AccPowerCoordinator.Cycle cycle) {
        // No participant action was acquired. Future real owners must implement bounded rollback.
    }

    @Override public boolean ready(AccPowerCoordinator.Cycle cycle) {
        // Neither /sys/power/mem_sleep text nor diagnostic enable parameters prove wake readiness.
        return false;
    }
}
