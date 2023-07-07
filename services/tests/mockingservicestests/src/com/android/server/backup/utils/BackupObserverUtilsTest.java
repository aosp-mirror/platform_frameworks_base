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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import android.app.backup.BackupProgress;
import android.app.backup.IBackupObserver;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupObserverUtilsTest {
    private static final String PACKAGE_NAME = "some.package";

    @Mock
    private IBackupObserver mBackupObserverMock;
    private final BackupProgress mBackupProgress = new BackupProgress(0, 0);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void sendBackupOnUpdate_observerIsNull_doesNotThrow() throws Exception {
        BackupObserverUtils.sendBackupOnUpdate(null, PACKAGE_NAME, mBackupProgress);

        // Should not throw.
    }

    @Test
    public void sendBackupOnUpdate_callsObserver() throws Exception {
        BackupObserverUtils.sendBackupOnUpdate(mBackupObserverMock, PACKAGE_NAME, mBackupProgress);

        verify(mBackupObserverMock).onUpdate(PACKAGE_NAME, mBackupProgress);
    }

    @Test
    public void sendBackupOnUpdate_handlesRemoteException() throws Exception {
        doThrow(new RemoteException()).when(mBackupObserverMock).onUpdate(PACKAGE_NAME,
                mBackupProgress);

        BackupObserverUtils.sendBackupOnUpdate(mBackupObserverMock, PACKAGE_NAME, mBackupProgress);

        verify(mBackupObserverMock).onUpdate(PACKAGE_NAME, mBackupProgress);
    }

    @Test
    public void sendBackupOnPackageResult_observerIsNull_doesNotThrow() throws Exception {
        BackupObserverUtils.sendBackupOnPackageResult(null, PACKAGE_NAME, 1);

        // Should not throw.
    }

    @Test
    public void sendBackupOnPackageResult_callsObserver() throws Exception {
        BackupObserverUtils.sendBackupOnPackageResult(mBackupObserverMock, PACKAGE_NAME, 1);

        verify(mBackupObserverMock).onResult(PACKAGE_NAME, 1);
    }

    @Test
    public void sendBackupOnPackageResult_handlesRemoteException() throws Exception {
        doThrow(new RemoteException()).when(mBackupObserverMock).onResult(PACKAGE_NAME, 1);

        BackupObserverUtils.sendBackupOnPackageResult(mBackupObserverMock, PACKAGE_NAME, 1);

        verify(mBackupObserverMock).onResult(PACKAGE_NAME, 1);
    }

    @Test
    public void sendBackupFinished_observerIsNull_doesNotThrow() throws Exception {
        BackupObserverUtils.sendBackupFinished(null, 1);

        // Should not throw.
    }

    @Test
    public void sendBackupFinished_callsObserver() throws Exception {
        BackupObserverUtils.sendBackupFinished(mBackupObserverMock, 1);

        verify(mBackupObserverMock).backupFinished(1);
    }

    @Test
    public void sendBackupFinished_handlesRemoteException() throws Exception {
        doThrow(new RemoteException()).when(mBackupObserverMock).onResult(PACKAGE_NAME, 1);

        BackupObserverUtils.sendBackupFinished(mBackupObserverMock, 1);

        verify(mBackupObserverMock).backupFinished(1);
    }
}