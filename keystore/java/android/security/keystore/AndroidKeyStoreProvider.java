/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.security.keystore;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.security.KeyStore;

import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.Mac;

/**
 * A provider focused on providing JCA interfaces for the Android KeyStore.
 *
 * @hide
 */
@SystemApi
public class AndroidKeyStoreProvider extends Provider {
    private static final String PROVIDER_NAME = "AndroidKeyStore";

    /** @hide */
    public AndroidKeyStoreProvider(@NonNull String name) {
        super(name, 1.0, "Android KeyStore security provider");
        throw new IllegalStateException("Should not be instantiated.");
    }

    /**
     * Gets the {@link KeyStore} operation handle corresponding to the provided JCA crypto
     * primitive.
     *
     * <p>The following primitives are supported: {@link Cipher} and {@link Mac}.
     *
     * @return KeyStore operation handle or {@code 0} if the provided primitive's KeyStore operation
     *         is not in progress.
     *
     * @throws IllegalArgumentException if the provided primitive is not supported or is not backed
     *         by AndroidKeyStore provider.
     * @throws IllegalStateException if the provided primitive is not initialized.
     * @hide
     */
    @UnsupportedAppUsage
    public static long getKeyStoreOperationHandle(Object cryptoPrimitive) {
        if (cryptoPrimitive == null) {
            throw new NullPointerException();
        }
        return 0;
    }

    /**
     * Returns an {@code AndroidKeyStore} {@link java.security.KeyStore}} of the specified UID.
     * The {@code KeyStore} contains keys and certificates owned by that UID. Such cross-UID
     * access is permitted to a few system UIDs and only to a few other UIDs (e.g., Wi-Fi, VPN)
     * all of which are system.
     *
     * <p>Note: the returned {@code KeyStore} is already initialized/loaded. Thus, there is
     * no need to invoke {@code load} on it.
     *
     * @param uid Uid for which the keystore provider is requested.
     * @throws KeyStoreException if a KeyStoreSpi implementation for the specified type is not
     * available from the specified provider.
     * @throws NoSuchProviderException If the specified provider is not registered in the security
     * provider list.
     * @hide
     */
    @SystemApi
    @NonNull
    public static java.security.KeyStore getKeyStoreForUid(int uid)
            throws KeyStoreException, NoSuchProviderException {
        final java.security.KeyStore.LoadStoreParameter loadParameter =
                new android.security.keystore2.AndroidKeyStoreLoadStoreParameter(
                        KeyProperties.legacyUidToNamespace(uid));
        java.security.KeyStore result = java.security.KeyStore.getInstance(PROVIDER_NAME);
        try {
            result.load(loadParameter);
        } catch (NoSuchAlgorithmException | CertificateException | IOException e) {
            throw new KeyStoreException(
                    "Failed to load AndroidKeyStore KeyStore for UID " + uid, e);
        }
        return result;
    }
}
