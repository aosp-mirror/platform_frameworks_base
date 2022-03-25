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

package com.android.server.notification;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.when;

import android.os.VibrationEffect;
import android.os.Vibrator;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class VibratorHelperTest extends UiServiceTestCase {

    // OFF/ON vibration pattern
    private static final long[] CUSTOM_PATTERN = new long[] { 100, 200, 300, 400 };
    // (amplitude, frequency, duration) triples list
    private static final float[] PWLE_PATTERN = new float[] { 1, 120, 100 };

    @Mock private Vibrator mVibrator;

    VibratorHelper mVibratorHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        getContext().addMockSystemService(Vibrator.class, mVibrator);

        mVibratorHelper = new VibratorHelper(getContext());
    }

    @Test
    public void createWaveformVibration_insistent_createsRepeatingVibration() {
        assertRepeatingVibration(
                VibratorHelper.createWaveformVibration(CUSTOM_PATTERN, /* insistent= */ true));
        assertRepeatingVibration(
                VibratorHelper.createPwleWaveformVibration(PWLE_PATTERN, /* insistent= */ true));
    }

    @Test
    public void createWaveformVibration_nonInsistent_createsSingleShotVibration() {
        assertSingleVibration(
                VibratorHelper.createWaveformVibration(CUSTOM_PATTERN, /* insistent= */ false));
        assertSingleVibration(
                VibratorHelper.createPwleWaveformVibration(PWLE_PATTERN, /* insistent= */ false));
    }

    @Test
    public void createWaveformVibration_invalidPattern_returnsNullAndDoesNotCrash() {
        assertNull(VibratorHelper.createWaveformVibration(null, false));
        assertNull(VibratorHelper.createWaveformVibration(new long[0], false));
        assertNull(VibratorHelper.createWaveformVibration(new long[] { 0, 0 }, false));

        assertNull(VibratorHelper.createPwleWaveformVibration(null, false));
        assertNull(VibratorHelper.createPwleWaveformVibration(new float[0], false));
        assertNull(VibratorHelper.createPwleWaveformVibration(new float[] { 0 }, false));
        assertNull(VibratorHelper.createPwleWaveformVibration(new float[] { 0, 0, 0 }, false));
    }

    @Test
    public void createVibration_insistent_createsRepeatingVibration() {
        when(mVibrator.hasFrequencyControl()).thenReturn(false);
        assertRepeatingVibration(mVibratorHelper.createDefaultVibration(/* insistent= */ true));
        assertRepeatingVibration(mVibratorHelper.createFallbackVibration(/* insistent= */ true));

        when(mVibrator.hasFrequencyControl()).thenReturn(true);
        assertRepeatingVibration(mVibratorHelper.createDefaultVibration(/* insistent= */ true));
        assertRepeatingVibration(mVibratorHelper.createFallbackVibration(/* insistent= */ true));
    }

    @Test
    public void createVibration_nonInsistent_createsSingleShotVibration() {
        when(mVibrator.hasFrequencyControl()).thenReturn(false);
        assertSingleVibration(mVibratorHelper.createDefaultVibration(/* insistent= */ false));
        assertSingleVibration(mVibratorHelper.createFallbackVibration(/* insistent= */ false));

        when(mVibrator.hasFrequencyControl()).thenReturn(true);
        assertSingleVibration(mVibratorHelper.createDefaultVibration(/* insistent= */ false));
        assertSingleVibration(mVibratorHelper.createFallbackVibration(/* insistent= */ false));
    }

    private void assertRepeatingVibration(VibrationEffect effect) {
        assertTrue(getRepeatIndex(effect) >= 0);
    }

    private void assertSingleVibration(VibrationEffect effect) {
        assertEquals(-1, getRepeatIndex(effect));
    }

    private static int getRepeatIndex(VibrationEffect effect) {
        assertTrue("Unknown vibration effect " + effect,
                effect instanceof VibrationEffect.Composed);
        return ((VibrationEffect.Composed) effect).getRepeatIndex();
    }
}
