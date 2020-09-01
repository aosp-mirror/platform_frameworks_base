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

import android.security.keystore.recovery.InternalRecoveryServiceException;

import com.android.server.backup.encryption.keys.RecoverableKeyStoreSecondaryKeyManager.RecoverableKeyStoreSecondaryKeyManagerProvider;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.Optional;

import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

/** Fetches the secondary key and uses it to unwrap the tertiary key during restore. */
public class RestoreKeyFetcher {

    /**
     * Retrieves the secondary key with the given alias and uses it to unwrap the given wrapped
     * tertiary key.
     *
     * @param secondaryKeyManagerProvider Provider which creates {@link
     *     RecoverableKeyStoreSecondaryKeyManager}
     * @param secondaryKeyAlias Alias of the secondary key used to wrap the tertiary key
     * @param wrappedTertiaryKey Tertiary key wrapped with the secondary key above
     * @return The unwrapped tertiary key
     */
    public static SecretKey unwrapTertiaryKey(
            RecoverableKeyStoreSecondaryKeyManagerProvider secondaryKeyManagerProvider,
            String secondaryKeyAlias,
            WrappedKeyProto.WrappedKey wrappedTertiaryKey)
            throws KeyException, InvalidAlgorithmParameterException, NoSuchAlgorithmException,
                    NoSuchPaddingException {
        Optional<RecoverableKeyStoreSecondaryKey> secondaryKey =
                getSecondaryKey(secondaryKeyManagerProvider, secondaryKeyAlias);
        if (!secondaryKey.isPresent()) {
            throw new KeyException("No key:" + secondaryKeyAlias);
        }

        return KeyWrapUtils.unwrap(secondaryKey.get().getSecretKey(), wrappedTertiaryKey);
    }

    private static Optional<RecoverableKeyStoreSecondaryKey> getSecondaryKey(
            RecoverableKeyStoreSecondaryKeyManagerProvider secondaryKeyManagerProvider,
            String secondaryKeyAlias)
            throws KeyException {
        try {
            return secondaryKeyManagerProvider.get().get(secondaryKeyAlias);
        } catch (InternalRecoveryServiceException | UnrecoverableKeyException e) {
            throw new KeyException("Could not retrieve key:" + secondaryKeyAlias, e);
        }
    }
}
