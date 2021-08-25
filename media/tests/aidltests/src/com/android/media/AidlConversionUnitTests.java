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

package android.media.audio.common;

import android.media.AudioFormat;
import android.media.AudioSystem;
import android.media.MediaFormat;
import android.platform.test.annotations.Presubmit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for AidlConversion utilities.
 *
 * Run with "atest AidlConversionUnitTests".
 */
@Presubmit
@RunWith(JUnit4.class)
public final class AidlConversionUnitTests {

    private static final String TAG = "AidlConvTests";

    @Test
    public void testAudioChannelConversionApiDefault() {
        final AudioChannelLayout aidl = new AudioChannelLayout();
        final int api = AidlConversion.aidl2api_AudioChannelLayout_AudioFormatChannelMask(
                aidl, false /*isInput*/);
        assertEquals(AudioFormat.CHANNEL_OUT_DEFAULT, api);
        final int apiInput = AidlConversion.aidl2api_AudioChannelLayout_AudioFormatChannelMask(
                aidl, true /*isInput*/);
        assertEquals(AudioFormat.CHANNEL_IN_DEFAULT, apiInput);
    }

    @Test
    public void testAudioChannelConversionApiOutput() {
        final AudioChannelLayout aidl = AudioChannelLayout.layoutMask(
                AudioChannelLayout.LAYOUT_MONO);
        final int api = AidlConversion.aidl2api_AudioChannelLayout_AudioFormatChannelMask(
                aidl, false /*isInput*/);
        assertEquals(AudioFormat.CHANNEL_OUT_MONO, api);
    }

    @Test
    public void testAudioChannelConversionApiInput() {
        final AudioChannelLayout aidl = AudioChannelLayout.layoutMask(
                AudioChannelLayout.LAYOUT_MONO);
        final int api = AidlConversion.aidl2api_AudioChannelLayout_AudioFormatChannelMask(
                aidl, true /*isInput*/);
        assertEquals(AudioFormat.CHANNEL_IN_MONO, api);
    }

    @Test
    public void testAudioChannelConversionApiIndex() {
        final AudioChannelLayout aidl = AudioChannelLayout.indexMask(
                AudioChannelLayout.INDEX_MASK_1);
        final int api = AidlConversion.aidl2api_AudioChannelLayout_AudioFormatChannelMask(
                aidl, false /*isInput*/);
        assertEquals(1, api);
        final int apiInput = AidlConversion.aidl2api_AudioChannelLayout_AudioFormatChannelMask(
                aidl, true /*isInput*/);
        assertEquals(1, apiInput);
    }

    @Test
    public void testAudioChannelConversionApiVoice() {
        final AudioChannelLayout aidl = AudioChannelLayout.voiceMask(
                AudioChannelLayout.VOICE_UPLINK_MONO);
        final int api = AidlConversion.aidl2api_AudioChannelLayout_AudioFormatChannelMask(
                aidl, true /*isInput*/);
        assertEquals(AudioFormat.CHANNEL_IN_VOICE_UPLINK | AudioFormat.CHANNEL_IN_MONO, api);
    }

