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

import static com.android.internal.util.Preconditions.checkArgument;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Locale;

/**
 * Tracks when a tertiary key rotation is due.
 *
 * <p>After a certain number of incremental backups, the device schedules a full backup, which will
 * generate a new encryption key, effecting a key rotation. We should do this on a regular basis so
 * that if a key does become compromised it has limited value to the attacker.
 *
 * <p>No additional synchronization of this class is provided. Only one instance should be used at
 * any time. This should be fine as there should be no parallelism in backups.
 */
public class TertiaryKeyRotationTracker {
    private static final int MAX_BACKUPS_UNTIL_TERTIARY_KEY_ROTATION = 31;
    private static final String SHARED_PREFERENCES_NAME = "tertiary_key_rotation_tracker";

    private static final String TAG = "TertiaryKeyRotationTracker";
    private static final boolean DEBUG = false;

    /**
     * A new instance, using {@code context} to commit data to disk via {@link SharedPreferences}.
     */
    public static TertiaryKeyRotationTracker getInstance(Context context) {
        return new TertiaryKeyRotationTracker(
                context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE),
                MAX_BACKUPS_UNTIL_TERTIARY_KEY_ROTATION);
    }

    private final SharedPreferences mSharedPreferences;
    private final int mMaxBackupsTillRotation;

    /**
     * New instance, storing data in {@code sharedPreferences} and initializing backup countdown to
     * {@code maxBackupsTillRotation}.
     */
    @VisibleForTesting
    TertiaryKeyRotationTracker(SharedPreferences sharedPreferences, int maxBackupsTillRotation) {
        checkArgument(
                maxBackupsTillRotation >= 0,
                String.format(
                        Locale.US,
                        "maxBackupsTillRotation should be non-negative but was %d",
                        maxBackupsTillRotation));
        mSharedPreferences = sharedPreferences;
        mMaxBackupsTillRotation = maxBackupsTillRotation;
    }

    /**
     * Returns {@code true} if the given app is due having its key rotated.
     *
     * @param packageName The package name of the app.
     */
    public boolean isKeyRotationDue(String packageName) {
        return getBackupsSinceRotation(packageName) >= mMaxBackupsTillRotation;
    }

    /**
     * Records that an incremental backup has occurred. Each incremental backup brings the app
     * closer to the time when its key should be rotated.
     *
     * @param packageName The package name of the app for which the backup occurred.
     */
    public void recordBackup(String packageName) {
        int backupsSinceRotation = getBackupsSinceRotation(packageName) + 1;
        mSharedPreferences.edit().putInt(packageName, backupsSinceRotation).apply();
        if (DEBUG) {
            Slog.d(
                    TAG,
                    String.format(
                            Locale.US,
                            "Incremental backup for %s. %d backups until key rotation.",
                            packageName,
                            Math.max(
                                    0,
                                    mMaxBackupsTillRotation
                                            - backupsSinceRotation)));
        }
    }

    /**
     * Resets the rotation delay for the given app. Should be invoked after a key rotation.
     *
     * @param packageName Package name of the app whose key has rotated.
     */
    public void resetCountdown(String packageName) {
        mSharedPreferences.edit().putInt(packageName, 0).apply();
    }

    /** Marks all enrolled packages for key rotation. */
    public void markAllForRotation() {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        for (String packageName : mSharedPreferences.getAll().keySet()) {
            editor.putInt(packageName, mMaxBackupsTillRotation);
        }
        editor.apply();
    }

    private int getBackupsSinceRotation(String packageName) {
        return mSharedPreferences.getInt(packageName, 0);
    }
}
