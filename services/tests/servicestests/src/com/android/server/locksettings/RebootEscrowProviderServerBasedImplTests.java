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

package com.android.server.locksettings;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class RebootEscrowProviderServerBasedImplTests {
    private SecretKey mKeyStoreEncryptionKey;
    private RebootEscrowKey mRebootEscrowKey;
    private ResumeOnRebootServiceProvider.ResumeOnRebootServiceConnection mServiceConnection;
    private LockSettingsStorageTestable mStorage;
    private RebootEscrowProviderServerBasedImpl mRebootEscrowProvider;
    private Answer<byte[]> mFakeEncryption;

    private static final byte[] TEST_AES_KEY = new byte[] {
            0x48, 0x19, 0x12, 0x54, 0x13, 0x13, 0x52, 0x31,
            0x44, 0x74, 0x61, 0x54, 0x29, 0x74, 0x37, 0x61,
            0x70, 0x70, 0x75, 0x25, 0x27, 0x31, 0x49, 0x09,
            0x26, 0x52, 0x72, 0x63, 0x63, 0x61, 0x78, 0x23,
    };

    @Before
    public void setUp() throws Exception {
        mKeyStoreEncryptionKey = new SecretKeySpec(TEST_AES_KEY, "AES");
        mRebootEscrowKey = RebootEscrowKey.generate();
        mServiceConnection = mock(
                ResumeOnRebootServiceProvider.ResumeOnRebootServiceConnection.class);

        Context context = new ContextWrapper(InstrumentationRegistry.getContext());
        mStorage = new LockSettingsStorageTestable(context,
                new File(InstrumentationRegistry.getContext().getFilesDir(), "locksettings"));
        mRebootEscrowProvider = new RebootEscrowProviderServerBasedImpl(mStorage,
                new RebootEscrowProviderServerBasedImpl.Injector(mServiceConnection));

        mFakeEncryption = invocation -> {
            byte[] secret = invocation.getArgument(0);
            for (int i = 0; i < secret.length; i++) {
                secret[i] = (byte) (secret[i] ^ 0xf);
            }
            return secret;
        };
    }

    @Test
    public void getAndClearRebootEscrowKey_loopback_success() throws Exception {
        when(mServiceConnection.wrapBlob(any(), anyLong(), anyLong())).thenAnswer(mFakeEncryption);
        when(mServiceConnection.unwrap(any(), anyLong())).thenAnswer(mFakeEncryption);

        assertTrue(mRebootEscrowProvider.hasRebootEscrowSupport());
        mRebootEscrowProvider.storeRebootEscrowKey(mRebootEscrowKey, mKeyStoreEncryptionKey);
        assertTrue(mStorage.hasRebootEscrowServerBlob());


        RebootEscrowKey ks = mRebootEscrowProvider.getAndClearRebootEscrowKey(
                mKeyStoreEncryptionKey);
        assertThat(ks.getKeyBytes(), is(mRebootEscrowKey.getKeyBytes()));
        assertFalse(mStorage.hasRebootEscrowServerBlob());
    }

    @Test
    public void getAndClearRebootEscrowKey_WrongDecryptionMethod_failure() throws Exception {
        when(mServiceConnection.wrapBlob(any(), anyLong(), anyLong())).thenAnswer(mFakeEncryption);
        when(mServiceConnection.unwrap(any(), anyLong())).thenAnswer(
                invocation -> {
                    byte[] secret = invocation.getArgument(0);
                    for (int i = 0; i < secret.length; i++) {
                        secret[i] = (byte) (secret[i] ^ 0xe);
                    }
                    return secret;
                }
        );

        assertTrue(mRebootEscrowProvider.hasRebootEscrowSupport());
        mRebootEscrowProvider.storeRebootEscrowKey(mRebootEscrowKey, mKeyStoreEncryptionKey);
        assertTrue(mStorage.hasRebootEscrowServerBlob());

        // Expect to get wrong key bytes
        RebootEscrowKey ks = mRebootEscrowProvider.getAndClearRebootEscrowKey(
                mKeyStoreEncryptionKey);
        assertNotEquals(ks.getKeyBytes(), mRebootEscrowKey.getKeyBytes());
        assertFalse(mStorage.hasRebootEscrowServerBlob());
    }

    @Test
    public void getAndClearRebootEscrowKey_ServiceConnectionException_failure() throws Exception {
        when(mServiceConnection.wrapBlob(any(), anyLong(), anyLong())).thenAnswer(mFakeEncryption);
        doThrow(IOException.class).when(mServiceConnection).unwrap(any(), anyLong());

        assertTrue(mRebootEscrowProvider.hasRebootEscrowSupport());
        mRebootEscrowProvider.storeRebootEscrowKey(mRebootEscrowKey, mKeyStoreEncryptionKey);
        assertTrue(mStorage.hasRebootEscrowServerBlob());

        // Expect to get null key bytes when the server service fails to unwrap the blob.
        RebootEscrowKey ks = mRebootEscrowProvider.getAndClearRebootEscrowKey(
                mKeyStoreEncryptionKey);
        assertNull(ks);
        assertFalse(mStorage.hasRebootEscrowServerBlob());
    }
}
