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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.rollback.RollbackInfo;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;


/**
 * Information about a rollback available for a set of atomically installed
 * packages.
 */
class Rollback {
    @IntDef(flag = true, prefix = { "ROLLBACK_STATE_" }, value = {
            ROLLBACK_STATE_ENABLING,
            ROLLBACK_STATE_AVAILABLE,
            ROLLBACK_STATE_COMMITTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface RollbackState {}

    /**
     * The rollback is in the process of being enabled. It is not yet
     * available for use.
     */
    static final int ROLLBACK_STATE_ENABLING = 0;

    /**
     * The rollback is currently available.
     */
    static final int ROLLBACK_STATE_AVAILABLE = 1;

    /**
     * The rollback has been committed.
     */
    static final int ROLLBACK_STATE_COMMITTED = 3;

    /**
     * The rollback info for this rollback.
     */
    public final RollbackInfo info;

    /**
     * The directory where the rollback data is stored.
     */
    private final File mBackupDir;

    /**
     * The time when the upgrade occurred, for purposes of expiring
     * rollback data.
     *
     * The timestamp is not applicable for all rollback states, but we make
     * sure to keep it non-null to avoid potential errors there.
     */
    private @NonNull Instant mTimestamp;

    /**
     * The session ID for the staged session if this rollback data represents a staged session,
     * {@code -1} otherwise.
     */
    private final int mStagedSessionId;

    /**
     * The current state of the rollback.
     * ENABLING, AVAILABLE, or COMMITTED.
     */
    private @RollbackState int mState;

    /**
     * The id of the post-reboot apk session for a staged install, if any.
     */
    private int mApkSessionId = -1;

    /**
     * True if we are expecting the package manager to call restoreUserData
     * for this rollback because it has just been committed but the rollback
     * has not yet been fully applied.
     */
    // NOTE: All accesses to this field are from the RollbackManager handler thread.
    private boolean mRestoreUserDataInProgress = false;

    /**
     * Constructs a new, empty Rollback instance.
     *
     * @param rollbackId the id of the rollback.
     * @param backupDir the directory where the rollback data is stored.
     * @param stagedSessionId the session id if this is a staged rollback, -1 otherwise.
     */
    Rollback(int rollbackId, File backupDir, int stagedSessionId) {
        this.info = new RollbackInfo(rollbackId,
                /* packages */ new ArrayList<>(),
                /* isStaged */ stagedSessionId != -1,
                /* causePackages */ new ArrayList<>(),
                /* committedSessionId */ -1);
        mBackupDir = backupDir;
        mStagedSessionId = stagedSessionId;
        mState = ROLLBACK_STATE_ENABLING;
        mTimestamp = Instant.now();
    }

    /**
     * Constructs a pre-populated Rollback instance.
     */
    Rollback(RollbackInfo info, File backupDir, Instant timestamp, int stagedSessionId,
            @RollbackState int state, int apkSessionId, boolean restoreUserDataInProgress) {
        this.info = info;
        mBackupDir = backupDir;
        mTimestamp = timestamp;
        mStagedSessionId = stagedSessionId;
        mState = state;
        mApkSessionId = apkSessionId;
        mRestoreUserDataInProgress = restoreUserDataInProgress;
    }

    /**
     * Whether the rollback is for rollback of a staged install.
     */
    boolean isStaged() {
        return info.isStaged();
    }

    /**
     * Returns the directory in which rollback data should be stored.
     */
    File getBackupDir() {
        return mBackupDir;
    }

    /**
     * Returns the time when the upgrade occurred, for purposes of expiring rollback data.
     */
    Instant getTimestamp() {
        return mTimestamp;
    }

    /**
     * Sets the time at which upgrade occurred.
     */
    void setTimestamp(Instant timestamp) {
        mTimestamp = timestamp;
    }

    /**
     * Returns the session ID for the staged session if this rollback data represents a staged
     * session, or {@code -1} otherwise.
     */
    int getStagedSessionId() {
        return mStagedSessionId;
    }

    /**
     * Returns true if the rollback is in the ENABLING state.
     */
    boolean isEnabling() {
        return mState == ROLLBACK_STATE_ENABLING;
    }

    /**
     * Returns true if the rollback is in the AVAILABLE state.
     */
    boolean isAvailable() {
        return mState == ROLLBACK_STATE_AVAILABLE;
    }

    /**
     * Returns true if the rollback is in the COMMITTED state.
     */
    boolean isCommitted() {
        return mState == ROLLBACK_STATE_COMMITTED;
    }

    /**
     * Sets the state of the rollback to AVAILABLE.
     */
    void setAvailable() {
        mState = ROLLBACK_STATE_AVAILABLE;
    }

    /**
     * Sets the state of the rollback to COMMITTED.
     */
    void setCommitted() {
        mState = ROLLBACK_STATE_COMMITTED;
    }

    /**
     * Returns the id of the post-reboot apk session for a staged install, if any.
     */
    int getApkSessionId() {
        return mApkSessionId;
    }

    /**
     * Sets the id of the post-reboot apk session for a staged install.
     */
    void setApkSessionId(int apkSessionId) {
        mApkSessionId = apkSessionId;
    }

    /**
     * Returns true if we are expecting the package manager to call restoreUserData for this
     * rollback because it has just been committed but the rollback has not yet been fully applied.
     */
    boolean isRestoreUserDataInProgress() {
        return mRestoreUserDataInProgress;
    }

    /**
     * Sets whether we are expecting the package manager to call restoreUserData for this
     * rollback because it has just been committed but the rollback has not yet been fully applied.
     */
    void setRestoreUserDataInProgress(boolean restoreUserDataInProgress) {
        mRestoreUserDataInProgress = restoreUserDataInProgress;
    }

    static String rollbackStateToString(@RollbackState int state) {
        switch (state) {
            case Rollback.ROLLBACK_STATE_ENABLING: return "enabling";
            case Rollback.ROLLBACK_STATE_AVAILABLE: return "available";
            case Rollback.ROLLBACK_STATE_COMMITTED: return "committed";
        }
        throw new AssertionError("Invalid rollback state: " + state);
    }

    static @RollbackState int rollbackStateFromString(String state)
            throws ParseException {
        switch (state) {
            case "enabling": return Rollback.ROLLBACK_STATE_ENABLING;
            case "available": return Rollback.ROLLBACK_STATE_AVAILABLE;
            case "committed": return Rollback.ROLLBACK_STATE_COMMITTED;
        }
        throw new ParseException("Invalid rollback state: " + state, 0);
    }

    String getStateAsString() {
        return rollbackStateToString(mState);
    }
}
