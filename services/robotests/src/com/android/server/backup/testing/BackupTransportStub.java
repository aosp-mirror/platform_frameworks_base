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

package com.android.server.backup.testing;

import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.android.internal.backup.IBackupTransport;

/**
 * Stub backup transport, doing nothing and returning default values.
 */
public class BackupTransportStub implements IBackupTransport {

    private final String mName;

    public BackupTransportStub(String name) {
        mName = name;
    }

    @Override
    public IBinder asBinder() {
        return null;
    }

    @Override
    public String name() throws RemoteException {
        return mName;
    }

    @Override
    public Intent configurationIntent() throws RemoteException {
        return null;
    }

    @Override
    public String currentDestinationString() throws RemoteException {
        return null;
    }

    @Override
    public Intent dataManagementIntent() throws RemoteException {
        return null;
    }

    @Override
    public String dataManagementLabel() throws RemoteException {
        return null;
    }

    @Override
    public String transportDirName() throws RemoteException {
        return null;
    }

    @Override
    public long requestBackupTime() throws RemoteException {
        return 0;
    }

    @Override
    public int initializeDevice() throws RemoteException {
        return 0;
    }

    @Override
    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor inFd, int flags)
            throws RemoteException {
        return 0;
    }

    @Override
    public int clearBackupData(PackageInfo packageInfo) throws RemoteException {
        return 0;
    }

    @Override
    public int finishBackup() throws RemoteException {
        return 0;
    }

    @Override
    public RestoreSet[] getAvailableRestoreSets() throws RemoteException {
        return new RestoreSet[0];
    }

    @Override
    public long getCurrentRestoreSet() throws RemoteException {
        return 0;
    }

    @Override
    public int startRestore(long token, PackageInfo[] packages) throws RemoteException {
        return 0;
    }

    @Override
    public RestoreDescription nextRestorePackage() throws RemoteException {
        return null;
    }

    @Override
    public int getRestoreData(ParcelFileDescriptor outFd) throws RemoteException {
        return 0;
    }

    @Override
    public void finishRestore() throws RemoteException {

    }

    @Override
    public long requestFullBackupTime() throws RemoteException {
        return 0;
    }

    @Override
    public int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor socket,
            int flags)
            throws RemoteException {
        return 0;
    }

    @Override
    public int checkFullBackupSize(long size) throws RemoteException {
        return 0;
    }

    @Override
    public int sendBackupData(int numBytes) throws RemoteException {
        return 0;
    }

    @Override
    public void cancelFullBackup() throws RemoteException {

    }

    @Override
    public boolean isAppEligibleForBackup(PackageInfo targetPackage, boolean isFullBackup)
            throws RemoteException {
        return false;
    }

    @Override
    public long getBackupQuota(String packageName, boolean isFullBackup)
            throws RemoteException {
        return 0;
    }

    @Override
    public int getNextFullRestoreDataChunk(ParcelFileDescriptor socket) throws RemoteException {
        return 0;
    }

    @Override
    public int abortFullRestore() throws RemoteException {
        return 0;
    }
}
