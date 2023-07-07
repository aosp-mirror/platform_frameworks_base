/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.mediaframeworktest.unit;

import static org.junit.Assert.assertEquals;

import android.bluetooth.BluetoothProfile;
import android.media.BluetoothProfileConnectionInfo;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BluetoothProfileConnectionInfoTest {

    @Test
    public void testCoverageLeAudioOutputVolume() {
        final boolean supprNoisy = false;
        final int volume = 1;
        final BluetoothProfileConnectionInfo info = BluetoothProfileConnectionInfo
                .createLeAudioOutputInfo(supprNoisy, volume);
        assertEquals(info.getProfile(), BluetoothProfile.LE_AUDIO);
        assertEquals(info.isSuppressNoisyIntent(), supprNoisy);
        assertEquals(info.isLeOutput(), true);
        assertEquals(info.getVolume(), volume);
    }

}
