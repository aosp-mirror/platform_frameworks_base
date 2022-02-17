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

import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;
import static com.android.server.backup.UserBackupManagerService.PACKAGE_MANAGER_SENTINEL;
import static com.android.server.backup.UserBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.annotation.Nullable;
import android.app.backup.BackupManager.OperationType;
import android.app.backup.BackupTransport;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.app.compat.CompatChanges;
import android.compat.annotation.Overridable;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.backup.transport.BackupTransportClient;
import com.android.server.backup.transport.TransportConnection;

import com.google.android.collect.Sets;

import java.util.Set;

/**
 * Utility methods wrapping operations on ApplicationInfo and PackageInfo.
 */
public class BackupEligibilityRules {
    private static final boolean DEBUG = false;
    // List of system packages that are eligible for backup in non-system users.
    private static final Set<String> systemPackagesAllowedForAllUsers =
            Sets.newArraySet(PACKAGE_MANAGER_SENTINEL, PLATFORM_PACKAGE_NAME);

    private final PackageManager mPackageManager;
    private final PackageManagerInternal mPackageManagerInternal;
    private final int mUserId;
    @OperationType  private final int mOperationType;

    /**
     * When  this change is enabled, {@code adb backup}  is automatically turned on for apps
     * running as debuggable ({@code android:debuggable} set to {@code true}) and unavailable to
     * any other apps.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    static final long RESTRICT_ADB_BACKUP = 171032338L;

    /**
     * When  this change is enabled, {@code android:allowBackup}  is ignored for apps during D2D
     * (device-to-device) migrations.
     */
    @ChangeId
    @Overridable
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    static final long IGNORE_ALLOW_BACKUP_IN_D2D = 183147249L;

    public static BackupEligibilityRules forBackup(PackageManager packageManager,
            PackageManagerInternal packageManagerInternal,
            int userId) {
        return new BackupEligibilityRules(packageManager, packageManagerInternal, userId,
                OperationType.BACKUP);
    }

    public BackupEligibilityRules(PackageManager packageManager,
            PackageManagerInternal packageManagerInternal,
            int userId,
            @OperationType int operationType) {
        mPackageManager = packageManager;
        mPackageManagerInternal = packageManagerInternal;
        mUserId = userId;
        mOperationType = operationType;
    }

    /**
     * Returns whether app is eligible for backup.
     *
     * High level policy: apps are generally ineligible for backup if certain conditions apply. The
     * conditions are:
     *
     * <ol>
     *     <li>their manifest states android:allowBackup="false"
     *     <li>they run as a system-level uid but do not supply their own backup agent
     *     <li>it is the special shared-storage backup package used for 'adb backup'
     * </ol>
     *
     * However, the above eligibility rules are ignored for non-system apps in in case of
     * device-to-device migration, see {@link OperationType}.
     */
    @VisibleForTesting
    public boolean appIsEligibleForBackup(ApplicationInfo app) {
        // 1. their manifest states android:allowBackup="false" and this is not a device-to-device
        // migration
        if (!isAppBackupAllowed(app)) {
            return false;
        }

        // 2. they run as a system-level uid
        if (UserHandle.isCore(app.uid)) {
            // and the backup is happening for a non-system user on a package that is not explicitly
            // allowed.
            if (mUserId != UserHandle.USER_SYSTEM
                    && !systemPackagesAllowedForAllUsers.contains(app.packageName)) {
                return false;
            }

            // or do not supply their own backup agent
            if (app.backupAgentName == null) {
                return false;
            }
        }

        // 3. it is the special shared-storage backup package used for 'adb backup'
        if (app.packageName.equals(SHARED_BACKUP_AGENT_PACKAGE)) {
            return false;
        }

        // 4. it is an "instant" app
        if (app.isInstantApp()) {
            return false;
        }

        return !appIsDisabled(app);
    }

