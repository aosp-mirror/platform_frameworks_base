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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.media.AudioAttributes;
import android.media.AudioDescriptor;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioProfile;
import android.media.AudioSystem;
import android.media.AudioTrack;
import android.media.MediaFormat;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Unit tests for AidlConversion utilities.
 *
 * Run with "atest AidlConversionUnitTests".
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public final class AidlConversionUnitTests {

    private static final String TAG = "AidlConvTests";
    // Negative values are considered to be "invalid" as a general rule.
    // However, '-1' is sometimes used as "system invalid" value, and thus
    // does not cause an exception to be thrown during conversion.
    private static int sInvalidValue = -2;
    private static byte sInvalidValueByte = -2;

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
    public void testAudioChannelConversionApiOutputMask() {
        final AudioChannelLayout aidl = AudioChannelLayout.layoutMask(
                AudioChannelLayout.CHANNEL_FRONT_LEFT | AudioChannelLayout.CHANNEL_FRONT_RIGHT
                | AudioChannelLayout.CHANNEL_FRONT_WIDE_LEFT
                | AudioChannelLayout.CHANNEL_FRONT_WIDE_RIGHT);
        final int api = AidlConversion.aidl2api_AudioChannelLayout_AudioFormatChannelMask(
                aidl, false /*isInput*/);
        assertEquals(AudioFormat.CHANNEL_OUT_FRONT_LEFT | AudioFormat.CHANNEL_OUT_FRONT_RIGHT
                | AudioFormat.CHANNEL_OUT_FRONT_WIDE_LEFT
                | AudioFormat.CHANNEL_OUT_FRONT_WIDE_RIGHT, api);
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
    public void testAudioChannelConversionApiInputMask() {
        final AudioChannelLayout aidl = AudioChannelLayout.layoutMask(
                AudioChannelLayout.CHANNEL_FRONT_LEFT | AudioChannelLayout.CHANNEL_FRONT_RIGHT
                | AudioChannelLayout.CHANNEL_TOP_SIDE_LEFT
                | AudioChannelLayout.CHANNEL_TOP_SIDE_RIGHT);
        final int api = AidlConversion.aidl2api_AudioChannelLayout_AudioFormatChannelMask(
                aidl, true /*isInput*/);
        assertEquals(AudioFormat.CHANNEL_IN_LEFT | AudioFormat.CHANNEL_IN_RIGHT
                // | AudioFormat.CHANNEL_IN_TOP_LEFT | AudioFormat.CHANNEL_IN_TOP_RIGHT,
                | 0x200000 | 0x400000,  // TODO: Replace with names when revealed.
                api);
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
    public void testAudioConfigConversionApiIndex() {
        final AudioConfig aidl = new AudioConfig();
        aidl.base = new AudioConfigBase();
        aidl.base.sampleRate = 8000;
        aidl.base.channelMask = AudioChannelLayout.indexMask(AudioChannelLayout.INDEX_MASK_1);
        aidl.base.format = createPcm16FormatAidl();
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
    public void testAudioConfigConversionApiLayout() {
        final AudioConfig aidl = new AudioConfig();
        aidl.base = new AudioConfigBase();
        aidl.base.sampleRate = 8000;
        aidl.base.channelMask = AudioChannelLayout.layoutMask(AudioChannelLayout.LAYOUT_MONO);
        aidl.base.format = createPcm16FormatAidl();
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
    public void testAudioConfigBaseConversionApiIndex() {
        final AudioConfigBase aidl = new AudioConfigBase();
        aidl.sampleRate = 8000;
        aidl.channelMask = AudioChannelLayout.indexMask(AudioChannelLayout.INDEX_MASK_1);
        aidl.format = createPcm16FormatAidl();
        final AudioFormat api = AidlConversion.aidl2api_AudioConfigBase_AudioFormat(
                aidl, false /*isInput*/);
        final AudioFormat apiInput = AidlConversion.aidl2api_AudioConfigBase_AudioFormat(
                aidl, true /*isInput*/);
        assertEquals(api, apiInput);
        assertEquals(8000, api.getSampleRate());
        assertEquals(1, api.getChannelIndexMask());
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, api.getEncoding());
    }

    @Test
    public void testAudioConfigBaseConversionApiLayout() {
        final AudioConfigBase aidl = new AudioConfigBase();
        aidl.sampleRate = 8000;
        aidl.channelMask = AudioChannelLayout.layoutMask(AudioChannelLayout.LAYOUT_MONO);
        aidl.format = createPcm16FormatAidl();
        final AudioFormat api = AidlConversion.aidl2api_AudioConfigBase_AudioFormat(
                aidl, false /*isInput*/);
        assertEquals(8000, api.getSampleRate());
        assertEquals(AudioFormat.CHANNEL_OUT_MONO, api.getChannelMask());
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, api.getEncoding());
        final AudioFormat apiInput = AidlConversion.aidl2api_AudioConfigBase_AudioFormat(
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
    public void testAudioChannelConversionLegacyInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> AidlConversion.aidl2legacy_AudioChannelLayout_audio_channel_mask_t(
                        AudioChannelLayout.voiceMask(sInvalidValue), false /*isInput*/));
        assertThrows(IllegalArgumentException.class,
                () -> AidlConversion.aidl2legacy_AudioChannelLayout_audio_channel_mask_t(
                        AudioChannelLayout.voiceMask(sInvalidValue), true /*isInput*/));
        assertThrows(IllegalArgumentException.class,
                () -> AidlConversion.legacy2aidl_audio_channel_mask_t_AudioChannelLayout(
                        sInvalidValue, false /*isInput*/));
        assertThrows(IllegalArgumentException.class,
                () -> AidlConversion.legacy2aidl_audio_channel_mask_t_AudioChannelLayout(
                        sInvalidValue, true /*isInput*/));
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

    @Test
    public void testAudioFormatConversionLegacyInvalid() {
        final AudioFormatDescription aidl = new AudioFormatDescription();
        aidl.type = sInvalidValueByte;
        assertThrows(IllegalArgumentException.class,
                () -> AidlConversion.aidl2legacy_AudioFormatDescription_audio_format_t(aidl));
        assertThrows(IllegalArgumentException.class,
                () -> AidlConversion.legacy2aidl_audio_format_t_AudioFormatDescription(
                        sInvalidValue));
    }

    @Test
    public void testAudioEncapsulationModeConversionLegacy() {
        // AIDL values are synchronized with SDK, so we can use the SDK values as AIDL.
        final int aidl = AudioTrack.ENCAPSULATION_MODE_ELEMENTARY_STREAM;
        final int legacy =
                AidlConversion.aidl2legacy_AudioEncapsulationMode_audio_encapsulation_mode_t(aidl);
        final int aidlBack =
                AidlConversion.legacy2aidl_audio_encapsulation_mode_t_AudioEncapsulationMode(
                        legacy);
        assertEquals(aidl, aidlBack);

        assertThrows(IllegalArgumentException.class,
                () -> AidlConversion.aidl2legacy_AudioEncapsulationMode_audio_encapsulation_mode_t(
                        sInvalidValue));
        assertThrows(IllegalArgumentException.class,
                () -> AidlConversion.legacy2aidl_audio_encapsulation_mode_t_AudioEncapsulationMode(
                        sInvalidValue));
    }

    @Test
    public void testAudioStreamTypeConversionLegacy() {
        // AIDL values are synchronized with SDK, so we can use the SDK values as AIDL.
        final int aidl = AudioSystem.STREAM_MUSIC;
        final int legacy = AidlConversion.aidl2legacy_AudioStreamType_audio_stream_type_t(aidl);
        final int aidlBack = AidlConversion.legacy2aidl_audio_stream_type_t_AudioStreamType(legacy);
        assertEquals(aidl, aidlBack);

        assertThrows(IllegalArgumentException.class,
                () -> AidlConversion.aidl2legacy_AudioStreamType_audio_stream_type_t(
                        sInvalidValue));
        assertThrows(IllegalArgumentException.class,
                () -> AidlConversion.legacy2aidl_audio_stream_type_t_AudioStreamType(
                        sInvalidValue));
    }

    @Test
    public void testAudioUsageConversionLegacy() {
        // AIDL values are synchronized with SDK, so we can use the SDK values as AIDL.
        final int aidl = AudioAttributes.USAGE_MEDIA;
        final int legacy = AidlConversion.aidl2legacy_AudioUsage_audio_usage_t(aidl);
        final int aidlBack = AidlConversion.legacy2aidl_audio_usage_t_AudioUsage(legacy);
        assertEquals(aidl, aidlBack);

        assertThrows(IllegalArgumentException.class,
                () -> AidlConversion.aidl2legacy_AudioUsage_audio_usage_t(sInvalidValue));
        assertThrows(IllegalArgumentException.class,
                () -> AidlConversion.legacy2aidl_audio_usage_t_AudioUsage(sInvalidValue));
    }

    @Test
    public void testAudioDescriptorConversion_Default() {
        ExtraAudioDescriptor aidl = createDefaultDescriptor();
        AudioDescriptor audioDescriptor =
                AidlConversion.aidl2api_ExtraAudioDescriptor_AudioDescriptor(aidl);
        assertEquals(AudioDescriptor.STANDARD_NONE, audioDescriptor.getStandard());
        assertEquals(
                AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE, audioDescriptor.getEncapsulationType());
        assertTrue(Arrays.equals(new byte[]{}, audioDescriptor.getDescriptor()));

        ExtraAudioDescriptor reconstructedExtraDescriptor =
                AidlConversion.api2aidl_AudioDescriptor_ExtraAudioDescriptor(audioDescriptor);
        assertEquals(aidl, reconstructedExtraDescriptor);
    }

    @Test
    public void testAudioDescriptorConversion() {
        ExtraAudioDescriptor aidl = createEncapsulationDescriptor(new byte[]{0x05, 0x18, 0x4A});
        AudioDescriptor audioDescriptor =
                AidlConversion.aidl2api_ExtraAudioDescriptor_AudioDescriptor(aidl);
        assertEquals(AudioDescriptor.STANDARD_EDID, audioDescriptor.getStandard());
        assertEquals(AudioProfile.AUDIO_ENCAPSULATION_TYPE_IEC61937,
                audioDescriptor.getEncapsulationType());
        assertTrue(Arrays.equals(new byte[]{0x05, 0x18, 0x4A}, audioDescriptor.getDescriptor()));

        ExtraAudioDescriptor reconstructedExtraDescriptor =
                AidlConversion.api2aidl_AudioDescriptor_ExtraAudioDescriptor(audioDescriptor);
        assertEquals(aidl, reconstructedExtraDescriptor);
    }

    @Test
    public void testAudioDeviceAttributesConversion_Default() {
        AudioDeviceAttributes attributes =
                new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_DEFAULT, "myAddress");
        AudioPort port = AidlConversion.api2aidl_AudioDeviceAttributes_AudioPort(attributes);
        assertEquals("", port.name);
        assertEquals(0, port.extraAudioDescriptors.length);
        assertEquals("myAddress", port.ext.getDevice().device.address.getId());
        assertEquals("", port.ext.getDevice().device.type.connection);
        assertEquals(AudioDeviceType.OUT_DEFAULT, port.ext.getDevice().device.type.type);
    }

    @Test
    public void testAudioDeviceAttributesConversion() {
        AudioDescriptor audioDescriptor1 =
                AidlConversion.aidl2api_ExtraAudioDescriptor_AudioDescriptor(
                        createEncapsulationDescriptor(new byte[]{0x05, 0x18, 0x4A}));

        AudioDescriptor audioDescriptor2 =
                AidlConversion.aidl2api_ExtraAudioDescriptor_AudioDescriptor(
                        createDefaultDescriptor());

        AudioDeviceAttributes attributes =
                new AudioDeviceAttributes(AudioDeviceAttributes.ROLE_OUTPUT,
                        AudioDeviceInfo.TYPE_HDMI_ARC, "myAddress", "myName", new ArrayList<>(),
                        new ArrayList<>(Arrays.asList(audioDescriptor1, audioDescriptor2)));
        AudioPort port = AidlConversion.api2aidl_AudioDeviceAttributes_AudioPort(
                attributes);
        assertEquals("myName", port.name);
        assertEquals(2, port.extraAudioDescriptors.length);
        assertEquals(AudioStandard.EDID, port.extraAudioDescriptors[0].standard);
        assertEquals(AudioEncapsulationType.IEC61937,
                port.extraAudioDescriptors[0].encapsulationType);
        assertTrue(Arrays.equals(new byte[]{0x05, 0x18, 0x4A},
                port.extraAudioDescriptors[0].audioDescriptor));
        assertEquals(AudioStandard.NONE, port.extraAudioDescriptors[1].standard);
        assertEquals(AudioEncapsulationType.NONE,
                port.extraAudioDescriptors[1].encapsulationType);
        assertTrue(Arrays.equals(new byte[]{},
                port.extraAudioDescriptors[1].audioDescriptor));
        assertEquals("myAddress", port.ext.getDevice().device.address.getId());
        assertEquals(AudioDeviceDescription.CONNECTION_HDMI_ARC,
                port.ext.getDevice().device.type.connection);
        assertEquals(AudioDeviceType.OUT_DEVICE, port.ext.getDevice().device.type.type);
    }

    private static AudioFormatDescription createPcm16FormatAidl() {
        final AudioFormatDescription aidl = new AudioFormatDescription();
        aidl.type = AudioFormatType.PCM;
        aidl.pcm = PcmType.INT_16_BIT;
        return aidl;
    }

    private static ExtraAudioDescriptor createDefaultDescriptor() {
        ExtraAudioDescriptor extraDescriptor = new ExtraAudioDescriptor();
        extraDescriptor.standard = AudioStandard.NONE;
        extraDescriptor.encapsulationType = AudioEncapsulationType.NONE;
        extraDescriptor.audioDescriptor = new byte[]{};
        return extraDescriptor;
    }

    private static ExtraAudioDescriptor createEncapsulationDescriptor(byte[] audioDescriptor) {
        ExtraAudioDescriptor extraDescriptor = new ExtraAudioDescriptor();
        extraDescriptor.standard = AudioStandard.EDID;
        extraDescriptor.encapsulationType = AudioEncapsulationType.IEC61937;
        extraDescriptor.audioDescriptor = audioDescriptor;
        return extraDescriptor;
    }
}
