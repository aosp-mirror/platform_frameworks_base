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

import java.util.Arrays;
import java.util.Objects;

import junit.framework.TestCase;

/**
 * Unit test cases for {@link BluetoothCodecStatus}.
 * <p>
 * To run this test, use:
 * runtest --path core/tests/bluetoothtests/src/android/bluetooth/BluetoothCodecStatusTest.java
 */
public class BluetoothCodecStatusTest extends TestCase {

    // Codec configs: A and B are same; C is different
    private static final BluetoothCodecConfig config_A =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig config_B =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig config_C =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    // Local capabilities: A and B are same; C is different
    private static final BluetoothCodecConfig local_capability1_A =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability1_B =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability1_C =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);


    private static final BluetoothCodecConfig local_capability2_A =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability2_B =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability2_C =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability3_A =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability3_B =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability3_C =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability4_A =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability4_B =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability4_C =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability5_A =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000 |
                                 BluetoothCodecConfig.SAMPLE_RATE_88200 |
                                 BluetoothCodecConfig.SAMPLE_RATE_96000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16 |
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24 |
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_32,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability5_B =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000 |
                                 BluetoothCodecConfig.SAMPLE_RATE_88200 |
                                 BluetoothCodecConfig.SAMPLE_RATE_96000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16 |
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24 |
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_32,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability5_C =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000 |
                                 BluetoothCodecConfig.SAMPLE_RATE_88200 |
                                 BluetoothCodecConfig.SAMPLE_RATE_96000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16 |
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24 |
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_32,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);


    // Selectable capabilities: A and B are same; C is different
    private static final BluetoothCodecConfig selectable_capability1_A =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability1_B =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability1_C =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability2_A =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability2_B =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability2_C =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability3_A =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability3_B =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability3_C =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability4_A =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability4_B =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability4_C =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability5_A =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000 |
                                 BluetoothCodecConfig.SAMPLE_RATE_88200 |
                                 BluetoothCodecConfig.SAMPLE_RATE_96000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16 |
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24 |
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_32,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability5_B =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000 |
                                 BluetoothCodecConfig.SAMPLE_RATE_88200 |
                                 BluetoothCodecConfig.SAMPLE_RATE_96000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16 |
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24 |
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_32,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability5_C =
        new BluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000 |
                                 BluetoothCodecConfig.SAMPLE_RATE_88200 |
                                 BluetoothCodecConfig.SAMPLE_RATE_96000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16 |
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24 |
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_32,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig[] local_capability_A = {
        local_capability1_A,
        local_capability2_A,
        local_capability3_A,
        local_capability4_A,
        local_capability5_A,
    };

    private static final BluetoothCodecConfig[] local_capability_B = {
        local_capability1_B,
        local_capability2_B,
        local_capability3_B,
        local_capability4_B,
        local_capability5_B,
    };

    private static final BluetoothCodecConfig[] local_capability_B_reordered = {
        local_capability5_B,
        local_capability4_B,
        local_capability2_B,
        local_capability3_B,
        local_capability1_B,
    };

    private static final BluetoothCodecConfig[] local_capability_C = {
        local_capability1_C,
        local_capability2_C,
        local_capability3_C,
        local_capability4_C,
        local_capability5_C,
    };

    private static final BluetoothCodecConfig[] selectable_capability_A = {
        selectable_capability1_A,
        selectable_capability2_A,
        selectable_capability3_A,
        selectable_capability4_A,
        selectable_capability5_A,
    };

    private static final BluetoothCodecConfig[] selectable_capability_B = {
        selectable_capability1_B,
        selectable_capability2_B,
        selectable_capability3_B,
        selectable_capability4_B,
        selectable_capability5_B,
    };

    private static final BluetoothCodecConfig[] selectable_capability_B_reordered = {
        selectable_capability5_B,
        selectable_capability4_B,
        selectable_capability2_B,
        selectable_capability3_B,
        selectable_capability1_B,
    };

    private static final BluetoothCodecConfig[] selectable_capability_C = {
        selectable_capability1_C,
        selectable_capability2_C,
        selectable_capability3_C,
        selectable_capability4_C,
        selectable_capability5_C,
    };

    private static final BluetoothCodecStatus bcs_A =
        new BluetoothCodecStatus(config_A, local_capability_A, selectable_capability_A);
    private static final BluetoothCodecStatus bcs_B =
        new BluetoothCodecStatus(config_B, local_capability_B, selectable_capability_B);
    private static final BluetoothCodecStatus bcs_B_reordered =
        new BluetoothCodecStatus(config_B, local_capability_B_reordered,
                                 selectable_capability_B_reordered);
    private static final BluetoothCodecStatus bcs_C =
        new BluetoothCodecStatus(config_C, local_capability_C, selectable_capability_C);

    @SmallTest
    public void testBluetoothCodecStatus_get_methods() {

        assertTrue(Objects.equals(bcs_A.getCodecConfig(), config_A));
        assertTrue(Objects.equals(bcs_A.getCodecConfig(), config_B));
        assertFalse(Objects.equals(bcs_A.getCodecConfig(), config_C));

        assertTrue(Arrays.equals(bcs_A.getCodecsLocalCapabilities(), local_capability_A));
        assertTrue(Arrays.equals(bcs_A.getCodecsLocalCapabilities(), local_capability_B));
        assertFalse(Arrays.equals(bcs_A.getCodecsLocalCapabilities(), local_capability_C));

        assertTrue(Arrays.equals(bcs_A.getCodecsSelectableCapabilities(),
                                 selectable_capability_A));
        assertTrue(Arrays.equals(bcs_A.getCodecsSelectableCapabilities(),
                                  selectable_capability_B));
        assertFalse(Arrays.equals(bcs_A.getCodecsSelectableCapabilities(),
                                  selectable_capability_C));
    }

    @SmallTest
    public void testBluetoothCodecStatus_equals() {
        assertTrue(bcs_A.equals(bcs_B));
        assertTrue(bcs_B.equals(bcs_A));
        assertTrue(bcs_A.equals(bcs_B_reordered));
        assertTrue(bcs_B_reordered.equals(bcs_A));
        assertFalse(bcs_A.equals(bcs_C));
        assertFalse(bcs_C.equals(bcs_A));
    }
}
