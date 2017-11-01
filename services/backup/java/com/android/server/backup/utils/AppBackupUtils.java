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

import static com.android.server.backup.RefactoredBackupManagerService.MORE_DEBUG;
import static com.android.server.backup.RefactoredBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;
import static com.android.server.backup.RefactoredBackupManagerService.TAG;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Process;
import android.util.Slog;

/**
 * Utility methods wrapping operations on ApplicationInfo and PackageInfo.
 */
public class AppBackupUtils {
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
    public static boolean appIsEligibleForBackup(ApplicationInfo app) {
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

        return true;
    }

    /**
     * Checks if the app is in a stopped state, that means it won't receive broadcasts.
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
     * Old style: directly match the stored vs on device signature blocks.
     */
    // TODO(b/37977154): Resolve questionable policies.
    public static boolean signaturesMatch(Signature[] storedSigs, PackageInfo target) {
        if (target == null) {
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

        // Allow unsigned apps, but not signed on one device and unsigned on the other
        // TODO(b/37977154): is this the right policy?
        Signature[] deviceSigs = target.signatures;
        if (MORE_DEBUG) {
            Slog.v(TAG, "signaturesMatch(): stored=" + storedSigs + " device=" + deviceSigs);
        }
        if ((storedSigs == null || storedSigs.length == 0)
                && (deviceSigs == null || deviceSigs.length == 0)) {
            return true;
        }
        // TODO(b/37977154): This allows empty stored signature, is this right?
        if (storedSigs == null || deviceSigs == null) {
            return false;
        }

        // TODO(b/37977154): this demands that every stored signature match one
        // that is present on device, and does not demand the converse.
        // Is this this right policy?
        int nStored = storedSigs.length;
        int nDevice = deviceSigs.length;

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
        return true;
    }
}
