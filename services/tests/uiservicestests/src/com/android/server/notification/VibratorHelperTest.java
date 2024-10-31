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

import android.media.Utils;
import android.net.Uri;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.provider.Settings;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

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
    public void createVibrationEffectFromSoundUri_nullInput() {
        assertNull(mVibratorHelper.createVibrationEffectFromSoundUri(null));
    }

    @Test
    public void createVibrationEffectFromSoundUri_emptyUri() {
        assertNull(mVibratorHelper.createVibrationEffectFromSoundUri(Uri.EMPTY));
    }

    @Test
    public void createVibrationEffectFromSoundUri_opaqueUri() {
        Uri uri = Uri.parse("a:b#c");
        assertNull(mVibratorHelper.createVibrationEffectFromSoundUri(uri));
    }

    @Test
    public void createVibrationEffectFromSoundUri_uriWithoutRequiredQueryParameter() {
        Uri uri = Settings.System.DEFAULT_NOTIFICATION_URI;
        assertNull(mVibratorHelper.createVibrationEffectFromSoundUri(uri));
    }

    @Test
    public void createVibrationEffectFromSoundUri_uriWithVibrationUri() throws IOException {
        // prepare the uri with vibration
        when(mVibrator.getInfo()).thenReturn(VibratorInfo.EMPTY_VIBRATOR_INFO);
        Uri validUri = getVibrationUriAppended(Settings.System.DEFAULT_NOTIFICATION_URI);

        assertSingleVibration(mVibratorHelper.createVibrationEffectFromSoundUri(validUri));
    }

    @Test
    public void createVibration_insistent_createsRepeatingVibration() {
        when(mVibrator.getInfo()).thenReturn(VibratorInfo.EMPTY_VIBRATOR_INFO);

        when(mVibrator.hasFrequencyControl()).thenReturn(false);
        assertRepeatingVibration(mVibratorHelper.createDefaultVibration(/* insistent= */ true));
        assertRepeatingVibration(mVibratorHelper.createFallbackVibration(/* insistent= */ true));

        when(mVibrator.hasFrequencyControl()).thenReturn(true);
        assertRepeatingVibration(mVibratorHelper.createDefaultVibration(/* insistent= */ true));
        assertRepeatingVibration(mVibratorHelper.createFallbackVibration(/* insistent= */ true));
    }

    @Test
    public void createVibration_nonInsistent_createsSingleShotVibration() {
        when(mVibrator.getInfo()).thenReturn(VibratorInfo.EMPTY_VIBRATOR_INFO);

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

    private static Uri getVibrationUriAppended(Uri baseUri) throws IOException {
        File tempVibrationFile = File.createTempFile("test_vibration_file", ".xml");
        FileWriter writer = new FileWriter(tempVibrationFile);
        writer.write("<vibration-effect>\n"
                + "    <waveform-effect>\n"
                + "        <!-- PRIMING -->\n"
                + "        <waveform-entry durationMs=\"0\" amplitude=\"0\"/>\n"
                + "        <waveform-entry durationMs=\"12\" amplitude=\"255\"/>\n"
                + "        <waveform-entry durationMs=\"250\" amplitude=\"0\"/>\n"
                + "        <waveform-entry durationMs=\"12\" amplitude=\"255\"/>\n"
                + "        <waveform-entry durationMs=\"500\" amplitude=\"0\"/>\n"
                + "    </waveform-effect>\n"
                + "</vibration-effect>"); // Your test XML content
        writer.close();

        Uri.Builder builder = baseUri.buildUpon();
        builder.appendQueryParameter(
                Utils.VIBRATION_URI_PARAM,
                tempVibrationFile.toURI().toString());
        return builder.build();
    }
}
