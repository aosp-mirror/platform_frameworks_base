/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.backup;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotations related to Android Backup&Restore.
 *
 * @hide
 */
public class BackupAnnotations {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            OperationType.UNKNOWN,
            OperationType.BACKUP,
            OperationType.RESTORE,
    })
    public @interface OperationType {
        int UNKNOWN = -1;
        int BACKUP = 0;
        int RESTORE = 1;
    }

    /**
     * Denotes where the backup data is going (e.g. to the cloud or directly to the other device)
     * during backup or where it is coming from during restore.
     *
     * @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            BackupDestination.CLOUD,
            BackupDestination.DEVICE_TRANSFER,
            BackupDestination.ADB_BACKUP
    })
    public @interface BackupDestination {
        // A cloud backup.
        int CLOUD = 0;
        // A device to device migration.
        int DEVICE_TRANSFER = 1;
        // An adb backup.
        int ADB_BACKUP = 2;
    }
}
