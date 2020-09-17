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
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ContentInterface;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;

import com.android.internal.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class VibrationEffectTest {
    private static final float SCALE_TOLERANCE = 1e-2f;

    private static final String RINGTONE_URI_1 = "content://test/system/ringtone_1";
    private static final String RINGTONE_URI_2 = "content://test/system/ringtone_2";
    private static final String RINGTONE_URI_3 = "content://test/system/ringtone_3";
    private static final String UNKNOWN_URI = "content://test/system/other_audio";

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
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 10)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0, 100)
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
    public void testScaleOneShot() {
        VibrationEffect.OneShot initial = (VibrationEffect.OneShot) TEST_ONE_SHOT;
        VibrationEffect.OneShot halved = initial.scale(1, 128);
        assertEquals(50, halved.getAmplitude());
        VibrationEffect.OneShot scaledUp = initial.scale(0.5f, 255);
        assertTrue(scaledUp.getAmplitude() > initial.getAmplitude());
        VibrationEffect.OneShot restored2 = scaledUp.scale(2, 255);
        assertEquals(100, restored2.getAmplitude(), 2); // May differ a bit due to rounding
        VibrationEffect.OneShot scaledDown = initial.scale(2, 255);
        assertTrue(scaledDown.getAmplitude() < initial.getAmplitude());
        VibrationEffect.OneShot restored3 = scaledDown.scale(0.5f, 255);
        assertEquals(100, restored3.getAmplitude(), 2); // May differ a bit due to rounding
    }

    @Test
    public void testScaleOneShotFailsWhenMaxAmplitudeAboveThreshold() {
        try {
            ((VibrationEffect.OneShot) TEST_ONE_SHOT).scale(1.1f, 1000);
            fail("Max amplitude above threshold, should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testResolveOneShot() {
        VibrationEffect.OneShot initial = (VibrationEffect.OneShot) DEFAULT_ONE_SHOT;
        VibrationEffect.OneShot resolved = initial.resolve(239);
        assertEquals(239, resolved.getAmplitude());
    }

    @Test
    public void testResolveOneShotFailsWhenMaxAmplitudeAboveThreshold() {
        try {
            ((VibrationEffect.OneShot) TEST_ONE_SHOT).resolve(1000);
            fail("Max amplitude above threshold, should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testScaleWaveform() {
        VibrationEffect.Waveform initial = (VibrationEffect.Waveform) TEST_WAVEFORM;

        VibrationEffect.Waveform copied = initial.scale(1f, 255);
        assertEquals(255, copied.getAmplitudes()[0]);
        assertEquals(0, copied.getAmplitudes()[1]);
        assertEquals(-1, copied.getAmplitudes()[2]);

        VibrationEffect.Waveform scaled = initial.scale(1.1f, 200);
        assertEquals(200, scaled.getAmplitudes()[0]);
        assertEquals(0, scaled.getAmplitudes()[1]);
    }

    @Test
    public void testScaleWaveformFailsWhenMaxAmplitudeAboveThreshold() {
        try {
            ((VibrationEffect.Waveform) TEST_WAVEFORM).scale(1.1f, 1000);
            fail("Max amplitude above threshold, should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testResolveWaveform() {
        VibrationEffect.Waveform resolved =
                ((VibrationEffect.Waveform) TEST_WAVEFORM).resolve(239);
        assertEquals(255, resolved.getAmplitudes()[0]);
        assertEquals(0, resolved.getAmplitudes()[1]);
        assertEquals(239, resolved.getAmplitudes()[2]);
    }

    @Test
    public void testResolveWaveformFailsWhenMaxAmplitudeAboveThreshold() {
        try {
            ((VibrationEffect.Waveform) TEST_WAVEFORM).resolve(1000);
            fail("Max amplitude above threshold, should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testScaleComposed() {
        VibrationEffect.Composed initial = (VibrationEffect.Composed) TEST_COMPOSED;

        VibrationEffect.Composed copied = initial.scale(1, 255);
        assertEquals(1f, copied.getPrimitiveEffects().get(0).scale);
        assertEquals(0.5f, copied.getPrimitiveEffects().get(1).scale);
        assertEquals(0f, copied.getPrimitiveEffects().get(2).scale);

        VibrationEffect.Composed halved = initial.scale(1, 128);
        assertEquals(0.5f, halved.getPrimitiveEffects().get(0).scale, SCALE_TOLERANCE);
        assertEquals(0.25f, halved.getPrimitiveEffects().get(1).scale, SCALE_TOLERANCE);
        assertEquals(0f, halved.getPrimitiveEffects().get(2).scale);

        VibrationEffect.Composed scaledUp = initial.scale(0.5f, 255);
        assertEquals(1f, scaledUp.getPrimitiveEffects().get(0).scale); // does not scale up from 1
        assertTrue(0.5f < scaledUp.getPrimitiveEffects().get(1).scale);
        assertEquals(0f, scaledUp.getPrimitiveEffects().get(2).scale);

        VibrationEffect.Composed restored = scaledUp.scale(2, 255);
        assertEquals(1f, restored.getPrimitiveEffects().get(0).scale, SCALE_TOLERANCE);
        assertEquals(0.5f, restored.getPrimitiveEffects().get(1).scale, SCALE_TOLERANCE);
        assertEquals(0f, restored.getPrimitiveEffects().get(2).scale);

        VibrationEffect.Composed scaledDown = initial.scale(2, 255);
        assertEquals(1f, scaledDown.getPrimitiveEffects().get(0).scale, SCALE_TOLERANCE);
        assertTrue(0.5f > scaledDown.getPrimitiveEffects().get(1).scale);
        assertEquals(0f, scaledDown.getPrimitiveEffects().get(2).scale, SCALE_TOLERANCE);

        VibrationEffect.Composed changeMax = initial.scale(1f, 51);
        assertEquals(0.2f, changeMax.getPrimitiveEffects().get(0).scale, SCALE_TOLERANCE);
        assertEquals(0.1f, changeMax.getPrimitiveEffects().get(1).scale, SCALE_TOLERANCE);
        assertEquals(0f, changeMax.getPrimitiveEffects().get(2).scale);
    }

    @Test
    public void testScaleComposedFailsWhenMaxAmplitudeAboveThreshold() {
        try {
            ((VibrationEffect.Composed) TEST_COMPOSED).scale(1.1f, 1000);
            fail("Max amplitude above threshold, should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testScaleAppliesSameAdjustmentsOnAllEffects() {
        VibrationEffect.OneShot oneShot = new VibrationEffect.OneShot(TEST_TIMING, TEST_AMPLITUDE);
        VibrationEffect.Waveform waveform = new VibrationEffect.Waveform(
                new long[] { TEST_TIMING }, new int[]{ TEST_AMPLITUDE }, -1);
        VibrationEffect.Composed composed =
                (VibrationEffect.Composed) VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, TEST_AMPLITUDE / 255f)
                    .compose();

        assertEquals(oneShot.scale(2f, 128).getAmplitude(),
                waveform.scale(2f, 128).getAmplitudes()[0]);
        assertEquals(oneShot.scale(2f, 128).getAmplitude() / 255f, // convert amplitude to scale
                composed.scale(2f, 128).getPrimitiveEffects().get(0).scale,
                SCALE_TOLERANCE);
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
