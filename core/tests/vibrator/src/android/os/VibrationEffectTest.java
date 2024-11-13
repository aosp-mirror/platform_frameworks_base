/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.os.VibrationEffect.DEFAULT_AMPLITUDE;
import static android.os.VibrationEffect.VibrationParameter.targetAmplitude;
import static android.os.VibrationEffect.VibrationParameter.targetFrequency;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertThrows;

import android.content.ContentInterface;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.vibrator.IVibrator;
import android.net.Uri;
import android.os.VibrationEffect.Composition.UnreachableAfterRepeatingIndefinitelyException;
import android.os.vibrator.Flags;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.RequiresFlagsEnabled;

import com.android.internal.R;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;
import java.util.Arrays;

@RunWith(MockitoJUnitRunner.class)
public class VibrationEffectTest {
    private static final float TOLERANCE = 1e-2f;
    private static final String RINGTONE_URI_1 = "content://test/system/ringtone_1";
    private static final String RINGTONE_URI_2 = "content://test/system/ringtone_2";
    private static final String RINGTONE_URI_3 = "content://test/system/ringtone_3";
    private static final String UNKNOWN_URI = "content://test/system/other_audio";

    private static final long TEST_TIMING = 100;
    private static final int TEST_AMPLITUDE = 100;
    private static final long[] TEST_TIMINGS = new long[] { 100, 100, 200 };
    private static final int[] TEST_AMPLITUDES =
            new int[] { 255, 0, DEFAULT_AMPLITUDE };

    private static final VibrationEffect TEST_ONE_SHOT =
            VibrationEffect.createOneShot(TEST_TIMING, TEST_AMPLITUDE);
    private static final VibrationEffect DEFAULT_ONE_SHOT =
            VibrationEffect.createOneShot(TEST_TIMING, DEFAULT_AMPLITUDE);
    private static final VibrationEffect TEST_WAVEFORM =
            VibrationEffect.createWaveform(TEST_TIMINGS, TEST_AMPLITUDES, -1);

