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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.IntDef;
import android.content.Context;
import android.security.keystore.recovery.InternalRecoveryServiceException;
import android.security.keystore.recovery.RecoveryController;
import android.util.Slog;

import javax.crypto.SecretKey;

/**
 * Wraps a {@link RecoveryController}'s {@link SecretKey}. These are kept in "AndroidKeyStore" (a
 * provider for {@link java.security.KeyStore} and {@link javax.crypto.KeyGenerator}. They are also
 * synced with the recoverable key store, wrapped by the primary key. This allows them to be
 * recovered on a user's subsequent device through providing their lock screen secret.
 */
public class RecoverableKeyStoreSecondaryKey {
    private static final String TAG = "RecoverableKeyStoreSecondaryKey";

    private final String mAlias;
    private final SecretKey mSecretKey;

    /**
     * A new instance.
     *
     * @param alias The alias. It is keyed with this in AndroidKeyStore and the recoverable key
     *     store.
     * @param secretKey The key.
     */
    public RecoverableKeyStoreSecondaryKey(String alias, SecretKey secretKey) {
        mAlias = checkNotNull(alias);
        mSecretKey = checkNotNull(secretKey);
    }

    /**
     * The ID, as stored in the recoverable {@link java.security.KeyStore}, and as used to identify
     * wrapped tertiary keys on the backup server.
     */
    public String getAlias() {
        return mAlias;
    }

    /** The secret key, to be used to wrap tertiary keys. */
    public SecretKey getSecretKey() {
        return mSecretKey;
    }

    /**
     * The status of the key. i.e., whether it's been synced to remote trusted hardware.
     *
     * @param context The application context.
     * @return One of {@link Status#SYNCED}, {@link Status#NOT_SYNCED} or {@link Status#DESTROYED}.
     */
    public @Status int getStatus(Context context) {
        try {
            return getStatusInternal(context);
        } catch (InternalRecoveryServiceException e) {
            Slog.wtf(TAG, "Internal error getting recovery status", e);
            // Return NOT_SYNCED by default, as we do not want the backups to fail or to repeatedly
            // attempt to reinitialize.
            return Status.NOT_SYNCED;
        }
    }

    private @Status int getStatusInternal(Context context) throws InternalRecoveryServiceException {
        int status = RecoveryController.getInstance(context).getRecoveryStatus(mAlias);
        switch (status) {
            case RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE:
                return Status.DESTROYED;
            case RecoveryController.RECOVERY_STATUS_SYNCED:
                return Status.SYNCED;
            case RecoveryController.RECOVERY_STATUS_SYNC_IN_PROGRESS:
                return Status.NOT_SYNCED;
            default:
                // Throw an exception if we encounter a status that doesn't match any of the above.
                throw new InternalRecoveryServiceException(
                        "Unexpected status from getRecoveryStatus: " + status);
        }
    }

    /** Status of a key in the recoverable key store. */
    @IntDef({Status.NOT_SYNCED, Status.SYNCED, Status.DESTROYED})
    public @interface Status {
        /**
         * The key has not yet been synced to remote trusted hardware. This may be because the user
         * has not yet unlocked their device.
         */
        int NOT_SYNCED = 1;

        /**
         * The key has been synced with remote trusted hardware. It should now be recoverable on
         * another device.
         */
        int SYNCED = 2;

        /** The key has been lost forever. This can occur if the user disables their lock screen. */
        int DESTROYED = 3;
    }
}
