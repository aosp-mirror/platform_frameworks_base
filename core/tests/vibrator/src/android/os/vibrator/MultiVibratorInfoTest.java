/*
 * Copyright 2023 The Android Open Source Project
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

package android.os.vibrator;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertEquals;

import android.hardware.vibrator.IVibrator;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MultiVibratorInfoTest {
    private static final float TEST_TOLERANCE = 1e-5f;

    @Test
    public void testGetId() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo secondInfo = new VibratorInfo.Builder(/* id= */ 2)
                .setSupportedEffects(new int[0])
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 3,
                new VibratorInfo[]{firstInfo, secondInfo});

        assertEquals(3, info.getId());
    }

    @Test
    public void testIsEffectSupported_supportedInAllVibrators_returnsYes() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo secondInfo = new VibratorInfo.Builder(/* id= */ 2)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_TICK)
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo});

        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
    }

    @Test
    public void testIsEffectSupported_unsupportedInOneVibrator_returnsNo() {
        VibratorInfo supportedVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo unsupportedVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setSupportedEffects(new int[0])
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{supportedVibrator, unsupportedVibrator});

        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
    }

    @Test
    public void testIsEffectSupported_unknownInOneVibrator_returnsUnknown() {
        VibratorInfo supportedVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo unknownSupportVibrator = VibratorInfo.EMPTY_VIBRATOR_INFO;

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{supportedVibrator, unknownSupportVibrator});
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
    }

    @Test
    public void testIsPrimitiveSupported_unsupportedInOneVibrator_returnsFalse() {
        VibratorInfo supportedVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        VibratorInfo unsupportedVibrator = VibratorInfo.EMPTY_VIBRATOR_INFO;

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{supportedVibrator, unsupportedVibrator});

        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void testIsPrimitiveSupported_supportedInAllVibrators_returnsTrue() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 5)
                .build();
        VibratorInfo secondInfo = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 15)
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo});

        assertTrue(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void testGetPrimitiveDuration_unsupportedInOneVibrator_returnsZero() {
        VibratorInfo supportedVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        VibratorInfo unsupportedVibrator = VibratorInfo.EMPTY_VIBRATOR_INFO;

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{supportedVibrator, unsupportedVibrator});

        assertEquals(0, info.getPrimitiveDuration(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void testGetPrimitiveDuration_supportedInAllVibrators_returnsMaxDuration() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        VibratorInfo secondInfo = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 20)
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo});

        assertEquals(20, info.getPrimitiveDuration(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void testGetQFactorAndResonantFrequency_differentValues_returnsNaN() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setQFactor(1f)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, 1, 1, null))
                .build();
        VibratorInfo secondInfo = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setQFactor(2f)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(2, 2, 2, null))
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo});

        assertTrue(Float.isNaN(info.getQFactor()));
        assertTrue(Float.isNaN(info.getResonantFrequencyHz()));
        assertEmptyFrequencyProfileAndControl(info);

        // One vibrator with values undefined.
        VibratorInfo thirdVibrator = new VibratorInfo.Builder(/* id= */ 3).build();
        info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, thirdVibrator});

        assertTrue(Float.isNaN(info.getQFactor()));
        assertTrue(Float.isNaN(info.getResonantFrequencyHz()));
        assertEmptyFrequencyProfileAndControl(info);
    }

    @Test
    public void testGetQFactorAndResonantFrequency_sameValues_returnsValue() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setQFactor(10f)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(
                        /* resonantFrequencyHz= */ 11, 10, 0.5f, null))
                .build();
        VibratorInfo secondInfo = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setQFactor(10f)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(
                        /* resonantFrequencyHz= */ 11, 5, 1, null))
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo});

        assertEquals(10f, info.getQFactor(), TEST_TOLERANCE);
        assertEquals(11f, info.getResonantFrequencyHz(), TEST_TOLERANCE);
        // No frequency range defined.
        assertTrue(info.getFrequencyProfile().isEmpty());
        assertEquals(false, info.hasCapability(IVibrator.CAP_FREQUENCY_CONTROL));
    }

    @Test
    public void testGetFrequencyProfile_differentResonantFrequencyOrResolutions_returnsEmpty() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, 1, 1,
                        new float[] { 0, 1 }))
                .build();
        VibratorInfo differentResonantFrequency = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(2, 1, 1,
                        new float[] { 0, 1 }))
                .build();
        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, differentResonantFrequency});

        assertEmptyFrequencyProfileAndControl(info);

        VibratorInfo differentFrequencyResolution = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, 1, 2,
                        new float[] { 0, 1 }))
                .build();
        info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, differentFrequencyResolution});

        assertEmptyFrequencyProfileAndControl(info);
    }

    @Test
    public void testGetFrequencyProfile_missingValues_returnsEmpty() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, 1, 1,
                        new float[] { 0, 1 }))
                .build();
        VibratorInfo missingResonantFrequency = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(Float.NaN, 1, 1,
                        new float[] { 0, 1 }))
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, missingResonantFrequency});

        assertEmptyFrequencyProfileAndControl(info);

        VibratorInfo missingMinFrequency = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, Float.NaN, 1,
                        new float[] { 0, 1 }))
                .build();
        info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, missingMinFrequency});

        assertEmptyFrequencyProfileAndControl(info);

        VibratorInfo missingFrequencyResolution = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, 1, Float.NaN,
                        new float[] { 0, 1 }))
                .build();
        info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, missingFrequencyResolution});

        assertEmptyFrequencyProfileAndControl(info);

        VibratorInfo missingMaxAmplitudes = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, 1, 1, null))
                .build();
        info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, missingMaxAmplitudes});

        assertEmptyFrequencyProfileAndControl(info);
    }

    @Test
    public void testGetFrequencyProfile_unalignedMaxAmplitudes_returnsEmpty() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10, 0.5f,
                        new float[] { 0, 1, 1, 0 }))
                .build();
        VibratorInfo unalignedMinFrequency = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10.1f, 0.5f,
                        new float[] { 0, 1, 1, 0 }))
                .build();
        VibratorInfo thirdVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10.5f, 0.5f,
                        new float[] { 0, 1, 1, 0 }))
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, unalignedMinFrequency, thirdVibrator});

        assertEmptyFrequencyProfileAndControl(info);
    }

    @Test
    public void testGetFrequencyProfile_alignedProfiles_returnsIntersection() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10, 0.5f,
                        new float[] { 0.5f, 1, 1, 0.5f }))
                .build();
        VibratorInfo secondInfo = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10.5f, 0.5f,
                        new float[] { 1, 1, 1 }))
                .build();
        VibratorInfo thirdVibrator = new VibratorInfo.Builder(/* id= */ 3)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10.5f, 0.5f,
                        new float[] { 0.8f, 1, 0.8f, 0.5f }))
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo, thirdVibrator});

        assertEquals(
                new VibratorInfo.FrequencyProfile(11, 10.5f, 0.5f, new float[] { 0.8f, 1, 0.5f }),
                info.getFrequencyProfile());
        assertEquals(true, info.hasCapability(IVibrator.CAP_FREQUENCY_CONTROL));

        // Third vibrator without frequency control capability.
        thirdVibrator = new VibratorInfo.Builder(/* id= */ 3)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10.5f, 0.5f,
                        new float[] { 0.8f, 1, 0.8f, 0.5f }))
                .build();
        info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo, thirdVibrator});

        assertEquals(
                new VibratorInfo.FrequencyProfile(11, 10.5f, 0.5f, new float[] { 0.8f, 1, 0.5f }),
                info.getFrequencyProfile());
        assertEquals(false, info.hasCapability(IVibrator.CAP_FREQUENCY_CONTROL));
    }

    /**
     * Asserts that the frequency profile is empty, and therefore frequency control isn't supported.
     */
    private void assertEmptyFrequencyProfileAndControl(VibratorInfo info) {
        assertTrue(info.getFrequencyProfile().isEmpty());
        assertEquals(false, info.hasCapability(IVibrator.CAP_FREQUENCY_CONTROL));
    }
}
