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

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * atest FrameworksServicesTests:RebootEscrowDataTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class RebootEscrowDataTest {
    private RebootEscrowKey mKey;
    private SecretKey mKeyStoreEncryptionKey;

    // Hex encoding of a randomly generated AES key for test.
    private static final byte[] TEST_AES_KEY = new byte[] {
            0x44, 0x74, 0x61, 0x54, 0x29, 0x74, 0x37, 0x61,
            0x48, 0x19, 0x12, 0x54, 0x13, 0x13, 0x52, 0x31,
            0x70, 0x70, 0x75, 0x25, 0x27, 0x31, 0x49, 0x09,
            0x26, 0x52, 0x72, 0x63, 0x63, 0x61, 0x78, 0x23,
    };

    @Before
    public void generateKey() throws Exception {
        mKey = RebootEscrowKey.generate();
        mKeyStoreEncryptionKey = new SecretKeySpec(TEST_AES_KEY, "AES");
    }

    private static byte[] getTestSp() {
        byte[] testSp = new byte[10];
        for (int i = 0; i < testSp.length; i++) {
            testSp[i] = (byte) i;
        }
        return testSp;
    }

    @Test(expected = NullPointerException.class)
    public void fromEntries_failsOnNull() throws Exception {
        RebootEscrowData.fromSyntheticPassword(mKey, (byte) 2, null, mKeyStoreEncryptionKey);
    }

    @Test(expected = NullPointerException.class)
    public void fromEncryptedData_failsOnNullData() throws Exception {
        byte[] testSp = getTestSp();
        RebootEscrowData expected = RebootEscrowData.fromSyntheticPassword(mKey, (byte) 2, testSp,
                mKeyStoreEncryptionKey);
        RebootEscrowKey key = RebootEscrowKey.fromKeyBytes(expected.getKey().getKeyBytes());
        RebootEscrowData.fromEncryptedData(key, null, mKeyStoreEncryptionKey);
    }

    @Test(expected = NullPointerException.class)
    public void fromEncryptedData_failsOnNullKey() throws Exception {
        byte[] testSp = getTestSp();
        RebootEscrowData expected = RebootEscrowData.fromSyntheticPassword(mKey, (byte) 2, testSp,
                mKeyStoreEncryptionKey);
        RebootEscrowData.fromEncryptedData(null, expected.getBlob(), mKeyStoreEncryptionKey);
    }

    @Test
    public void fromEntries_loopback_success() throws Exception {
        byte[] testSp = getTestSp();
        RebootEscrowData expected = RebootEscrowData.fromSyntheticPassword(mKey, (byte) 2, testSp,
                mKeyStoreEncryptionKey);

        RebootEscrowKey key = RebootEscrowKey.fromKeyBytes(expected.getKey().getKeyBytes());
        RebootEscrowData actual = RebootEscrowData.fromEncryptedData(key, expected.getBlob(),
                mKeyStoreEncryptionKey);

        assertThat(actual.getSpVersion(), is(expected.getSpVersion()));
        assertThat(actual.getKey().getKeyBytes(), is(expected.getKey().getKeyBytes()));
        assertThat(actual.getBlob(), is(expected.getBlob()));
        assertThat(actual.getSyntheticPassword(), is(expected.getSyntheticPassword()));
    }

    @Test
    public void aesEncryptedBlob_loopback_success() throws Exception {
        byte[] testSp = getTestSp();
        byte [] encrypted = AesEncryptionUtil.encrypt(mKeyStoreEncryptionKey, testSp);
        byte [] decrypted = AesEncryptionUtil.decrypt(mKeyStoreEncryptionKey, encrypted);

        assertThat(decrypted, is(testSp));
    }

    @Test
    public void fromEncryptedData_legacyVersion_success() throws Exception {
        byte[] testSp = getTestSp();
        byte[] ksEncryptedBlob = AesEncryptionUtil.encrypt(mKey.getKey(), testSp);

        // Write a legacy blob encrypted only by k_s.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(1);
        dos.writeByte(3);
        dos.write(ksEncryptedBlob);
        byte[] legacyBlob = bos.toByteArray();

        RebootEscrowData actual = RebootEscrowData.fromEncryptedData(mKey, legacyBlob, null);

        assertThat(actual.getSpVersion(), is((byte) 3));
        assertThat(actual.getKey().getKeyBytes(), is(mKey.getKeyBytes()));
        assertThat(actual.getSyntheticPassword(), is(testSp));
    }
}
