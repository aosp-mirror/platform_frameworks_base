/*
 * Copyright 2018 The Android Open Source Project
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

package android.bluetooth;

import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import junit.framework.TestCase;

/**
 * Unit test cases for {@link BluetoothCodecConfig}.
 * <p>
 * To run this test, use:
 * runtest --path core/tests/bluetoothtests/src/android/bluetooth/BluetoothCodecConfigTest.java
 */
public class BluetoothCodecConfigTest extends TestCase {
    private static final int[] kCodecTypeArray = new int[] {
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_MAX,
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID,
    };
    private static final int[] kCodecPriorityArray = new int[] {
        BluetoothCodecConfig.CODEC_PRIORITY_DISABLED,
        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
        BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST,
    };
    private static final int[] kSampleRateArray = new int[] {
        BluetoothCodecConfig.SAMPLE_RATE_NONE,
        BluetoothCodecConfig.SAMPLE_RATE_44100,
        BluetoothCodecConfig.SAMPLE_RATE_48000,
        BluetoothCodecConfig.SAMPLE_RATE_88200,
        BluetoothCodecConfig.SAMPLE_RATE_96000,
        BluetoothCodecConfig.SAMPLE_RATE_176400,
        BluetoothCodecConfig.SAMPLE_RATE_192000,
    };
    private static final int[] kBitsPerSampleArray = new int[] {
        BluetoothCodecConfig.BITS_PER_SAMPLE_NONE,
        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
        BluetoothCodecConfig.BITS_PER_SAMPLE_24,
        BluetoothCodecConfig.BITS_PER_SAMPLE_32,
    };
    private static final int[] kChannelModeArray = new int[] {
        BluetoothCodecConfig.CHANNEL_MODE_NONE,
        BluetoothCodecConfig.CHANNEL_MODE_MONO,
        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
    };
    private static final long[] kCodecSpecific1Array = new long[] { 1000, 1001, 1002, 1003, };
    private static final long[] kCodecSpecific2Array = new long[] { 2000, 2001, 2002, 2003, };
    private static final long[] kCodecSpecific3Array = new long[] { 3000, 3001, 3002, 3003, };
    private static final long[] kCodecSpecific4Array = new long[] { 4000, 4001, 4002, 4003, };

    private static final int kTotalConfigs = kCodecTypeArray.length * kCodecPriorityArray.length *
        kSampleRateArray.length * kBitsPerSampleArray.length * kChannelModeArray.length *
        kCodecSpecific1Array.length * kCodecSpecific2Array.length * kCodecSpecific3Array.length *
        kCodecSpecific4Array.length;

    private int selectCodecType(int configId) {
        int left = kCodecTypeArray.length;
        int right = kTotalConfigs / left;
        int index = configId / right;
        index = index % kCodecTypeArray.length;
        return kCodecTypeArray[index];
    }

    private int selectCodecPriority(int configId) {
        int left = kCodecTypeArray.length * kCodecPriorityArray.length;
        int right = kTotalConfigs / left;
        int index = configId / right;
        index = index % kCodecPriorityArray.length;
        return kCodecPriorityArray[index];
    }

    private int selectSampleRate(int configId) {
        int left = kCodecTypeArray.length * kCodecPriorityArray.length * kSampleRateArray.length;
        int right = kTotalConfigs / left;
        int index = configId / right;
        index = index % kSampleRateArray.length;
        return kSampleRateArray[index];
    }

    private int selectBitsPerSample(int configId) {
        int left = kCodecTypeArray.length * kCodecPriorityArray.length * kSampleRateArray.length *
            kBitsPerSampleArray.length;
        int right = kTotalConfigs / left;
        int index = configId / right;
        index = index % kBitsPerSampleArray.length;
        return kBitsPerSampleArray[index];
    }

    private int selectChannelMode(int configId) {
        int left = kCodecTypeArray.length * kCodecPriorityArray.length * kSampleRateArray.length *
            kBitsPerSampleArray.length * kChannelModeArray.length;
        int right = kTotalConfigs / left;
        int index = configId / right;
        index = index % kChannelModeArray.length;
        return kChannelModeArray[index];
    }

