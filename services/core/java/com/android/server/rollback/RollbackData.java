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

package com.android.server.rollback;

import android.content.rollback.RollbackInfo;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;

/**
 * Information about a rollback available for a set of atomically installed
 * packages.
 */
class RollbackData {
    /**
     * The rollback info for this rollback.
     */
    public final RollbackInfo info;

    /**
     * The directory where the rollback data is stored.
     */
    public final File backupDir;

    /**
     * The time when the upgrade occurred, for purposes of expiring
     * rollback data.
     */
    public Instant timestamp;

    /**
     * The session ID for the staged session if this rollback data represents a staged session,
     * {@code -1} otherwise.
     */
    public int stagedSessionId;

    /**
     * A flag to indicate whether the rollback should be considered available
     * for use. This will always be true for rollbacks of non-staged sessions.
     * For rollbacks of staged sessions, this is not set to true until after
     * the staged session has been applied.
     */
    public boolean isAvailable;

    /**
     * The id of the post-reboot apk session for a staged install, if any.
     */
    public int apkSessionId = -1;

    /**
     * True if we are expecting the package manager to call restoreUserData
     * for this rollback because it has just been committed but the rollback
     * has not yet been fully applied.
     */
    // NOTE: All accesses to this field are from the RollbackManager handler thread.
    public boolean restoreUserDataInProgress = false;

    /**
     * Constructs a new, empty RollbackData instance.
     *
     * @param rollbackId the id of the rollback.
     * @param backupDir the directory where the rollback data is stored.
     * @param stagedSessionId the session id if this is a staged rollback, -1 otherwise.
     */
    RollbackData(int rollbackId, File backupDir, int stagedSessionId) {
        this.info = new RollbackInfo(rollbackId,
                /* packages */ new ArrayList<>(),
                /* isStaged */ stagedSessionId != -1,
                /* causePackages */ new ArrayList<>(),
                /* committedSessionId */ -1);
        this.backupDir = backupDir;
        this.stagedSessionId = stagedSessionId;
        this.isAvailable = (stagedSessionId == -1);
    }

    /**
     * Constructs a RollbackData instance with full rollback data information.
     */
    RollbackData(RollbackInfo info, File backupDir, Instant timestamp, int stagedSessionId,
            boolean isAvailable, int apkSessionId, boolean restoreUserDataInProgress) {
        this.info = info;
        this.backupDir = backupDir;
        this.timestamp = timestamp;
        this.stagedSessionId = stagedSessionId;
        this.isAvailable = isAvailable;
        this.apkSessionId = apkSessionId;
        this.restoreUserDataInProgress = restoreUserDataInProgress;
    }

    /**
     * Whether the rollback is for rollback of a staged install.
     */
    public boolean isStaged() {
        return info.isStaged();
    }
}
