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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import android.app.backup.IFullBackupRestoreObserver;
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
public class FullBackupRestoreObserverUtilsTest {
    private static final String PACKAGE_NAME = "some.package";
    @Mock
    private IFullBackupRestoreObserver mFullBackupRestoreObserverMock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void sendStartRestore_observerIsNull_returnsNull() throws Exception {
        IFullBackupRestoreObserver result = FullBackupRestoreObserverUtils.sendStartRestore(null);

        assertThat(result).isNull();
    }

    @Test
    public void sendStartRestore_callsObserver() throws Exception {
        IFullBackupRestoreObserver result = FullBackupRestoreObserverUtils.sendStartRestore(
                mFullBackupRestoreObserverMock);

        assertThat(result).isEqualTo(mFullBackupRestoreObserverMock);
        verify(mFullBackupRestoreObserverMock).onStartRestore();
    }

    @Test
    public void sendStartRestore_observerThrows_returnsNull() throws Exception {
        doThrow(new RemoteException()).when(mFullBackupRestoreObserverMock).onStartRestore();

        IFullBackupRestoreObserver result = FullBackupRestoreObserverUtils.sendStartRestore(
                mFullBackupRestoreObserverMock);

        assertThat(result).isNull();
        verify(mFullBackupRestoreObserverMock).onStartRestore();
    }

    @Test
    public void sendOnRestorePackage_observerIsNull_returnsNull() throws Exception {
        IFullBackupRestoreObserver result = FullBackupRestoreObserverUtils.sendOnRestorePackage(
                null, PACKAGE_NAME);

        assertThat(result).isNull();
    }

    @Test
    public void sendOnRestorePackage_callsObserver() throws Exception {
        IFullBackupRestoreObserver result = FullBackupRestoreObserverUtils.sendOnRestorePackage(
                mFullBackupRestoreObserverMock, PACKAGE_NAME);

        assertThat(result).isEqualTo(mFullBackupRestoreObserverMock);
        verify(mFullBackupRestoreObserverMock).onRestorePackage(PACKAGE_NAME);
    }

    @Test
    public void sendOnRestorePackage_observerThrows_returnsNull() throws Exception {
        doThrow(new RemoteException()).when(mFullBackupRestoreObserverMock).onRestorePackage(
                PACKAGE_NAME);

        IFullBackupRestoreObserver result = FullBackupRestoreObserverUtils.sendOnRestorePackage(
                mFullBackupRestoreObserverMock, PACKAGE_NAME);

        assertThat(result).isNull();
        verify(mFullBackupRestoreObserverMock).onRestorePackage(PACKAGE_NAME);
    }

    @Test
    public void sendEndRestore_observerIsNull_returnsNull() throws Exception {
        IFullBackupRestoreObserver result = FullBackupRestoreObserverUtils.sendEndRestore(null);

        assertThat(result).isNull();
    }

    @Test
    public void sendEndRestore_callsObserver() throws Exception {
        IFullBackupRestoreObserver result = FullBackupRestoreObserverUtils.sendEndRestore(
                mFullBackupRestoreObserverMock);

        assertThat(result).isEqualTo(mFullBackupRestoreObserverMock);
        verify(mFullBackupRestoreObserverMock).onEndRestore();
    }

    @Test
    public void sendEndRestore_observerThrows_returnsNull() throws Exception {
        doThrow(new RemoteException()).when(mFullBackupRestoreObserverMock).onEndRestore();

        IFullBackupRestoreObserver result = FullBackupRestoreObserverUtils.sendEndRestore(
                mFullBackupRestoreObserverMock);

        assertThat(result).isNull();
        verify(mFullBackupRestoreObserverMock).onEndRestore();
    }
}