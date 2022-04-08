/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore.storage;

import static android.security.keystore.recovery.RecoveryController.ERROR_SERVICE_INTERNAL_ERROR;

import android.annotation.Nullable;
import android.os.ServiceSpecificException;
import android.security.Credentials;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.security.KeyStore;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.locksettings.recoverablekeystore.KeyStoreProxy;
import com.android.server.locksettings.recoverablekeystore.KeyStoreProxyImpl;

import java.security.KeyStore.SecretKeyEntry;
import java.security.KeyStoreException;
import java.util.Locale;

import javax.crypto.spec.SecretKeySpec;

/**
 * Storage for Application keys in LockSettings service KeyStore namespace.
 *
 * <p> Uses KeyStore's grant mechanism to make keys usable by application process without
 * revealing key material
 */
public class ApplicationKeyStorage {
    private static final String TAG = "RecoverableAppKeyStore";

    private static final String APPLICATION_KEY_ALIAS_PREFIX =
            "com.android.server.locksettings.recoverablekeystore/application/";

    private final KeyStoreProxy mKeyStore;
    private final KeyStore mKeystoreService;

    public static ApplicationKeyStorage getInstance(KeyStore keystoreService)
            throws KeyStoreException {
        return new ApplicationKeyStorage(
                new KeyStoreProxyImpl(KeyStoreProxyImpl.getAndLoadAndroidKeyStore()),
                keystoreService);
    }

    @VisibleForTesting
    ApplicationKeyStorage(KeyStoreProxy keyStore, KeyStore keystoreService) {
        mKeyStore = keyStore;
        mKeystoreService = keystoreService;
    }

    /**
     * Returns grant alias, valid in Applications namespace.
     */
    public @Nullable String getGrantAlias(int userId, int uid, String alias) {
        // Aliases used by {@link KeyStore} are different than used by public API.
        // {@code USER_PRIVATE_KEY} prefix is used secret keys.
        Log.i(TAG, String.format(Locale.US, "Get %d/%d/%s", userId, uid, alias));
        String keystoreAlias = Credentials.USER_PRIVATE_KEY + getInternalAlias(userId, uid, alias);
        return mKeystoreService.grant(keystoreAlias, uid);
    }

    public void setSymmetricKeyEntry(int userId, int uid, String alias, byte[] secretKey)
            throws KeyStoreException {
        Log.i(TAG, String.format(Locale.US, "Set %d/%d/%s: %d bytes of key material",
                userId, uid, alias, secretKey.length));
        try {
            mKeyStore.setEntry(
                getInternalAlias(userId, uid, alias),
                new SecretKeyEntry(
                    new SecretKeySpec(secretKey, KeyProperties.KEY_ALGORITHM_AES)),
                new KeyProtection.Builder(
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
        } catch (KeyStoreException e) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        }
    }

    public void deleteEntry(int userId, int uid, String alias) {
        Log.i(TAG, String.format(Locale.US, "Del %d/%d/%s", userId, uid, alias));
        try {
            mKeyStore.deleteEntry(getInternalAlias(userId, uid, alias));
        } catch (KeyStoreException e) {
            throw new ServiceSpecificException(ERROR_SERVICE_INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * Returns the alias in locksettins service's KeyStore namespace used for given application key.
     *
     * <p>These IDs look as follows:
     * {@code com.security.recoverablekeystore/application/<userId>/<uid>/<alias>}
     *
     * @param userId The ID of the user
     * @param uid The uid
     * @param alias - alias in application's namespace
     * @return The alias.
     */
    private String getInternalAlias(int userId, int uid, String alias) {
        return APPLICATION_KEY_ALIAS_PREFIX + userId + "/" + uid + "/" + alias;
    }
}
