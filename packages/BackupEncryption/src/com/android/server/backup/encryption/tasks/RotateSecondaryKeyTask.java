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

import static android.os.Build.VERSION_CODES.P;

import android.content.Context;
import android.security.keystore.recovery.InternalRecoveryServiceException;
import android.security.keystore.recovery.RecoveryController;
import android.util.Slog;

import com.android.server.backup.encryption.CryptoSettings;
import com.android.server.backup.encryption.client.CryptoBackupServer;
import com.android.server.backup.encryption.keys.KeyWrapUtils;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey;
import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKeyManager;
import com.android.server.backup.encryption.keys.TertiaryKeyStore;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

/**
 * Finishes a rotation for a {@link
 * com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKey}.
 */
public class RotateSecondaryKeyTask {
    private static final String TAG = "RotateSecondaryKeyTask";

    private final Context mContext;
    private final RecoverableKeyStoreSecondaryKeyManager mSecondaryKeyManager;
    private final CryptoBackupServer mBackupServer;
    private final CryptoSettings mCryptoSettings;
    private final RecoveryController mRecoveryController;

    /**
     * A new instance.
     *
     * @param secondaryKeyManager For loading the currently active and next secondary key.
     * @param backupServer For loading and storing tertiary keys and for setting active secondary
     *     key.
     * @param cryptoSettings For checking the stored aliases for the next and active key.
     * @param recoveryController For communicating with the Framework apis.
     */
    public RotateSecondaryKeyTask(
            Context context,
            RecoverableKeyStoreSecondaryKeyManager secondaryKeyManager,
            CryptoBackupServer backupServer,
            CryptoSettings cryptoSettings,
            RecoveryController recoveryController) {
        mContext = context;
        mSecondaryKeyManager = Objects.requireNonNull(secondaryKeyManager);
        mCryptoSettings = Objects.requireNonNull(cryptoSettings);
        mBackupServer = Objects.requireNonNull(backupServer);
        mRecoveryController = Objects.requireNonNull(recoveryController);
    }

    /** Runs the task. */
    public void run() {
        // Never run more than one of these at the same time.
        synchronized (RotateSecondaryKeyTask.class) {
            runInternal();
        }
    }

    private void runInternal() {
        Optional<RecoverableKeyStoreSecondaryKey> maybeNextKey;
        try {
            maybeNextKey = getNextKey();
        } catch (Exception e) {
            Slog.e(TAG, "Error checking for next key", e);
            return;
        }

        if (!maybeNextKey.isPresent()) {
            Slog.d(TAG, "No secondary key rotation task pending. Exiting.");
            return;
        }

        RecoverableKeyStoreSecondaryKey nextKey = maybeNextKey.get();
        boolean isReady;
        try {
            isReady = isSecondaryKeyRotationReady(nextKey);
        } catch (InternalRecoveryServiceException e) {
            Slog.e(TAG, "Error encountered checking whether next secondary key is synced", e);
            return;
        }

        if (!isReady) {
            return;
        }

        try {
            rotateToKey(nextKey);
        } catch (Exception e) {
            Slog.e(TAG, "Error trying to rotate to new secondary key", e);
        }
    }

    private Optional<RecoverableKeyStoreSecondaryKey> getNextKey()
            throws InternalRecoveryServiceException, UnrecoverableKeyException {
        Optional<String> maybeNextAlias = mCryptoSettings.getNextSecondaryKeyAlias();
        if (!maybeNextAlias.isPresent()) {
            return Optional.empty();
        }
        return mSecondaryKeyManager.get(maybeNextAlias.get());
    }

    private boolean isSecondaryKeyRotationReady(RecoverableKeyStoreSecondaryKey nextKey)
            throws InternalRecoveryServiceException {
        String nextAlias = nextKey.getAlias();
        Slog.i(TAG, "Key rotation to " + nextAlias + " is pending. Checking key sync status.");
        int status = mRecoveryController.getRecoveryStatus(nextAlias);

        if (status == RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE) {
            Slog.e(
                    TAG,
                    "Permanent failure to sync " + nextAlias + ". Cannot possibly rotate to it.");
            mCryptoSettings.removeNextSecondaryKeyAlias();
            return false;
        }

        if (status == RecoveryController.RECOVERY_STATUS_SYNCED) {
            Slog.i(TAG, "Secondary key " + nextAlias + " has now synced! Commencing rotation.");
        } else {
            Slog.i(TAG, "Sync still pending for " + nextAlias);
        }
        return status == RecoveryController.RECOVERY_STATUS_SYNCED;
    }

