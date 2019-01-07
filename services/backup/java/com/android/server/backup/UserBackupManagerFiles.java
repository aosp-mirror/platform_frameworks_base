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
 * limitations under the License.
 */

package com.android.server.backup;

import android.os.Environment;
import android.os.UserHandle;

import java.io.File;

/** Directories used for user specific backup/restore persistent state and book-keeping. */
final class UserBackupManagerFiles {
    // Name of the directories the service stores bookkeeping data under.
    private static final String BACKUP_PERSISTENT_DIR = "backup";
    private static final String BACKUP_STAGING_DIR = "backup_stage";

    private static File getBaseDir(int userId) {
        return Environment.getDataSystemCeDirectory(userId);
    }

    static File getBaseStateDir(int userId) {
        if (userId != UserHandle.USER_SYSTEM) {
            return new File(getBaseDir(userId), BACKUP_PERSISTENT_DIR);
        }
        // TODO (b/120424138) remove if clause above and use same logic for system user.
        // simultaneously, copy below dir to new system user dir
        return new File(Environment.getDataDirectory(), BACKUP_PERSISTENT_DIR);
    }

    static File getDataDir(int userId) {
        if (userId != UserHandle.USER_SYSTEM) {
            return new File(getBaseDir(userId), BACKUP_STAGING_DIR);
        }
        // TODO (b/120424138) remove if clause above and use same logic for system user. Since this
        // is a staging dir, we dont need to copy below dir to new system user dir
        return new File(Environment.getDownloadCacheDirectory(), BACKUP_STAGING_DIR);
    }
}
