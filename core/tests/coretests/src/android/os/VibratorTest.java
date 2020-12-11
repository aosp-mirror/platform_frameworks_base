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

import static junit.framework.TestCase.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.media.AudioAttributes;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Tests for {@link Vibrator}.
 *
 * Build/Install/Run:
 * atest FrameworksCoreTests:VibratorTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class VibratorTest {

    private Vibrator mVibratorSpy;

    @Before
    public void setUp() {
        mVibratorSpy = spy(InstrumentationRegistry.getContext().getSystemService(Vibrator.class));
    }

    @Test
    public void areEffectsSupported_returnsArrayOfSameSize() {
        assertEquals(0, mVibratorSpy.areEffectsSupported(new int[0]).length);
        assertEquals(1,
                mVibratorSpy.areEffectsSupported(new int[]{VibrationEffect.EFFECT_CLICK}).length);
        assertEquals(2,
                mVibratorSpy.areEffectsSupported(new int[]{VibrationEffect.EFFECT_CLICK,
                        VibrationEffect.EFFECT_TICK}).length);
    }

    @Test
    public void arePrimitivesSupported_returnsArrayOfSameSize() {
        assertEquals(0, mVibratorSpy.arePrimitivesSupported(new int[0]).length);
        assertEquals(1, mVibratorSpy.arePrimitivesSupported(
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK}).length);
        assertEquals(2, mVibratorSpy.arePrimitivesSupported(
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE}).length);
    }

    @Test
    public void vibrate_withAudioAttributes_createsVibrationAttributesWithSameUsage() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        AudioAttributes audioAttributes = new AudioAttributes.Builder().setUsage(
                AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build();

        mVibratorSpy.vibrate(effect, audioAttributes);

        ArgumentCaptor<VibrationAttributes> captor = ArgumentCaptor.forClass(
                VibrationAttributes.class);
        verify(mVibratorSpy).vibrate(anyInt(), anyString(), eq(effect), isNull(), captor.capture());

        VibrationAttributes vibrationAttributes = captor.getValue();
        assertEquals(VibrationAttributes.USAGE_COMMUNICATION_REQUEST,
                vibrationAttributes.getUsage());
        // Keeps original AudioAttributes usage to be used by the VibratorService.
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
                vibrationAttributes.getAudioUsage());
    }

    @Test
    public void vibrate_withUnknownAudioAttributes_hasTouchUsageFromEffect() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        AudioAttributes audioAttributes = new AudioAttributes.Builder().setUsage(
                AudioAttributes.USAGE_UNKNOWN).build();

        mVibratorSpy.vibrate(effect, audioAttributes);

        ArgumentCaptor<VibrationAttributes> captor = ArgumentCaptor.forClass(
                VibrationAttributes.class);
        verify(mVibratorSpy).vibrate(anyInt(), anyString(), eq(effect), isNull(), captor.capture());

        VibrationAttributes vibrationAttributes = captor.getValue();
        assertEquals(VibrationAttributes.USAGE_TOUCH,
                vibrationAttributes.getUsage());
        // Sets AudioAttributes usage based on effect.
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                vibrationAttributes.getAudioUsage());
    }

    @Test
    public void vibrate_withoutAudioAttributes_hasTouchUsageFromEffect() {
        mVibratorSpy.vibrate(VibrationEffect.get(VibrationEffect.EFFECT_CLICK));

        ArgumentCaptor<VibrationAttributes> captor = ArgumentCaptor.forClass(
                VibrationAttributes.class);
        verify(mVibratorSpy).vibrate(anyInt(), anyString(), any(), isNull(), captor.capture());

        VibrationAttributes vibrationAttributes = captor.getValue();
        assertEquals(VibrationAttributes.USAGE_TOUCH, vibrationAttributes.getUsage());
        // Sets AudioAttributes usage based on effect.
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                vibrationAttributes.getAudioUsage());
    }

    @Test
    public void vibrate_withoutAudioAttributesAndLongEffect_hasUnknownUsage() {
        mVibratorSpy.vibrate(VibrationEffect.createOneShot(10_000, 255));

        ArgumentCaptor<VibrationAttributes> captor = ArgumentCaptor.forClass(
                VibrationAttributes.class);
        verify(mVibratorSpy).vibrate(anyInt(), anyString(), any(), isNull(), captor.capture());

        VibrationAttributes vibrationAttributes = captor.getValue();
        assertEquals(VibrationAttributes.USAGE_UNKNOWN, vibrationAttributes.getUsage());
        assertEquals(AudioAttributes.USAGE_UNKNOWN, vibrationAttributes.getAudioUsage());
    }
}