    /**
     * @throws ActiveSecondaryNotInKeychainException if the currently active secondary key is not in
     *     the keychain.
     * @throws IOException if there is an IO issue communicating with the server or loading from
     *     disk.
     * @throws NoActiveSecondaryKeyException if there is no active key set.
     * @throws IllegalBlockSizeException if there is an issue decrypting a tertiary key.
     * @throws InvalidKeyException if any of the secondary keys cannot be used for wrapping or
     *     unwrapping tertiary keys.
     */
    private void rotateToKey(RecoverableKeyStoreSecondaryKey newSecondaryKey)
            throws ActiveSecondaryNotInKeychainException, IOException,
                    NoActiveSecondaryKeyException, IllegalBlockSizeException, InvalidKeyException,
                    InternalRecoveryServiceException, UnrecoverableKeyException,
                    InvalidAlgorithmParameterException, NoSuchAlgorithmException,
                    NoSuchPaddingException {
        RecoverableKeyStoreSecondaryKey activeSecondaryKey = getActiveSecondaryKey();
        String activeSecondaryKeyAlias = activeSecondaryKey.getAlias();
        String newSecondaryKeyAlias = newSecondaryKey.getAlias();
        if (newSecondaryKeyAlias.equals(activeSecondaryKeyAlias)) {
            Slog.i(TAG, activeSecondaryKeyAlias + " was already the active alias.");
            return;
        }

        TertiaryKeyStore tertiaryKeyStore =
                TertiaryKeyStore.newInstance(mContext, activeSecondaryKey);
        Map<String, SecretKey> tertiaryKeys = tertiaryKeyStore.getAll();

        if (tertiaryKeys.isEmpty()) {
            Slog.i(
                    TAG,
                    "No tertiary keys for " + activeSecondaryKeyAlias + ". No need to rewrap. ");
            mBackupServer.setActiveSecondaryKeyAlias(
                    newSecondaryKeyAlias, /*tertiaryKeys=*/ Collections.emptyMap());
        } else {
            Map<String, WrappedKeyProto.WrappedKey> rewrappedTertiaryKeys =
                    rewrapAll(newSecondaryKey, tertiaryKeys);
            TertiaryKeyStore.newInstance(mContext, newSecondaryKey).putAll(rewrappedTertiaryKeys);
            Slog.i(
                    TAG,
                    "Successfully rewrapped " + rewrappedTertiaryKeys.size() + " tertiary keys");
            mBackupServer.setActiveSecondaryKeyAlias(newSecondaryKeyAlias, rewrappedTertiaryKeys);
            Slog.i(
                    TAG,
                    "Successfully uploaded new set of tertiary keys to "
                            + newSecondaryKeyAlias
                            + " alias");
        }

        mCryptoSettings.setActiveSecondaryKeyAlias(newSecondaryKeyAlias);
        mCryptoSettings.removeNextSecondaryKeyAlias();
        try {
            mRecoveryController.removeKey(activeSecondaryKeyAlias);
        } catch (InternalRecoveryServiceException e) {
            Slog.e(TAG, "Error removing old secondary key from RecoverableKeyStoreLoader", e);
        }
    }

    private RecoverableKeyStoreSecondaryKey getActiveSecondaryKey()
            throws NoActiveSecondaryKeyException, ActiveSecondaryNotInKeychainException,
                    InternalRecoveryServiceException, UnrecoverableKeyException {

        Optional<String> activeSecondaryAlias = mCryptoSettings.getActiveSecondaryKeyAlias();

        if (!activeSecondaryAlias.isPresent()) {
            Slog.i(
                    TAG,
                    "Was asked to rotate secondary key, but local config did not have a secondary "
                            + "key alias set.");
            throw new NoActiveSecondaryKeyException("No local active secondary key set.");
        }

        String activeSecondaryKeyAlias = activeSecondaryAlias.get();
        Optional<RecoverableKeyStoreSecondaryKey> secondaryKey =
                mSecondaryKeyManager.get(activeSecondaryKeyAlias);

        if (!secondaryKey.isPresent()) {
            throw new ActiveSecondaryNotInKeychainException(
                    String.format(
                            Locale.US,
                            "Had local active recoverable key alias of %s but key was not in"
                                + " user's keychain.",
                            activeSecondaryKeyAlias));
        }

        return secondaryKey.get();
    }

    /**
     * Rewraps all the tertiary keys.
     *
     * @param newSecondaryKey The secondary key with which to rewrap the tertiaries.
     * @param tertiaryKeys The tertiary keys, by package name.
     * @return The newly wrapped tertiary keys, by package name.
     * @throws InvalidKeyException if any key is unusable.
     * @throws IllegalBlockSizeException if could not decrypt.
     */
    private Map<String, WrappedKeyProto.WrappedKey> rewrapAll(
            RecoverableKeyStoreSecondaryKey newSecondaryKey, Map<String, SecretKey> tertiaryKeys)
            throws InvalidKeyException, IllegalBlockSizeException, NoSuchPaddingException,
                    NoSuchAlgorithmException {
        Map<String, WrappedKeyProto.WrappedKey> wrappedKeys = new HashMap<>();

        for (String packageName : tertiaryKeys.keySet()) {
            SecretKey tertiaryKey = tertiaryKeys.get(packageName);
            wrappedKeys.put(
                    packageName, KeyWrapUtils.wrap(newSecondaryKey.getSecretKey(), tertiaryKey));
        }

        return wrappedKeys;
    }
}
