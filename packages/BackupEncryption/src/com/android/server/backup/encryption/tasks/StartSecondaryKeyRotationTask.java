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

package com.android.server.backup.encryption.tasks;

import android.security.keystore.recovery.InternalRecoveryServiceException;
import android.security.keystore.recovery.LockScreenRequiredException;
import android.util.Slog;

import com.android.internal.util.Preconditions;
import com.android.server.backup.encryption.CryptoSettings;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKeyManager;

import java.security.UnrecoverableKeyException;
import java.util.Objects;
import java.util.Optional;

/**
 * Starts rotating to a new secondary key. Cannot complete until the screen is unlocked and the new
 * key is synced.
 */
public class StartSecondaryKeyRotationTask {
    private static final String TAG = "BE-StSecondaryKeyRotTsk";

    private final CryptoSettings mCryptoSettings;
    private final RecoverableKeyStoreSecondaryKeyManager mSecondaryKeyManager;

    public StartSecondaryKeyRotationTask(
            CryptoSettings cryptoSettings,
            RecoverableKeyStoreSecondaryKeyManager secondaryKeyManager) {
        mCryptoSettings = Objects.requireNonNull(cryptoSettings);
        mSecondaryKeyManager = Objects.requireNonNull(secondaryKeyManager);
    }

    /** Begin the key rotation */
    public void run() {
        Slog.i(TAG, "Attempting to initiate a secondary key rotation.");

        Optional<String> maybeCurrentAlias = mCryptoSettings.getActiveSecondaryKeyAlias();
        if (!maybeCurrentAlias.isPresent()) {
            Slog.w(TAG, "No active current alias. Cannot trigger a secondary rotation.");
            return;
        }
        String currentAlias = maybeCurrentAlias.get();

        Optional<String> maybeNextAlias = mCryptoSettings.getNextSecondaryKeyAlias();
        if (maybeNextAlias.isPresent()) {
            String nextAlias = maybeNextAlias.get();
            if (nextAlias.equals(currentAlias)) {
                // Shouldn't be possible, but guard against accidentally deleting the active key.
                Slog.e(TAG, "Was already trying to rotate to what is already the active key.");
            } else {
                Slog.w(TAG, "Was already rotating to another key. Cancelling that.");
                try {
                    mSecondaryKeyManager.remove(nextAlias);
                } catch (Exception e) {
                    Slog.wtf(TAG, "Could not remove old key", e);
                }
            }
            mCryptoSettings.removeNextSecondaryKeyAlias();
        }

        RecoverableKeyStoreSecondaryKey newSecondaryKey;
        try {
            newSecondaryKey = mSecondaryKeyManager.generate();
        } catch (LockScreenRequiredException e) {
            Slog.e(TAG, "No lock screen is set - cannot generate a new key to rotate to.", e);
            return;
        } catch (InternalRecoveryServiceException e) {
            Slog.e(TAG, "Internal error in Recovery Controller, failed to rotate key.", e);
            return;
        } catch (UnrecoverableKeyException e) {
            Slog.e(TAG, "Failed to get key after generating, failed to rotate", e);
            return;
        }

        String alias = newSecondaryKey.getAlias();
        Slog.i(TAG, "Generated a new secondary key with alias '" + alias + "'.");
        try {
            mCryptoSettings.setNextSecondaryAlias(alias);
            Slog.i(TAG, "Successfully set '" + alias + "' as next key to rotate to");
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Unexpected error setting next alias", e);
            try {
                mSecondaryKeyManager.remove(alias);
            } catch (Exception err) {
                Slog.wtf(TAG, "Failed to remove generated key after encountering error", err);
            }
        }
    }
}
