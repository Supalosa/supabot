package com.supalosa.bot.awareness;

import org.immutables.value.Value;

@Value.Immutable
public interface Estimation {

    int estimation();

    EstimationConfidence confidence();

    static Estimation none() {
        return ImmutableEstimation.builder()
                .estimation(0)
                .confidence(EstimationConfidence.NONE)
                .build();
    }

    static Estimation lowConfidence(int value) {
        return ImmutableEstimation.builder()
                .estimation(value)
                .confidence(EstimationConfidence.LOW)
                .build();
    }

    static Estimation highConfidence(int value) {
        return ImmutableEstimation.builder()
                .estimation(value)
                .confidence(EstimationConfidence.HIGH)
                .build();
    }
}
