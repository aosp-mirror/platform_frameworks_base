/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.utils;

import static com.google.common.truth.Truth.assertThat;

import android.app.backup.IBackupManagerMonitor;
import android.content.Context;
import android.content.pm.Signature;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.frameworks.servicestests.R;
import com.android.server.backup.FileMetadata;
import com.android.server.backup.restore.PerformAdbRestoreTask;

import com.google.common.hash.Hashing;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.InputStream;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TarBackupReaderTest {
    private static final String TELEPHONY_PACKAGE_NAME = "com.android.providers.telephony";
    private static final String TELEPHONY_PACKAGE_SIGNATURE_SHA256 =
            "301aa3cb081134501c45f1422abc66c24224fd5ded5fdc8f17e697176fd866aa";
    private static final int TELEPHONY_PACKAGE_VERSION = 25;

    @Mock
    private BytesReadListener mBytesReadListenerMock;
    @Mock
    private IBackupManagerMonitor mBackupManagerMonitorMock;
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getContext();
    }

    @Test
    public void readTarHeaders_backupEncrypted_correctlyParsesFileMetadata() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_with_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, "123");
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        FileMetadata fileMetadata = tarBackupReader.readTarHeaders();

        assertThat(fileMetadata.packageName).isEqualTo(TELEPHONY_PACKAGE_NAME);
        assertThat(fileMetadata.mode).isEqualTo(0600);
        assertThat(fileMetadata.size).isEqualTo(2438);
        assertThat(fileMetadata.domain).isEqualTo(null);
        assertThat(fileMetadata.path).isEqualTo("_manifest");
    }

    @Test
    public void readTarHeaders_backupNotEncrypted_correctlyParsesFileMetadata() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        FileMetadata fileMetadata = tarBackupReader.readTarHeaders();

        assertThat(fileMetadata.packageName).isEqualTo(TELEPHONY_PACKAGE_NAME);
        assertThat(fileMetadata.mode).isEqualTo(0600);
        assertThat(fileMetadata.size).isEqualTo(2438);
        assertThat(fileMetadata.domain).isEqualTo(null);
        assertThat(fileMetadata.path).isEqualTo("_manifest");
    }

    @Test
    public void readAppManifest_backupEncrypted_correctlyParsesAppManifest() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_with_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, "123");
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        FileMetadata fileMetadata = tarBackupReader.readTarHeaders();

        Signature[] signatures = tarBackupReader.readAppManifestAndReturnSignatures(fileMetadata);

        assertThat(fileMetadata.version).isEqualTo(TELEPHONY_PACKAGE_VERSION);
        assertThat(fileMetadata.hasApk).isFalse();
        assertThat(signatures).isNotNull();
        assertThat(signatures).hasLength(1);

        String signatureSha256 = Hashing.sha256().hashBytes(signatures[0].toByteArray()).toString();
        assertThat(signatureSha256).isEqualTo(TELEPHONY_PACKAGE_SIGNATURE_SHA256);
    }

    @Test
    public void readAppManifest_backupNotEncrypted_correctlyParsesAppManifest() throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);
        TarBackupReader tarBackupReader = new TarBackupReader(tarInputStream,
                mBytesReadListenerMock, mBackupManagerMonitorMock);
        FileMetadata fileMetadata = tarBackupReader.readTarHeaders();

        Signature[] signatures = tarBackupReader.readAppManifestAndReturnSignatures(fileMetadata);

        assertThat(fileMetadata.version).isEqualTo(TELEPHONY_PACKAGE_VERSION);
        assertThat(fileMetadata.hasApk).isFalse();
        assertThat(signatures).isNotNull();
        assertThat(signatures).hasLength(1);

        String signatureSha256 = Hashing.sha256().hashBytes(signatures[0].toByteArray()).toString();
        assertThat(signatureSha256).isEqualTo(TELEPHONY_PACKAGE_SIGNATURE_SHA256);
    }
}