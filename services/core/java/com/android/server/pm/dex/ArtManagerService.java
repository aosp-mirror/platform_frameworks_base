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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.dex.ArtManager;
import android.content.pm.dex.ArtManager.ProfileType;
import android.content.pm.dex.ArtManagerInternal;
import android.content.pm.dex.ISnapshotRuntimeProfileCallback;
import android.content.pm.dex.PackageOptimizationInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.internal.os.RoSystemProperties;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.art.ArtManagerLocal;
import com.android.server.pm.DexOptHelper;
import com.android.server.pm.Installer;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageManagerServiceCompilerMapping;
import com.android.server.pm.PackageManagerServiceUtils;
import com.android.server.pm.pkg.AndroidPackage;

import dalvik.system.DexFile;
import dalvik.system.VMRuntime;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

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
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Package name used to create the profile directory layout when
    // taking a snapshot of the boot image profile.
    private static final String BOOT_IMAGE_ANDROID_PACKAGE = "android";
    // Profile name used for the boot image profile.
    private static final String BOOT_IMAGE_PROFILE_NAME = "android.prof";

    private final Context mContext;
    private IPackageManager mPackageManager;
    private final Installer mInstaller;

    private final Handler mHandler;

    static {
        verifyTronLoggingConstants();
    }

    public ArtManagerService(Context context, Installer installer,
            Object ignored) {
        mContext = context;
        mInstaller = installer;
        mHandler = new Handler(BackgroundThread.getHandler().getLooper());

        LocalServices.addService(ArtManagerInternal.class, new ArtManagerInternalImpl());
    }

    @NonNull
    private IPackageManager getPackageManager() {
        if (mPackageManager == null) {
            mPackageManager = IPackageManager.Stub.asInterface(
                    ServiceManager.getService("package"));
        }
        return mPackageManager;
    }

    private boolean checkAndroidPermissions(int callingUid, String callingPackage) {
        // Callers always need this permission
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.READ_RUNTIME_PROFILES, TAG);

        // Callers also need the ability to read usage statistics
        switch (mContext.getSystemService(AppOpsManager.class)
                .noteOp(AppOpsManager.OP_GET_USAGE_STATS, callingUid, callingPackage)) {
            case AppOpsManager.MODE_ALLOWED:
                return true;
            case AppOpsManager.MODE_DEFAULT:
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.PACKAGE_USAGE_STATS, TAG);
                return true;
            default:
                return false;
        }
    }

    /**
     * Checks if the calling user is the shell user and if it is, it checks if it can
     * to take a profile snapshot of the give package:
     *   - on debuggable builds the shell user can take profile snapshots of any app.
     *   - on non-debuggable builds the shell user can only take snapshots of debuggable apps.
     *
     * Returns true iff the callingUid is the shell uid and the shell is allowed snapshot profiles.
     *
     * Note that the root users will go through the regular {@link #checkAndroidPermissions) checks.
     */
    private boolean checkShellPermissions(@ProfileType int profileType, String packageName,
            int callingUid) {
        if (callingUid != Process.SHELL_UID) {
            return false;
        }
        if (RoSystemProperties.DEBUGGABLE) {
            return true;
        }
        if (profileType == ArtManager.PROFILE_BOOT_IMAGE) {
            // The shell cannot profile the boot image on non-debuggable builds.
            return false;
        }
        PackageInfo info = null;
        try {
            info =  getPackageManager().getPackageInfo(packageName, /*flags*/ 0, /*userId*/ 0);
        } catch (RemoteException ignored) {
            // Should not happen.
        }
        if (info == null) {
            return false;
        }

        // On user builds the shell can only profile debuggable apps.
        return (info.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE)
                == ApplicationInfo.FLAG_DEBUGGABLE;
    }

    @Override
    public void snapshotRuntimeProfile(@ProfileType int profileType, @Nullable String packageName,
            @Nullable String codePath, @NonNull ISnapshotRuntimeProfileCallback callback,
            String callingPackage) {
        int callingUid = Binder.getCallingUid();
        if (!checkShellPermissions(profileType, packageName, callingUid) &&
                !checkAndroidPermissions(callingUid, callingPackage)) {
            try {
                callback.onError(ArtManager.SNAPSHOT_FAILED_INTERNAL_ERROR);
            } catch (RemoteException ignored) {
            }
            return;
        }

        // Validity checks on the arguments.
        Objects.requireNonNull(callback);

        boolean bootImageProfile = profileType == ArtManager.PROFILE_BOOT_IMAGE;
        if (!bootImageProfile) {
            Preconditions.checkStringNotEmpty(codePath);
            Preconditions.checkStringNotEmpty(packageName);
        }

        // Verify that runtime profiling is enabled.
        if (!isRuntimeProfilingEnabled(profileType, callingPackage)) {
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

    private void snapshotAppProfile(
            String packageName, String codePath, ISnapshotRuntimeProfileCallback callback) {
        PackageInfo info = null;
        try {
            // Note that we use the default user 0 to retrieve the package info.
            // This doesn't really matter because for user 0 we always get a package back (even if
            // it's not installed for the user 0). It is ok because we only care about the code
            // paths and not if the package is enabled or not for the user.

            // TODO(calin): consider adding an API to PMS which can retrieve the
            // PackageParser.Package.
            info =  getPackageManager().getPackageInfo(packageName, /*flags*/ 0, /*userId*/ 0);
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
        ParcelFileDescriptor fd;

        try (PackageManagerLocal.FilteredSnapshot snapshot =
                        PackageManagerServiceUtils.getPackageManagerLocal()
                                .withFilteredSnapshot()) {
            fd = DexOptHelper.getArtManagerLocal().snapshotAppProfile(
                    snapshot, packageName, splitName);
        } catch (IllegalArgumentException e) {
            // ArtManagerLocal.snapshotAppProfile couldn't find the package or split. Since
            // we've checked them above this can only happen due to race, i.e. the package got
            // removed. So let's report it as SNAPSHOT_FAILED_PACKAGE_NOT_FOUND even if it was
            // for the split.
            // TODO(mast): Reuse the same snapshot to avoid this race.
            postError(callback, packageName, ArtManager.SNAPSHOT_FAILED_PACKAGE_NOT_FOUND);
            return;
        } catch (IllegalStateException | ArtManagerLocal.SnapshotProfileException e) {
            postError(callback, packageName, ArtManager.SNAPSHOT_FAILED_INTERNAL_ERROR);
            return;
        }

        postSuccess(packageName, fd, callback);
    }

    @Override
    public boolean isRuntimeProfilingEnabled(@ProfileType int profileType, String callingPackage) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SHELL_UID && !checkAndroidPermissions(callingUid, callingPackage)) {
            return false;
        }

        switch (profileType) {
            case ArtManager.PROFILE_APPS :
                return true;
            case ArtManager.PROFILE_BOOT_IMAGE:
                // The device config property overrides the system property version.
                boolean profileBootClassPath = SystemProperties.getBoolean(
                        "persist.device_config.runtime_native_boot.profilebootclasspath",
                        SystemProperties.getBoolean("dalvik.vm.profilebootclasspath", false));
                return (Build.IS_USERDEBUG || Build.IS_ENG) && profileBootClassPath;
            default:
                throw new IllegalArgumentException("Invalid profile type:" + profileType);
        }
    }

    private void snapshotBootImageProfile(ISnapshotRuntimeProfileCallback callback) {
        ParcelFileDescriptor fd;

        try (PackageManagerLocal.FilteredSnapshot snapshot =
                        PackageManagerServiceUtils.getPackageManagerLocal()
                                .withFilteredSnapshot()) {
            fd = DexOptHelper.getArtManagerLocal().snapshotBootImageProfile(snapshot);
        } catch (IllegalStateException | ArtManagerLocal.SnapshotProfileException e) {
            postError(callback, BOOT_IMAGE_ANDROID_PACKAGE,
                    ArtManager.SNAPSHOT_FAILED_INTERNAL_ERROR);
            return;
        }

        postSuccess(BOOT_IMAGE_ANDROID_PACKAGE, fd, callback);
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
            } catch (RemoteException | RuntimeException e) {
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
                // Double check that the descriptor is still valid.
                // We've seen production issues (b/76028139) where this can turn invalid (there are
                // suspicions around the finalizer behaviour).
                if (fd.getFileDescriptor().valid()) {
                    callback.onSuccess(fd);
                } else {
                    Slog.wtf(TAG, "The snapshot FD became invalid before posting the result for "
                            + packageName);
                    callback.onError(ArtManager.SNAPSHOT_FAILED_INTERNAL_ERROR);
                }
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG,
                        "Failed to call onSuccess after profile snapshot for " + packageName, e);
            } finally {
                IoUtils.closeQuietly(fd);
            }
        });
    }

    // Constants used for logging compilation filter to TRON.
    // DO NOT CHANGE existing values.
    //
    // NOTE: '-1' value is reserved for the case where we cannot produce a valid
    // PackageOptimizationInfo because the ArtManagerInternal is not ready to be used by the
    // ActivityMetricsLoggers.
    private static final int TRON_COMPILATION_FILTER_ERROR = 0;
    private static final int TRON_COMPILATION_FILTER_UNKNOWN = 1;
    private static final int TRON_COMPILATION_FILTER_ASSUMED_VERIFIED = 2;
    private static final int TRON_COMPILATION_FILTER_EXTRACT = 3;
    private static final int TRON_COMPILATION_FILTER_VERIFY = 4;
    private static final int TRON_COMPILATION_FILTER_QUICKEN = 5;
    private static final int TRON_COMPILATION_FILTER_SPACE_PROFILE = 6;
    private static final int TRON_COMPILATION_FILTER_SPACE = 7;
    private static final int TRON_COMPILATION_FILTER_SPEED_PROFILE = 8;
    private static final int TRON_COMPILATION_FILTER_SPEED = 9;
    private static final int TRON_COMPILATION_FILTER_EVERYTHING_PROFILE = 10;
    private static final int TRON_COMPILATION_FILTER_EVERYTHING = 11;
    private static final int TRON_COMPILATION_FILTER_FAKE_RUN_FROM_APK = 12;
    private static final int TRON_COMPILATION_FILTER_FAKE_RUN_FROM_APK_FALLBACK = 13;
    private static final int TRON_COMPILATION_FILTER_FAKE_RUN_FROM_VDEX_FALLBACK = 14;
    // Filter with IORap
    private static final int TRON_COMPILATION_FILTER_ASSUMED_VERIFIED_IORAP = 15;
    private static final int TRON_COMPILATION_FILTER_EXTRACT_IORAP = 16;
    private static final int TRON_COMPILATION_FILTER_VERIFY_IORAP = 17;
    private static final int TRON_COMPILATION_FILTER_QUICKEN_IORAP = 18;
    private static final int TRON_COMPILATION_FILTER_SPACE_PROFILE_IORAP = 19;
    private static final int TRON_COMPILATION_FILTER_SPACE_IORAP = 20;
    private static final int TRON_COMPILATION_FILTER_SPEED_PROFILE_IORAP = 21;
    private static final int TRON_COMPILATION_FILTER_SPEED_IORAP = 22;
    private static final int TRON_COMPILATION_FILTER_EVERYTHING_PROFILE_IORAP = 23;
    private static final int TRON_COMPILATION_FILTER_EVERYTHING_IORAP = 24;
    private static final int TRON_COMPILATION_FILTER_FAKE_RUN_FROM_APK_IORAP = 25;
    private static final int TRON_COMPILATION_FILTER_FAKE_RUN_FROM_APK_FALLBACK_IORAP = 26;
    private static final int TRON_COMPILATION_FILTER_FAKE_RUN_FROM_VDEX_FALLBACK_IORAP = 27;

    // Constants used for logging compilation reason to TRON.
    // DO NOT CHANGE existing values.
    //
    // In the below constants, the abbreviation DM stands for "DEX metadata".
    //
    // NOTE: '-1' value is reserved for the case where we cannot produce a valid
    // PackageOptimizationInfo because the ArtManagerInternal is not ready to be used by the
    // ActivityMetricsLoggers.
    private static final int TRON_COMPILATION_REASON_ERROR = 0;
    private static final int TRON_COMPILATION_REASON_UNKNOWN = 1;
    private static final int TRON_COMPILATION_REASON_FIRST_BOOT = 2;
    private static final int TRON_COMPILATION_REASON_BOOT_DEPRECATED_SINCE_S = 3;
    private static final int TRON_COMPILATION_REASON_INSTALL = 4;
    private static final int TRON_COMPILATION_REASON_BG_DEXOPT = 5;
    private static final int TRON_COMPILATION_REASON_AB_OTA = 6;
    private static final int TRON_COMPILATION_REASON_INACTIVE = 7;
    private static final int TRON_COMPILATION_REASON_SHARED = 8;
    private static final int TRON_COMPILATION_REASON_INSTALL_WITH_DM = 9;
    private static final int TRON_COMPILATION_REASON_INSTALL_FAST = 10;
    private static final int TRON_COMPILATION_REASON_INSTALL_BULK = 11;
    private static final int TRON_COMPILATION_REASON_INSTALL_BULK_SECONDARY = 12;
    private static final int TRON_COMPILATION_REASON_INSTALL_BULK_DOWNGRADED = 13;
    private static final int TRON_COMPILATION_REASON_INSTALL_BULK_SECONDARY_DOWNGRADED = 14;
    private static final int TRON_COMPILATION_REASON_INSTALL_FAST_WITH_DM = 15;
    private static final int TRON_COMPILATION_REASON_INSTALL_BULK_WITH_DM = 16;
    private static final int TRON_COMPILATION_REASON_INSTALL_BULK_SECONDARY_WITH_DM = 17;
    private static final int TRON_COMPILATION_REASON_INSTALL_BULK_DOWNGRADED_WITH_DM = 18;
    private static final int
            TRON_COMPILATION_REASON_INSTALL_BULK_SECONDARY_DOWNGRADED_WITH_DM = 19;
    private static final int TRON_COMPILATION_REASON_BOOT_AFTER_OTA = 20;
    private static final int TRON_COMPILATION_REASON_POST_BOOT = 21;
    private static final int TRON_COMPILATION_REASON_CMDLINE = 22;
    private static final int TRON_COMPILATION_REASON_PREBUILT = 23;
    private static final int TRON_COMPILATION_REASON_VDEX = 24;
    private static final int TRON_COMPILATION_REASON_BOOT_AFTER_MAINLINE_UPDATE = 25;
    private static final int TRON_COMPILATION_REASON_CLOUD = 26;

    // The annotation to add as a suffix to the compilation reason when dexopt was
    // performed with dex metadata.
    public static final String DEXOPT_REASON_WITH_DEX_METADATA_ANNOTATION = "-dm";

    /**
     * Convert the compilation reason to an int suitable to be logged to TRON.
     */
    private static int getCompilationReasonTronValue(String compilationReason) {
        switch (compilationReason) {
            case "cmdline" : return TRON_COMPILATION_REASON_CMDLINE;
            case "error" : return TRON_COMPILATION_REASON_ERROR;
            case "first-boot" : return TRON_COMPILATION_REASON_FIRST_BOOT;
            case "boot-after-ota": return TRON_COMPILATION_REASON_BOOT_AFTER_OTA;
            case "boot-after-mainline-update":
                return TRON_COMPILATION_REASON_BOOT_AFTER_MAINLINE_UPDATE;
            case "post-boot" : return TRON_COMPILATION_REASON_POST_BOOT;
            case "install" : return TRON_COMPILATION_REASON_INSTALL;
            case "bg-dexopt" : return TRON_COMPILATION_REASON_BG_DEXOPT;
            case "ab-ota" : return TRON_COMPILATION_REASON_AB_OTA;
            case "inactive" : return TRON_COMPILATION_REASON_INACTIVE;
            case "shared" : return TRON_COMPILATION_REASON_SHARED;
            case "prebuilt" : return TRON_COMPILATION_REASON_PREBUILT;
            case "vdex" : return TRON_COMPILATION_REASON_VDEX;
            case "install-fast" :
                return TRON_COMPILATION_REASON_INSTALL_FAST;
            case "install-bulk" :
                return TRON_COMPILATION_REASON_INSTALL_BULK;
            case "install-bulk-secondary" :
                return TRON_COMPILATION_REASON_INSTALL_BULK_SECONDARY;
            case "install-bulk-downgraded" :
                return TRON_COMPILATION_REASON_INSTALL_BULK_DOWNGRADED;
            case "install-bulk-secondary-downgraded" :
                return TRON_COMPILATION_REASON_INSTALL_BULK_SECONDARY_DOWNGRADED;
            case "cloud":
                return TRON_COMPILATION_REASON_CLOUD;
            // These are special markers for dex metadata installation that do not
            // have an equivalent as a system property.
            case "install" + DEXOPT_REASON_WITH_DEX_METADATA_ANNOTATION :
                return TRON_COMPILATION_REASON_INSTALL_WITH_DM;
            case "install-fast" + DEXOPT_REASON_WITH_DEX_METADATA_ANNOTATION :
                return TRON_COMPILATION_REASON_INSTALL_FAST_WITH_DM;
            case "install-bulk" + DEXOPT_REASON_WITH_DEX_METADATA_ANNOTATION :
                return TRON_COMPILATION_REASON_INSTALL_BULK_WITH_DM;
            case "install-bulk-secondary" + DEXOPT_REASON_WITH_DEX_METADATA_ANNOTATION :
                return TRON_COMPILATION_REASON_INSTALL_BULK_SECONDARY_WITH_DM;
            case "install-bulk-downgraded" + DEXOPT_REASON_WITH_DEX_METADATA_ANNOTATION :
                return TRON_COMPILATION_REASON_INSTALL_BULK_DOWNGRADED_WITH_DM;
            case "install-bulk-secondary-downgraded" + DEXOPT_REASON_WITH_DEX_METADATA_ANNOTATION :
                return TRON_COMPILATION_REASON_INSTALL_BULK_SECONDARY_DOWNGRADED_WITH_DM;
            default: return TRON_COMPILATION_REASON_UNKNOWN;
        }
    }

    /**
     * Convert the compilation filter to an int suitable to be logged to TRON.
     */
    private static int getCompilationFilterTronValue(String compilationFilter) {
        switch (compilationFilter) {
            case "error" : return TRON_COMPILATION_FILTER_ERROR;
            case "unknown" : return TRON_COMPILATION_FILTER_UNKNOWN;
            case "assume-verified" : return TRON_COMPILATION_FILTER_ASSUMED_VERIFIED;
            case "extract" : return TRON_COMPILATION_FILTER_EXTRACT;
            case "verify" : return TRON_COMPILATION_FILTER_VERIFY;
            case "quicken" : return TRON_COMPILATION_FILTER_QUICKEN;
            case "space-profile" : return TRON_COMPILATION_FILTER_SPACE_PROFILE;
            case "space" : return TRON_COMPILATION_FILTER_SPACE;
            case "speed-profile" : return TRON_COMPILATION_FILTER_SPEED_PROFILE;
            case "speed" : return TRON_COMPILATION_FILTER_SPEED;
            case "everything-profile" : return TRON_COMPILATION_FILTER_EVERYTHING_PROFILE;
            case "everything" : return TRON_COMPILATION_FILTER_EVERYTHING;
            case "run-from-apk" : return TRON_COMPILATION_FILTER_FAKE_RUN_FROM_APK;
            case "run-from-apk-fallback" :
                return TRON_COMPILATION_FILTER_FAKE_RUN_FROM_APK_FALLBACK;
            case "run-from-vdex-fallback" :
                return TRON_COMPILATION_FILTER_FAKE_RUN_FROM_VDEX_FALLBACK;
            case "assume-verified-iorap" : return TRON_COMPILATION_FILTER_ASSUMED_VERIFIED_IORAP;
            case "extract-iorap" : return TRON_COMPILATION_FILTER_EXTRACT_IORAP;
            case "verify-iorap" : return TRON_COMPILATION_FILTER_VERIFY_IORAP;
            case "quicken-iorap" : return TRON_COMPILATION_FILTER_QUICKEN_IORAP;
            case "space-profile-iorap" : return TRON_COMPILATION_FILTER_SPACE_PROFILE_IORAP;
            case "space-iorap" : return TRON_COMPILATION_FILTER_SPACE_IORAP;
            case "speed-profile-iorap" : return TRON_COMPILATION_FILTER_SPEED_PROFILE_IORAP;
            case "speed-iorap" : return TRON_COMPILATION_FILTER_SPEED_IORAP;
            case "everything-profile-iorap" :
                return TRON_COMPILATION_FILTER_EVERYTHING_PROFILE_IORAP;
            case "everything-iorap" : return TRON_COMPILATION_FILTER_EVERYTHING_IORAP;
            case "run-from-apk-iorap" : return TRON_COMPILATION_FILTER_FAKE_RUN_FROM_APK_IORAP;
            case "run-from-apk-fallback-iorap" :
                return TRON_COMPILATION_FILTER_FAKE_RUN_FROM_APK_FALLBACK_IORAP;
            case "run-from-vdex-fallback-iorap" :
                return TRON_COMPILATION_FILTER_FAKE_RUN_FROM_VDEX_FALLBACK_IORAP;
            default: return TRON_COMPILATION_FILTER_UNKNOWN;
        }
    }

    private static void verifyTronLoggingConstants() {
        for (int i = 0; i < PackageManagerServiceCompilerMapping.REASON_STRINGS.length; i++) {
            String reason = PackageManagerServiceCompilerMapping.REASON_STRINGS[i];
            int value = getCompilationReasonTronValue(reason);
            if (value == TRON_COMPILATION_REASON_ERROR
                    || value == TRON_COMPILATION_REASON_UNKNOWN) {
                throw new IllegalArgumentException("Compilation reason not configured for TRON "
                        + "logging: " + reason);
            }
        }
    }

    private class ArtManagerInternalImpl extends ArtManagerInternal {
        private static final String IORAP_DIR = "/data/misc/iorapd";
        private static final String TAG = "ArtManagerInternalImpl";

        @Override
        public PackageOptimizationInfo getPackageOptimizationInfo(
                ApplicationInfo info, String abi, String activityName) {
            if (info.packageName.equals(PackageManagerService.PLATFORM_PACKAGE_NAME)) {
                // PackageManagerService.PLATFORM_PACKAGE_NAME in this context means that the
                // activity is defined in bootclasspath. Currently, we don't have an API to get the
                // correct optimization info.
                return PackageOptimizationInfo.createWithNoInfo();
            }

            String compilationReason;
            String compilationFilter;
            try {
                String isa = VMRuntime.getInstructionSet(abi);
                DexFile.OptimizationInfo optInfo =
                        DexFile.getDexFileOptimizationInfo(info.getBaseCodePath(), isa);
                compilationFilter = optInfo.getStatus();
                compilationReason = optInfo.getReason();
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

            if (checkIorapCompiledTrace(info.packageName, activityName, info.longVersionCode)) {
                compilationFilter = compilationFilter + "-iorap";
            }

            int compilationFilterTronValue = getCompilationFilterTronValue(compilationFilter);
            int compilationReasonTronValue = getCompilationReasonTronValue(compilationReason);

            return new PackageOptimizationInfo(
                    compilationFilterTronValue, compilationReasonTronValue);
        }

        /*
         * Checks the existence of IORap compiled trace for an app.
         *
         * @return true if the compiled trace exists and the size is greater than 1kb.
         */
        private boolean checkIorapCompiledTrace(
                String packageName, String activityName, long version) {
            // For example: /data/misc/iorapd/com.google.android.GoogleCamera/
            // 60092239/com.android.camera.CameraLauncher/compiled_traces/compiled_trace.pb
            // TODO(b/258223472): Clean up iorap code.
            Path tracePath = Paths.get(IORAP_DIR,
                                       packageName,
                                       Long.toString(version),
                                       activityName,
                                       "compiled_traces",
                                       "compiled_trace.pb");
            try {
                boolean exists =  Files.exists(tracePath);
                if (DEBUG) {
                    Log.d(TAG, tracePath.toString() + (exists? " exists" : " doesn't exist"));
                }
                if (exists) {
                    long bytes = Files.size(tracePath);
                    if (DEBUG) {
                        Log.d(TAG, tracePath.toString() + " size is " + Long.toString(bytes));
                    }
                    return bytes > 0L;
                }
                return exists;
            } catch (IOException e) {
                Log.d(TAG, e.getMessage());
                return false;
            }
        }
    }
}
