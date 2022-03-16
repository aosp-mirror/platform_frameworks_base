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

package com.android.server.pm;

import static android.content.pm.PackageManager.CERT_INPUT_SHA256;

import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManagerNative;
import android.content.pm.IStagedApexObserver;
import android.content.pm.PackageInfo;
import android.content.pm.StagedApexInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import java.util.Arrays;

final class PackageManagerNative extends IPackageManagerNative.Stub {
    private final PackageManagerService mPm;

    PackageManagerNative(PackageManagerService pm) {
        mPm = pm;
    }

    @Override
    public String[] getNamesForUids(int[] uids) throws RemoteException {
        String[] names = null;
        String[] results = null;
        try {
            if (uids == null || uids.length == 0) {
                return null;
            }
            names = mPm.snapshotComputer().getNamesForUids(uids);
            results = (names != null) ? names : new String[uids.length];
            // massage results so they can be parsed by the native binder
            for (int i = results.length - 1; i >= 0; --i) {
                if (results[i] == null) {
                    results[i] = "";
                }
            }
            return results;
        } catch (Throwable t) {
            // STOPSHIP(186558987): revert addition of try/catch/log
            Slog.e(TAG, "uids: " + Arrays.toString(uids));
            Slog.e(TAG, "names: " + Arrays.toString(names));
            Slog.e(TAG, "results: " + Arrays.toString(results));
            Slog.e(TAG, "throwing exception", t);
            throw t;
        }
    }

    // NB: this differentiates between preloads and sideloads
    @Override
    public String getInstallerForPackage(String packageName) throws RemoteException {
        final Computer snapshot = mPm.snapshotComputer();
        final String installerName = snapshot.getInstallerPackageName(packageName);
        if (!TextUtils.isEmpty(installerName)) {
            return installerName;
        }
        // differentiate between preload and sideload
        int callingUser = UserHandle.getUserId(Binder.getCallingUid());
        ApplicationInfo appInfo = snapshot.getApplicationInfo(packageName,
                /*flags*/ 0,
                /*userId*/ callingUser);
        if (appInfo != null && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return "preload";
        }
        return "";
    }

    @Override
    public long getVersionCodeForPackage(String packageName) throws RemoteException {
        try {
            int callingUser = UserHandle.getUserId(Binder.getCallingUid());
            PackageInfo pInfo = mPm.snapshotComputer()
                    .getPackageInfo(packageName, 0, callingUser);
            if (pInfo != null) {
                return pInfo.getLongVersionCode();
            }
        } catch (Exception e) {
        }
        return 0;
    }

    @Override
    public int getTargetSdkVersionForPackage(String packageName) throws RemoteException {
        int targetSdk = mPm.snapshotComputer().getTargetSdkVersion(packageName);
        if (targetSdk != -1) {
            return targetSdk;
        }

        throw new RemoteException("Couldn't get targetSdkVersion for package " + packageName);
    }

    @Override
    public boolean isPackageDebuggable(String packageName) throws RemoteException {
        int callingUser = UserHandle.getCallingUserId();
        ApplicationInfo appInfo = mPm.snapshotComputer()
                .getApplicationInfo(packageName, 0, callingUser);
        if (appInfo != null) {
            return (0 != (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE));
        }

        throw new RemoteException("Couldn't get debug flag for package " + packageName);
    }

    @Override
    public boolean[] isAudioPlaybackCaptureAllowed(String[] packageNames)
            throws RemoteException {
        int callingUser = UserHandle.getUserId(Binder.getCallingUid());
        final Computer snapshot = mPm.snapshotComputer();
        boolean[] results = new boolean[packageNames.length];
        for (int i = results.length - 1; i >= 0; --i) {
            ApplicationInfo appInfo = snapshot.getApplicationInfo(packageNames[i], 0, callingUser);
            results[i] = appInfo != null && appInfo.isAudioPlaybackCaptureAllowed();
        }
        return results;
    }

    @Override
    public int getLocationFlags(String packageName) throws RemoteException {
        int callingUser = UserHandle.getUserId(Binder.getCallingUid());
        ApplicationInfo appInfo = mPm.snapshotComputer().getApplicationInfo(packageName,
                /*flags*/ 0,
                /*userId*/ callingUser);
        if (appInfo == null) {
            throw new RemoteException(
                    "Couldn't get ApplicationInfo for package " + packageName);
        }
        return ((appInfo.isSystemApp() ? IPackageManagerNative.LOCATION_SYSTEM : 0)
                | (appInfo.isVendor() ? IPackageManagerNative.LOCATION_VENDOR : 0)
                | (appInfo.isProduct() ? IPackageManagerNative.LOCATION_PRODUCT : 0));
    }

    @Override
    public String getModuleMetadataPackageName() throws RemoteException {
        return mPm.getModuleMetadataPackageName();
    }

    @Override
    public boolean hasSha256SigningCertificate(String packageName, byte[] certificate)
            throws RemoteException {
        return mPm.snapshotComputer()
                .hasSigningCertificate(packageName, certificate, CERT_INPUT_SHA256);
    }

    @Override
    public boolean hasSystemFeature(String featureName, int version) {
        return mPm.hasSystemFeature(featureName, version);
    }

    @Override
    public void registerStagedApexObserver(IStagedApexObserver observer) {
        mPm.mInstallerService.getStagingManager().registerStagedApexObserver(observer);
    }

    @Override
    public void unregisterStagedApexObserver(IStagedApexObserver observer) {
        mPm.mInstallerService.getStagingManager().unregisterStagedApexObserver(observer);
    }

    @Override
    public String[] getStagedApexModuleNames() {
        return mPm.mInstallerService.getStagingManager()
                .getStagedApexModuleNames().toArray(new String[0]);
    }

    @Override
    @Nullable
    public StagedApexInfo getStagedApexInfo(String moduleName) {
        return mPm.mInstallerService.getStagingManager().getStagedApexInfo(moduleName);
    }
}
