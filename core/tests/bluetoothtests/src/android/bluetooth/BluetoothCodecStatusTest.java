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

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Unit test cases for {@link BluetoothCodecStatus}.
 * <p>
 * To run this test, use:
 * runtest --path core/tests/bluetoothtests/src/android/bluetooth/BluetoothCodecStatusTest.java
 */
public class BluetoothCodecStatusTest extends TestCase {

    // Codec configs: A and B are same; C is different
    private static final BluetoothCodecConfig config_A =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig config_B =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig config_C =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    // Local capabilities: A and B are same; C is different
    private static final BluetoothCodecConfig local_capability1_A =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability1_B =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability1_C =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);


    private static final BluetoothCodecConfig local_capability2_A =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability2_B =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability2_C =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability3_A =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability3_B =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability3_C =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability4_A =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability4_B =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability4_C =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100 |
                                 BluetoothCodecConfig.SAMPLE_RATE_48000,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig local_capability5_A =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
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
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
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
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
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
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability1_B =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability1_C =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability2_A =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability2_B =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability2_C =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability3_A =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability3_B =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability3_C =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability4_A =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability4_B =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO |
                                 BluetoothCodecConfig.CHANNEL_MODE_MONO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability4_C =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
                                 BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                                 BluetoothCodecConfig.SAMPLE_RATE_44100,
                                 BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                                 BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                                 1000, 2000, 3000, 4000);

    private static final BluetoothCodecConfig selectable_capability5_A =
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
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
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
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
            buildBluetoothCodecConfig(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
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

    private static final List<BluetoothCodecConfig> LOCAL_CAPABILITY_A =
            new ArrayList() {{
                    add(local_capability1_A);
                    add(local_capability2_A);
                    add(local_capability3_A);
                    add(local_capability4_A);
                    add(local_capability5_A);
            }};

    private static final List<BluetoothCodecConfig> LOCAL_CAPABILITY_B =
            new ArrayList() {{
                    add(local_capability1_B);
                    add(local_capability2_B);
                    add(local_capability3_B);
                    add(local_capability4_B);
                    add(local_capability5_B);
            }};

    private static final List<BluetoothCodecConfig> LOCAL_CAPABILITY_B_REORDERED =
            new ArrayList() {{
                    add(local_capability5_B);
                    add(local_capability4_B);
                    add(local_capability2_B);
                    add(local_capability3_B);
                    add(local_capability1_B);
            }};

    private static final List<BluetoothCodecConfig> LOCAL_CAPABILITY_C =
            new ArrayList() {{
                    add(local_capability1_C);
                    add(local_capability2_C);
                    add(local_capability3_C);
                    add(local_capability4_C);
                    add(local_capability5_C);
            }};

    private static final List<BluetoothCodecConfig> SELECTABLE_CAPABILITY_A =
            new ArrayList() {{
                    add(selectable_capability1_A);
                    add(selectable_capability2_A);
                    add(selectable_capability3_A);
                    add(selectable_capability4_A);
                    add(selectable_capability5_A);
            }};

    private static final List<BluetoothCodecConfig> SELECTABLE_CAPABILITY_B =
            new ArrayList() {{
                    add(selectable_capability1_B);
                    add(selectable_capability2_B);
                    add(selectable_capability3_B);
                    add(selectable_capability4_B);
                    add(selectable_capability5_B);
            }};

    private static final List<BluetoothCodecConfig> SELECTABLE_CAPABILITY_B_REORDERED =
            new ArrayList() {{
                    add(selectable_capability5_B);
                    add(selectable_capability4_B);
                    add(selectable_capability2_B);
                    add(selectable_capability3_B);
                    add(selectable_capability1_B);
            }};

    private static final List<BluetoothCodecConfig> SELECTABLE_CAPABILITY_C =
            new ArrayList() {{
                    add(selectable_capability1_C);
                    add(selectable_capability2_C);
                    add(selectable_capability3_C);
                    add(selectable_capability4_C);
                    add(selectable_capability5_C);
            }};

    private static final BluetoothCodecStatus bcs_A =
            new BluetoothCodecStatus(config_A, LOCAL_CAPABILITY_A, SELECTABLE_CAPABILITY_A);
    private static final BluetoothCodecStatus bcs_B =
            new BluetoothCodecStatus(config_B, LOCAL_CAPABILITY_B, SELECTABLE_CAPABILITY_B);
    private static final BluetoothCodecStatus bcs_B_reordered =
            new BluetoothCodecStatus(config_B, LOCAL_CAPABILITY_B_REORDERED,
                                 SELECTABLE_CAPABILITY_B_REORDERED);
    private static final BluetoothCodecStatus bcs_C =
            new BluetoothCodecStatus(config_C, LOCAL_CAPABILITY_C, SELECTABLE_CAPABILITY_C);

    @SmallTest
    public void testBluetoothCodecStatus_get_methods() {

        assertTrue(Objects.equals(bcs_A.getCodecConfig(), config_A));
        assertTrue(Objects.equals(bcs_A.getCodecConfig(), config_B));
        assertFalse(Objects.equals(bcs_A.getCodecConfig(), config_C));

        assertTrue(bcs_A.getCodecsLocalCapabilities().equals(LOCAL_CAPABILITY_A));
        assertTrue(bcs_A.getCodecsLocalCapabilities().equals(LOCAL_CAPABILITY_B));
        assertFalse(bcs_A.getCodecsLocalCapabilities().equals(LOCAL_CAPABILITY_C));

        assertTrue(bcs_A.getCodecsSelectableCapabilities()
                                 .equals(SELECTABLE_CAPABILITY_A));
        assertTrue(bcs_A.getCodecsSelectableCapabilities()
                                  .equals(SELECTABLE_CAPABILITY_B));
        assertFalse(bcs_A.getCodecsSelectableCapabilities()
                                  .equals(SELECTABLE_CAPABILITY_C));
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

    private static BluetoothCodecConfig buildBluetoothCodecConfig(int sourceCodecType,
            int codecPriority, int sampleRate, int bitsPerSample, int channelMode,
            long codecSpecific1, long codecSpecific2, long codecSpecific3, long codecSpecific4) {
        return new BluetoothCodecConfig.Builder()
                    .setCodecType(sourceCodecType)
                    .setCodecPriority(codecPriority)
                    .setSampleRate(sampleRate)
                    .setBitsPerSample(bitsPerSample)
                    .setChannelMode(channelMode)
                    .setCodecSpecific1(codecSpecific1)
                    .setCodecSpecific2(codecSpecific2)
                    .setCodecSpecific3(codecSpecific3)
                    .setCodecSpecific4(codecSpecific4)
                    .build();

    }
}
