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

package android.bluetooth;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * Unit test cases for {@link BluetoothLeAudioCodecConfig}.
 */
public class BluetoothLeAudioCodecConfigTest extends TestCase {
    private int[] mCodecTypeArray = new int[] {
        BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3,
        BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID,
    };

    @SmallTest
    public void testBluetoothLeAudioCodecConfig_valid_get_methods() {

        for (int codecIdx = 0; codecIdx < mCodecTypeArray.length; codecIdx++) {
            int codecType = mCodecTypeArray[codecIdx];

            BluetoothLeAudioCodecConfig leAudioCodecConfig =
                    buildBluetoothLeAudioCodecConfig(codecType);

            if (codecType == BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3) {
                assertEquals("LC3", leAudioCodecConfig.getCodecName());
            }
            if (codecType == BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
                assertEquals("INVALID CODEC", leAudioCodecConfig.getCodecName());
            }

            assertEquals(codecType, leAudioCodecConfig.getCodecType());
        }
    }

    private BluetoothLeAudioCodecConfig buildBluetoothLeAudioCodecConfig(int sourceCodecType) {
        return new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(sourceCodecType)
                    .build();

    }
}
