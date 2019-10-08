/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.IntDef;
import android.content.pm.ApplicationInfo;
import android.os.Process;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

// TODO: Preconditions is not available, include its target in dependencies
public class PackageData {
    public static final PackageData PM_PACKAGE = new PmPackageData();

    public static PackageData keyValuePackage(int identifier) {
        return androidPackage(identifier, BackupStatus.KEY_VALUE_BACKUP);
    }

    public static PackageData fullBackupPackage(int identifier) {
        return androidPackage(identifier, BackupStatus.FULL_BACKUP);
    }

    /** Returns {@link PackageData} for unique package identifier {@code identifier}. */
    public static PackageData androidPackage(int identifier) {
        return androidPackage(identifier, BackupStatus.KEY_VALUE_BACKUP);
    }

    public final String packageName;
    public final String agentName;
    @BackupStatus public final int backupStatus;
    public final boolean available;
    public final boolean stopped;
    public final int uid;

    private PackageData(
            String packageName,
            String agentName,
            int backupStatus,
            boolean stopped,
            boolean available,
            int uid) {
        // checkArgument(!stopped || !available, "stopped => !available")

        this.packageName = packageName;
        this.agentName = agentName;
        this.backupStatus = backupStatus;
        this.stopped = stopped;
        this.available = available;
        this.uid = uid;
    }

    public int flags() {
        int flags = 0;
        if (backupStatus != BackupStatus.BACKUP_NOT_ALLOWED) {
            flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;
        }
        if (backupStatus == BackupStatus.FULL_BACKUP) {
            flags |= ApplicationInfo.FLAG_FULL_BACKUP_ONLY;
        }
        if (stopped) {
            flags |= ApplicationInfo.FLAG_STOPPED;
        }
        return flags;
    }

    public PackageData backupNotAllowed() {
        return new PackageData(
                packageName, agentName, BackupStatus.BACKUP_NOT_ALLOWED, stopped, available, uid);
    }

    public PackageData stopped() {
        return new PackageData(packageName, agentName, backupStatus, true, false, uid);
    }

    public PackageData unavailable() {
        return new PackageData(packageName, agentName, backupStatus, stopped, false, uid);
    }

    public boolean isPm() {
        return this == PM_PACKAGE;
    }

    private static PackageData androidPackage(int identifier, @BackupStatus int backupStatus) {
        // checkArgument(identifier >= 0, "identifier can't be < 0");

        String packageName = "com.sample.package" + identifier;
        return new PackageData(
                packageName,
                packageName + ".BackupAgent",
                backupStatus,
                false,
                true,
                Process.FIRST_APPLICATION_UID + identifier);
    }

    private static class PmPackageData extends PackageData {
        private PmPackageData() {
            super(
                    "@pm@",
                    "com.android.server.backup.PackageManagerBackupAgent",
                    BackupStatus.KEY_VALUE_BACKUP,
                    false,
                    true,
                    Process.SYSTEM_UID);
        }

        @Override
        public int flags() {
            throw new UnsupportedOperationException("PM \"package\" has no flags");
        }

        @Override
        public PackageData backupNotAllowed() {
            throw new UnsupportedOperationException("PM \"package\" has backup allowed");
        }

        @Override
        public PackageData stopped() {
            throw new UnsupportedOperationException("PM \"package\" can't be stopped");
        }

        @Override
        public PackageData unavailable() {
            throw new UnsupportedOperationException("PM \"package\" is always available");
        }
    }

    @IntDef({
        BackupStatus.KEY_VALUE_BACKUP,
        BackupStatus.FULL_BACKUP,
        BackupStatus.BACKUP_NOT_ALLOWED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BackupStatus {
        int KEY_VALUE_BACKUP = 0;
        int FULL_BACKUP = 1;
        int BACKUP_NOT_ALLOWED = 2;
    }
}
