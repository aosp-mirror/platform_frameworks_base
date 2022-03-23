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

package com.android.server.backup.encryption.transport;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.backup.BackupTransport;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.encryption.KeyValueEncrypter;
import com.android.server.backup.transport.TransportClient;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class IntermediateEncryptingTransportTest {
    private static final String TEST_PACKAGE_NAME = "test_package";

    private IntermediateEncryptingTransport mIntermediateEncryptingTransport;
    private final PackageInfo mTestPackage = new PackageInfo();

    @Mock private IBackupTransport mRealTransport;
    @Mock private TransportClient mTransportClient;
    @Mock private ParcelFileDescriptor mParcelFileDescriptor;
    @Mock private KeyValueEncrypter mKeyValueEncrypter;
    @Mock private Context mContext;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    private File mTempFile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mIntermediateEncryptingTransport =
                new IntermediateEncryptingTransport(
                        mTransportClient, mContext, mKeyValueEncrypter, true);
        mTestPackage.packageName = TEST_PACKAGE_NAME;
        mTempFile = mTemporaryFolder.newFile();

        when(mTransportClient.connect(anyString())).thenReturn(mRealTransport);
        when(mRealTransport.getRestoreData(any())).thenReturn(BackupTransport.TRANSPORT_OK);
    }

    @Test
    public void testGetDelegate_callsConnect() throws Exception {
        IBackupTransport ret = mIntermediateEncryptingTransport.getDelegate();

        assertEquals(mRealTransport, ret);
        verify(mTransportClient, times(1)).connect(anyString());
        verifyNoMoreInteractions(mTransportClient);
    }

    @Test
    public void testGetDelegate_callTwice_callsConnectOnce() throws Exception {
        when(mTransportClient.connect(anyString())).thenReturn(mRealTransport);

        IBackupTransport ret1 = mIntermediateEncryptingTransport.getDelegate();
        IBackupTransport ret2 = mIntermediateEncryptingTransport.getDelegate();

        assertEquals(mRealTransport, ret1);
        assertEquals(mRealTransport, ret2);
        verify(mTransportClient, times(1)).connect(anyString());
        verifyNoMoreInteractions(mTransportClient);
    }

    @Test
    public void testPerformBackup_shouldEncryptTrue_encryptsDataAndPassesToDelegate()
            throws Exception {
        mIntermediateEncryptingTransport =
                new TestIntermediateTransport(mTransportClient, mContext, mKeyValueEncrypter, true);

        mIntermediateEncryptingTransport.performBackup(mTestPackage, mParcelFileDescriptor, 0);

        verify(mKeyValueEncrypter, times(1))
                .encryptKeyValueData(eq(TEST_PACKAGE_NAME), eq(mParcelFileDescriptor), any());
        verify(mRealTransport, times(1)).performBackup(eq(mTestPackage), any(), eq(0));
    }

    @Test
    public void testPerformBackup_shouldEncryptFalse_doesntEncryptDataAndPassedToDelegate()
            throws Exception {
        mIntermediateEncryptingTransport =
                new TestIntermediateTransport(
                        mTransportClient, mContext, mKeyValueEncrypter, false);

        mIntermediateEncryptingTransport.performBackup(mTestPackage, mParcelFileDescriptor, 0);

        verifyZeroInteractions(mKeyValueEncrypter);
        verify(mRealTransport, times(1))
                .performBackup(eq(mTestPackage), eq(mParcelFileDescriptor), eq(0));
    }

    @Test
    public void testGetRestoreData_shouldEncryptTrue_decryptsDataAndPassesToDelegate()
            throws Exception {
        mIntermediateEncryptingTransport =
                new TestIntermediateTransport(mTransportClient, mContext, mKeyValueEncrypter, true);
        mIntermediateEncryptingTransport.setNextRestorePackage(TEST_PACKAGE_NAME);

        mIntermediateEncryptingTransport.getRestoreData(mParcelFileDescriptor);

        verify(mKeyValueEncrypter, times(1))
                .decryptKeyValueData(eq(TEST_PACKAGE_NAME), any(), eq(mParcelFileDescriptor));
        verify(mRealTransport, times(1)).getRestoreData(any());
    }

    @Test
    public void testGetRestoreData_shouldEncryptFalse_doesntDecryptDataAndPassesToDelegate()
            throws Exception {
        mIntermediateEncryptingTransport =
                new TestIntermediateTransport(
                        mTransportClient, mContext, mKeyValueEncrypter, false);
        mIntermediateEncryptingTransport.setNextRestorePackage(TEST_PACKAGE_NAME);

        mIntermediateEncryptingTransport.getRestoreData(mParcelFileDescriptor);

        verifyZeroInteractions(mKeyValueEncrypter);
        verify(mRealTransport, times(1)).getRestoreData(eq(mParcelFileDescriptor));
    }

    private final class TestIntermediateTransport extends IntermediateEncryptingTransport {
        TestIntermediateTransport(
                TransportClient transportClient,
                Context context,
                KeyValueEncrypter keyValueEncrypter,
                boolean shouldEncrypt) {
            super(transportClient, context, keyValueEncrypter, shouldEncrypt);
        }

        @Override
        protected File getBackupTempStorage(String packageName) {
            return mTempFile;
        }

        @Override
        protected File getRestoreTempStorage(String packageName) {
            return mTempFile;
        }
    }
}
