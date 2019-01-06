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

import java.io.File;

/** Directories used for user specific backup/restore persistent state and book-keeping. */
public final class UserBackupManagerFiles {
    // Name of the directories the service stores bookkeeping data under.
    private static final String BACKUP_PERSISTENT_DIR = "backup";
    private static final String BACKUP_STAGING_DIR = "backup_stage";

    static File getBaseStateDir(int userId) {
        // TODO (b/120424138) this should be per user
        return new File(Environment.getDataDirectory(), BACKUP_PERSISTENT_DIR);
    }

    static File getDataDir(int userId) {
        // TODO (b/120424138) this should be per user
        // This dir on /cache is managed directly in init.rc
        return new File(Environment.getDownloadCacheDirectory(), BACKUP_STAGING_DIR);
    }

    /** Directory used by full backup engine to store state. */
    public static File getFullBackupEngineFilesDir(int userId) {
        // TODO (b/120424138) this should be per user
        return new File("/data/system");
    }
}
