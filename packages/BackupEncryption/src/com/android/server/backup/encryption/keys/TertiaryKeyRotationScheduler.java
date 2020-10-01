/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.keys;

import android.content.Context;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Schedules tertiary key rotations in a staggered fashion.
 *
 * <p>Apps are due a key rotation after a certain number of backups. Rotations are then staggerered
 * over a period of time, through restricting the number of rotations allowed in a 24-hour window.
 * This will causes the apps to enter a staggered cycle of regular rotations.
 *
 * <p>Note: the methods in this class are not optimized to be super fast. They make blocking IO to
 * ensure that scheduler information is committed to disk, so that it is available after the user
 * turns their device off and on. This ought to be fine as
 *
 * <ul>
 *   <li>It will be invoked before a backup, so should never be invoked on the UI thread
 *   <li>It will be invoked before a backup, so the vast amount of time is spent on the backup, not
 *       writing tiny amounts of data to disk.
 * </ul>
 */
public class TertiaryKeyRotationScheduler {
    /** Default number of key rotations allowed within 24 hours. */
    private static final int KEY_ROTATION_LIMIT = 2;

    /** A new instance, using {@code context} to determine where to store state. */
    public static TertiaryKeyRotationScheduler getInstance(Context context) {
        TertiaryKeyRotationWindowedCount windowedCount =
                TertiaryKeyRotationWindowedCount.getInstance(context);
        TertiaryKeyRotationTracker tracker = TertiaryKeyRotationTracker.getInstance(context);
        return new TertiaryKeyRotationScheduler(tracker, windowedCount, KEY_ROTATION_LIMIT);
    }

    private final TertiaryKeyRotationTracker mTracker;
    private final TertiaryKeyRotationWindowedCount mWindowedCount;
    private final int mMaximumRotationsPerWindow;

    /**
     * A new instance.
     *
     * @param tracker Tracks how many times each application has backed up.
     * @param windowedCount Tracks how many rotations have happened in the last 24 hours.
     * @param maximumRotationsPerWindow The maximum number of key rotations allowed per 24 hours.
     */
    @VisibleForTesting
    TertiaryKeyRotationScheduler(
            TertiaryKeyRotationTracker tracker,
            TertiaryKeyRotationWindowedCount windowedCount,
            int maximumRotationsPerWindow) {
        mTracker = tracker;
        mWindowedCount = windowedCount;
        mMaximumRotationsPerWindow = maximumRotationsPerWindow;
    }

    /**
     * Returns {@code true} if the app with {@code packageName} is due having its key rotated.
     *
     * <p>This ought to be queried before backing up an app, to determine whether to do an
     * incremental backup or a full backup. (A full backup forces key rotation.)
     */
    public boolean isKeyRotationDue(String packageName) {
        if (mWindowedCount.getCount() >= mMaximumRotationsPerWindow) {
            return false;
        }
        return mTracker.isKeyRotationDue(packageName);
    }

    /**
     * Records that a backup happened for the app with the given {@code packageName}.
     *
     * <p>Each backup brings the app closer to the point at which a key rotation is due.
     */
    public void recordBackup(String packageName) {
        mTracker.recordBackup(packageName);
    }

    /**
     * Records a key rotation happened for the app with the given {@code packageName}.
     *
     * <p>This resets the countdown until the next key rotation is due.
     */
    public void recordKeyRotation(String packageName) {
        mTracker.resetCountdown(packageName);
        mWindowedCount.record();
    }
}
