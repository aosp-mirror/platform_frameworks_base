/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.locksettings;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import javax.crypto.spec.SecretKeySpec;

/**
 * atest FrameworksServicesTests:RebootEscrowDataTest
 */
@RunWith(AndroidJUnit4.class)
public class RebootEscrowDataTest {
    private static byte[] getTestSp() {
        byte[] testSp = new byte[10];
        for (int i = 0; i < testSp.length; i++) {
            testSp[i] = (byte) i;
        }
        return testSp;
    }

    @Test(expected = NullPointerException.class)
    public void fromEntries_failsOnNull() throws Exception {
        RebootEscrowData.fromSyntheticPassword((byte) 2, null);
    }

    @Test(expected = NullPointerException.class)
    public void fromEncryptedData_failsOnNullData() throws Exception {
        byte[] testSp = getTestSp();
        RebootEscrowData expected = RebootEscrowData.fromSyntheticPassword((byte) 2, testSp);
        SecretKeySpec key = RebootEscrowData.fromKeyBytes(expected.getKey());
        RebootEscrowData.fromEncryptedData(key, null);
    }

    @Test(expected = NullPointerException.class)
    public void fromEncryptedData_failsOnNullKey() throws Exception {
        byte[] testSp = getTestSp();
        RebootEscrowData expected = RebootEscrowData.fromSyntheticPassword((byte) 2, testSp);
        RebootEscrowData.fromEncryptedData(null, expected.getBlob());
    }

    @Test
    public void fromEntries_loopback_success() throws Exception {
        byte[] testSp = getTestSp();
        RebootEscrowData expected = RebootEscrowData.fromSyntheticPassword((byte) 2, testSp);

        SecretKeySpec key = RebootEscrowData.fromKeyBytes(expected.getKey());
        RebootEscrowData actual = RebootEscrowData.fromEncryptedData(key, expected.getBlob());

        assertThat(actual.getSpVersion(), is(expected.getSpVersion()));
        assertThat(actual.getIv(), is(expected.getIv()));
        assertThat(actual.getKey(), is(expected.getKey()));
        assertThat(actual.getBlob(), is(expected.getBlob()));
        assertThat(actual.getSyntheticPassword(), is(expected.getSyntheticPassword()));
    }
}