    private long selectCodecSpecific1(int configId) {
        int left = kCodecTypeArray.length * kCodecPriorityArray.length * kSampleRateArray.length *
            kBitsPerSampleArray.length * kChannelModeArray.length * kCodecSpecific1Array.length;
        int right = kTotalConfigs / left;
        int index = configId / right;
        index = index % kCodecSpecific1Array.length;
        return kCodecSpecific1Array[index];
    }

    private long selectCodecSpecific2(int configId) {
        int left = kCodecTypeArray.length * kCodecPriorityArray.length * kSampleRateArray.length *
            kBitsPerSampleArray.length * kChannelModeArray.length * kCodecSpecific1Array.length *
            kCodecSpecific2Array.length;
        int right = kTotalConfigs / left;
        int index = configId / right;
        index = index % kCodecSpecific2Array.length;
        return kCodecSpecific2Array[index];
    }

    private long selectCodecSpecific3(int configId) {
        int left = kCodecTypeArray.length * kCodecPriorityArray.length * kSampleRateArray.length *
            kBitsPerSampleArray.length * kChannelModeArray.length * kCodecSpecific1Array.length *
            kCodecSpecific2Array.length * kCodecSpecific3Array.length;
        int right = kTotalConfigs / left;
        int index = configId / right;
        index = index % kCodecSpecific3Array.length;
        return kCodecSpecific3Array[index];
    }

    private long selectCodecSpecific4(int configId) {
        int left = kCodecTypeArray.length * kCodecPriorityArray.length * kSampleRateArray.length *
            kBitsPerSampleArray.length * kChannelModeArray.length * kCodecSpecific1Array.length *
            kCodecSpecific2Array.length * kCodecSpecific3Array.length *
            kCodecSpecific4Array.length;
        int right = kTotalConfigs / left;
        int index = configId / right;
        index = index % kCodecSpecific4Array.length;
        return kCodecSpecific4Array[index];
    }

