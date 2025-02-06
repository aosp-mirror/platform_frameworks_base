/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static com.android.server.backup.BackupManagerService.TAG;

import android.annotation.Nullable;
import android.os.PowerManager;
import android.os.WorkSource;
import android.util.Slog;

/**
 * Wrapper over {@link PowerManager.WakeLock} to prevent double-free exceptions on release()
 * after quit().
 *
 * <p>There should be a single instance of this class per {@link UserBackupManagerService}.
 */
public class BackupWakeLock {
    private final PowerManager.WakeLock mPowerManagerWakeLock;
    private boolean mHasQuit = false;
    private final String mUserIdMessage;
    private final BackupManagerConstants mBackupManagerConstants;

    public BackupWakeLock(PowerManager.WakeLock powerManagerWakeLock, int userId,
            BackupManagerConstants backupManagerConstants) {
        mPowerManagerWakeLock = powerManagerWakeLock;
        mUserIdMessage = "[UserID:" + userId + "] ";
        mBackupManagerConstants = backupManagerConstants;
    }

    /** Acquires the {@link PowerManager.WakeLock} if hasn't been quit. */
    public synchronized void acquire() {
        if (mHasQuit) {
            Slog.d(TAG, mUserIdMessage + "Ignore wakelock acquire after quit: "
                    + mPowerManagerWakeLock.getTag());
            return;
        }
        // Set a timeout for the wakelock. Otherwise if we fail internally and never call
        // release(), the device might stay awake and drain battery indefinitely.
        mPowerManagerWakeLock.acquire(mBackupManagerConstants.getWakelockTimeoutMillis());
        Slog.d(TAG, mUserIdMessage + "Acquired wakelock:" + mPowerManagerWakeLock.getTag());
    }

    /** Releases the {@link PowerManager.WakeLock} if hasn't been quit. */
    public synchronized void release() {
        if (mHasQuit) {
            Slog.d(TAG, mUserIdMessage + "Ignore wakelock release after quit: "
                    + mPowerManagerWakeLock.getTag());
            return;
        }

        if (!mPowerManagerWakeLock.isHeld()) {
            Slog.w(TAG, mUserIdMessage + "Wakelock not held: " + mPowerManagerWakeLock.getTag());
            return;
        }

        mPowerManagerWakeLock.release();
        Slog.d(TAG, mUserIdMessage + "Released wakelock:" + mPowerManagerWakeLock.getTag());
    }

    /**
     * Returns true if the {@link PowerManager.WakeLock} has been acquired but not yet released.
     */
    public synchronized boolean isHeld() {
        return mPowerManagerWakeLock.isHeld();
    }

    /** Release the {@link PowerManager.WakeLock} till it isn't held. */
    public synchronized void quit() {
        while (mPowerManagerWakeLock.isHeld()) {
            Slog.d(TAG, mUserIdMessage + "Releasing wakelock: " + mPowerManagerWakeLock.getTag());
            mPowerManagerWakeLock.release();
        }
        mHasQuit = true;
    }

    /** Calls {@link PowerManager.WakeLock#setWorkSource} on the underlying wake lock. */
    public void setWorkSource(@Nullable WorkSource workSource) {
        mPowerManagerWakeLock.setWorkSource(workSource);
    }
}
