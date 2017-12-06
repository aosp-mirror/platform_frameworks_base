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

package com.android.server.pm.dex;

import android.Manifest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.dex.ArtManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.content.pm.IPackageManager;
import android.content.pm.dex.ISnapshotRuntimeProfileCallback;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.Preconditions;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * A system service that provides access to runtime and compiler artifacts.
 *
 * This service is not accessed by users directly, instead one uses an instance of
 * {@link ArtManager}, which can be accessed via {@link PackageManager} as follows:
 * <p/>
 * {@code context().getPackageManager().getArtManager();}
 * <p class="note">
 * Note: Accessing runtime artifacts may require extra permissions. For example querying the
 * runtime profiles of apps requires {@link android.Manifest.permission#READ_RUNTIME_PROFILES}
 * which is a system-level permission that will not be granted to normal apps.
 */
public class ArtManagerService extends android.content.pm.dex.IArtManager.Stub {
    private static final String TAG = "ArtManagerService";

    private static boolean DEBUG = false;
    private static boolean DEBUG_IGNORE_PERMISSIONS = false;

    private final IPackageManager mPackageManager;
    private final Object mInstallLock;
    @GuardedBy("mInstallLock")
    private final Installer mInstaller;

    private final Handler mHandler;

    public ArtManagerService(IPackageManager pm, Installer installer, Object installLock) {
        mPackageManager = pm;
        mInstaller = installer;
        mInstallLock = installLock;
        mHandler = new Handler(BackgroundThread.getHandler().getLooper());
    }

    @Override
    public void snapshotRuntimeProfile(String packageName, String codePath,
            ISnapshotRuntimeProfileCallback callback) {
        // Sanity checks on the arguments.
        Preconditions.checkStringNotEmpty(packageName);
        Preconditions.checkStringNotEmpty(codePath);
        Preconditions.checkNotNull(callback);

        // Verify that the caller has the right permissions.
        checkReadRuntimeProfilePermission();

        if (DEBUG) {
            Slog.d(TAG, "Requested snapshot for " + packageName + ":" + codePath);
        }

        PackageInfo info = null;
        try {
            // Note that we use the default user 0 to retrieve the package info.
            // This doesn't really matter because for user 0 we always get a package back (even if
            // it's not installed for the user 0). It is ok because we only care about the code
            // paths and not if the package is enabled or not for the user.

            // TODO(calin): consider adding an API to PMS which can retrieve the
            // PackageParser.Package.
            info = mPackageManager.getPackageInfo(packageName, /*flags*/ 0, /*userId*/ 0);
        } catch (RemoteException ignored) {
            // Should not happen.
        }
        if (info == null) {
            postError(callback, packageName, ArtManager.SNAPSHOT_FAILED_PACKAGE_NOT_FOUND);
            return;
        }

        boolean pathFound = info.applicationInfo.getBaseCodePath().equals(codePath);
        String[] splitCodePaths = info.applicationInfo.getSplitCodePaths();
        if (!pathFound && (splitCodePaths != null)) {
            for (String path : splitCodePaths) {
                if (path.equals(codePath)) {
                    pathFound = true;
                    break;
                }
            }
        }
        if (!pathFound) {
            postError(callback, packageName, ArtManager.SNAPSHOT_FAILED_CODE_PATH_NOT_FOUND);
            return;
        }

        // All good, create the profile snapshot.
        createProfileSnapshot(packageName, codePath, callback, info);
        // Destroy the snapshot, we no longer need it.
        destroyProfileSnapshot(packageName, codePath);
    }

    private void createProfileSnapshot(String packageName, String codePath,
            ISnapshotRuntimeProfileCallback callback, PackageInfo info) {
        // Ask the installer to snapshot the profile.
        synchronized (mInstallLock) {
            try {
                if (!mInstaller.createProfileSnapshot(UserHandle.getAppId(info.applicationInfo.uid),
                        packageName, codePath)) {
                    postError(callback, packageName, ArtManager.SNAPSHOT_FAILED_INTERNAL_ERROR);
                    return;
                }
            } catch (InstallerException e) {
                postError(callback, packageName, ArtManager.SNAPSHOT_FAILED_INTERNAL_ERROR);
                return;
            }
        }

        // Open the snapshot and invoke the callback.
        File snapshotProfile = Environment.getProfileSnapshotPath(packageName, codePath);
        ParcelFileDescriptor fd;
        try {
            fd = ParcelFileDescriptor.open(snapshotProfile, ParcelFileDescriptor.MODE_READ_ONLY);
            postSuccess(packageName, fd, callback);
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Could not open snapshot profile for " + packageName + ":" + codePath, e);
            postError(callback, packageName, ArtManager.SNAPSHOT_FAILED_INTERNAL_ERROR);
        }
    }

    private void destroyProfileSnapshot(String packageName, String codePath) {
        if (DEBUG) {
            Slog.d(TAG, "Destroying profile snapshot for" + packageName + ":" + codePath);
        }

        synchronized (mInstallLock) {
            try {
                mInstaller.destroyProfileSnapshot(packageName, codePath);
            } catch (InstallerException e) {
                Slog.e(TAG, "Failed to destroy profile snapshot for " +
                    packageName + ":" + codePath, e);
            }
        }
    }

    @Override
    public boolean isRuntimeProfilingEnabled() {
        // Verify that the caller has the right permissions.
        checkReadRuntimeProfilePermission();

        return SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false);
    }

    /**
     * Post {@link ISnapshotRuntimeProfileCallback#onError(int)} with the given error message
     * on the internal {@code mHandler}.
     */
    private void postError(ISnapshotRuntimeProfileCallback callback, String packageName,
            int errCode) {
        if (DEBUG) {
            Slog.d(TAG, "Failed to snapshot profile for " + packageName + " with error: " +
                    errCode);
        }
        mHandler.post(() -> {
            try {
                callback.onError(errCode);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to callback after profile snapshot for " + packageName, e);
            }
        });
    }

    private void postSuccess(String packageName, ParcelFileDescriptor fd,
            ISnapshotRuntimeProfileCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "Successfully snapshot profile for " + packageName);
        }
        mHandler.post(() -> {
            try {
                callback.onSuccess(fd);
            } catch (RemoteException e) {
                Slog.w(TAG,
                        "Failed to call onSuccess after profile snapshot for " + packageName, e);
            }
        });
    }

    /**
     * Verify that the binder calling uid has {@code android.permission.READ_RUNTIME_PROFILE}.
     * If not, it throws a {@link SecurityException}.
     */
    private void checkReadRuntimeProfilePermission() {
        if (DEBUG_IGNORE_PERMISSIONS) {
            return;
        }
        try {
            int result = mPackageManager.checkUidPermission(
                    Manifest.permission.READ_RUNTIME_PROFILES, Binder.getCallingUid());
            if (result != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("You need "
                        + Manifest.permission.READ_RUNTIME_PROFILES
                        + " permission to snapshot profiles.");
            }
        } catch (RemoteException e) {
            // Should not happen.
        }
    }
}