    @SmallTest
    public void testBluetoothCodecConfig_valid_get_methods() {

        for (int config_id = 0; config_id < kTotalConfigs; config_id++) {
            int codec_type = selectCodecType(config_id);
            int codec_priority = selectCodecPriority(config_id);
            int sample_rate = selectSampleRate(config_id);
            int bits_per_sample = selectBitsPerSample(config_id);
            int channel_mode = selectChannelMode(config_id);
            long codec_specific1 = selectCodecSpecific1(config_id);
            long codec_specific2 = selectCodecSpecific2(config_id);
            long codec_specific3 = selectCodecSpecific3(config_id);
            long codec_specific4 = selectCodecSpecific4(config_id);

            BluetoothCodecConfig bcc = new BluetoothCodecConfig(codec_type, codec_priority,
                                                                sample_rate, bits_per_sample,
                                                                channel_mode, codec_specific1,
                                                                codec_specific2, codec_specific3,
                                                                codec_specific4);
            if (sample_rate == BluetoothCodecConfig.SAMPLE_RATE_NONE) {
                assertFalse(bcc.isValid());
            } else if (bits_per_sample == BluetoothCodecConfig.BITS_PER_SAMPLE_NONE) {
                assertFalse(bcc.isValid());
            } else if (channel_mode == BluetoothCodecConfig.CHANNEL_MODE_NONE) {
                assertFalse(bcc.isValid());
            } else {
                assertTrue(bcc.isValid());
            }

            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC) {
                assertTrue(bcc.isMandatoryCodec());
            } else {
                assertFalse(bcc.isMandatoryCodec());
            }

            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC) {
                assertEquals("SBC", bcc.getCodecName());
            }
            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC) {
                assertEquals("AAC", bcc.getCodecName());
            }
            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX) {
                assertEquals("aptX", bcc.getCodecName());
            }
            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD) {
                assertEquals("aptX HD", bcc.getCodecName());
            }
            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
                assertEquals("LDAC", bcc.getCodecName());
            }
            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_MAX) {
                assertEquals("UNKNOWN CODEC(" + BluetoothCodecConfig.SOURCE_CODEC_TYPE_MAX + ")",
                             bcc.getCodecName());
            }
            if (codec_type == BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
                assertEquals("INVALID CODEC", bcc.getCodecName());
            }

            assertEquals(codec_type, bcc.getCodecType());
            assertEquals(codec_priority, bcc.getCodecPriority());
            assertEquals(sample_rate, bcc.getSampleRate());
            assertEquals(bits_per_sample, bcc.getBitsPerSample());
            assertEquals(channel_mode, bcc.getChannelMode());
            assertEquals(codec_specific1, bcc.getCodecSpecific1());
            assertEquals(codec_specific2, bcc.getCodecSpecific2());
            assertEquals(codec_specific3, bcc.getCodecSpecific3());
            assertEquals(codec_specific4, bcc.getCodecSpecific4());
        }
    }

    @SmallTest
    public void testBluetoothCodecConfig_equals() {
        BluetoothCodecConfig bcc1 =
            new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                     BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                     BluetoothCodecConfig.SAMPLE_RATE_44100,
                                     BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                     BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                     1000, 2000, 3000, 4000);

        BluetoothCodecConfig bcc2_same =
            new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                     BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                     BluetoothCodecConfig.SAMPLE_RATE_44100,
                                     BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                     BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                     1000, 2000, 3000, 4000);
        assertTrue(bcc1.equals(bcc2_same));

        BluetoothCodecConfig bcc3_codec_type =
            new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                     BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                     BluetoothCodecConfig.SAMPLE_RATE_44100,
                                     BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                     BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                     1000, 2000, 3000, 4000);
        assertFalse(bcc1.equals(bcc3_codec_type));

        BluetoothCodecConfig bcc4_codec_priority =
            new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                     BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST,
                                     BluetoothCodecConfig.SAMPLE_RATE_44100,
                                     BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                     BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                     1000, 2000, 3000, 4000);
        assertFalse(bcc1.equals(bcc4_codec_priority));

        BluetoothCodecConfig bcc5_sample_rate =
            new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                     BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                     BluetoothCodecConfig.SAMPLE_RATE_48000,
                                     BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                     BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                     1000, 2000, 3000, 4000);
        assertFalse(bcc1.equals(bcc5_sample_rate));

        BluetoothCodecConfig bcc6_bits_per_sample =
            new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                     BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                     BluetoothCodecConfig.SAMPLE_RATE_44100,
                                     BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                     BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                     1000, 2000, 3000, 4000);
        assertFalse(bcc1.equals(bcc6_bits_per_sample));

        BluetoothCodecConfig bcc7_channel_mode =
            new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                     BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                     BluetoothCodecConfig.SAMPLE_RATE_44100,
                                     BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                     BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                     1000, 2000, 3000, 4000);
        assertFalse(bcc1.equals(bcc7_channel_mode));

        BluetoothCodecConfig bcc8_codec_specific1 =
            new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                     BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                     BluetoothCodecConfig.SAMPLE_RATE_44100,
                                     BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                     BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                     1001, 2000, 3000, 4000);
        assertFalse(bcc1.equals(bcc8_codec_specific1));

        BluetoothCodecConfig bcc9_codec_specific2 =
            new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                     BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                     BluetoothCodecConfig.SAMPLE_RATE_44100,
                                     BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                     BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                     1000, 2002, 3000, 4000);
        assertFalse(bcc1.equals(bcc9_codec_specific2));

        BluetoothCodecConfig bcc10_codec_specific3 =
            new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                     BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                     BluetoothCodecConfig.SAMPLE_RATE_44100,
                                     BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                     BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                     1000, 2000, 3003, 4000);
        assertFalse(bcc1.equals(bcc10_codec_specific3));

        BluetoothCodecConfig bcc11_codec_specific4 =
            new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                     BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                     BluetoothCodecConfig.SAMPLE_RATE_44100,
                                     BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                     BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                     1000, 2000, 3000, 4004);
        assertFalse(bcc1.equals(bcc11_codec_specific4));
    }
}
