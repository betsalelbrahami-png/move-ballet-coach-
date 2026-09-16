package com.betsalel.voixlive;

import static org.junit.Assert.assertEquals;

import java.util.Random;

import org.junit.Test;

public final class VoiceProcessingServiceTest {
    @Test
    public void findsModelDelayBeforeSubtraction() {
        float[] mixture = noise(32_000, 41);
        float[] target = new float[32_000];
        int expectedLag = 320;
        for (int i = 0; i + expectedLag < target.length; i++) {
            target[i + expectedLag] = mixture[i];
        }

        assertEquals(expectedLag, VoiceProcessingService.bestLag(mixture, target));
    }

    @Test
    public void estimatesLeastSquaresTargetGain() {
        float[] source = noise(32_000, 73);
        float[] interference = noise(32_000, 91);
        float[] mixture = new float[32_000];
        for (int i = 0; i < mixture.length; i++) {
            mixture[i] = 0.75f * source[i] + 0.03f * interference[i];
        }

        assertEquals(0.75f, VoiceProcessingService.optimalGain(mixture, source, 0), 0.01f);
    }

    private static float[] noise(int length, long seed) {
        Random random = new Random(seed);
        float[] values = new float[length];
        for (int i = 0; i < length; i++) values[i] = (random.nextFloat() - 0.5f) * 0.2f;
        return values;
    }
}