    @Test
    public void computeLegacyPattern_timingsAndAmplitudes_zeroAmplitudesOnEvenIndices() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 3, 4, 5},
                /* amplitudes= */ new int[] {0, DEFAULT_AMPLITUDE, 0, DEFAULT_AMPLITUDE, 0},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {1, 2, 3, 4, 5};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsAndAmplitudes_zeroAmplitudesOnOddIndices() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 3, 4, 5},
                /* amplitudes= */ new int[] {
                        DEFAULT_AMPLITUDE, 0, DEFAULT_AMPLITUDE, 0, DEFAULT_AMPLITUDE},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {0, 1, 2, 3, 4, 5};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsAndAmplitudes_zeroAmplitudesAtTheStart() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 3},
                /* amplitudes= */ new int[] {0, 0, DEFAULT_AMPLITUDE},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {3, 3};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsAndAmplitudes_zeroAmplitudesAtTheEnd() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 3},
                /* amplitudes= */ new int[] {DEFAULT_AMPLITUDE, 0, 0},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {0, 1, 5};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsAndAmplitudes_allDefaultAmplitudes() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 3},
                /* amplitudes= */ new int[] {
                        DEFAULT_AMPLITUDE, DEFAULT_AMPLITUDE, DEFAULT_AMPLITUDE},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {0, 6};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsAndAmplitudes_allZeroAmplitudes() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 3},
                /* amplitudes= */ new int[] {0, 0, 0},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {6};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsAndAmplitudes_sparsedZeroAmplitudes() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 3, 4, 5, 6, 7},
                /* amplitudes= */ new int[] {
                        0, 0, DEFAULT_AMPLITUDE, 0, DEFAULT_AMPLITUDE, DEFAULT_AMPLITUDE, 0},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {3, 3, 4, 11, 7};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsAndAmplitudes_oneTimingWithDefaultAmplitude() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1},
                /* amplitudes= */ new int[] {DEFAULT_AMPLITUDE},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {0, 1};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsAndAmplitudes_oneTimingWithZeroAmplitude() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1},
                /* amplitudes= */ new int[] {0},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {1};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsAndAmplitudes_repeating() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 3, 4, 5},
                /* amplitudes= */ new int[] {0, DEFAULT_AMPLITUDE, 0, DEFAULT_AMPLITUDE, 0},
                /* repeatIndex= */ 0);

        assertNull(effect.computeCreateWaveformOffOnTimingsOrNull());

        effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 3, 4, 5},
                /* amplitudes= */ new int[] {0, DEFAULT_AMPLITUDE, 0, DEFAULT_AMPLITUDE, 0},
                /* repeatIndex= */ 3);

        assertNull(effect.computeCreateWaveformOffOnTimingsOrNull());

        effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2},
                /* amplitudes= */ new int[] {DEFAULT_AMPLITUDE, DEFAULT_AMPLITUDE},
                /* repeatIndex= */ 1);

        assertNull(effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsAndAmplitudes_badAmplitude() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1},
                /* amplitudes= */ new int[] {200},
                /* repeatIndex= */ -1);

        assertNull(effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsOnly_nonZeroTimings() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 3},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {1, 2, 3};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsOnly_oneValue() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {5},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {5};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsOnly_zeroesAtTheEnd() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 3, 0, 0},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {1, 2, 3, 0, 0};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsOnly_zeroesAtTheStart() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {0, 0, 1, 2, 3},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {0, 0, 1, 2, 3};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsOnly_zeroesAtTheMiddle() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 0, 0, 3, 4, 5},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {1, 2, 0, 0, 3, 4, 5};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsOnly_sparsedZeroes() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {0, 1, 2, 0, 0, 3, 4, 5, 0},
                /* repeatIndex= */ -1);
        long[] expectedPattern = new long[] {0, 1, 2, 0, 0, 3, 4, 5, 0};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_timingsOnly_repeating() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {0, 1, 2, 0, 0, 3, 4, 5, 0},
                /* repeatIndex= */ 0);

        assertNull(effect.computeCreateWaveformOffOnTimingsOrNull());

        effect = VibrationEffect.createWaveform(
                /* timings= */ new long[] {1, 2, 3, 4},
                /* repeatIndex= */ 2);

        assertNull(effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_notPatternBased() {
        assertNull(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                .computeCreateWaveformOffOnTimingsOrNull());
        if (Flags.vendorVibrationEffects()) {
            assertNull(VibrationEffect.createVendorEffect(createNonEmptyBundle())
                    .computeCreateWaveformOffOnTimingsOrNull());
        }
    }

    @Test
    public void computeLegacyPattern_oneShot_defaultAmplitude() {
        VibrationEffect effect = VibrationEffect.createOneShot(
                /* milliseconds= */ 5, /* ampliutde= */ DEFAULT_AMPLITUDE);
        long[] expectedPattern = new long[] {0, 5};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_oneShot_badAmplitude() {
        VibrationEffect effect = VibrationEffect.createOneShot(
                /* milliseconds= */ 5, /* ampliutde= */ 50);

        assertNull(effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_composition_noOffDuration() {
        VibrationEffect effect = VibrationEffect.startComposition()
                .addEffect(
                        VibrationEffect.createWaveform(
                                /* timings= */ new long[] {5},
                                /* repeatIndex= */ -1))
                .addEffect(
                        VibrationEffect.createWaveform(
                                /* timings= */ new long[] {2, 3},
                                /* repeatIndex= */ -1))
                .addEffect(
                        VibrationEffect.createWaveform(
                                /* timings= */ new long[] {10, 20},
                                /* amplitudes= */ new int[] {DEFAULT_AMPLITUDE, DEFAULT_AMPLITUDE},
                                /* repeatIndex= */ -1))
                .addEffect(
                        VibrationEffect.createWaveform(
                                /* timings= */ new long[] {4, 5},
                                /* amplitudes= */ new int[] {0, DEFAULT_AMPLITUDE},
                                /* repeatIndex= */ -1))
                .compose();
        long[] expectedPattern = new long[] {7, 33, 4, 5};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_composition_withOffDuration() {
        VibrationEffect effect = VibrationEffect.startComposition()
                .addOffDuration(Duration.ofMillis(20))
                .addEffect(
                        VibrationEffect.createWaveform(
                                /* timings= */ new long[] {10, 20},
                                /* amplitudes= */ new int[] {0, DEFAULT_AMPLITUDE},
                                /* repeatIndex= */ -1))
                .addEffect(
                        VibrationEffect.createWaveform(
                                /* timings= */ new long[] {30, 40},
                                /* amplitudes= */ new int[] {DEFAULT_AMPLITUDE, DEFAULT_AMPLITUDE},
                                /* repeatIndex= */ -1))
                .addOffDuration(Duration.ofMillis(10))
                .addEffect(
                        VibrationEffect.createWaveform(
                                /* timings= */ new long[] {4, 5},
                                /* repeatIndex= */ -1))
                .addOffDuration(Duration.ofMillis(5))
                .compose();
        long[] expectedPattern = new long[] {30, 90, 14, 5, 5};

        assertArrayEq(expectedPattern, effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_composition_withPrimitives() {
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                .addOffDuration(Duration.ofMillis(20))
                .addEffect(
                        VibrationEffect.createWaveform(
                                /* timings= */ new long[] {5},
                                /* repeatIndex= */ -1))
                .compose();

        assertNull(effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_composition_repeating() {
        VibrationEffect effect = VibrationEffect.startComposition()
                .addEffect(
                        VibrationEffect.createWaveform(
                                /* timings= */ new long[] {5},
                                /* repeatIndex= */ -1))
                .repeatEffectIndefinitely(
                        VibrationEffect.createWaveform(
                                /* timings= */ new long[] {2, 3},
                                /* repeatIndex= */ -1))
                .compose();

        assertNull(effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void computeLegacyPattern_effectsViaStartWaveform() {
        // Effects created via startWaveform are not expected to be converted to long[] patterns, as
        // they are not configured to always play with the default amplitude.
        VibrationEffect effect = VibrationEffect.startWaveform(targetFrequency(60))
                .addTransition(Duration.ofMillis(100), targetAmplitude(1), targetFrequency(120))
                .addSustain(Duration.ofMillis(200))
                .addTransition(Duration.ofMillis(100), targetAmplitude(0), targetFrequency(60))
                .build();

        assertNull(effect.computeCreateWaveformOffOnTimingsOrNull());

        effect = VibrationEffect.startWaveform(targetFrequency(60))
                .addTransition(Duration.ofMillis(80), targetAmplitude(1))
                .addSustain(Duration.ofMillis(200))
                .addTransition(Duration.ofMillis(100), targetAmplitude(0))
                .build();

        assertNull(effect.computeCreateWaveformOffOnTimingsOrNull());

        effect = VibrationEffect.startWaveform(targetFrequency(60))
                .addTransition(Duration.ofMillis(100), targetFrequency(50))
                .addSustain(Duration.ofMillis(50))
                .addTransition(Duration.ofMillis(20), targetFrequency(75))
                .build();

        assertNull(effect.computeCreateWaveformOffOnTimingsOrNull());
    }

    @Test
    public void cropToLength_waveform_underLength() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[]{0, 1, 2},
                /* repeatIndex= */ -1);
        VibrationEffect result = effect.cropToLengthOrNull(5);

        assertThat(result).isEqualTo(effect); // unchanged
    }

    @Test
    public void cropToLength_waveform_overLength() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[]{0, 1, 2, 3, 4, 5, 6},
                /* repeatIndex= */ -1);
        VibrationEffect result = effect.cropToLengthOrNull(4);

        assertThat(result).isEqualTo(VibrationEffect.createWaveform(
                new long[]{0, 1, 2, 3},
                -1));
    }

    @Test
    public void cropToLength_waveform_repeating() {
        // repeating waveforms cannot be truncated
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[]{0, 1, 2, 3, 4, 5, 6},
                /* repeatIndex= */ 2);
        VibrationEffect result = effect.cropToLengthOrNull(3);

        assertThat(result).isNull();
    }

    @Test
    public void cropToLength_waveform_withAmplitudes() {
        VibrationEffect effect = VibrationEffect.createWaveform(
                /* timings= */ new long[]{0, 1, 2, 3, 4, 5, 6},
                /* amplitudes= */ new int[]{10, 20, 40, 10, 20, 40, 10},
                /* repeatIndex= */ -1);
        VibrationEffect result = effect.cropToLengthOrNull(3);

        assertThat(result).isEqualTo(VibrationEffect.createWaveform(
                new long[]{0, 1, 2},
                new int[]{10, 20, 40},
                -1));
    }

    @Test
    public void cropToLength_composed() {
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                .compose();
        VibrationEffect result = effect.cropToLengthOrNull(1);

        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .compose());
    }

    @Test
    public void cropToLength_composed_repeating() {
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .repeatEffectIndefinitely(TEST_ONE_SHOT)
                .compose();
        assertThat(effect.cropToLengthOrNull(1)).isNull();
    }

    @Test
    @RequiresFlagsEnabled(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void cropToLength_vendorEffect() {
        PersistableBundle vendorData = new PersistableBundle();
        vendorData.putInt("key", 1);
        VibrationEffect effect = VibrationEffect.createVendorEffect(vendorData);

        assertThat(effect.cropToLengthOrNull(2)).isNull();
    }

    @Test
    public void getRingtones_noPrebakedRingtones() {
        Resources r = mockRingtoneResources(new String[0]);
        Context context = mockContext(r);
        VibrationEffect effect = VibrationEffect.get(Uri.parse(RINGTONE_URI_1), context);
        assertNull(effect);
    }

    @Test
    public void getRingtones_noPrebakedRingtoneForUri() {
        Resources r = mockRingtoneResources();
        Context context = mockContext(r);
        VibrationEffect effect = VibrationEffect.get(Uri.parse(UNKNOWN_URI), context);
        assertNull(effect);
    }

    @Test
    public void getRingtones_getPrebakedRingtone() {
        Resources r = mockRingtoneResources();
        Context context = mockContext(r);
        VibrationEffect effect = VibrationEffect.get(Uri.parse(RINGTONE_URI_2), context);
        VibrationEffect expectedEffect = VibrationEffect.get(VibrationEffect.RINGTONES[1]);
        assertNotNull(expectedEffect);
        assertEquals(expectedEffect, effect);
    }

    @Test
    public void testValidateOneShot() {
        VibrationEffect.createOneShot(1, 255).validate();
        VibrationEffect.createOneShot(1, DEFAULT_AMPLITUDE).validate();

        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createOneShot(-1, 255).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createOneShot(0, 255).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createOneShot(1, -2).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createOneShot(1, 0).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createOneShot(-1, 255).validate());
    }

    @Test
    public void testValidatePrebaked() {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK).validate();
        VibrationEffect.createPredefined(VibrationEffect.RINGTONES[1]).validate();

        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createPredefined(-1).validate());
    }

    @Test
    @RequiresFlagsEnabled(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void testValidateVendorEffect() {
        PersistableBundle vendorData = new PersistableBundle();
        vendorData.putInt("key", 1);
        VibrationEffect.createVendorEffect(vendorData).validate();

        PersistableBundle emptyData = new PersistableBundle();
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createVendorEffect(emptyData).validate());
    }

    @Test
    public void testValidateWaveform() {
        VibrationEffect.createWaveform(TEST_TIMINGS, TEST_AMPLITUDES, -1).validate();
        VibrationEffect.createWaveform(new long[]{10, 10}, new int[] {0, 0}, -1).validate();
        VibrationEffect.createWaveform(TEST_TIMINGS, TEST_AMPLITUDES, 0).validate();

        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createWaveform(new long[0], new int[0], -1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createWaveform(TEST_TIMINGS, new int[0], -1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createWaveform(
                        new long[]{0, 0, 0}, TEST_AMPLITUDES, -1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createWaveform(
                        TEST_TIMINGS, new int[]{-1, -1, -2}, -1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createWaveform(
                        TEST_TIMINGS, TEST_AMPLITUDES, TEST_TIMINGS.length).validate());
    }

    @Test
    public void testValidateWaveformBuilder() {
        // Cover builder methods
        VibrationEffect.startWaveform(targetAmplitude(1))
                .addTransition(Duration.ofSeconds(1), targetAmplitude(0.5f), targetFrequency(100))
                .addTransition(Duration.ZERO, targetAmplitude(0f), targetFrequency(200))
                .addSustain(Duration.ofMinutes(2))
                .addTransition(Duration.ofMillis(10), targetAmplitude(1f), targetFrequency(50))
                .addSustain(Duration.ofMillis(1))
                .addTransition(Duration.ZERO, targetFrequency(150))
                .addSustain(Duration.ofMillis(2))
                .addTransition(Duration.ofSeconds(15), targetAmplitude(1))
                .build()
                .validate();

        // Make sure class summary javadoc examples compile and are valid.
        // NOTE: IF THIS IS UPDATED, PLEASE ALSO UPDATE WaveformBuilder javadocs.
        VibrationEffect.startWaveform(targetFrequency(60))
                .addTransition(Duration.ofMillis(100), targetAmplitude(1), targetFrequency(120))
                .addSustain(Duration.ofMillis(200))
                .addTransition(Duration.ofMillis(100), targetAmplitude(0), targetFrequency(60))
                .build()
                .validate();
        VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                .addOffDuration(Duration.ofMillis(20))
                .repeatEffectIndefinitely(
                        VibrationEffect.startWaveform(targetAmplitude(0.2f))
                                .addSustain(Duration.ofMillis(10))
                                .addTransition(Duration.ofMillis(20), targetAmplitude(0.4f))
                                .addSustain(Duration.ofMillis(30))
                                .addTransition(Duration.ofMillis(40), targetAmplitude(0.8f))
                                .addSustain(Duration.ofMillis(50))
                                .addTransition(Duration.ofMillis(60), targetAmplitude(0.2f))
                                .build())
                .compose()
                .validate();
        VibrationEffect.createWaveform(new long[]{10, 20, 30}, new int[]{51, 102, 204}, -1)
                .validate();
        VibrationEffect.startWaveform(targetAmplitude(0.2f))
                .addSustain(Duration.ofMillis(10))
                .addTransition(Duration.ZERO, targetAmplitude(0.4f))
                .addSustain(Duration.ofMillis(20))
                .addTransition(Duration.ZERO, targetAmplitude(0.8f))
                .addSustain(Duration.ofMillis(30))
                .build()
                .validate();

        assertThrows(IllegalStateException.class,
                () -> VibrationEffect.startWaveform().build().validate());
        assertThrows(IllegalArgumentException.class, () -> targetAmplitude(-2));
        assertThrows(IllegalArgumentException.class, () -> targetFrequency(0));
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startWaveform().addTransition(
                        Duration.ofMillis(-10), targetAmplitude(1)).build().validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startWaveform().addSustain(Duration.ZERO).build().validate());
    }

    @Test
    public void testValidateComposed() {
        // Cover builder methods
        VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .addEffect(TEST_ONE_SHOT)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .addOffDuration(Duration.ofMillis(100))
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 10)
                .addEffect(VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addEffect(VibrationEffect.createWaveform(new long[]{10, 20}, /* repeat= */ 0))
                .compose()
                .validate();
        VibrationEffect.startComposition()
                .repeatEffectIndefinitely(TEST_ONE_SHOT)
                .compose()
                .validate();

        // Make sure class summary javadoc examples compile and are valid.
        // NOTE: IF THIS IS UPDATED, PLEASE ALSO UPDATE Composition javadocs.
        VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.5f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.5f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1.0f, 100)
                .compose()
                .validate();
        VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
                .addOffDuration(Duration.ofMillis(10))
                .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                .addOffDuration(Duration.ofMillis(50))
                .addEffect(VibrationEffect.createWaveform(new long[]{10, 20}, /* repeat= */ 0))
                .compose()
                .validate();

        assertThrows(IllegalStateException.class,
                () -> VibrationEffect.startComposition().compose().validate());
        assertThrows(IllegalStateException.class,
                () -> VibrationEffect.startComposition()
                        .addOffDuration(Duration.ofSeconds(0))
                        .compose()
                        .validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startComposition().addPrimitive(-1).compose().validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, -1, 10)
                        .compose()
                        .validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, -10)
                        .compose()
                        .validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, -1)
                        .compose()
                        .validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startComposition()
                        .repeatEffectIndefinitely(
                                // Repeating waveform.
                                VibrationEffect.createWaveform(
                                        new long[] { 10 }, new int[] { 100}, 0))
                        .compose()
                        .validate());
        assertThrows(UnreachableAfterRepeatingIndefinitelyException.class,
                () -> VibrationEffect.startComposition()
                        .repeatEffectIndefinitely(TEST_WAVEFORM)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .compose()
                        .validate());
        assertThrows(UnreachableAfterRepeatingIndefinitelyException.class,
                () -> VibrationEffect.startComposition()
                        .repeatEffectIndefinitely(TEST_WAVEFORM)
                        .addEffect(TEST_ONE_SHOT)
                        .compose()
                        .validate());
    }

    @Test
    public void testResolveOneShot() {
        VibrationEffect resolved = DEFAULT_ONE_SHOT.resolve(51);
        assertEquals(0.2f, getStepSegment(resolved, 0).getAmplitude());

        assertThrows(IllegalArgumentException.class, () -> DEFAULT_ONE_SHOT.resolve(1000));
    }

    @Test
    public void testResolveWaveform() {
        VibrationEffect resolved = TEST_WAVEFORM.resolve(102);
        assertEquals(0.4f, getStepSegment(resolved, 2).getAmplitude());

        assertThrows(IllegalArgumentException.class, () -> TEST_WAVEFORM.resolve(1000));
    }

    @Test
    public void testResolvePrebaked() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        assertEquals(effect, effect.resolve(51));
    }

    @Test
    @RequiresFlagsEnabled(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void testResolveVendorEffect() {
        VibrationEffect effect = VibrationEffect.createVendorEffect(createNonEmptyBundle());
        assertEquals(effect, effect.resolve(51));
    }

    @Test
    public void testResolveComposed() {
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 1)
                .compose();
        assertEquals(effect, effect.resolve(51));

        VibrationEffect resolved = VibrationEffect.startComposition()
                .addEffect(DEFAULT_ONE_SHOT)
                .compose()
                .resolve(51);
        assertEquals(0.2f, getStepSegment(resolved, 0).getAmplitude());
    }

    @Test
    public void testScaleOneShot() {
        VibrationEffect scaledUp = TEST_ONE_SHOT.scale(1.5f);
        assertTrue(100 / 255f < getStepSegment(scaledUp, 0).getAmplitude());

        VibrationEffect scaledDown = TEST_ONE_SHOT.scale(0.5f);
        assertTrue(100 / 255f > getStepSegment(scaledDown, 0).getAmplitude());
    }

    @Test
    public void testScaleWaveform() {
        VibrationEffect scaledUp = TEST_WAVEFORM.scale(1.5f);
        assertEquals(1f, getStepSegment(scaledUp, 0).getAmplitude(), TOLERANCE);

        VibrationEffect scaledDown = TEST_WAVEFORM.scale(0.5f);
        assertTrue(1f > getStepSegment(scaledDown, 0).getAmplitude());
    }

    @Test
    public void testScalePrebaked() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);

        VibrationEffect scaledUp = effect.scale(1.5f);
        assertEquals(effect, scaledUp);

        VibrationEffect scaledDown = effect.scale(0.5f);
        assertEquals(effect, scaledDown);
    }

    @Test
    @RequiresFlagsEnabled(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void testScaleVendorEffect() {
        VibrationEffect effect = VibrationEffect.createVendorEffect(createNonEmptyBundle());

        VibrationEffect.VendorEffect scaledUp = (VibrationEffect.VendorEffect) effect.scale(1.5f);
        assertEquals(1.5f, scaledUp.getScale());

        VibrationEffect.VendorEffect scaledDown = (VibrationEffect.VendorEffect) effect.scale(0.5f);
        assertEquals(0.5f, scaledDown.getScale());
    }

    @Test
    public void testScaleComposed() {
        VibrationEffect effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 1)
                    .addEffect(TEST_ONE_SHOT)
                    .compose();

        VibrationEffect scaledUp = effect.scale(1.5f);
        assertTrue(0.5f < getPrimitiveSegment(scaledUp, 0).getScale());
        assertTrue(100 / 255f < getStepSegment(scaledUp, 1).getAmplitude());

        VibrationEffect scaledDown = effect.scale(0.5f);
        assertTrue(0.5f > getPrimitiveSegment(scaledDown, 0).getScale());
        assertTrue(100 / 255f > getStepSegment(scaledDown, 1).getAmplitude());
    }

    @Test
    public void testApplyAdaptiveScaleOneShot() {
        VibrationEffect oneShot = VibrationEffect.createOneShot(TEST_TIMING, /* amplitude= */ 100);

        VibrationEffect scaledUp = oneShot.applyAdaptiveScale(1.5f);
        assertThat(getStepSegment(scaledUp, 0).getAmplitude()).isWithin(TOLERANCE).of(150 / 255f);

        VibrationEffect scaledDown = oneShot.applyAdaptiveScale(0.5f);
        assertThat(getStepSegment(scaledDown, 0).getAmplitude()).isWithin(TOLERANCE).of(50 / 255f);
    }

    @Test
    public void testApplyAdaptiveScaleWaveform() {
        VibrationEffect waveform = VibrationEffect.createWaveform(
                new long[] { 100, 100 }, new int[] { 10, 0 }, -1);

        VibrationEffect scaledUp = waveform.applyAdaptiveScale(1.5f);
        assertThat(getStepSegment(scaledUp, 0).getAmplitude()).isWithin(TOLERANCE).of(15 / 255f);

        VibrationEffect scaledDown = waveform.applyAdaptiveScale(0.5f);
        assertThat(getStepSegment(scaledDown, 0).getAmplitude()).isWithin(TOLERANCE).of(5 / 255f);
    }

    @Test
    public void testApplyAdaptiveScalePrebaked() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);

        VibrationEffect scaledUp = effect.applyAdaptiveScale(1.5f);
        assertEquals(effect, scaledUp);

        VibrationEffect scaledDown = effect.applyAdaptiveScale(0.5f);
        assertEquals(effect, scaledDown);
    }

    @Test
    @RequiresFlagsEnabled(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void testApplyAdaptiveScaleVendorEffect() {
        VibrationEffect effect = VibrationEffect.createVendorEffect(createNonEmptyBundle());

        VibrationEffect.VendorEffect scaledUp =
                (VibrationEffect.VendorEffect) effect.applyAdaptiveScale(1.5f);
        assertEquals(1.5f, scaledUp.getAdaptiveScale());

        VibrationEffect.VendorEffect scaledDown =
                (VibrationEffect.VendorEffect) effect.applyAdaptiveScale(0.5f);
        assertEquals(0.5f, scaledDown.getAdaptiveScale());
    }

    @Test
    public void testApplyAdaptiveScaleComposed() {
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 1)
                .addEffect(VibrationEffect.createOneShot(TEST_TIMING, /* amplitude= */ 100))
                .compose();

        VibrationEffect scaledUp = effect.applyAdaptiveScale(1.5f);
        assertThat(getPrimitiveSegment(scaledUp, 0).getScale()).isWithin(TOLERANCE).of(0.75f);
        assertThat(getStepSegment(scaledUp, 1).getAmplitude()).isWithin(TOLERANCE).of(150 / 255f);

        VibrationEffect scaledDown = effect.applyAdaptiveScale(0.5f);
        assertThat(getPrimitiveSegment(scaledDown, 0).getScale()).isWithin(TOLERANCE).of(0.25f);
        assertThat(getStepSegment(scaledDown, 1).getAmplitude()).isWithin(TOLERANCE).of(50 / 255f);
    }

    @Test
    public void testApplyEffectStrengthToOneShotWaveformAndPrimitives() {
        VibrationEffect oneShot = VibrationEffect.createOneShot(100, 100);
        VibrationEffect waveform = VibrationEffect.createWaveform(new long[] { 10, 20 }, 0);
        VibrationEffect composition = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .compose();

        assertEquals(oneShot, oneShot.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_STRONG));
        assertEquals(waveform,
                waveform.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_STRONG));
        assertEquals(composition,
                composition.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_STRONG));
    }

    @Test
    public void testApplyEffectStrengthToPredefinedEffect() {
        VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);

        VibrationEffect scaledUp =
                effect.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_STRONG);
        assertNotEquals(effect, scaledUp);
        assertEquals(VibrationEffect.EFFECT_STRENGTH_STRONG,
                getPrebakedSegment(scaledUp, 0).getEffectStrength());

        VibrationEffect scaledDown =
                effect.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_LIGHT);
        assertNotEquals(effect, scaledDown);
        assertEquals(VibrationEffect.EFFECT_STRENGTH_LIGHT,
                getPrebakedSegment(scaledDown, 0).getEffectStrength());
    }

    @Test
    @RequiresFlagsEnabled(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void testApplyEffectStrengthToVendorEffect() {
        VibrationEffect effect = VibrationEffect.createVendorEffect(createNonEmptyBundle());

        VibrationEffect scaledUp =
                effect.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_STRONG);
        assertNotEquals(effect, scaledUp);

        VibrationEffect scaledDown =
                effect.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_LIGHT);
        assertNotEquals(effect, scaledDown);
    }

    private void doTestApplyRepeatingWithNonRepeatingOriginal(@NotNull VibrationEffect original) {
        assertTrue(original.getDuration() != Long.MAX_VALUE);
        int loopDelayMs = 123;
        assertEquals(original, original.applyRepeatingIndefinitely(false, loopDelayMs));

        // Looping with no delay gets the raw repeated effect.
        VibrationEffect loopingOriginal = VibrationEffect.startComposition()
                .repeatEffectIndefinitely(original)
                .compose();
        assertEquals(Long.MAX_VALUE, loopingOriginal.getDuration());
        assertEquals(loopingOriginal, original.applyRepeatingIndefinitely(true, 0));

        VibrationEffect loopingPart = VibrationEffect.startComposition()
                .addEffect(original)
                .addOffDuration(Duration.ofMillis(loopDelayMs))
                .compose();

        VibrationEffect loopingWithDelay = VibrationEffect.startComposition()
                .repeatEffectIndefinitely(loopingPart)
                .compose();
        assertEquals(Long.MAX_VALUE, loopingWithDelay.getDuration());
        assertEquals(loopingWithDelay, original.applyRepeatingIndefinitely(true, loopDelayMs));
    }

    @Test
    public void testApplyRepeatingIndefinitely_nonRepeatingOriginal() {
        VibrationEffect oneshot = VibrationEffect.createOneShot(100, DEFAULT_AMPLITUDE);
        doTestApplyRepeatingWithNonRepeatingOriginal(oneshot);

        VibrationEffect predefined = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
        doTestApplyRepeatingWithNonRepeatingOriginal(predefined);

        VibrationEffect primitives = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 100)
                .compose();
        doTestApplyRepeatingWithNonRepeatingOriginal(primitives);

        VibrationEffect legacyWaveform = VibrationEffect.createWaveform(
                new long[]{1, 2, 3}, new int[]{1, 2, 3}, -1);
        doTestApplyRepeatingWithNonRepeatingOriginal(legacyWaveform);

        // Test a mix of segments ending in a delay, for completeness.
        doTestApplyRepeatingWithNonRepeatingOriginal(VibrationEffect.startComposition()
                .addEffect(oneshot)
                .addEffect(predefined)
                .addEffect(primitives)
                .addEffect(legacyWaveform)
                .addOffDuration(Duration.ofMillis(1000))
                .compose());
    }

    @Test
    public void testApplyRepeatingIndefinitely_repeatingOriginalWaveform() {
        // The delay parameter has no effect when the effect is already repeating.
        int delayMs = 999;
        VibrationEffect waveformNoRepeat = VibrationEffect.createWaveform(
                new long[]{1, 2, 3}, new int[]{1, 2, 3}, -1);
        VibrationEffect waveformFullRepeat = VibrationEffect.createWaveform(
                new long[]{1, 2, 3}, new int[]{1, 2, 3}, 0);
        assertEquals(waveformFullRepeat,
                waveformFullRepeat.applyRepeatingIndefinitely(true, delayMs));
        assertEquals(waveformNoRepeat,
                waveformFullRepeat.applyRepeatingIndefinitely(false, delayMs));

        VibrationEffect waveformOffsetRepeat = VibrationEffect.createWaveform(
                new long[]{1, 2, 3}, new int[]{1, 2, 3}, 1);
        assertEquals(waveformOffsetRepeat,
                waveformOffsetRepeat.applyRepeatingIndefinitely(true, delayMs));
        assertEquals(waveformNoRepeat,
                waveformOffsetRepeat.applyRepeatingIndefinitely(false, delayMs));
    }

    @Test
    public void testApplyRepeatingIndefinitely_repeatingOriginalComposition() {
        // The delay parameter has no effect when the effect is already repeating.
        int delayMs = 999;
        VibrationEffect innerEffect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                .compose();

        VibrationEffect repeatingOriginal = VibrationEffect.startComposition()
                .repeatEffectIndefinitely(innerEffect)
                .compose();
        assertEquals(repeatingOriginal,
                repeatingOriginal.applyRepeatingIndefinitely(true, delayMs));
        assertEquals(innerEffect,
                repeatingOriginal.applyRepeatingIndefinitely(false, delayMs));

        VibrationEffect offsetOriginal = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD)
                .repeatEffectIndefinitely(innerEffect)
                .compose();
        assertEquals(offsetOriginal,
                offsetOriginal.applyRepeatingIndefinitely(true, delayMs));
        assertEquals(VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                .compose(),
                offsetOriginal.applyRepeatingIndefinitely(false, delayMs));
    }

    @Test
    @RequiresFlagsEnabled(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void testApplyRepeatingIndefinitely_vendorEffect() {
        VibrationEffect effect = VibrationEffect.createVendorEffect(createNonEmptyBundle());

        assertEquals(effect, effect.applyRepeatingIndefinitely(true, 10));
        assertEquals(effect, effect.applyRepeatingIndefinitely(false, 10));
    }

    @Test
    public void testDuration() {
        assertEquals(1, VibrationEffect.createOneShot(1, 1).getDuration());
        assertEquals(-1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK).getDuration());
        assertEquals(-1,
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1, 100)
                        .compose()
                        .getDuration());
        assertEquals(6, VibrationEffect.createWaveform(
                new long[]{1, 2, 3}, new int[]{1, 2, 3}, -1).getDuration());
        assertEquals(Long.MAX_VALUE, VibrationEffect.createWaveform(
                new long[]{1, 2, 3}, new int[]{1, 2, 3}, 0).getDuration());
        if (Flags.vendorVibrationEffects()) {
            assertEquals(-1,
                    VibrationEffect.createVendorEffect(createNonEmptyBundle()).getDuration());
        }
    }

    @Test
    public void testAreVibrationFeaturesSupported_allSegmentsSupported() {
        VibratorInfo info = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL)
                .build();

        assertTrue(VibrationEffect.createWaveform(
                        /* timings= */ new long[] {1, 2, 3}, /* repeatIndex= */ -1)
                .areVibrationFeaturesSupported(info));
        assertTrue(VibrationEffect.createWaveform(
                        /* timings= */ new long[] {1, 2, 3},
                        /* amplitudes= */ new int[] {10, 20, 40},
                        /* repeatIndex= */ 2)
                .areVibrationFeaturesSupported(info));
        assertTrue(
                VibrationEffect.startComposition()
                        .addEffect(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                        .repeatEffectIndefinitely(TEST_ONE_SHOT)
                        .compose()
                .areVibrationFeaturesSupported(info));
    }

    @Test
    public void testAreVibrationFeaturesSupported_withUnsupportedSegments() {
        VibratorInfo info = new VibratorInfo.Builder(/* id= */ 1).build();

        assertFalse(
                VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .addEffect(VibrationEffect.createWaveform(
                                /* timings= */ new long[] {1, 2, 3},
                                /* amplitudes= */ new int[] {10, 20, 40},
                                /* repeatIndex= */ -1))
                        .compose()
                .areVibrationFeaturesSupported(info));
    }

    @Test
    @RequiresFlagsEnabled(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void testAreVibrationFeaturesSupported_vendorEffects() {
        VibratorInfo supportedVibratorInfo = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_PERFORM_VENDOR_EFFECTS)
                .build();

        assertTrue(VibrationEffect.createVendorEffect(createNonEmptyBundle())
                .areVibrationFeaturesSupported(supportedVibratorInfo));
        assertFalse(VibrationEffect.createVendorEffect(createNonEmptyBundle())
                .areVibrationFeaturesSupported(new VibratorInfo.Builder(/* id= */ 1).build()));
    }

    @Test
    public void testIsHapticFeedbackCandidate_repeatingEffects_notCandidates() {
        assertFalse(VibrationEffect.createWaveform(
                new long[]{1, 2, 3}, new int[]{1, 2, 3}, 0).isHapticFeedbackCandidate());
    }

    @Test
    public void testIsHapticFeedbackCandidate_longEffects_notCandidates() {
        assertFalse(VibrationEffect.createOneShot(1500, 255).isHapticFeedbackCandidate());
        assertFalse(VibrationEffect.createWaveform(
                new long[]{200, 200, 700}, new int[]{1, 2, 3}, -1).isHapticFeedbackCandidate());
        assertFalse(VibrationEffect.startWaveform()
                .addTransition(Duration.ofMillis(500), targetAmplitude(1))
                .addTransition(Duration.ofMillis(200), targetAmplitude(0.5f))
                .addTransition(Duration.ofMillis(500), targetAmplitude(0))
                .build()
                .isHapticFeedbackCandidate());
    }

    @Test
    public void testIsHapticFeedbackCandidate_shortEffects_areCandidates() {
        assertTrue(VibrationEffect.createOneShot(500, 255).isHapticFeedbackCandidate());
        assertTrue(VibrationEffect.createWaveform(
                new long[]{100, 200, 300}, new int[]{1, 2, 3}, -1).isHapticFeedbackCandidate());
        assertTrue(VibrationEffect.startWaveform()
                .addTransition(Duration.ofMillis(300), targetAmplitude(1))
                .addTransition(Duration.ofMillis(200), targetAmplitude(0.5f))
                .addTransition(Duration.ofMillis(300), targetAmplitude(0))
                .build()
                .isHapticFeedbackCandidate());
    }

    @Test
    public void testIsHapticFeedbackCandidate_longCompositions_notCandidates() {
        assertFalse(VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)
                .compose()
                .isHapticFeedbackCandidate());

        assertFalse(VibrationEffect.startComposition()
                .addEffect(VibrationEffect.createOneShot(1500, 255))
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                .compose()
                .isHapticFeedbackCandidate());
    }

    @Test
    public void testIsHapticFeedbackCandidate_shortCompositions_areCandidates() {
        assertTrue(VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .compose()
                .isHapticFeedbackCandidate());

        assertTrue(VibrationEffect.startComposition()
                .addEffect(VibrationEffect.createOneShot(100, 255))
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                .compose()
                .isHapticFeedbackCandidate());
    }

    @Test
    public void testIsHapticFeedbackCandidate_prebakedRingtones_notCandidates() {
        assertFalse(VibrationEffect.get(
                VibrationEffect.RINGTONES[1]).isHapticFeedbackCandidate());
    }

    @Test
    public void testIsHapticFeedbackCandidate_prebakedNotRingtoneConstants_areCandidates() {
        assertTrue(VibrationEffect.get(VibrationEffect.EFFECT_CLICK).isHapticFeedbackCandidate());
        assertTrue(VibrationEffect.get(VibrationEffect.EFFECT_THUD).isHapticFeedbackCandidate());
        assertTrue(VibrationEffect.get(VibrationEffect.EFFECT_TICK).isHapticFeedbackCandidate());
    }

    @Test
    @RequiresFlagsEnabled(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void testIsHapticFeedbackCandidate_vendorEffects_notCandidates() {
        assertFalse(VibrationEffect.createVendorEffect(createNonEmptyBundle())
                .isHapticFeedbackCandidate());
    }

    @Test
    public void testParcelingComposed() {
        Parcel p = Parcel.obtain();
        VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
        effect.writeToParcel(p, 0);
        p.setDataPosition(0);
        VibrationEffect parceledEffect = VibrationEffect.Composed.CREATOR.createFromParcel(p);
        assertThat(parceledEffect).isEqualTo(effect);
    }

    @Test
    @RequiresFlagsEnabled(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void testParcelingVendorEffect() {
        Parcel p = Parcel.obtain();
        VibrationEffect effect = VibrationEffect.createVendorEffect(createNonEmptyBundle());
        effect.writeToParcel(p, 0);
        p.setDataPosition(0);
        VibrationEffect parceledEffect = VibrationEffect.VendorEffect.CREATOR.createFromParcel(p);
        assertThat(parceledEffect).isEqualTo(effect);
    }

    private void assertArrayEq(long[] expected, long[] actual) {
        assertTrue(
                String.format("Expected pattern %s, but was %s",
                        Arrays.toString(expected), Arrays.toString(actual)),
                Arrays.equals(expected, actual));
    }

    private Resources mockRingtoneResources() {
        return mockRingtoneResources(new String[]{
                RINGTONE_URI_1,
                RINGTONE_URI_2,
                RINGTONE_URI_3
        });
    }

    private Resources mockRingtoneResources(String[] ringtoneUris) {
        Resources mockResources = mock(Resources.class);
        when(mockResources.getStringArray(R.array.config_ringtoneEffectUris))
                .thenReturn(ringtoneUris);
        return mockResources;
    }

    private Context mockContext(Resources resources) {
        Context context = mock(Context.class);
        ContentInterface contentInterface = mock(ContentInterface.class);
        ContentResolver contentResolver = ContentResolver.wrap(contentInterface);

        try {
            // ContentResolver#uncanonicalize is final, so we need to mock the ContentInterface it
            // delegates the call to for the tests that require matching with the mocked URIs.
            when(contentInterface.uncanonicalize(any())).then(
                    invocation -> invocation.getArgument(0));
            when(context.getContentResolver()).thenReturn(contentResolver);
            when(context.getResources()).thenReturn(resources);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        return context;
    }

    private StepSegment getStepSegment(VibrationEffect effect, int index) {
        VibrationEffectSegment segment = getEffectSegment(effect, index);
        assertThat(segment).isInstanceOf(StepSegment.class);
        return (StepSegment) segment;
    }

    private PrimitiveSegment getPrimitiveSegment(VibrationEffect effect, int index) {
        VibrationEffectSegment segment = getEffectSegment(effect, index);
        assertThat(segment).isInstanceOf(PrimitiveSegment.class);
        return (PrimitiveSegment) segment;
    }

    private PrebakedSegment getPrebakedSegment(VibrationEffect effect, int index) {
        VibrationEffectSegment segment = getEffectSegment(effect, index);
        assertThat(segment).isInstanceOf(PrebakedSegment.class);
        return (PrebakedSegment) segment;
    }

    private VibrationEffectSegment getEffectSegment(VibrationEffect effect, int index) {
        assertThat(effect).isInstanceOf(VibrationEffect.Composed.class);
        VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
        assertThat(index).isLessThan(composed.getSegments().size());
        return composed.getSegments().get(index);
    }

    private PersistableBundle createNonEmptyBundle() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putInt("key", 1);
        return bundle;
    }
}
