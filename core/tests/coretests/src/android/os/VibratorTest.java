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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.vibrator.IVibrator;
import android.media.AudioAttributes;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;

import org.junit.Before;
import org.junit.Rule;
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

    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    private static final float TEST_TOLERANCE = 1e-5f;

    private Context mContextSpy;
    private Vibrator mVibratorSpy;

    @Before
    public void setUp() {
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        mVibratorSpy = spy(new SystemVibrator(mContextSpy));
    }

    @Test
    public void getId_returnsDefaultId() {
        assertEquals(-1, mVibratorSpy.getId());
        assertEquals(-1, new SystemVibrator.NoVibratorInfo().getId());
        assertEquals(-1, new SystemVibrator.MultiVibratorInfo(new VibratorInfo[] {
                VibratorInfo.EMPTY_VIBRATOR_INFO, VibratorInfo.EMPTY_VIBRATOR_INFO }).getId());
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
    public void areEffectsSupported_noVibrator_returnsAlwaysNo() {
        VibratorInfo info = new SystemVibrator.NoVibratorInfo();
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
    }

    @Test
    public void areEffectsSupported_unsupportedInOneVibrator_returnsNo() {
        VibratorInfo supportedVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo unsupportedVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setSupportedEffects(new int[0])
                .build();
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{supportedVibrator, unsupportedVibrator});
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
    }

    @Test
    public void areEffectsSupported_unknownInOneVibrator_returnsUnknown() {
        VibratorInfo supportedVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo unknownSupportVibrator = VibratorInfo.EMPTY_VIBRATOR_INFO;
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{supportedVibrator, unknownSupportVibrator});
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
    }

    @Test
    public void arePrimitivesSupported_supportedInAllVibrators_returnsYes() {
        VibratorInfo firstVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo secondVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, secondVibrator});
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                info.isEffectSupported(VibrationEffect.EFFECT_CLICK));
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
    public void arePrimitivesSupported_noVibrator_returnsAlwaysFalse() {
        VibratorInfo info = new SystemVibrator.NoVibratorInfo();
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void arePrimitivesSupported_unsupportedInOneVibrator_returnsFalse() {
        VibratorInfo supportedVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        VibratorInfo unsupportedVibrator = VibratorInfo.EMPTY_VIBRATOR_INFO;
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{supportedVibrator, unsupportedVibrator});
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void arePrimitivesSupported_supportedInAllVibrators_returnsTrue() {
        VibratorInfo firstVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 5)
                .build();
        VibratorInfo secondVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 15)
                .build();
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, secondVibrator});
        assertTrue(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void getPrimitivesDurations_returnsArrayOfSameSize() {
        assertEquals(0, mVibratorSpy.getPrimitiveDurations(new int[0]).length);
        assertEquals(1, mVibratorSpy.getPrimitiveDurations(
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK}).length);
        assertEquals(2, mVibratorSpy.getPrimitiveDurations(
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE}).length);
    }

    @Test
    public void getPrimitivesDurations_noVibrator_returnsAlwaysZero() {
        VibratorInfo info = new SystemVibrator.NoVibratorInfo();
        assertEquals(0, info.getPrimitiveDuration(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void getPrimitivesDurations_unsupportedInOneVibrator_returnsZero() {
        VibratorInfo supportedVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        VibratorInfo unsupportedVibrator = VibratorInfo.EMPTY_VIBRATOR_INFO;
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{supportedVibrator, unsupportedVibrator});
        assertEquals(0, info.getPrimitiveDuration(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void getPrimitivesDurations_supportedInAllVibrators_returnsMaxDuration() {
        VibratorInfo firstVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        VibratorInfo secondVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 20)
                .build();
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, secondVibrator});
        assertEquals(20, info.getPrimitiveDuration(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void getQFactorAndResonantFrequency_noVibrator_returnsNaN() {
        VibratorInfo info = new SystemVibrator.NoVibratorInfo();

        assertTrue(Float.isNaN(info.getQFactor()));
        assertTrue(Float.isNaN(info.getResonantFrequencyHz()));
    }

    @Test
    public void getQFactorAndResonantFrequency_differentValues_returnsNaN() {
        VibratorInfo firstVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setQFactor(1f)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, 1, 1, null))
                .build();
        VibratorInfo secondVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setQFactor(2f)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(2, 2, 2, null))
                .build();
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, secondVibrator});

        assertTrue(Float.isNaN(info.getQFactor()));
        assertTrue(Float.isNaN(info.getResonantFrequencyHz()));

        // One vibrator with values undefined.
        VibratorInfo thirdVibrator = new VibratorInfo.Builder(/* id= */ 3).build();
        info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, thirdVibrator});

        assertTrue(Float.isNaN(info.getQFactor()));
        assertTrue(Float.isNaN(info.getResonantFrequencyHz()));
    }

    @Test
    public void getQFactorAndResonantFrequency_sameValues_returnsValue() {
        VibratorInfo firstVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setQFactor(10f)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(
                        /* resonantFrequencyHz= */ 11, 10, 0.5f, null))
                .build();
        VibratorInfo secondVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setQFactor(10f)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(
                        /* resonantFrequencyHz= */ 11, 5, 1, null))
                .build();
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, secondVibrator});

        assertEquals(10f, info.getQFactor(), TEST_TOLERANCE);
        assertEquals(11f, info.getResonantFrequencyHz(), TEST_TOLERANCE);
    }

    @Test
    public void getFrequencyProfile_noVibrator_returnsEmpty() {
        VibratorInfo info = new SystemVibrator.NoVibratorInfo();

        assertTrue(info.getFrequencyProfile().isEmpty());
    }

    @Test
    public void getFrequencyProfile_differentResonantFrequencyOrResolutionValues_returnsEmpty() {
        VibratorInfo firstVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, 1, 1,
                        new float[] { 0, 1 }))
                .build();
        VibratorInfo differentResonantFrequency = new VibratorInfo.Builder(/* id= */ 2)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(2, 1, 1,
                        new float[] { 0, 1 }))
                .build();
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, differentResonantFrequency});

        assertTrue(info.getFrequencyProfile().isEmpty());

        VibratorInfo differentFrequencyResolution = new VibratorInfo.Builder(/* id= */ 2)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, 1, 2,
                        new float[] { 0, 1 }))
                .build();
        info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, differentFrequencyResolution});

        assertTrue(info.getFrequencyProfile().isEmpty());
    }

    @Test
    public void getFrequencyProfile_missingValues_returnsEmpty() {
        VibratorInfo firstVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, 1, 1,
                        new float[] { 0, 1 }))
                .build();
        VibratorInfo missingResonantFrequency = new VibratorInfo.Builder(/* id= */ 2)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(Float.NaN, 1, 1,
                        new float[] { 0, 1 }))
                .build();
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, missingResonantFrequency});

        assertTrue(info.getFrequencyProfile().isEmpty());

        VibratorInfo missingMinFrequency = new VibratorInfo.Builder(/* id= */ 2)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, Float.NaN, 1,
                        new float[] { 0, 1 }))
                .build();
        info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, missingMinFrequency});

        assertTrue(info.getFrequencyProfile().isEmpty());

        VibratorInfo missingFrequencyResolution = new VibratorInfo.Builder(/* id= */ 2)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, 1, Float.NaN,
                        new float[] { 0, 1 }))
                .build();
        info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, missingFrequencyResolution});

        assertTrue(info.getFrequencyProfile().isEmpty());

        VibratorInfo missingMaxAmplitudes = new VibratorInfo.Builder(/* id= */ 2)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(1, 1, 1, null))
                .build();
        info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, missingMaxAmplitudes});

        assertTrue(info.getFrequencyProfile().isEmpty());
    }

    @Test
    public void getFrequencyProfile_unalignedMaxAmplitudes_returnsEmpty() {
        VibratorInfo firstVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10, 0.5f,
                        new float[] { 0, 1, 1, 0 }))
                .build();
        VibratorInfo unalignedMinFrequency = new VibratorInfo.Builder(/* id= */ 2)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10.1f, 0.5f,
                        new float[] { 0, 1, 1, 0 }))
                .build();
        VibratorInfo thirdVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10.5f, 0.5f,
                        new float[] { 0, 1, 1, 0 }))
                .build();
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, unalignedMinFrequency, thirdVibrator});

        assertTrue(info.getFrequencyProfile().isEmpty());
    }

    @Test
    public void getFrequencyProfile_alignedProfiles_returnsIntersection() {
        VibratorInfo firstVibrator = new VibratorInfo.Builder(/* id= */ 1)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10, 0.5f,
                        new float[] { 0.5f, 1, 1, 0.5f }))
                .build();
        VibratorInfo secondVibrator = new VibratorInfo.Builder(/* id= */ 2)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10.5f, 0.5f,
                        new float[] { 1, 1, 1 }))
                .build();
        VibratorInfo thirdVibrator = new VibratorInfo.Builder(/* id= */ 3)
                .setFrequencyProfile(new VibratorInfo.FrequencyProfile(11, 10.5f, 0.5f,
                        new float[] { 0.8f, 1, 0.8f, 0.5f }))
                .build();
        VibratorInfo info = new SystemVibrator.MultiVibratorInfo(
                new VibratorInfo[]{firstVibrator, secondVibrator, thirdVibrator});

        assertEquals(
                new VibratorInfo.FrequencyProfile(11, 10.5f, 0.5f, new float[] { 0.8f, 1, 0.5f }),
                info.getFrequencyProfile());
    }

    @Test
    public void vibrate_withVibrationAttributes_usesGivenAttributes() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        VibrationAttributes attributes = new VibrationAttributes.Builder().setUsage(
                VibrationAttributes.USAGE_TOUCH).build();

        mVibratorSpy.vibrate(effect, attributes);

        verify(mVibratorSpy).vibrate(anyInt(), anyString(), eq(effect), isNull(), eq(attributes));
    }

    @Test
    public void vibrate_withAudioAttributes_createsVibrationAttributesWithSameUsage() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        AudioAttributes audioAttributes = new AudioAttributes.Builder().setUsage(
                AudioAttributes.USAGE_VOICE_COMMUNICATION).build();

        mVibratorSpy.vibrate(effect, audioAttributes);

        ArgumentCaptor<VibrationAttributes> captor = ArgumentCaptor.forClass(
                VibrationAttributes.class);
        verify(mVibratorSpy).vibrate(anyInt(), anyString(), eq(effect), isNull(), captor.capture());

        VibrationAttributes vibrationAttributes = captor.getValue();
        assertEquals(VibrationAttributes.USAGE_COMMUNICATION_REQUEST,
                vibrationAttributes.getUsage());
        // Keeps original AudioAttributes usage to be used by the VibratorService.
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION,
                vibrationAttributes.getAudioUsage());
    }

    @Test
    public void vibrate_withoutAudioAttributes_passesOnDefaultAttributes() {
        mVibratorSpy.vibrate(VibrationEffect.get(VibrationEffect.EFFECT_CLICK));

        ArgumentCaptor<VibrationAttributes> captor = ArgumentCaptor.forClass(
                VibrationAttributes.class);
        verify(mVibratorSpy).vibrate(anyInt(), anyString(), any(), isNull(), captor.capture());

        VibrationAttributes vibrationAttributes = captor.getValue();
        assertEquals(new VibrationAttributes.Builder().build(), vibrationAttributes);
    }
}
