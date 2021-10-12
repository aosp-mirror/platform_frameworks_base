/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Context;

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@LargeTest
public class VibratorPerfTest {
    @Rule
    public final BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    private Vibrator mVibrator;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mVibrator = context.getSystemService(Vibrator.class);
    }

    @Test
    public void testEffectClick() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            mVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        }
    }

    @Test
    public void testOneShot() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            mVibrator.vibrate(VibrationEffect.createOneShot(SECONDS.toMillis(2),
                    VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    @Test
    public void testWaveform() {
        final BenchmarkState state = mBenchmarkRule.getState();
        long[] timings = new long[]{SECONDS.toMillis(1), SECONDS.toMillis(2), SECONDS.toMillis(1)};
        while (state.keepRunning()) {
            mVibrator.vibrate(VibrationEffect.createWaveform(timings, -1));
        }
    }

    @Test
    public void testCompose() {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            mVibrator.vibrate(
                    VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 100)
                            .compose());
        }
    }

    @Test
    public void testAreEffectsSupported() {
        final BenchmarkState state = mBenchmarkRule.getState();
        int[] effects = new int[]{VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_TICK};
        while (state.keepRunning()) {
            mVibrator.areEffectsSupported(effects);
        }
    }

    @Test
    public void testArePrimitivesSupported() {
        final BenchmarkState state = mBenchmarkRule.getState();
        int[] primitives = new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK};
        while (state.keepRunning()) {
            mVibrator.arePrimitivesSupported(primitives);
        }
    }
}
