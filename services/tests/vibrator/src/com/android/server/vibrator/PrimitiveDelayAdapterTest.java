/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.vibrator;

import static android.os.VibrationEffect.Composition.DELAY_TYPE_PAUSE;
import static android.os.VibrationEffect.Composition.DELAY_TYPE_RELATIVE_START_OFFSET;
import static android.os.VibrationEffect.Composition.PRIMITIVE_CLICK;
import static android.os.VibrationEffect.Composition.PRIMITIVE_TICK;

import static org.junit.Assert.assertEquals;

import android.hardware.vibrator.IVibrator;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.Flags;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PrimitiveDelayAdapterTest {
    private static final VibratorInfo EMPTY_VIBRATOR_INFO = new VibratorInfo.Builder(0).build();
    private static final VibratorInfo BASIC_VIBRATOR_INFO = createVibratorInfoWithPrimitives(
            new int[] { PRIMITIVE_CLICK, PRIMITIVE_TICK },
            new int[] { 20, 10 });

    private PrimitiveDelayAdapter mAdapter;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() throws Exception {
        mAdapter = new PrimitiveDelayAdapter();
    }

    @Test
    @DisableFlags(Flags.FLAG_PRIMITIVE_COMPOSITION_ABSOLUTE_DELAY)
    public void testPrimitiveSegments_flagDisabled_keepsListUnchanged() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new PrimitiveSegment(PRIMITIVE_CLICK, 1f, 100, DELAY_TYPE_RELATIVE_START_OFFSET),
                new PrimitiveSegment(PRIMITIVE_TICK, 0.5f, 10, DELAY_TYPE_PAUSE)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        assertEquals(-1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, -1));
        assertEquals(1, mAdapter.adaptToVibrator(BASIC_VIBRATOR_INFO, segments, 1));

        assertEquals(originalSegments, segments);
    }

    @Test
    @EnableFlags(Flags.FLAG_PRIMITIVE_COMPOSITION_ABSOLUTE_DELAY)
    public void testNonPrimitiveSegments_keepsListUnchanged() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 1, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 1, /* duration= */ 20),
                new PrebakedSegment(VibrationEffect.EFFECT_CLICK, false,
                        VibrationEffect.EFFECT_STRENGTH_LIGHT)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        assertEquals(-1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, -1));
        assertEquals(1, mAdapter.adaptToVibrator(BASIC_VIBRATOR_INFO, segments, 1));

        assertEquals(originalSegments, segments);
    }

    @Test
    @EnableFlags(Flags.FLAG_PRIMITIVE_COMPOSITION_ABSOLUTE_DELAY)
    public void testPrimitiveWithPause_keepsListUnchanged() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new PrimitiveSegment(PRIMITIVE_CLICK, 1f, 100, DELAY_TYPE_PAUSE),
                new PrimitiveSegment(PRIMITIVE_TICK, 0.5f, 10, DELAY_TYPE_PAUSE)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        assertEquals(-1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, -1));
        assertEquals(1, mAdapter.adaptToVibrator(BASIC_VIBRATOR_INFO, segments, 1));

        assertEquals(originalSegments, segments);
    }

    @Test
    @EnableFlags(Flags.FLAG_PRIMITIVE_COMPOSITION_ABSOLUTE_DELAY)
    public void testPrimitiveWithRelativeDelay_afterPrimitive_usesPrimitiveStartTimeForDelay() {
        VibratorInfo info = createVibratorInfoWithPrimitives(
                new int[] { PRIMITIVE_CLICK }, new int[] { 20 });

        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new PrimitiveSegment(PRIMITIVE_CLICK, 0.1f, 100, DELAY_TYPE_RELATIVE_START_OFFSET),
                new PrimitiveSegment(PRIMITIVE_CLICK, 0.2f, 10, DELAY_TYPE_RELATIVE_START_OFFSET),
                new PrimitiveSegment(PRIMITIVE_CLICK, 0.3f, 0, DELAY_TYPE_RELATIVE_START_OFFSET),
                new PrimitiveSegment(PRIMITIVE_CLICK, 0.4f, 10, DELAY_TYPE_RELATIVE_START_OFFSET)));

        List<VibrationEffectSegment> expectedSegments = new ArrayList<>(Arrays.asList(
                new PrimitiveSegment(PRIMITIVE_CLICK, 0.1f, 100, DELAY_TYPE_PAUSE),
                new PrimitiveSegment(PRIMITIVE_CLICK, 0.4f, 0, DELAY_TYPE_PAUSE)));

        // Repeat index is fixed after removals
        assertEquals(-1, mAdapter.adaptToVibrator(info, segments, -1));

        assertEquals(expectedSegments, segments);
    }

    @Test
    @EnableFlags(Flags.FLAG_PRIMITIVE_COMPOSITION_ABSOLUTE_DELAY)
    public void testPrimitiveWithRelativeDelay_afterRepeatIndex_usesPauseAsFirstDelay() {
        VibratorInfo info = createVibratorInfoWithPrimitives(
                new int[] { PRIMITIVE_CLICK }, new int[] { 20 });

        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new PrimitiveSegment(PRIMITIVE_CLICK, 0.1f, 100, DELAY_TYPE_RELATIVE_START_OFFSET),
                new PrimitiveSegment(PRIMITIVE_CLICK, 0.2f, 10, DELAY_TYPE_RELATIVE_START_OFFSET),
                new PrimitiveSegment(PRIMITIVE_CLICK, 0.3f, 10, DELAY_TYPE_RELATIVE_START_OFFSET),
                new PrimitiveSegment(PRIMITIVE_CLICK, 0.4f, 10, DELAY_TYPE_RELATIVE_START_OFFSET)));

        List<VibrationEffectSegment> expectedSegments = new ArrayList<>(Arrays.asList(
                new PrimitiveSegment(PRIMITIVE_CLICK, 0.1f, 100, DELAY_TYPE_PAUSE),
                new PrimitiveSegment(PRIMITIVE_CLICK, 0.3f, 10, DELAY_TYPE_PAUSE)));

        // Relative offset reset after repeat index.
        assertEquals(1, mAdapter.adaptToVibrator(info, segments, 2));

        assertEquals(expectedSegments, segments);
    }

    @Test
    @EnableFlags(Flags.FLAG_PRIMITIVE_COMPOSITION_ABSOLUTE_DELAY)
    public void testPrimitiveWithRelativeDelayAfter_afterStep_usesSegmentStartTimeForDelay() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 1, /* duration= */ 10),
                new PrimitiveSegment(PRIMITIVE_CLICK, 1f, 10, DELAY_TYPE_RELATIVE_START_OFFSET)));

        List<VibrationEffectSegment> expectedSegments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 1, /* duration= */ 10),
                new PrimitiveSegment(PRIMITIVE_CLICK, 1f, 0, DELAY_TYPE_PAUSE)));

        assertEquals(-1, mAdapter.adaptToVibrator(BASIC_VIBRATOR_INFO, segments, -1));
        assertEquals(expectedSegments, segments);
    }

    @Test
    @EnableFlags(Flags.FLAG_PRIMITIVE_COMPOSITION_ABSOLUTE_DELAY)
    public void testPrimitiveWithRelativeDelayAfter_afterUnknownDuration_usesZeroAsDuration() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new PrebakedSegment(VibrationEffect.EFFECT_POP, false,
                        VibrationEffect.EFFECT_STRENGTH_STRONG),
                new PrimitiveSegment(PRIMITIVE_CLICK, 1f, 10, DELAY_TYPE_RELATIVE_START_OFFSET)));

        assertEquals(-1, mAdapter.adaptToVibrator(BASIC_VIBRATOR_INFO, segments, -1));

        List<VibrationEffectSegment> expectedSegments = new ArrayList<>(Arrays.asList(
                new PrebakedSegment(VibrationEffect.EFFECT_POP, false,
                        VibrationEffect.EFFECT_STRENGTH_STRONG),
                new PrimitiveSegment(PRIMITIVE_CLICK, 1f, 10, DELAY_TYPE_PAUSE)));

        assertEquals(expectedSegments, segments);
    }

    private static VibratorInfo createVibratorInfoWithPrimitives(int[] ids, int[] durations) {
        VibratorInfo.Builder builder = new VibratorInfo.Builder(0)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);

        for (int i = 0; i < ids.length; i++) {
            builder.setSupportedPrimitive(ids[i], durations[i]);
        }

        return builder.build();
    }
}
