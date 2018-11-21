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

package com.android.server.backup.encryption.keys;

import android.content.Context;
import android.security.keystore.recovery.InternalRecoveryServiceException;
import android.security.keystore.recovery.LockScreenRequiredException;
import android.security.keystore.recovery.RecoveryController;
import android.util.ByteStringUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.util.Optional;

import javax.crypto.SecretKey;

/**
 * Manages generating, deleting, and retrieving secondary keys through {@link RecoveryController}.
 *
 * <p>The recoverable key store will be synced remotely via the {@link RecoveryController}, allowing
 * recovery of keys on other devices owned by the user.
 */
public class RecoverableKeyStoreSecondaryKeyManager {
    private static final String BACKUP_KEY_ALIAS_PREFIX =
            "com.android.server.backup/recoverablekeystore/";
    private static final int BACKUP_KEY_SUFFIX_LENGTH_BITS = 128;
    private static final int BITS_PER_BYTE = 8;

    /** A new instance. */
    public static RecoverableKeyStoreSecondaryKeyManager getInstance(Context context) {
        return new RecoverableKeyStoreSecondaryKeyManager(
                RecoveryController.getInstance(context), new SecureRandom());
    }

    private final RecoveryController mRecoveryController;
    private final SecureRandom mSecureRandom;

    @VisibleForTesting
    public RecoverableKeyStoreSecondaryKeyManager(
            RecoveryController recoveryController, SecureRandom secureRandom) {
        mRecoveryController = recoveryController;
        mSecureRandom = secureRandom;
    }

    /**
     * Generates a new recoverable key using the {@link RecoveryController}.
     *
     * @throws InternalRecoveryServiceException if an unexpected error occurred generating the key.
     * @throws LockScreenRequiredException if the user does not have a lock screen. A lock screen is
     *     required to generate a recoverable key.
     */
    public RecoverableKeyStoreSecondaryKey generate()
            throws InternalRecoveryServiceException, LockScreenRequiredException,
                    UnrecoverableKeyException {
        String alias = generateId();
        mRecoveryController.generateKey(alias);
        SecretKey key = (SecretKey) mRecoveryController.getKey(alias);
        if (key == null) {
            throw new InternalRecoveryServiceException(
                    String.format(
                            "Generated key %s but could not get it back immediately afterwards.",
                            alias));
        }
        return new RecoverableKeyStoreSecondaryKey(alias, key);
    }

    /**
     * Removes the secondary key. This means the key will no longer be recoverable.
     *
     * @param alias The alias of the key.
     * @throws InternalRecoveryServiceException if there was a {@link RecoveryController} error.
     */
    public void remove(String alias) throws InternalRecoveryServiceException {
        mRecoveryController.removeKey(alias);
    }

    /**
     * Returns the {@link RecoverableKeyStoreSecondaryKey} with {@code alias} if it is in the {@link
     * RecoveryController}. Otherwise, {@link Optional#empty()}.
     */
    public Optional<RecoverableKeyStoreSecondaryKey> get(String alias)
            throws InternalRecoveryServiceException, UnrecoverableKeyException {
        SecretKey secretKey = (SecretKey) mRecoveryController.getKey(alias);
        return Optional.ofNullable(secretKey)
                .map(key -> new RecoverableKeyStoreSecondaryKey(alias, key));
    }

    /**
     * Generates a new key alias. This has more entropy than a UUID - it can be considered
     * universally unique.
     */
    private String generateId() {
        byte[] id = new byte[BACKUP_KEY_SUFFIX_LENGTH_BITS / BITS_PER_BYTE];
        mSecureRandom.nextBytes(id);
        return BACKUP_KEY_ALIAS_PREFIX + ByteStringUtils.toHexString(id);
    }

    /** Constructs a {@link RecoverableKeyStoreSecondaryKeyManager}. */
    public interface RecoverableKeyStoreSecondaryKeyManagerProvider {
        /** Returns a newly constructed {@link RecoverableKeyStoreSecondaryKeyManager}. */
        RecoverableKeyStoreSecondaryKeyManager get();
    }
}
