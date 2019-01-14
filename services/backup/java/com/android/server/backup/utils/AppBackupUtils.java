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
import static com.android.server.backup.UserBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;

import android.annotation.Nullable;
import android.app.backup.BackupTransport;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Process;
import android.util.Slog;

import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.ArrayUtils;
import com.android.server.backup.transport.TransportClient;

/**
 * Utility methods wrapping operations on ApplicationInfo and PackageInfo.
 */
public class AppBackupUtils {

    private static final boolean DEBUG = false;

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
     */
    public static boolean appIsEligibleForBackup(ApplicationInfo app, PackageManager pm) {
        // 1. their manifest states android:allowBackup="false"
        if ((app.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) == 0) {
            return false;
        }

        // 2. they run as a system-level uid but do not supply their own backup agent
        if ((app.uid < Process.FIRST_APPLICATION_UID) && (app.backupAgentName == null)) {
            return false;
        }

        // 3. it is the special shared-storage backup package used for 'adb backup'
        if (app.packageName.equals(SHARED_BACKUP_AGENT_PACKAGE)) {
            return false;
        }

        // 4. it is an "instant" app
        if (app.isInstantApp()) {
            return false;
        }

        // Everything else checks out; the only remaining roadblock would be if the
        // package were disabled
        return !appIsDisabled(app, pm);
    }

    /**
     * Returns whether an app is eligible for backup at runtime. That is, the app has to:
     * <ol>
     *     <li>Return true for {@link #appIsEligibleForBackup(ApplicationInfo, PackageManager)}
     *     <li>Return false for {@link #appIsStopped(ApplicationInfo)}
     *     <li>Return false for {@link #appIsDisabled(ApplicationInfo, PackageManager)}
     *     <li>Be eligible for the transport via
     *         {@link BackupTransport#isAppEligibleForBackup(PackageInfo, boolean)}
     * </ol>
     */
    public static boolean appIsRunningAndEligibleForBackupWithTransport(
            @Nullable TransportClient transportClient,
            String packageName,
            PackageManager pm,
            int userId) {
        try {
            PackageInfo packageInfo = pm.getPackageInfoAsUser(packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES, userId);
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            if (!appIsEligibleForBackup(applicationInfo, pm)
                    || appIsStopped(applicationInfo)
                    || appIsDisabled(applicationInfo, pm)) {
                return false;
            }
            if (transportClient != null) {
                try {
                    IBackupTransport transport =
                            transportClient.connectOrThrow(
                                    "AppBackupUtils.appIsRunningAndEligibleForBackupWithTransport");
                    return transport.isAppEligibleForBackup(
                            packageInfo, AppBackupUtils.appGetsFullBackup(packageInfo));
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
    public static boolean appIsDisabled(ApplicationInfo app, PackageManager pm) {
        switch (pm.getApplicationEnabledSetting(app.packageName)) {
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
    public static boolean appIsStopped(ApplicationInfo app) {
        return ((app.flags & ApplicationInfo.FLAG_STOPPED) != 0);
    }

    /**
     * Returns whether the app can get full backup. Does *not* check overall backup eligibility
     * policy!
     */
    public static boolean appGetsFullBackup(PackageInfo pkg) {
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
    public static boolean appIsKeyValueOnly(PackageInfo pkg) {
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
    public static boolean signaturesMatch(Signature[] storedSigs, PackageInfo target,
            PackageManagerInternal pmi) {
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
            return pmi.isDataRestoreSafe(storedSigs[0], target.packageName);
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
}
