/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.text.TextUtils;

import com.android.server.pm.pkg.PackageStateInternal;

import java.util.Objects;

/**
 * Responsible archiving apps and returning information about archived apps.
 *
 * <p> An archived app is in a state where the app is not fully on the device. APKs are removed
 * while the data directory is kept. Archived apps are included in the list of launcher apps where
 * tapping them re-installs the full app.
 */
final class ArchiveManager {

    private final PackageManagerService mPm;

    ArchiveManager(PackageManagerService mPm) {
        this.mPm = mPm;
    }

    void archiveApp(
            @NonNull String packageName,
            @NonNull String callerPackageName,
            int userId,
            @NonNull IntentSender intentSender) throws PackageManager.NameNotFoundException {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(callerPackageName);
        Objects.requireNonNull(intentSender);

        Computer snapshot = mPm.snapshotComputer();
        int callingUid = Binder.getCallingUid();
        String callingPackageName = snapshot.getNameForUid(callingUid);
        snapshot.enforceCrossUserPermission(callingUid, userId, true, true, "archiveApp");
        verifyCaller(callerPackageName, callingPackageName);

        PackageStateInternal ps = snapshot.getPackageStateInternal(packageName);
        if (ps == null) {
            throw new PackageManager.NameNotFoundException(
                    TextUtils.formatSimple("Package %s not found.", packageName));
        }

        verifyInstallOwnership(packageName, callingPackageName, ps);

        // TODO(b/278553670) Complete implementations
        throw new UnsupportedOperationException("Method not implemented.");
    }

    private static void verifyCaller(String callerPackageName, String callingPackageName) {
        if (!TextUtils.equals(callingPackageName, callerPackageName)) {
            throw new SecurityException(
                    TextUtils.formatSimple(
                            "The callerPackageName %s set by the caller doesn't match the "
                                    + "caller's own package name %s.",
                            callerPackageName,
                            callingPackageName));
        }
    }

    private static void verifyInstallOwnership(String packageName, String callingPackageName,
            PackageStateInternal ps) {
        if (!TextUtils.equals(ps.getInstallSource().mInstallerPackageName,
                callingPackageName)) {
            throw new SecurityException(
                    TextUtils.formatSimple("Caller is not the installer of record for %s.",
                            packageName));
        }
        String updateOwnerPackageName = ps.getInstallSource().mUpdateOwnerPackageName;
        if (updateOwnerPackageName != null
                && !TextUtils.equals(updateOwnerPackageName, callingPackageName)) {
            throw new SecurityException(
                    TextUtils.formatSimple("Caller is not the update owner for %s.", packageName));
        }
    }
}
