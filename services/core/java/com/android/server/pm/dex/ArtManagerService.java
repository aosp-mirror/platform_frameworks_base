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
import android.annotation.UserIdInt;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.dex.ArtManager;
import android.content.pm.dex.ArtManager.ProfileType;
import android.content.pm.dex.ArtManagerInternal;
import android.content.pm.dex.DexMetadataHelper;
import android.content.pm.dex.PackageOptimizationInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.content.pm.dex.ISnapshotRuntimeProfileCallback;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.system.Os;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

import dalvik.system.DexFile;

import dalvik.system.VMRuntime;
import java.io.File;
import java.io.FileNotFoundException;
import libcore.io.IoUtils;
import libcore.util.NonNull;
import libcore.util.Nullable;

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

    // Package name used to create the profile directory layout when
    // taking a snapshot of the boot image profile.
    private static final String BOOT_IMAGE_ANDROID_PACKAGE = "android";
    // Profile name used for the boot image profile.
    private static final String BOOT_IMAGE_PROFILE_NAME = "android.prof";

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

        LocalServices.addService(ArtManagerInternal.class, new ArtManagerInternalImpl());
    }

    @Override
    public void snapshotRuntimeProfile(@ProfileType int profileType, @Nullable String packageName,
            @Nullable String codePath, @NonNull ISnapshotRuntimeProfileCallback callback) {
        // Sanity checks on the arguments.
        Preconditions.checkNotNull(callback);

        boolean bootImageProfile = profileType == ArtManager.PROFILE_BOOT_IMAGE;
        if (!bootImageProfile) {
            Preconditions.checkStringNotEmpty(codePath);
            Preconditions.checkStringNotEmpty(packageName);
        }

        // Verify that the caller has the right permissions and that the runtime profiling is
        // enabled. The call to isRuntimePermissions will checkReadRuntimeProfilePermission.
        if (!isRuntimeProfilingEnabled(profileType)) {
            throw new IllegalStateException("Runtime profiling is not enabled for " + profileType);
        }

        if (DEBUG) {
            Slog.d(TAG, "Requested snapshot for " + packageName + ":" + codePath);
        }

        if (bootImageProfile) {
            snapshotBootImageProfile(callback);
        } else {
            snapshotAppProfile(packageName, codePath, callback);
        }
    }

    private void snapshotAppProfile(String packageName, String codePath,
            ISnapshotRuntimeProfileCallback callback) {
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
        String splitName = null;
        String[] splitCodePaths = info.applicationInfo.getSplitCodePaths();
        if (!pathFound && (splitCodePaths != null)) {
            for (int i = splitCodePaths.length - 1; i >= 0; i--) {
                if (splitCodePaths[i].equals(codePath)) {
                    pathFound = true;
                    splitName = info.applicationInfo.splitNames[i];
                    break;
                }
            }
        }
        if (!pathFound) {
            postError(callback, packageName, ArtManager.SNAPSHOT_FAILED_CODE_PATH_NOT_FOUND);
            return;
        }

        // All good, create the profile snapshot.
        int appId = UserHandle.getAppId(info.applicationInfo.uid);
        if (appId < 0) {
            postError(callback, packageName, ArtManager.SNAPSHOT_FAILED_INTERNAL_ERROR);
            Slog.wtf(TAG, "AppId is -1 for package: " + packageName);
            return;
        }

        createProfileSnapshot(packageName, ArtManager.getProfileName(splitName), codePath,
                appId, callback);
        // Destroy the snapshot, we no longer need it.
        destroyProfileSnapshot(packageName, ArtManager.getProfileName(splitName));
    }

    private void createProfileSnapshot(String packageName, String profileName, String classpath,
            int appId, ISnapshotRuntimeProfileCallback callback) {
        // Ask the installer to snapshot the profile.
        synchronized (mInstallLock) {
            try {
                if (!mInstaller.createProfileSnapshot(appId, packageName, profileName, classpath)) {
                    postError(callback, packageName, ArtManager.SNAPSHOT_FAILED_INTERNAL_ERROR);
                    return;
                }
            } catch (InstallerException e) {
                postError(callback, packageName, ArtManager.SNAPSHOT_FAILED_INTERNAL_ERROR);
                return;
            }
        }

        // Open the snapshot and invoke the callback.
        File snapshotProfile = ArtManager.getProfileSnapshotFileForName(packageName, profileName);

        ParcelFileDescriptor fd = null;
        try {
            fd = ParcelFileDescriptor.open(snapshotProfile, ParcelFileDescriptor.MODE_READ_ONLY);
            postSuccess(packageName, fd, callback);
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Could not open snapshot profile for " + packageName + ":"
                    + snapshotProfile, e);
            postError(callback, packageName, ArtManager.SNAPSHOT_FAILED_INTERNAL_ERROR);
        } finally {
            IoUtils.closeQuietly(fd);
        }
    }

    private void destroyProfileSnapshot(String packageName, String profileName) {
        if (DEBUG) {
            Slog.d(TAG, "Destroying profile snapshot for" + packageName + ":" + profileName);
        }

        synchronized (mInstallLock) {
            try {
                mInstaller.destroyProfileSnapshot(packageName, profileName);
            } catch (InstallerException e) {
                Slog.e(TAG, "Failed to destroy profile snapshot for " +
                    packageName + ":" + profileName, e);
            }
        }
    }

    @Override
    public boolean isRuntimeProfilingEnabled(@ProfileType int profileType) {
        // Verify that the caller has the right permissions.
        checkReadRuntimeProfilePermission();

        switch (profileType) {
            case ArtManager.PROFILE_APPS :
                return SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false);
            case ArtManager.PROFILE_BOOT_IMAGE:
                return (Build.IS_USERDEBUG || Build.IS_ENG) &&
                        SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false) &&
                        SystemProperties.getBoolean("dalvik.vm.profilebootimage", false);
            default:
                throw new IllegalArgumentException("Invalid profile type:" + profileType);
        }
    }

    private void snapshotBootImageProfile(ISnapshotRuntimeProfileCallback callback) {
        // Combine the profiles for boot classpath and system server classpath.
        // This avoids having yet another type of profiles and simplifies the processing.
        String classpath = String.join(":", Os.getenv("BOOTCLASSPATH"),
                Os.getenv("SYSTEMSERVERCLASSPATH"));

        // Create the snapshot.
        createProfileSnapshot(BOOT_IMAGE_ANDROID_PACKAGE, BOOT_IMAGE_PROFILE_NAME, classpath,
                /*appId*/ -1, callback);
        // Destroy the snapshot, we no longer need it.
        destroyProfileSnapshot(BOOT_IMAGE_ANDROID_PACKAGE, BOOT_IMAGE_PROFILE_NAME);
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

    /**
     * Prepare the application profiles.
     * For all code paths:
     *   - create the current primary profile to save time at app startup time.
     *   - copy the profiles from the associated dex metadata file to the reference profile.
     */
    public void prepareAppProfiles(PackageParser.Package pkg, @UserIdInt int user) {
        final int appId = UserHandle.getAppId(pkg.applicationInfo.uid);
        if (user < 0) {
            Slog.wtf(TAG, "Invalid user id: " + user);
            return;
        }
        if (appId < 0) {
            Slog.wtf(TAG, "Invalid app id: " + appId);
            return;
        }
        try {
            ArrayMap<String, String> codePathsProfileNames = getPackageProfileNames(pkg);
            for (int i = codePathsProfileNames.size() - 1; i >= 0; i--) {
                String codePath = codePathsProfileNames.keyAt(i);
                String profileName = codePathsProfileNames.valueAt(i);
                File dexMetadata = DexMetadataHelper.findDexMetadataForFile(new File(codePath));
                String dexMetadataPath = dexMetadata == null ? null : dexMetadata.getAbsolutePath();
                synchronized (mInstaller) {
                    boolean result = mInstaller.prepareAppProfile(pkg.packageName, user, appId,
                            profileName, codePath, dexMetadataPath);
                    if (!result) {
                        Slog.e(TAG, "Failed to prepare profile for " +
                                pkg.packageName + ":" + codePath);
                    }
                }
            }
        } catch (InstallerException e) {
            Slog.e(TAG, "Failed to prepare profile for " + pkg.packageName, e);
        }
    }

    /**
     * Prepares the app profiles for a set of users. {@see ArtManagerService#prepareAppProfiles}.
     */
    public void prepareAppProfiles(PackageParser.Package pkg, int[] user) {
        for (int i = 0; i < user.length; i++) {
            prepareAppProfiles(pkg, user[i]);
        }
    }

    /**
     * Clear the profiles for the given package.
     */
    public void clearAppProfiles(PackageParser.Package pkg) {
        try {
            ArrayMap<String, String> packageProfileNames = getPackageProfileNames(pkg);
            for (int i = packageProfileNames.size() - 1; i >= 0; i--) {
                String profileName = packageProfileNames.valueAt(i);
                mInstaller.clearAppProfiles(pkg.packageName, profileName);
            }
        } catch (InstallerException e) {
            Slog.w(TAG, String.valueOf(e));
        }
    }

    /**
     * Dumps the profiles for the given package.
     */
    public void dumpProfiles(PackageParser.Package pkg) {
        final int sharedGid = UserHandle.getSharedAppGid(pkg.applicationInfo.uid);
        try {
            ArrayMap<String, String> packageProfileNames = getPackageProfileNames(pkg);
            for (int i = packageProfileNames.size() - 1; i >= 0; i--) {
                String codePath = packageProfileNames.keyAt(i);
                String profileName = packageProfileNames.valueAt(i);
                synchronized (mInstallLock) {
                    mInstaller.dumpProfiles(sharedGid, pkg.packageName, profileName, codePath);
                }
            }
        } catch (InstallerException e) {
            Slog.w(TAG, "Failed to dump profiles", e);
        }
    }

    /**
     * Build the profiles names for all the package code paths (excluding resource only paths).
     * Return the map [code path -> profile name].
     */
    private ArrayMap<String, String> getPackageProfileNames(PackageParser.Package pkg) {
        ArrayMap<String, String> result = new ArrayMap<>();
        if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) {
            result.put(pkg.baseCodePath, ArtManager.getProfileName(null));
        }
        if (!ArrayUtils.isEmpty(pkg.splitCodePaths)) {
            for (int i = 0; i < pkg.splitCodePaths.length; i++) {
                if ((pkg.splitFlags[i] & ApplicationInfo.FLAG_HAS_CODE) != 0) {
                    result.put(pkg.splitCodePaths[i], ArtManager.getProfileName(pkg.splitNames[i]));
                }
            }
        }
        return result;
    }

    private class ArtManagerInternalImpl extends ArtManagerInternal {
        @Override
        public PackageOptimizationInfo getPackageOptimizationInfo(
                ApplicationInfo info, String abi) {
            String compilationReason;
            String compilationFilter;
            try {
                String isa = VMRuntime.getInstructionSet(abi);
                String[] stats = DexFile.getDexFileOptimizationStatus(info.getBaseCodePath(), isa);
                compilationFilter = stats[0];
                compilationReason = stats[1];
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "Could not get optimizations status for " + info.getBaseCodePath(), e);
                compilationFilter = "error";
                compilationReason = "error";
            } catch (IllegalArgumentException e) {
                Slog.wtf(TAG, "Requested optimization status for " + info.getBaseCodePath()
                        + " due to an invalid abi " + abi, e);
                compilationFilter = "error";
                compilationReason = "error";
            }

            return new PackageOptimizationInfo(compilationFilter, compilationReason);
        }
    }
}
