/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics;

import static junit.framework.Assert.assertEquals;

import android.hardware.keymaster.HardwareAuthToken;
import android.hardware.keymaster.Timestamp;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@Presubmit
@SmallTest
public class HardwareAuthTokenUtilsTest {

    @Test
    public void testByteArrayLoopBack() {
        final byte[] hat = new byte[69];
        for (int i = 0; i < 69; i++) {
            hat[i] = (byte) i;
        }

        final HardwareAuthToken hardwareAuthToken = HardwareAuthTokenUtils.toHardwareAuthToken(hat);
        final byte[] hat2 = HardwareAuthTokenUtils.toByteArray(hardwareAuthToken);

        for (int i = 0; i < hat.length; i++) {
            assertEquals(hat[i], hat2[i]);
        }
    }

    @Test
    public void testHardwareAuthTokenLoopBack() {
        final long testChallenge = 1000L;
        final long testUserId = 2000L;
        final long testAuthenticatorId = 3000L;
        final int testAuthenticatorType = 4000;
        final long testTimestamp = 5000L;

        final HardwareAuthToken hardwareAuthToken = new HardwareAuthToken();
        hardwareAuthToken.challenge = testChallenge;
        hardwareAuthToken.userId = testUserId;
        hardwareAuthToken.authenticatorId = testAuthenticatorId;
        hardwareAuthToken.authenticatorType = testAuthenticatorType;
        hardwareAuthToken.timestamp = new Timestamp();
        hardwareAuthToken.timestamp.milliSeconds = testTimestamp;
        hardwareAuthToken.mac = new byte[32];

        for (int i = 0; i < hardwareAuthToken.mac.length; i++) {
            hardwareAuthToken.mac[i] = (byte) i;
        }

        final byte[] hat = HardwareAuthTokenUtils.toByteArray(hardwareAuthToken);
        final HardwareAuthToken hardwareAuthToken2 =
                HardwareAuthTokenUtils.toHardwareAuthToken(hat);

        assertEquals(testChallenge, hardwareAuthToken2.challenge);
        assertEquals(testUserId, hardwareAuthToken2.userId);
        assertEquals(testAuthenticatorId, hardwareAuthToken2.authenticatorId);
        assertEquals(testAuthenticatorType, hardwareAuthToken2.authenticatorType);
        assertEquals(testTimestamp, hardwareAuthToken2.timestamp.milliSeconds);

        for (int i = 0; i < hardwareAuthToken.mac.length; i++) {
            assertEquals(hardwareAuthToken.mac[i], hardwareAuthToken2.mac[i]);
        }
    }
}
