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

package com.android.server.backup.restore;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.servicestests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.io.InputStream;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class PerformAdbRestoreTaskTest {
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getContext();
    }

    @Test
    public void parseBackupFileAndReturnTarStream_backupNotEncrypted_returnsNonNull()
            throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_no_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);

        assertThat(tarInputStream).isNotNull();
    }

    @Test
    public void
    parseBackupFileAndReturnTarStream_backupEncryptedAndPasswordProvided_returnsNonNull()
            throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_with_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, "123");

        assertThat(tarInputStream).isNotNull();
    }

    @Test
    public void
    parseBackupFileAndReturnTarStream_backupEncryptedAndPasswordNotProvided_returnsNull()
            throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_with_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, null);

        assertThat(tarInputStream).isNull();
    }

    @Test
    public void
    parseBackupFileAndReturnTarStream_backupEncryptedAndIncorrectPassword_returnsNull()
            throws Exception {
        InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.backup_telephony_with_password);
        InputStream tarInputStream = PerformAdbRestoreTask.parseBackupFileHeaderAndReturnTarStream(
                inputStream, "1234");

        assertThat(tarInputStream).isNull();
    }
}
