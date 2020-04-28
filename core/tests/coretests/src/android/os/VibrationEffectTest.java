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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNotSame;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertSame;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ContentInterface;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.platform.test.annotations.Presubmit;

import com.android.internal.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class VibrationEffectTest {

    private static final String RINGTONE_URI_1 = "content://test/system/ringtone_1";
    private static final String RINGTONE_URI_2 = "content://test/system/ringtone_2";
    private static final String RINGTONE_URI_3 = "content://test/system/ringtone_3";
    private static final String UNKNOWN_URI = "content://test/system/other_audio";

    private static final float INTENSITY_SCALE_TOLERANCE = 1e-2f;
    private static final int AMPLITUDE_SCALE_TOLERANCE = 1;

    private static final long TEST_TIMING = 100;
    private static final int TEST_AMPLITUDE = 100;
    private static final long[] TEST_TIMINGS = new long[] { 100, 100, 200 };
    private static final int[] TEST_AMPLITUDES =
            new int[] { 255, 0, VibrationEffect.DEFAULT_AMPLITUDE };

    private static final VibrationEffect TEST_ONE_SHOT =
            VibrationEffect.createOneShot(TEST_TIMING, TEST_AMPLITUDE);
    private static final VibrationEffect DEFAULT_ONE_SHOT =
            VibrationEffect.createOneShot(TEST_TIMING, VibrationEffect.DEFAULT_AMPLITUDE);
    private static final VibrationEffect TEST_WAVEFORM =
            VibrationEffect.createWaveform(TEST_TIMINGS, TEST_AMPLITUDES, -1);
    private static final VibrationEffect TEST_COMPOSED =
            VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 1)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 10)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0f, 100)
                    .compose();

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
    public void testScalePrebaked_ignoresScaleAndReturnsSameEffect() {
        VibrationEffect initial = VibrationEffect.get(VibrationEffect.RINGTONES[1]);
        assertSame(initial, initial.scale(0.5f));
    }

    @Test
    public void testResolvePrebaked_ignoresDefaultAmplitudeAndReturnsSameEffect() {
        VibrationEffect initial = VibrationEffect.get(VibrationEffect.RINGTONES[1]);
        assertSame(initial, initial.resolve(1000));
    }

    @Test
    public void testScaleOneShot() {
        VibrationEffect.OneShot unset = new VibrationEffect.OneShot(
                TEST_TIMING, VibrationEffect.DEFAULT_AMPLITUDE);
        assertEquals(VibrationEffect.DEFAULT_AMPLITUDE, unset.scale(2).getAmplitude());

        VibrationEffect.OneShot initial = (VibrationEffect.OneShot) TEST_ONE_SHOT;

        VibrationEffect.OneShot halved = initial.scale(0.5f);
        assertEquals(34, halved.getAmplitude(), AMPLITUDE_SCALE_TOLERANCE);

        VibrationEffect.OneShot copied = initial.scale(1f);
        assertEquals(TEST_AMPLITUDE, copied.getAmplitude());

        VibrationEffect.OneShot scaledUp = initial.scale(1.5f);
        assertTrue(scaledUp.getAmplitude() > initial.getAmplitude());
        VibrationEffect.OneShot restored = scaledUp.scale(2 / 3f);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(105, restored.getAmplitude(), AMPLITUDE_SCALE_TOLERANCE);

        VibrationEffect.OneShot scaledDown = initial.scale(0.8f);
        assertTrue(scaledDown.getAmplitude() < initial.getAmplitude());
        restored = scaledDown.scale(1.25f);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(101, restored.getAmplitude(), AMPLITUDE_SCALE_TOLERANCE);
    }

    @Test
    public void testResolveOneShot() {
        VibrationEffect.OneShot initial = (VibrationEffect.OneShot) DEFAULT_ONE_SHOT;
        VibrationEffect.OneShot resolved = initial.resolve(239);
        assertNotSame(initial, resolved);
        assertEquals(239, resolved.getAmplitude());

        // Ignores input when amplitude already set.
        VibrationEffect.OneShot resolved2 = resolved.resolve(10);
        assertSame(resolved, resolved2);
        assertEquals(239, resolved2.getAmplitude());
    }

    @Test
    public void testResolveOneshotFailsWhenMaxAmplitudeAboveThreshold() {
        try {
            TEST_ONE_SHOT.resolve(1000);
            fail("Max amplitude above threshold, should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testScaleWaveform() {
        VibrationEffect.Waveform initial = (VibrationEffect.Waveform) TEST_WAVEFORM;

        VibrationEffect.Waveform copied = initial.scale(1f);
        assertArrayEquals(TEST_AMPLITUDES, copied.getAmplitudes());

        VibrationEffect.Waveform scaled = initial.scale(0.9f);
        assertEquals(216, scaled.getAmplitudes()[0], AMPLITUDE_SCALE_TOLERANCE);
        assertEquals(0, scaled.getAmplitudes()[1]);
        assertEquals(-1, scaled.getAmplitudes()[2]);
    }

    @Test
    public void testResolveWaveform() {
        VibrationEffect.Waveform initial = (VibrationEffect.Waveform) TEST_WAVEFORM;
        VibrationEffect.Waveform resolved = initial.resolve(123);
        assertNotSame(initial, resolved);
        assertArrayEquals(new int[]{255, 0, 123}, resolved.getAmplitudes());

        // Ignores input when amplitude already set.
        VibrationEffect.Waveform resolved2 = resolved.resolve(10);
        assertSame(resolved, resolved2);
        assertArrayEquals(new int[]{255, 0, 123}, resolved2.getAmplitudes());
    }

    @Test
    public void testResolveWaveformFailsWhenMaxAmplitudeAboveThreshold() {
        try {
            TEST_WAVEFORM.resolve(1000);
            fail("Max amplitude above threshold, should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testScaleComposed() {
        VibrationEffect.Composed initial = (VibrationEffect.Composed) TEST_COMPOSED;

        VibrationEffect.Composed copied = initial.scale(1);
        assertEquals(1f, copied.getPrimitiveEffects().get(0).scale);
        assertEquals(0.5f, copied.getPrimitiveEffects().get(1).scale);
        assertEquals(0f, copied.getPrimitiveEffects().get(2).scale);

        VibrationEffect.Composed halved = initial.scale(0.5f);
        assertEquals(0.34f, halved.getPrimitiveEffects().get(0).scale, INTENSITY_SCALE_TOLERANCE);
        assertEquals(0.17f, halved.getPrimitiveEffects().get(1).scale, INTENSITY_SCALE_TOLERANCE);
        assertEquals(0f, halved.getPrimitiveEffects().get(2).scale);

        VibrationEffect.Composed scaledUp = initial.scale(1.5f);
        // Does not scale up from 1.
        assertEquals(1f, scaledUp.getPrimitiveEffects().get(0).scale, INTENSITY_SCALE_TOLERANCE);
        assertTrue(0.5f < scaledUp.getPrimitiveEffects().get(1).scale);
        assertEquals(0f, scaledUp.getPrimitiveEffects().get(2).scale);

        VibrationEffect.Composed restored = scaledUp.scale(2 / 3f);
        // The original value was not scaled up, so this only scales it down.
        assertEquals(0.53f, restored.getPrimitiveEffects().get(0).scale, INTENSITY_SCALE_TOLERANCE);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(0.47f, restored.getPrimitiveEffects().get(1).scale, INTENSITY_SCALE_TOLERANCE);
        assertEquals(0f, restored.getPrimitiveEffects().get(2).scale);

        VibrationEffect.Composed scaledDown = initial.scale(0.8f);
        assertTrue(1f > scaledDown.getPrimitiveEffects().get(0).scale);
        assertTrue(0.5f > scaledDown.getPrimitiveEffects().get(1).scale);
        assertEquals(0f, scaledDown.getPrimitiveEffects().get(2).scale);

        restored = scaledDown.scale(1.25f);
        // Does not restore to the exact original value because scale up is a bit offset.
        assertEquals(0.84f, restored.getPrimitiveEffects().get(0).scale, INTENSITY_SCALE_TOLERANCE);
        assertEquals(0.5f, restored.getPrimitiveEffects().get(1).scale, INTENSITY_SCALE_TOLERANCE);
        assertEquals(0f, restored.getPrimitiveEffects().get(2).scale);
    }

    @Test
    public void testResolveComposed_ignoresDefaultAmplitudeAndReturnsSameEffect() {
        VibrationEffect initial = TEST_COMPOSED;
        assertSame(initial, initial.resolve(1000));
    }

    @Test
    public void testScaleAppliesSameAdjustmentsOnAllEffects() {
        VibrationEffect.OneShot oneShot = new VibrationEffect.OneShot(TEST_TIMING, TEST_AMPLITUDE);
        VibrationEffect.Waveform waveform = new VibrationEffect.Waveform(
                new long[] { TEST_TIMING }, new int[]{ TEST_AMPLITUDE }, -1);
        VibrationEffect.Composed composed =
                (VibrationEffect.Composed) VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK,
                                TEST_AMPLITUDE / 255f)
                        .compose();

        assertEquals(oneShot.scale(0.8f).getAmplitude(),
                waveform.scale(0.8f).getAmplitudes()[0],
                AMPLITUDE_SCALE_TOLERANCE);
        assertEquals(oneShot.scale(1.2f).getAmplitude() / 255f,
                composed.scale(1.2f).getPrimitiveEffects().get(0).scale,
                INTENSITY_SCALE_TOLERANCE);
    }

    @Test
    public void testScaleOnMaxAmplitude() {
        VibrationEffect.OneShot oneShot = new VibrationEffect.OneShot(
                TEST_TIMING, VibrationEffect.MAX_AMPLITUDE);
        VibrationEffect.Waveform waveform = new VibrationEffect.Waveform(
                new long[]{TEST_TIMING}, new int[]{VibrationEffect.MAX_AMPLITUDE}, -1);
        VibrationEffect.Composed composed =
                (VibrationEffect.Composed) VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                        .compose();

        // Scale up does NOT scale MAX_AMPLITUDE
        assertEquals(VibrationEffect.MAX_AMPLITUDE, oneShot.scale(1.1f).getAmplitude());
        assertEquals(VibrationEffect.MAX_AMPLITUDE, waveform.scale(1.2f).getAmplitudes()[0]);
        assertEquals(1f,
                composed.scale(1.4f).getPrimitiveEffects().get(0).scale,
                INTENSITY_SCALE_TOLERANCE); // This needs tolerance for float point comparison.

        // Scale down does scale MAX_AMPLITUDE
        assertEquals(216, oneShot.scale(0.9f).getAmplitude(), AMPLITUDE_SCALE_TOLERANCE);
        assertEquals(180, waveform.scale(0.8f).getAmplitudes()[0], AMPLITUDE_SCALE_TOLERANCE);
        assertEquals(0.57f, composed.scale(0.7f).getPrimitiveEffects().get(0).scale,
                INTENSITY_SCALE_TOLERANCE);
    }

    private Resources mockRingtoneResources() {
        return mockRingtoneResources(new String[] {
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
}