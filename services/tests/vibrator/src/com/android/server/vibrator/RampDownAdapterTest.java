/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;

import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RampDownAdapterTest {
    private static final int TEST_RAMP_DOWN_DURATION = 20;
    private static final int TEST_STEP_DURATION = 5;
    private static final VibratorInfo EMPTY_VIBRATOR_INFO = new VibratorInfo.Builder(0).build();

    private RampDownAdapter mAdapter;

    @Before
    public void setUp() throws Exception {
        mAdapter = new RampDownAdapter(TEST_RAMP_DOWN_DURATION, TEST_STEP_DURATION);
    }

    @Test
    public void testPrebakedAndPrimitiveSegments_keepsListUnchanged() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new PrebakedSegment(
                        VibrationEffect.EFFECT_CLICK, false, VibrationEffect.EFFECT_STRENGTH_LIGHT),
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 10)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        assertEquals(-1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, -1));
        assertEquals(1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 1));

        assertEquals(originalSegments, segments);
    }

    @Test
    public void testRampAndStepSegments_withNoOffSegment_keepsListUnchanged() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 50, /* duration= */ 20)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        assertEquals(-1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, -1));
        assertEquals(0, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 0));

        assertEquals(originalSegments, segments);
    }

    @Test
    public void testRampAndStepSegments_withNoRampDownDuration_keepsOriginalSteps() {
        mAdapter = new RampDownAdapter(/* rampDownDuration= */ 0, TEST_STEP_DURATION);

        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 100),
                new RampSegment(/* startAmplitude= */ 0.8f, /* endAmplitude= */ 0.2f,
                        /* startFrequencyHz= */ 10, /* endFrequencyHz= */ 50, /* duration= */ 20),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 0,
                        /* startFrequencyHz= */ 0, /* endFrequencyHz= */ 0, /* duration= */ 50)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        assertEquals(-1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, -1));
        assertEquals(2, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 2));
        assertEquals(originalSegments, segments);
    }

    @Test
    public void testStepSegments_withShortZeroSegment_replaceWithStepsDown() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 10)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 5));

        assertEquals(-1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, -1));
        assertEquals(expectedSegments, segments);
    }

    @Test
    public void testStepSegments_withLongZeroSegment_replaceWithStepsDownWithRemainingOffSegment() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 0,
                        /* startFrequencyHz= */ 0, /* endFrequencyHz= */ 0, /* duration= */ 50),
                new StepSegment(/* amplitude= */ 0.8f, /* frequencyHz= */ 0, /* duration= */ 100)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.75f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0.25f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 35),
                new StepSegment(/* amplitude= */ 0.8f, /* frequencyHz= */ 0, /* duration= */ 100));

        assertEquals(-1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, -1));
        assertEquals(expectedSegments, segments);
    }

    @Test
    public void testStepSegments_withZeroSegmentBeforeRepeat_fixesRepeat() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 50),
                new StepSegment(/* amplitude= */ 0.8f, /* frequencyHz= */ 0, /* duration= */ 100)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.75f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0.25f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 35),
                new StepSegment(/* amplitude= */ 0.8f, /* frequencyHz= */ 0, /* duration= */ 100));

        // Repeat index fixed after intermediate steps added
        assertEquals(5, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 2));
        assertEquals(expectedSegments, segments);
    }

    @Test
    public void testStepSegments_withZeroSegmentAfterRepeat_preservesRepeat() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.8f, /* frequencyHz= */ 0, /* duration= */ 100)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0.8f, /* frequencyHz= */ 0, /* duration= */ 100));

        assertEquals(3, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 2));
        assertEquals(expectedSegments, segments);
    }

    @Test
    public void testStepSegments_withZeroSegmentAtRepeat_fixesRepeatAndAppendOriginalToListEnd() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 50),
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 100)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.75f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0.25f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 35),
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 100),
                // Original zero segment appended to the end of new looping vibration,
                // then converted to ramp down as well.
                new StepSegment(/* amplitude= */ 0.75f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0.25f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 35));

        // Repeat index fixed after intermediate steps added
        assertEquals(5, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 1));
        assertEquals(expectedSegments, segments);
    }

    @Test
    public void testStepSegments_withRepeatToNonZeroSegment_keepsOriginalSteps() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 0.8f, /* frequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 100)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        assertEquals(0, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 0));

        assertEquals(originalSegments, segments);
    }

    @Test
    public void testStepSegments_withRepeatToShortZeroSegment_skipAndAppendRampDown() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 0,
                        /* startfrequencyHz= */ 0, /* endfrequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 30)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 0,
                        /* startfrequencyHz= */ 0, /* endfrequencyHz= */ 0, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 30),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 5));

        // Shift repeat index to the right to use append instead of zero segment.
        assertEquals(1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 0));

        assertEquals(expectedSegments, segments);
    }

    @Test
    public void testStepSegments_withRepeatToLongZeroSegment_splitAndAppendRampDown() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 120),
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 30)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                // Split long zero segment to skip part of it.
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 20),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 100),
                new StepSegment(/* amplitude= */ 1, /* frequencyHz= */ 0, /* duration= */ 30),
                new StepSegment(/* amplitude= */ 0.75f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0.5f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0.25f, /* frequencyHz= */ 0, /* duration= */ 5),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 0, /* duration= */ 5));

        // Shift repeat index to the right to use append with part of the zero segment.
        assertEquals(1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 0));

        assertEquals(expectedSegments, segments);
    }

    @Test
    public void testRampSegments_withShortZeroSegment_replaceWithRampDown() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude*/ 0.5f,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 100, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 0,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 100, /* duration= */ 20),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 200, /* endFrequencyHz= */ 200,
                        /* duration= */ 30)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude*/ 0.5f,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 100, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 100, /* duration= */ 20),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 200, /* endFrequencyHz= */ 200,
                        /* duration= */ 30));

        assertEquals(2, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 2));

        assertEquals(expectedSegments, segments);
    }

    @Test
    public void testRampSegments_withLongZeroSegment_splitAndAddRampDown() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0.5f,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 100, /* duration= */ 10),
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 150, /* duration= */ 150),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 200, /* endFrequencyHz= */ 200,
                        /* duration= */ 30)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude*/ 0.5f,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 100, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 100, /* duration= */ 20),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 0,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 100,
                        /* duration= */ 130),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 200, /* endFrequencyHz= */ 200,
                        /* duration= */ 30));

        // Repeat index fixed after intermediate steps added
        assertEquals(3, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 2));

        assertEquals(expectedSegments, segments);
    }

    @Test
    public void testRampSegments_withRepeatToNonZeroSegment_keepsOriginalSteps() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0.5f, /* endAmplitude*/ 0.5f,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 100, /* duration= */ 10),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 200, /* endFrequencyHz= */ 200,
                        /* duration= */ 30)));
        List<VibrationEffectSegment> originalSegments = new ArrayList<>(segments);

        assertEquals(0, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 0));

        assertEquals(originalSegments, segments);
    }

    @Test
    public void testRampSegments_withRepeatToShortZeroSegment_skipAndAppendRampDown() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 200, /* duration= */ 20),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude*/ 1,
                        /* startFrequencyHz= */ 40, /* endFrequencyHz= */ 80, /* duration= */ 20)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                new StepSegment(/* amplitude= */ 0, /* frequencyHz= */ 200, /* duration= */ 20),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 40, /* endFrequencyHz= */ 80, /* duration= */ 20),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 0,
                        /* startFrequencyHz= */ 80, /* endFrequencyHz= */ 80, /* duration= */ 20));

        // Shift repeat index to the right to use append instead of zero segment.
        assertEquals(1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 0));

        assertEquals(expectedSegments, segments);
    }

    @Test
    public void testRampSegments_withRepeatToLongZeroSegment_splitAndAppendRampDown() {
        List<VibrationEffectSegment> segments = new ArrayList<>(Arrays.asList(
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude*/ 0,
                        /* startFrequencyHz= */ 1, /* endFrequencyHz= */ 1, /* duration= */ 70),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 1, /* endFrequencyHz= */ 1, /* duration= */ 30)));
        List<VibrationEffectSegment> expectedSegments = Arrays.asList(
                // Split long zero segment to skip part of it.
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude*/ 0,
                        /* startFrequencyHz= */ 1, /* endFrequencyHz= */ 1, /* duration= */ 20),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude*/ 0,
                        /* startFrequencyHz= */ 1, /* endFrequencyHz= */ 1, /* duration= */ 50),
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 1,
                        /* startFrequencyHz= */ 1, /* endFrequencyHz= */ 1, /* duration= */ 30),
                new RampSegment(/* startAmplitude= */ 1, /* endAmplitude= */ 0,
                        /* startFrequencyHz= */ 1, /* endFrequencyHz= */ 1, /* duration= */ 20));

        // Shift repeat index to the right to use append with part of the zero segment.
        assertEquals(1, mAdapter.adaptToVibrator(EMPTY_VIBRATOR_INFO, segments, 0));

        assertEquals(expectedSegments, segments);
    }
}
