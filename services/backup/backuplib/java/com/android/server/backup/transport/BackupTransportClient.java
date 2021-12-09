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

package com.android.server.backup.transport;

import android.annotation.Nullable;
import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.android.internal.backup.IBackupTransport;

/**
 * Client to {@link com.android.internal.backup.IBackupTransport}. Manages the call to the remote
 * transport service and delivers the results.
 */
public class BackupTransportClient {
    private final IBackupTransport mTransportBinder;

    BackupTransportClient(IBackupTransport transportBinder) {
        mTransportBinder = transportBinder;

        // This is a temporary fix to allow blocking calls.
        // TODO: b/147702043. Redesign IBackupTransport so as to make the calls non-blocking.
        Binder.allowBlocking(mTransportBinder.asBinder());
    }

    /**
     * See {@link IBackupTransport#name()}.
     */
    public String name() throws RemoteException {
        return mTransportBinder.name();
    }

    /**
     * See {@link IBackupTransport#configurationIntent()}
     */
    public Intent configurationIntent() throws RemoteException {
        return mTransportBinder.configurationIntent();
    }

    /**
     * See {@link IBackupTransport#currentDestinationString()}
     */
    public String currentDestinationString() throws RemoteException {
        return mTransportBinder.currentDestinationString();
    }

    /**
     * See {@link IBackupTransport#dataManagementIntent()}
     */
    public Intent dataManagementIntent() throws RemoteException {
        return mTransportBinder.dataManagementIntent();
    }

    /**
     * See {@link IBackupTransport#dataManagementIntentLabel()}
     */
    @Nullable
    public CharSequence dataManagementIntentLabel() throws RemoteException {
        return mTransportBinder.dataManagementIntentLabel();
    }

    /**
     * See {@link IBackupTransport#transportDirName()}
     */
    public String transportDirName() throws RemoteException {
        return mTransportBinder.transportDirName();
    }

    /**
     * See {@link IBackupTransport#initializeDevice()}
     */
    public int initializeDevice() throws RemoteException {
        return mTransportBinder.initializeDevice();
    }

    /**
     * See {@link IBackupTransport#clearBackupData(PackageInfo)}
     */
    public int clearBackupData(PackageInfo packageInfo) throws RemoteException {
        return mTransportBinder.clearBackupData(packageInfo);
    }

    /**
     * See {@link IBackupTransport#finishBackup()}
     */
    public int finishBackup() throws RemoteException {
        return mTransportBinder.finishBackup();
    }

    /**
     * See {@link IBackupTransport#requestBackupTime()}
     */
    public long requestBackupTime() throws RemoteException {
        return mTransportBinder.requestBackupTime();
    }

    /**
     * See {@link IBackupTransport#performBackup(PackageInfo, ParcelFileDescriptor, int)}
     */
    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor inFd, int flags)
            throws RemoteException {
        return mTransportBinder.performBackup(packageInfo, inFd, flags);
    }

    /**
     * See {@link IBackupTransport#getAvailableRestoreSets()}
     */
    public RestoreSet[] getAvailableRestoreSets() throws RemoteException {
        return mTransportBinder.getAvailableRestoreSets();
    }

    /**
     * See {@link IBackupTransport#getCurrentRestoreSet()}
     */
    public long getCurrentRestoreSet() throws RemoteException {
        return mTransportBinder.getCurrentRestoreSet();
    }

    /**
     * See {@link IBackupTransport#startRestore(long, PackageInfo[])}
     */
    public int startRestore(long token, PackageInfo[] packages) throws RemoteException {
        return mTransportBinder.startRestore(token, packages);
    }

    /**
     * See {@link IBackupTransport#nextRestorePackage()}
     */
    public RestoreDescription nextRestorePackage() throws RemoteException {
        return mTransportBinder.nextRestorePackage();
    }

    /**
     * See {@link IBackupTransport#getRestoreData(ParcelFileDescriptor)}
     */
    public int getRestoreData(ParcelFileDescriptor outFd) throws RemoteException {
        return mTransportBinder.getRestoreData(outFd);
    }

    /**
     * See {@link IBackupTransport#finishRestore()}
     */
    public void finishRestore() throws RemoteException {
        mTransportBinder.finishRestore();
    }

    /**
     * See {@link IBackupTransport#requestFullBackupTime()}
     */
    public long requestFullBackupTime() throws RemoteException {
        return mTransportBinder.requestFullBackupTime();
    }

    /**
     * See {@link IBackupTransport#performFullBackup(PackageInfo, ParcelFileDescriptor, int)}
     */
    public int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor socket,
            int flags) throws RemoteException {
        return mTransportBinder.performFullBackup(targetPackage, socket, flags);
    }

    /**
     * See {@link IBackupTransport#checkFullBackupSize(long)}
     */
    public int checkFullBackupSize(long size) throws RemoteException {
        return mTransportBinder.checkFullBackupSize(size);
    }

    /**
     * See {@link IBackupTransport#sendBackupData(int)}
     */
    public int sendBackupData(int numBytes) throws RemoteException {
        return mTransportBinder.sendBackupData(numBytes);
    }

    /**
     * See {@link IBackupTransport#cancelFullBackup()}
     */
    public void cancelFullBackup() throws RemoteException {
        mTransportBinder.cancelFullBackup();
    }

    /**
     * See {@link IBackupTransport#isAppEligibleForBackup(PackageInfo, boolean)}
     */
    public boolean isAppEligibleForBackup(PackageInfo targetPackage, boolean isFullBackup)
            throws RemoteException {
        return mTransportBinder.isAppEligibleForBackup(targetPackage, isFullBackup);
    }

    /**
     * See {@link IBackupTransport#getBackupQuota(String, boolean)}
     */
    public long getBackupQuota(String packageName, boolean isFullBackup) throws RemoteException {
        return mTransportBinder.getBackupQuota(packageName, isFullBackup);
    }

    /**
     * See {@link IBackupTransport#getNextFullRestoreDataChunk(ParcelFileDescriptor)}
     */
    public int getNextFullRestoreDataChunk(ParcelFileDescriptor socket) throws RemoteException {
        return mTransportBinder.getNextFullRestoreDataChunk(socket);
    }

    /**
     * See {@link IBackupTransport#abortFullRestore()}
     */
    public int abortFullRestore() throws RemoteException {
        return mTransportBinder.abortFullRestore();
    }

    /**
     * See {@link IBackupTransport#getTransportFlags()}
     */
    public int getTransportFlags() throws RemoteException {
        return mTransportBinder.getTransportFlags();
    }
}