    /**
    * Check if this app allows backup. Apps can opt out of backup by stating
    * android:allowBackup="false" in their manifest. However, this flag is ignored for non-system
    * apps during device-to-device migrations, see {@link OperationType}.
    *
    * @param app The app under check.
    * @return boolean indicating whether backup is allowed.
    */
    public boolean isAppBackupAllowed(ApplicationInfo app) {
        boolean allowBackup = (app.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0;
        switch (mOperationType) {
            case OperationType.MIGRATION:
                // Backup / restore of all non-system apps is force allowed during
                // device-to-device migration.
                boolean isSystemApp = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean ignoreAllowBackup = !isSystemApp && CompatChanges.isChangeEnabled(
                        IGNORE_ALLOW_BACKUP_IN_D2D, app.packageName, UserHandle.of(mUserId));
                return ignoreAllowBackup || allowBackup;
            case OperationType.ADB_BACKUP:
                String packageName = app.packageName;
                if (packageName == null) {
                    Slog.w(TAG, "Invalid ApplicationInfo object");
                    return false;
                }

                if (!CompatChanges.isChangeEnabled(RESTRICT_ADB_BACKUP, packageName,
                        UserHandle.of(mUserId))) {
                    return allowBackup;
                }

                if (PLATFORM_PACKAGE_NAME.equals(packageName)) {
                    // Always enable adb backup for SystemBackupAgent in "android" package (this is
                    // done to avoid breaking existing integration tests and might change in the
                    // future).
                    return true;
                }

                boolean isPrivileged = (app.flags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
                boolean isDebuggable = (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                if (UserHandle.isCore(app.uid) || isPrivileged) {
                    try {
                        return mPackageManager.getProperty(PackageManager.PROPERTY_ALLOW_ADB_BACKUP,
                                packageName).getBoolean();
                    } catch (PackageManager.NameNotFoundException e) {
                        Slog.w(TAG, "Failed to read allowAdbBackup property for + "
                                + packageName);

                        // This temporarily falls back to the legacy allowBackup flag to
                        // avoid breaking existing users of adb backup. Once they're able to use
                        // the new ALLOW_ADB_BACKUP property, we'll return false here.
                        // TODO(b/176088499): Return false here.
                        return allowBackup;
                    }
                } else {
                    // All other apps can use adb backup only when running in debuggable mode.
                    return isDebuggable;
                }
            case OperationType.BACKUP:
                return allowBackup;
            default:
                Slog.w(TAG, "Unknown operation type:" + mOperationType);
                return false;
        }
    }

    /**
     * Returns whether an app is eligible for backup at runtime. That is, the app has to:
     * <ol>
     *     <li>Return true for {@link #appIsEligibleForBackup(ApplicationInfo, int)}
     *     <li>Return false for {@link #appIsStopped(ApplicationInfo)}
     *     <li>Return false for {@link #appIsDisabled(ApplicationInfo, int)}
     *     <li>Be eligible for the transport via
     *         {@link BackupTransport#isAppEligibleForBackup(PackageInfo, boolean)}
     * </ol>
     */
    public boolean appIsRunningAndEligibleForBackupWithTransport(
            @Nullable TransportConnection transportConnection,
            String packageName) {
        try {
            PackageInfo packageInfo = mPackageManager.getPackageInfoAsUser(packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES, mUserId);
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            if (!appIsEligibleForBackup(applicationInfo)
                    || appIsStopped(applicationInfo)
                    || appIsDisabled(applicationInfo)) {
                return false;
            }
            if (transportConnection != null) {
                try {
                    BackupTransportClient transport =
                            transportConnection.connectOrThrow(
                                    "AppBackupUtils.appIsRunningAndEligibleForBackupWithTransport");
                    return transport.isAppEligibleForBackup(
                            packageInfo, appGetsFullBackup(packageInfo));
                } catch (Exception e) {
                    Slog.e(TAG, "Unable to ask about eligibility: " + e.getMessage());
                }
            }
            // If transport is not present we couldn't tell that the package is not eligible.
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Avoid backups of 'disabled' apps. */
    @VisibleForTesting
    boolean appIsDisabled(
            ApplicationInfo app) {
        int enabledSetting = mPackageManagerInternal.getApplicationEnabledState(app.packageName,
                mUserId);

        switch (enabledSetting) {
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return true;
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                return !app.enabled;
            default:
                return false;
        }
    }

    /**
     * Checks if the app is in a stopped state.  This is not part of the general "eligible for
     * backup?" check because we *do* still need to restore data to apps in this state (e.g.
     * newly-installing ones).
     *
     * <p>Reasons for such state:
     * <ul>
     *     <li>The app has been force-stopped.
     *     <li>The app has been cleared.
     *     <li>The app has just been installed.
     * </ul>
     */
    public boolean appIsStopped(ApplicationInfo app) {
        return ((app.flags & ApplicationInfo.FLAG_STOPPED) != 0);
    }

    /**
     * Returns whether the app can get full backup. Does *not* check overall backup eligibility
     * policy!
     */
    @VisibleForTesting
    public boolean appGetsFullBackup(PackageInfo pkg) {
        if (pkg.applicationInfo.backupAgentName != null) {
            // If it has an agent, it gets full backups only if it says so
            return (pkg.applicationInfo.flags & ApplicationInfo.FLAG_FULL_BACKUP_ONLY) != 0;
        }

        // No agent or fullBackupOnly="true" means we do indeed perform full-data backups for it
        return true;
    }

    /**
     * Returns whether the app is only capable of doing key/value. We say it's not if it allows full
     * backup, and it is otherwise.
     */
    public boolean appIsKeyValueOnly(PackageInfo pkg) {
        return !appGetsFullBackup(pkg);
    }

    /**
     * Returns whether the signatures stored {@param storedSigs}, coming from the source apk, match
     * the signatures of the apk installed on the device, the target apk. If the target resides in
     * the system partition we return true. Otherwise it's considered a match if both conditions
     * hold:
     *
     * <ul>
     *   <li>Source and target have at least one signature each
     *   <li>Target contains all signatures in source, and nothing more
     * </ul>
     *
     * or if both source and target have exactly one signature, and they don't match, we check
     * if the app was ever signed with source signature (i.e. app has rotated key)
     * Note: key rotation is only supported for apps ever signed with one key, and those apps will
     * not be allowed to be signed by more certificates in the future
     *
     * Note that if {@param target} is null we return false.
     */
    public boolean signaturesMatch(Signature[] storedSigs, PackageInfo target) {
        if (target == null || target.packageName == null) {
            return false;
        }

        // If the target resides on the system partition, we allow it to restore
        // data from the like-named package in a restore set even if the signatures
        // do not match.  (Unlike general applications, those flashed to the system
        // partition will be signed with the device's platform certificate, so on
        // different phones the same system app will have different signatures.)
        if ((target.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            if (MORE_DEBUG) {
                Slog.v(TAG, "System app " + target.packageName + " - skipping sig check");
            }
            return true;
        }

        // Don't allow unsigned apps on either end
        if (ArrayUtils.isEmpty(storedSigs)) {
            return false;
        }

        SigningInfo signingInfo = target.signingInfo;
        if (signingInfo == null) {
            Slog.w(TAG, "signingInfo is empty, app was either unsigned or the flag" +
                    " PackageManager#GET_SIGNING_CERTIFICATES was not specified");
            return false;
        }

        if (DEBUG) {
            Slog.v(TAG, "signaturesMatch(): stored=" + storedSigs + " device="
                    + signingInfo.getApkContentsSigners());
        }

        final int nStored = storedSigs.length;
        if (nStored == 1) {
            // if the app is only signed with one sig, it's possible it has rotated its key
            // (the checks with signing history are delegated to PackageManager)
            // TODO(b/73988180): address the case that app has declared restoreAnyVersion and is
            // restoring from higher version to lower after having rotated the key (i.e. higher
            // version has different sig than lower version that we want to restore to)
            return mPackageManagerInternal.isDataRestoreSafe(storedSigs[0], target.packageName);
        } else {
            // the app couldn't have rotated keys, since it was signed with multiple sigs - do
            // a check to see if we find a match for all stored sigs
            // since app hasn't rotated key, we only need to check with its current signers
            Signature[] deviceSigs = signingInfo.getApkContentsSigners();
            int nDevice = deviceSigs.length;

            // ensure that each stored sig matches an on-device sig
            for (int i = 0; i < nStored; i++) {
                boolean match = false;
                for (int j = 0; j < nDevice; j++) {
                    if (storedSigs[i].equals(deviceSigs[j])) {
                        match = true;
                        break;
                    }
                }
                if (!match) {
                    return false;
                }
            }
            // we have found a match for all stored sigs
            return true;
        }
    }

    public int getOperationType() {
        return mOperationType;
    }
}
