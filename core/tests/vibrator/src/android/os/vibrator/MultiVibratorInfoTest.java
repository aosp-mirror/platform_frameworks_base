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

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertEquals;

import android.hardware.vibrator.IVibrator;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MultiVibratorInfoTest {
    private static final float TEST_TOLERANCE = 1e-5f;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

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
    @DisableFlags(android.os.vibrator.Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testGetQFactorAndResonantFrequency_differentValues_returnsNaN() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setQFactor(1f)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(1, 1, 1, null))
                .build();
        VibratorInfo secondInfo = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setQFactor(2f)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(2, 2, 2, null))
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
    @DisableFlags(android.os.vibrator.Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testGetQFactorAndResonantFrequency_sameValues_returnsValue() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setQFactor(10f)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(
                        /* resonantFrequencyHz= */ 11, 10, 0.5f, null))
                .build();
        VibratorInfo secondInfo = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setQFactor(10f)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(
                        /* resonantFrequencyHz= */ 11, 5, 1, null))
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo});

        assertEquals(10f, info.getQFactor(), TEST_TOLERANCE);
        assertEquals(11f, info.getResonantFrequencyHz(), TEST_TOLERANCE);
        // No frequency range defined.
        assertTrue(info.getFrequencyProfileLegacy().isEmpty());
        assertEquals(false, info.hasCapability(IVibrator.CAP_FREQUENCY_CONTROL));
    }

    @Test
    @DisableFlags(android.os.vibrator.Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testGetFrequencyProfileLegacy_differentResonantFreqOrResolutions_returnsEmpty() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(1, 1, 1,
                        new float[] { 0, 1 }))
                .build();
        VibratorInfo differentResonantFrequency = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(2, 1, 1,
                        new float[] { 0, 1 }))
                .build();
        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, differentResonantFrequency});

        assertEmptyFrequencyProfileAndControl(info);

        VibratorInfo differentFrequencyResolution = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(1, 1, 2,
                        new float[] { 0, 1 }))
                .build();
        info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, differentFrequencyResolution});

        assertEmptyFrequencyProfileAndControl(info);
    }

    @Test
    @DisableFlags(android.os.vibrator.Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testGetFrequencyProfileLegacy_missingValues_returnsEmpty() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(1, 1, 1,
                        new float[] { 0, 1 }))
                .build();
        VibratorInfo missingResonantFrequency = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(Float.NaN, 1, 1,
                        new float[] { 0, 1 }))
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, missingResonantFrequency});

        assertEmptyFrequencyProfileAndControl(info);

        VibratorInfo missingMinFrequency = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(1, Float.NaN, 1,
                        new float[] { 0, 1 }))
                .build();
        info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, missingMinFrequency});

        assertEmptyFrequencyProfileAndControl(info);

        VibratorInfo missingFrequencyResolution = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(1, 1, Float.NaN,
                        new float[] { 0, 1 }))
                .build();
        info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, missingFrequencyResolution});

        assertEmptyFrequencyProfileAndControl(info);

        VibratorInfo missingMaxAmplitudes = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(1, 1, 1, null))
                .build();
        info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, missingMaxAmplitudes});

        assertEmptyFrequencyProfileAndControl(info);
    }

    @Test
    @DisableFlags(android.os.vibrator.Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testGetFrequencyProfileLegacy_unalignedMaxAmplitudes_returnsEmpty() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(11, 10, 0.5f,
                        new float[] { 0, 1, 1, 0 }))
                .build();
        VibratorInfo unalignedMinFrequency = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(11, 10.1f, 0.5f,
                        new float[] { 0, 1, 1, 0 }))
                .build();
        VibratorInfo thirdVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(11, 10.5f, 0.5f,
                        new float[] { 0, 1, 1, 0 }))
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, unalignedMinFrequency, thirdVibrator});

        assertEmptyFrequencyProfileAndControl(info);
    }

    @Test
    @DisableFlags(android.os.vibrator.Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testGetFrequencyProfileLegacy_alignedProfiles_returnsIntersection() {
        VibratorInfo firstInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(11, 10, 0.5f,
                        new float[] { 0.5f, 1, 1, 0.5f }))
                .build();
        VibratorInfo secondInfo = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(11, 10.5f, 0.5f,
                        new float[] { 1, 1, 1 }))
                .build();
        VibratorInfo thirdVibrator = new VibratorInfo.Builder(/* id= */ 3)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(11, 10.5f, 0.5f,
                        new float[] { 0.8f, 1, 0.8f, 0.5f }))
                .build();

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo, thirdVibrator});

        assertEquals(
                new VibratorInfo.FrequencyProfileLegacy(11, 10.5f, 0.5f,
                        new float[]{0.8f, 1, 0.5f}),
                info.getFrequencyProfileLegacy());
        assertEquals(true, info.hasCapability(IVibrator.CAP_FREQUENCY_CONTROL));

        // Third vibrator without frequency control capability.
        thirdVibrator = new VibratorInfo.Builder(/* id= */ 3)
                .setFrequencyProfileLegacy(new VibratorInfo.FrequencyProfileLegacy(11, 10.5f, 0.5f,
                        new float[] { 0.8f, 1, 0.8f, 0.5f }))
                .build();
        info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo, thirdVibrator});

        assertEquals(
                new VibratorInfo.FrequencyProfileLegacy(11, 10.5f, 0.5f,
                        new float[]{0.8f, 1, 0.5f}),
                info.getFrequencyProfileLegacy());
        assertEquals(false, info.hasCapability(IVibrator.CAP_FREQUENCY_CONTROL));
    }

    @Test
    @EnableFlags(android.os.vibrator.Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testGetFrequencyProfile_alignedProfiles_returnsIntersection() {
        VibratorInfo firstInfo = createVibratorInfoWithFrequencyProfile(/*id=*/ 1,
                IVibrator.CAP_FREQUENCY_CONTROL, /*resonantFrequencyHz=*/ 180f,
                /*frequencies=*/new float[]{30f, 60f, 120f, 150f, 180f, 210f, 270f, 300f},
                /*accelerations=*/new float[]{0.1f, 0.6f, 1.8f, 2.4f, 3.0f, 2.2f, 1.0f, 0.5f});

        VibratorInfo secondInfo = createVibratorInfoWithFrequencyProfile(/*id=*/ 2,
                IVibrator.CAP_FREQUENCY_CONTROL, /*resonantFrequencyHz=*/ 180f,
                /*frequencies=*/new float[]{120f, 150f, 180f, 210f},
                /*accelerations=*/new float[]{1.5f, 2.6f, 2.7f, 2.1f});

        VibratorInfo.FrequencyProfile expectedFrequencyProfile =
                new VibratorInfo.FrequencyProfile(/*resonantFrequencyHz=*/
                        180f, /*frequenciesHz=*/new float[]{120.0f, 150.0f, 180.0f, 210.0f},
                        /*outputAccelerationsGs=*/new float[]{1.5f, 2.4f, 2.7f, 2.1f});

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo});

        assertThat(info.getFrequencyProfile()).isEqualTo(expectedFrequencyProfile);
    }

    @Test
    @EnableFlags(android.os.vibrator.Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testGetFrequencyProfile_alignedProfilesUsingInterpolation_returnsIntersection() {
        VibratorInfo firstInfo = createVibratorInfoWithFrequencyProfile(/*id=*/ 1,
                IVibrator.CAP_FREQUENCY_CONTROL, /*resonantFrequencyHz=*/ 180f,
                /*frequencies=*/new float[]{30f, 60f, 120f},
                /*accelerations=*/new float[]{0.25f, 1.0f, 4.0f});

        VibratorInfo secondInfo = createVibratorInfoWithFrequencyProfile(/*id=*/ 2,
                IVibrator.CAP_FREQUENCY_CONTROL, /*resonantFrequencyHz=*/ 180f,
                /*frequencies=*/new float[]{40f, 70f, 110f},
                /*accelerations=*/new float[]{1.0f, 2.5f, 4.0f});

        VibratorInfo.FrequencyProfile expectedFrequencyProfile =
                new VibratorInfo.FrequencyProfile(/*resonantFrequencyHz=*/
                        180f, /*frequenciesHz=*/new float[]{40f, 60f, 70f, 110f},
                        /*outputAccelerationsGs=*/new float[]{0.5f, 1.0f, 1.5f, 3.5f});

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo});

        assertThat(info.getFrequencyProfile()).isEqualTo(expectedFrequencyProfile);
    }

    @Test
    @EnableFlags(android.os.vibrator.Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testGetFrequencyProfile_disjointFrequencyRange_returnsEmpty() {

        VibratorInfo firstInfo = createVibratorInfoWithFrequencyProfile(/*id=*/ 1,
                IVibrator.CAP_FREQUENCY_CONTROL, /*resonantFrequencyHz=*/ 180f,
                /*frequencies=*/new float[]{30f, 60f, 120f, 150f, 180f, 210f, 270f, 300f},
                /*accelerations=*/new float[]{0.1f, 0.6f, 1.8f, 2.4f, 3.0f, 2.2f, 1.0f, 0.5f});

        VibratorInfo secondInfo = createVibratorInfoWithFrequencyProfile(/*id=*/ 2,
                IVibrator.CAP_FREQUENCY_CONTROL, /*resonantFrequencyHz=*/ 180f,
                /*frequencies=*/new float[]{310f, 320f, 350f, 380f, 410f, 440f},
                /*accelerations=*/new float[]{0.3f, 0.75f, 1.82f, 2.11f, 2.8f, 2.12f, 1.4f, 0.42f});

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo});

        assertThat(info.getFrequencyProfile()).isEqualTo(
                new VibratorInfo.FrequencyProfile(/*resonantFrequencyHz=*/ Float.NaN,
                        /*frequenciesHz=*/null, /*outputAccelerationsGs=*/null));
        assertThat(info.hasCapability(IVibrator.CAP_FREQUENCY_CONTROL)).isFalse();
    }

    @Test
    @EnableFlags(android.os.vibrator.Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testGetFrequencyProfile_emptyFrequencyRange_returnsEmpty() {
        VibratorInfo firstInfo = createVibratorInfoWithFrequencyProfile(/*id=*/ 1,
                IVibrator.CAP_FREQUENCY_CONTROL, /*resonantFrequencyHz=*/180f,
                /*frequencies=*/null, /*accelerations=*/null);

        VibratorInfo secondInfo = createVibratorInfoWithFrequencyProfile(/*id=*/ 2,
                IVibrator.CAP_FREQUENCY_CONTROL, /*resonantFrequencyHz=*/180f,
                /*frequencies=*/new float[]{30f, 60f, 150f, 180f, 210f, 240f, 300f},
                /*accelerations=*/new float[]{0.1f, 0.6f, 2.4f, 3.0f, 2.2f, 1.9f, 0.5f});

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo});

        assertThat(info.getFrequencyProfile()).isEqualTo(
                new VibratorInfo.FrequencyProfile(/*resonantFrequencyHz=*/ Float.NaN,
                        /*frequenciesHz=*/null,
                        /*outputAccelerationsGs=*/null));
        assertThat(info.hasCapability(IVibrator.CAP_FREQUENCY_CONTROL)).isFalse();
    }

    @Test
    @EnableFlags(android.os.vibrator.Flags.FLAG_NORMALIZED_PWLE_EFFECTS)
    public void testGetFrequencyProfile_differentResonantFrequency_returnsEmpty() {
        VibratorInfo firstInfo = createVibratorInfoWithFrequencyProfile(/*id=*/ 1,
                IVibrator.CAP_FREQUENCY_CONTROL, /*resonantFrequencyHz=*/ 160f,
                /*frequencies=*/new float[]{30f, 60f, 120f, 150f, 180f, 210f, 270f, 300f},
                /*accelerations=*/new float[]{0.1f, 0.6f, 1.8f, 2.4f, 3.0f, 2.2f, 1.0f, 0.5f});

        VibratorInfo secondInfo = createVibratorInfoWithFrequencyProfile(/*id=*/ 2,
                IVibrator.CAP_FREQUENCY_CONTROL, /*resonantFrequencyHz=*/ 180f,
                /*frequencies=*/new float[]{30f, 60f, 120f, 150f, 180f, 210f, 270f, 300f},
                /*accelerations=*/new float[]{0.1f, 0.6f, 1.8f, 2.4f, 3.0f, 2.2f, 1.0f, 0.5f});

        VibratorInfo info = new MultiVibratorInfo(/* id= */ 1,
                new VibratorInfo[]{firstInfo, secondInfo});

        assertThat(info.getFrequencyProfile()).isEqualTo(
                new VibratorInfo.FrequencyProfile(/*resonantFrequencyHz=*/ Float.NaN,
                        /*frequenciesHz=*/null,
                        /*outputAccelerationsGs=*/null));
        assertThat(info.hasCapability(IVibrator.CAP_FREQUENCY_CONTROL)).isFalse();
    }

    private VibratorInfo createVibratorInfoWithFrequencyProfile(int id, long capabilities,
            float resonantFrequencyHz, float[] frequencies, float[] accelerations) {
        return new VibratorInfo.Builder(id)
                .setCapabilities(capabilities)
                .setFrequencyProfile(
                        new VibratorInfo.FrequencyProfile(resonantFrequencyHz, frequencies,
                                accelerations))
                .build();
    }

    /**
     * Asserts that the frequency profile is empty, and therefore frequency control isn't supported.
     */
    private void assertEmptyFrequencyProfileAndControl(VibratorInfo info) {
        assertTrue(info.getFrequencyProfileLegacy().isEmpty());
        assertEquals(false, info.hasCapability(IVibrator.CAP_FREQUENCY_CONTROL));
    }
}
