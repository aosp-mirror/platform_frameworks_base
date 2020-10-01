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

import android.content.Context;
import android.security.keystore.recovery.InternalRecoveryServiceException;
import android.security.keystore.recovery.LockScreenRequiredException;
import android.security.keystore.recovery.RecoveryController;
import android.util.Slog;

import com.android.server.backup.encryption.CryptoSettings;
import com.android.server.backup.encryption.client.CryptoBackupServer;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKeyManager;

import java.security.InvalidKeyException;
import java.security.UnrecoverableKeyException;
import java.util.Collections;
import java.util.Optional;

/**
 * Initializes the device for encrypted backup, through generating a secondary key, and setting its
 * alias in the settings.
 *
 * <p>If the device is already initialized, this is a no-op.
 */
public class InitializeRecoverableSecondaryKeyTask {
    private static final String TAG = "InitializeRecoverableSecondaryKeyTask";

    private final Context mContext;
    private final CryptoSettings mCryptoSettings;
    private final RecoverableKeyStoreSecondaryKeyManager mSecondaryKeyManager;
    private final CryptoBackupServer mBackupServer;

    /**
     * A new instance.
     *
     * @param cryptoSettings Settings to store the active key alias.
     * @param secondaryKeyManager Key manager to generate the new active secondary key.
     * @param backupServer Server with which to sync the active key alias.
     */
    public InitializeRecoverableSecondaryKeyTask(
            Context context,
            CryptoSettings cryptoSettings,
            RecoverableKeyStoreSecondaryKeyManager secondaryKeyManager,
            CryptoBackupServer backupServer) {
        mContext = context;
        mCryptoSettings = cryptoSettings;
        mSecondaryKeyManager = secondaryKeyManager;
        mBackupServer = backupServer;
    }

    /**
     * Initializes the device for encrypted backup, by generating a recoverable secondary key, then
     * sending that alias to the backup server and saving it in local settings.
     *
     * <p>If there is already an active secondary key then does nothing. If the active secondary key
     * is destroyed then throws {@link InvalidKeyException}.
     *
     * <p>If a key rotation is pending and able to finish (i.e., the new key has synced with the
     * remote trusted hardware module), then it completes the rotation before returning the key.
     *
     * @return The active secondary key.
     * @throws InvalidKeyException if the secondary key is in a bad state.
     */
    public RecoverableKeyStoreSecondaryKey run()
            throws InvalidKeyException, LockScreenRequiredException, UnrecoverableKeyException,
                    InternalRecoveryServiceException {
        // Complete any pending key rotations
        new RotateSecondaryKeyTask(
                        mContext,
                        mSecondaryKeyManager,
                        mBackupServer,
                        mCryptoSettings,
                        RecoveryController.getInstance(mContext))
                .run();

        return runInternal();
    }

    private RecoverableKeyStoreSecondaryKey runInternal()
            throws InvalidKeyException, LockScreenRequiredException, UnrecoverableKeyException,
                    InternalRecoveryServiceException {
        Optional<RecoverableKeyStoreSecondaryKey> maybeActiveKey = loadFromSetting();

        if (maybeActiveKey.isPresent()) {
            assertKeyNotDestroyed(maybeActiveKey.get());
            Slog.d(TAG, "Secondary key already initialized: " + maybeActiveKey.get().getAlias());
            return maybeActiveKey.get();
        }

        Slog.v(TAG, "Initializing for crypto: generating a secondary key.");
        RecoverableKeyStoreSecondaryKey key = mSecondaryKeyManager.generate();

        String alias = key.getAlias();
        Slog.i(TAG, "Generated new secondary key " + alias);

        // No tertiary keys yet as we are creating a brand new secondary (without rotation).
        mBackupServer.setActiveSecondaryKeyAlias(alias, /*tertiaryKeys=*/ Collections.emptyMap());
        Slog.v(TAG, "Successfully synced %s " + alias + " with server.");

        mCryptoSettings.initializeWithKeyAlias(alias);
        Slog.v(TAG, "Successfully saved " + alias + " as active secondary to disk.");

        return key;
    }

    private void assertKeyNotDestroyed(RecoverableKeyStoreSecondaryKey key)
            throws InvalidKeyException {
        if (key.getStatus(mContext) == RecoverableKeyStoreSecondaryKey.Status.DESTROYED) {
            throw new InvalidKeyException("Key destroyed: " + key.getAlias());
        }
    }

    private Optional<RecoverableKeyStoreSecondaryKey> loadFromSetting()
            throws InvalidKeyException, UnrecoverableKeyException,
                    InternalRecoveryServiceException {

        // TODO: b/141856950.
        if (!mCryptoSettings.getIsInitialized()) {
            return Optional.empty();
        }

        Optional<String> maybeAlias = mCryptoSettings.getActiveSecondaryKeyAlias();
        if (!maybeAlias.isPresent()) {
            throw new InvalidKeyException(
                    "Settings said crypto was initialized, but there was no active secondary"
                            + " alias");
        }

        String alias = maybeAlias.get();

        Optional<RecoverableKeyStoreSecondaryKey> key;
        key = mSecondaryKeyManager.get(alias);

        if (!key.isPresent()) {
            throw new InvalidKeyException(
                    "Initialized with key but it was not in key store: " + alias);
        }

        return key;
    }
}
