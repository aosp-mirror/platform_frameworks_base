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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;

import com.android.internal.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class VibrationEffectTest {
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
        VibrationEffect.Waveform scaled =
                ((VibrationEffect.Waveform) TEST_WAVEFORM).scale(1.1f, 200);
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

    private Context mockContext(Resources r) {
        Context ctx = mock(Context.class);
        when(ctx.getResources()).thenReturn(r);
        return ctx;
    }
}