    @Test
    public void testAudioConfigConfersionApiIndex() {
        final AudioConfig aidl = new AudioConfig();
        aidl.sampleRateHz = 8000;
        aidl.channelMask = AudioChannelLayout.indexMask(AudioChannelLayout.INDEX_MASK_1);
        aidl.format = createPcm16FormatAidl();
        // Other fields in AudioConfig are irrelevant.
        final AudioFormat api = AidlConversion.aidl2api_AudioConfig_AudioFormat(
                aidl, false /*isInput*/);
        final AudioFormat apiInput = AidlConversion.aidl2api_AudioConfig_AudioFormat(
                aidl, true /*isInput*/);
        assertEquals(api, apiInput);
        assertEquals(8000, api.getSampleRate());
        assertEquals(1, api.getChannelIndexMask());
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, api.getEncoding());
    }

    @Test
    public void testAudioConfigConfersionApiLayout() {
        final AudioConfig aidl = new AudioConfig();
        aidl.sampleRateHz = 8000;
        aidl.channelMask = AudioChannelLayout.layoutMask(AudioChannelLayout.LAYOUT_MONO);
        aidl.format = createPcm16FormatAidl();
        // Other fields in AudioConfig are irrelevant.
        final AudioFormat api = AidlConversion.aidl2api_AudioConfig_AudioFormat(
                aidl, false /*isInput*/);
        assertEquals(8000, api.getSampleRate());
        assertEquals(AudioFormat.CHANNEL_OUT_MONO, api.getChannelMask());
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, api.getEncoding());
        final AudioFormat apiInput = AidlConversion.aidl2api_AudioConfig_AudioFormat(
                aidl, true /*isInput*/);
        assertEquals(8000, apiInput.getSampleRate());
        assertEquals(AudioFormat.CHANNEL_IN_MONO, apiInput.getChannelMask());
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, apiInput.getEncoding());
    }

    @Test
    public void testAudioFormatConversionApiDefault() {
        final AudioFormatDescription aidl = new AudioFormatDescription();
        final int api = AidlConversion.aidl2api_AudioFormat_AudioFormatEncoding(aidl);
        assertEquals(AudioFormat.ENCODING_DEFAULT, api);
    }

    @Test
    public void testAudioFormatConversionApiPcm16() {
        final AudioFormatDescription aidl = createPcm16FormatAidl();
        final int api = AidlConversion.aidl2api_AudioFormat_AudioFormatEncoding(aidl);
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, api);
    }

    @Test
    public void testAudioFormatConversionApiAacLc() {
        final AudioFormatDescription aidl = new AudioFormatDescription();
        aidl.type = AudioFormatType.NON_PCM;
        aidl.encoding = MediaFormat.MIMETYPE_AUDIO_AAC_LC;
        final int api = AidlConversion.aidl2api_AudioFormat_AudioFormatEncoding(aidl);
        assertEquals(AudioFormat.ENCODING_AAC_LC, api);
    }

    @Test
    public void testAudioChannelConversionLegacyDefault() {
        final int legacy = 0; /*AUDIO_CHANNEL_NONE*/
        final AudioChannelLayout aidl =
                AidlConversion.legacy2aidl_audio_channel_mask_t_AudioChannelLayout(
                        legacy, false /*isInput*/);
        final AudioChannelLayout aidlInput =
                AidlConversion.legacy2aidl_audio_channel_mask_t_AudioChannelLayout(
                        legacy, true /*isInput*/);
        assertEquals(aidl, aidlInput);
        assertEquals(AudioChannelLayout.none, aidl.getTag());
        final int legacyBack =
                AidlConversion.aidl2legacy_AudioChannelLayout_audio_channel_mask_t(
                        aidl, false /*isInput*/);
        final int legacyBackInput =
                AidlConversion.aidl2legacy_AudioChannelLayout_audio_channel_mask_t(
                        aidl, true /*isInput*/);
        assertEquals(legacy, legacyBack);
        assertEquals(legacy, legacyBackInput);
    }

    @Test
    public void testAudioChannelConversionLegacyOutput() {
        final int legacy = 1; /*AUDIO_CHANNEL_OUT_MONO*/
        final AudioChannelLayout aidl =
                AidlConversion.legacy2aidl_audio_channel_mask_t_AudioChannelLayout(
                        legacy, false /*isInput*/);
        assertEquals(AudioChannelLayout.layoutMask, aidl.getTag());
        assertEquals(AudioChannelLayout.LAYOUT_MONO, aidl.getLayoutMask());
        final int legacyBack =
                AidlConversion.aidl2legacy_AudioChannelLayout_audio_channel_mask_t(
                        aidl, false /*isInput*/);
        assertEquals(legacy, legacyBack);
    }

    @Test
    public void testAudioChannelConversionLegacyInput() {
        final int legacy = 0x10; /*AUDIO_CHANNEL_IN_MONO*/
        final AudioChannelLayout aidl =
                AidlConversion.legacy2aidl_audio_channel_mask_t_AudioChannelLayout(
                        legacy, true /*isInput*/);
        assertEquals(AudioChannelLayout.layoutMask, aidl.getTag());
        assertEquals(AudioChannelLayout.LAYOUT_MONO, aidl.getLayoutMask());
        final int legacyBack =
                AidlConversion.aidl2legacy_AudioChannelLayout_audio_channel_mask_t(
                        aidl, true /*isInput*/);
        assertEquals(legacy, legacyBack);
    }

    @Test
    public void testAudioChannelConversionLegacyIndex() {
        final int legacy = 0x80000001; /*AUDIO_CHANNEL_INDEX_MASK_1*/
        final AudioChannelLayout aidl =
                AidlConversion.legacy2aidl_audio_channel_mask_t_AudioChannelLayout(
                        legacy, false /*isInput*/);
        final AudioChannelLayout aidlInput =
                AidlConversion.legacy2aidl_audio_channel_mask_t_AudioChannelLayout(
                        legacy, true /*isInput*/);
        assertEquals(aidl, aidlInput);
        assertEquals(AudioChannelLayout.indexMask, aidl.getTag());
        assertEquals(AudioChannelLayout.INDEX_MASK_1, aidl.getIndexMask());
        final int legacyBack =
                AidlConversion.aidl2legacy_AudioChannelLayout_audio_channel_mask_t(
                        aidl, false /*isInput*/);
        final int legacyBackInput =
                AidlConversion.aidl2legacy_AudioChannelLayout_audio_channel_mask_t(
                        aidl, true /*isInput*/);
        assertEquals(legacy, legacyBack);
        assertEquals(legacy, legacyBackInput);
    }

    @Test
    public void testAudioChannelConversionLegacyVoice() {
        final int legacy = 0x4010; /*AUDIO_CHANNEL_IN_VOICE_UPLINK_MONO*/
        final AudioChannelLayout aidl =
                AidlConversion.legacy2aidl_audio_channel_mask_t_AudioChannelLayout(
                        legacy, true /*isInput*/);
        assertEquals(AudioChannelLayout.voiceMask, aidl.getTag());
        assertEquals(AudioChannelLayout.VOICE_UPLINK_MONO, aidl.getVoiceMask());
        final int legacyBack =
                AidlConversion.aidl2legacy_AudioChannelLayout_audio_channel_mask_t(
                        aidl, true /*isInput*/);
        assertEquals(legacy, legacyBack);
    }

    @Test
    public void testAudioFormatConversionLegacyDefault() {
        final int legacy = AudioSystem.AUDIO_FORMAT_DEFAULT;
        final AudioFormatDescription aidl =
                AidlConversion.legacy2aidl_audio_format_t_AudioFormatDescription(legacy);
        assertEquals(AudioFormatType.DEFAULT, aidl.type);
        assertNotNull(aidl.encoding);
        assertTrue(aidl.encoding.isEmpty());
        final int legacyBack =
                AidlConversion.aidl2legacy_AudioFormatDescription_audio_format_t(aidl);
        assertEquals(legacy, legacyBack);
    }

    @Test
    public void testAudioFormatConversionLegacyPcm16() {
        final int legacy = 1; /*AUDIO_FORMAT_PCM_16_BIT*/
        final AudioFormatDescription aidl =
                AidlConversion.legacy2aidl_audio_format_t_AudioFormatDescription(legacy);
        assertEquals(AudioFormatType.PCM, aidl.type);
        assertEquals(PcmType.INT_16_BIT, aidl.pcm);
        final int legacyBack =
                AidlConversion.aidl2legacy_AudioFormatDescription_audio_format_t(aidl);
        assertEquals(legacy, legacyBack);
    }

    @Test
    public void testAudioFormatConversionLegacyAac() {
        final int legacy = AudioSystem.AUDIO_FORMAT_AAC;
        final AudioFormatDescription aidl =
                AidlConversion.legacy2aidl_audio_format_t_AudioFormatDescription(legacy);
        assertEquals(AudioFormatType.NON_PCM, aidl.type);
        assertNotNull(aidl.encoding);
        assertFalse(aidl.encoding.isEmpty());
        final int legacyBack =
                AidlConversion.aidl2legacy_AudioFormatDescription_audio_format_t(aidl);
        assertEquals(legacy, legacyBack);
    }

    private AudioFormatDescription createPcm16FormatAidl() {
        final AudioFormatDescription aidl = new AudioFormatDescription();
        aidl.type = AudioFormatType.PCM;
        aidl.pcm = PcmType.INT_16_BIT;
        return aidl;
    }
}
