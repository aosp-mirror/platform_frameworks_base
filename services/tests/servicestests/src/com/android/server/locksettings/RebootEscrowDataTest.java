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

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.GeneralSecurityException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * atest FrameworksServicesTests:RebootEscrowDataTest
 */
@RunWith(AndroidJUnit4.class)
public class RebootEscrowDataTest {
    private RebootEscrowKey mKey;
    private SecretKey mKeyStoreEncryptionKey;

    private SecretKey generateNewRebootEscrowEncryptionKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES);
        generator.init(new KeyGenParameterSpec.Builder(
                "reboot_escrow_data_test_key",
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    @Before
    public void generateKey() throws Exception {
        mKey = RebootEscrowKey.generate();
        mKeyStoreEncryptionKey = generateNewRebootEscrowEncryptionKey();
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

}
