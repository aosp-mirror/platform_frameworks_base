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

package com.android.server.backup.transport;

import static junit.framework.Assert.assertEquals;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.backup.IBackupTransport;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class DelegatingTransportTest {
    @Mock private IBackupTransport mBackupTransport;
    @Mock private PackageInfo mPackageInfo;
    @Mock private ParcelFileDescriptor mFd;

    private final String mPackageName = "testpackage";
    private final RestoreSet mRestoreSet = new RestoreSet();
    private final int mFlags = 1;
    private final long mRestoreToken = 10;
    private final long mSize = 100;
    private final int mNumBytes = 1000;
    private DelegatingTransport mDelegatingTransport;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDelegatingTransport = new DelegatingTransport() {
            @Override
            protected IBackupTransport getDelegate() {
                return mBackupTransport;
            }
        };
    }

    @Test
    public void testName() throws RemoteException {
        String exp = "dummy";
        when(mBackupTransport.name()).thenReturn(exp);

        String ret = mDelegatingTransport.name();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).name();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testConfigurationIntent() throws RemoteException {
        Intent exp = new Intent("dummy");
        when(mBackupTransport.configurationIntent()).thenReturn(exp);

        Intent ret = mDelegatingTransport.configurationIntent();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).configurationIntent();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testCurrentDestinationString() throws RemoteException {
        String exp = "dummy";
        when(mBackupTransport.currentDestinationString()).thenReturn(exp);

        String ret = mDelegatingTransport.currentDestinationString();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).currentDestinationString();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testDataManagementIntent() throws RemoteException {
        Intent exp = new Intent("dummy");
        when(mBackupTransport.dataManagementIntent()).thenReturn(exp);

        Intent ret = mDelegatingTransport.dataManagementIntent();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).dataManagementIntent();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testDataManagementIntentLabel() throws RemoteException {
        String exp = "dummy";
        when(mBackupTransport.dataManagementIntentLabel()).thenReturn(exp);

        CharSequence ret = mDelegatingTransport.dataManagementIntentLabel();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).dataManagementIntentLabel();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testTransportDirName() throws RemoteException {
        String exp = "dummy";
        when(mBackupTransport.transportDirName()).thenReturn(exp);

        String ret = mDelegatingTransport.transportDirName();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).transportDirName();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testRequestBackupTime() throws RemoteException {
        long exp = 1000L;
        when(mBackupTransport.requestBackupTime()).thenReturn(exp);

        long ret = mDelegatingTransport.requestBackupTime();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).requestBackupTime();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testInitializeDevice() throws RemoteException {
        int exp = 1000;
        when(mBackupTransport.initializeDevice()).thenReturn(exp);

        long ret = mDelegatingTransport.initializeDevice();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).initializeDevice();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testPerformBackup() throws RemoteException {
        int exp = 1000;
        when(mBackupTransport.performBackup(mPackageInfo, mFd, mFlags)).thenReturn(exp);

        int ret = mDelegatingTransport.performBackup(mPackageInfo, mFd, mFlags);

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).performBackup(mPackageInfo, mFd, mFlags);
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testClearBackupData() throws RemoteException {
        int exp = 1000;
        when(mBackupTransport.clearBackupData(mPackageInfo)).thenReturn(exp);

        int ret = mDelegatingTransport.clearBackupData(mPackageInfo);

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).clearBackupData(mPackageInfo);
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testFinishBackup() throws RemoteException {
        int exp = 1000;
        when(mBackupTransport.finishBackup()).thenReturn(exp);

        int ret = mDelegatingTransport.finishBackup();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).finishBackup();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testGetAvailableRestoreSets() throws RemoteException {
        RestoreSet[] exp = new RestoreSet[] {mRestoreSet};
        when(mBackupTransport.getAvailableRestoreSets()).thenReturn(exp);

        RestoreSet[] ret = mDelegatingTransport.getAvailableRestoreSets();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).getAvailableRestoreSets();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testGetCurrentRestoreSet() throws RemoteException {
        long exp = 1000;
        when(mBackupTransport.getCurrentRestoreSet()).thenReturn(exp);

        long ret = mDelegatingTransport.getCurrentRestoreSet();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).getCurrentRestoreSet();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testStartRestore() throws RemoteException {
        int exp = 1000;
        PackageInfo[] packageInfos = {mPackageInfo};
        when(mBackupTransport.startRestore(mRestoreToken, packageInfos)).thenReturn(exp);

        int ret = mDelegatingTransport.startRestore(mRestoreToken, packageInfos);

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).startRestore(mRestoreToken, packageInfos);
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testNextRestorePackage() throws RemoteException {
        RestoreDescription exp = new RestoreDescription(mPackageName, 1);
        when(mBackupTransport.nextRestorePackage()).thenReturn(exp);

        RestoreDescription ret = mDelegatingTransport.nextRestorePackage();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).nextRestorePackage();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testGetRestoreData() throws RemoteException {
        int exp = 1000;
        when(mBackupTransport.getRestoreData(mFd)).thenReturn(exp);

        int ret = mDelegatingTransport.getRestoreData(mFd);

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).getRestoreData(mFd);
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void tesFinishRestore() throws RemoteException {
        mDelegatingTransport.finishRestore();

        verify(mBackupTransport, times(1)).finishRestore();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testRequestFullBackupTime() throws RemoteException {
        long exp = 1000L;
        when(mBackupTransport.requestFullBackupTime()).thenReturn(exp);

        long ret = mDelegatingTransport.requestFullBackupTime();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).requestFullBackupTime();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testPerformFullBackup() throws RemoteException {
        int exp = 1000;
        when(mBackupTransport.performFullBackup(mPackageInfo, mFd, mFlags)).thenReturn(exp);

        int ret = mDelegatingTransport.performFullBackup(mPackageInfo, mFd, mFlags);

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).performFullBackup(mPackageInfo, mFd, mFlags);
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testCheckFullBackupSize() throws RemoteException {
        int exp = 1000;
        when(mBackupTransport.checkFullBackupSize(mSize)).thenReturn(exp);

        int ret = mDelegatingTransport.checkFullBackupSize(mSize);

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).checkFullBackupSize(mSize);
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testSendBackupData() throws RemoteException {
        int exp = 1000;
        when(mBackupTransport.sendBackupData(mNumBytes)).thenReturn(exp);

        int ret = mDelegatingTransport.sendBackupData(mNumBytes);

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).sendBackupData(mNumBytes);
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testCancelFullBackup() throws RemoteException {
        mDelegatingTransport.cancelFullBackup();

        verify(mBackupTransport, times(1)).cancelFullBackup();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testIsAppEligibleForBackup() throws RemoteException {
        boolean exp = true;
        when(mBackupTransport.isAppEligibleForBackup(mPackageInfo, true)).thenReturn(exp);

        boolean ret = mDelegatingTransport.isAppEligibleForBackup(mPackageInfo, true);

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).isAppEligibleForBackup(mPackageInfo, true);
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testGetBackupQuota() throws RemoteException {
        long exp = 1000;
        when(mBackupTransport.getBackupQuota(mPackageName, true)).thenReturn(exp);

        long ret = mDelegatingTransport.getBackupQuota(mPackageName, true);

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).getBackupQuota(mPackageName, true);
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testGetNextFullRestoreDataChunk() throws RemoteException {
        int exp = 1000;
        when(mBackupTransport.getNextFullRestoreDataChunk(mFd)).thenReturn(exp);

        int ret = mDelegatingTransport.getNextFullRestoreDataChunk(mFd);

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).getNextFullRestoreDataChunk(mFd);
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testAbortFullRestore() throws RemoteException {
        int exp = 1000;
        when(mBackupTransport.abortFullRestore()).thenReturn(exp);

        int ret = mDelegatingTransport.abortFullRestore();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).abortFullRestore();
        verifyNoMoreInteractions(mBackupTransport);
    }

    @Test
    public void testGetTransportFlags() throws RemoteException {
        int exp = 1000;
        when(mBackupTransport.getTransportFlags()).thenReturn(exp);

        int ret = mDelegatingTransport.getTransportFlags();

        assertEquals(exp, ret);
        verify(mBackupTransport, times(1)).getTransportFlags();
        verifyNoMoreInteractions(mBackupTransport);
    }
}
