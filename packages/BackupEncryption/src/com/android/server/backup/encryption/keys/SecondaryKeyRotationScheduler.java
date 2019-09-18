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
import android.util.Slog;

import com.android.server.backup.encryption.CryptoSettings;
import com.android.server.backup.encryption.tasks.StartSecondaryKeyRotationTask;

import java.io.File;
import java.time.Clock;
import java.util.Optional;

/**
 * Helps schedule rotations of secondary keys.
 *
 * <p>TODO(b/72028016) Replace with a job.
 */
public class SecondaryKeyRotationScheduler {

    private static final String TAG = "SecondaryKeyRotationScheduler";
    private static final String SENTINEL_FILE_PATH = "force_secondary_key_rotation";

    private final Context mContext;
    private final RecoverableKeyStoreSecondaryKeyManager mSecondaryKeyManager;
    private final CryptoSettings mCryptoSettings;
    private final Clock mClock;

    public SecondaryKeyRotationScheduler(
            Context context,
            RecoverableKeyStoreSecondaryKeyManager secondaryKeyManager,
            CryptoSettings cryptoSettings,
            Clock clock) {
        mContext = context;
        mCryptoSettings = cryptoSettings;
        mClock = clock;
        mSecondaryKeyManager = secondaryKeyManager;
    }

    /**
     * Returns {@code true} if a sentinel file for forcing secondary key rotation is present. This
     * is only for testing purposes.
     */
    private boolean isForceRotationTestSentinelPresent() {
        File file = new File(mContext.getFilesDir(), SENTINEL_FILE_PATH);
        if (file.exists()) {
            file.delete();
            return true;
        }
        return false;
    }

    /** Start the key rotation task if it's time to do so */
    public void startRotationIfScheduled() {
        if (isForceRotationTestSentinelPresent()) {
            Slog.i(TAG, "Found force flag for secondary rotation. Starting now.");
            startRotation();
            return;
        }

        Optional<Long> maybeLastRotated = mCryptoSettings.getSecondaryLastRotated();
        if (!maybeLastRotated.isPresent()) {
            Slog.v(TAG, "No previous rotation, scheduling from now.");
            scheduleRotationFromNow();
            return;
        }

        long lastRotated = maybeLastRotated.get();
        long now = mClock.millis();

        if (lastRotated > now) {
            Slog.i(TAG, "Last rotation was in the future. Clock must have changed. Rotate now.");
            startRotation();
            return;
        }

        long millisSinceLastRotation = now - lastRotated;
        long rotationInterval = mCryptoSettings.backupSecondaryKeyRotationIntervalMs();
        if (millisSinceLastRotation >= rotationInterval) {
            Slog.i(
                    TAG,
                    "Last rotation was more than "
                            + rotationInterval
                            + "ms ("
                            + millisSinceLastRotation
                            + "ms) in the past. Rotate now.");
            startRotation();
        }

        Slog.v(TAG, "No rotation required, last " + lastRotated + ".");
    }

    private void startRotation() {
        scheduleRotationFromNow();
        new StartSecondaryKeyRotationTask(mCryptoSettings, mSecondaryKeyManager).run();
    }

    private void scheduleRotationFromNow() {
        mCryptoSettings.setSecondaryLastRotated(mClock.millis());
    }
}
