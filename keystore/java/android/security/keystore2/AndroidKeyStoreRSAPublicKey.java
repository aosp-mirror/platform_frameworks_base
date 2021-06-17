/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.keystore2;

import android.annotation.NonNull;
import android.security.KeyStoreSecurityLevel;
import android.security.keystore.KeyProperties;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyMetadata;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;

/**
 * {@link RSAPublicKey} backed by Android Keystore.
 *
 * @hide
 */
public class AndroidKeyStoreRSAPublicKey extends AndroidKeyStorePublicKey implements RSAPublicKey {
    private final BigInteger mModulus;
    private final BigInteger mPublicExponent;

    public AndroidKeyStoreRSAPublicKey(@NonNull KeyDescriptor descriptor,
            @NonNull KeyMetadata metadata,
            @NonNull byte[] x509EncodedForm,
            @NonNull KeyStoreSecurityLevel securityLevel, @NonNull BigInteger modulus,
            @NonNull BigInteger publicExponent) {
        super(descriptor, metadata, x509EncodedForm, KeyProperties.KEY_ALGORITHM_RSA,
                securityLevel);
        mModulus = modulus;
        mPublicExponent = publicExponent;
    }

    public AndroidKeyStoreRSAPublicKey(@NonNull KeyDescriptor descriptor,
            @NonNull KeyMetadata metadata,
            @NonNull KeyStoreSecurityLevel securityLevel, @NonNull RSAPublicKey info) {
        this(descriptor, metadata, info.getEncoded(), securityLevel, info.getModulus(),
                info.getPublicExponent());
        if (!"X.509".equalsIgnoreCase(info.getFormat())) {
            throw new IllegalArgumentException(
                    "Unsupported key export format: " + info.getFormat());
        }
    }

    @Override
    public AndroidKeyStorePrivateKey getPrivateKey() {
        return new AndroidKeyStoreRSAPrivateKey(getUserKeyDescriptor(), getKeyIdDescriptor().nspace,
                getAuthorizations(), getSecurityLevel(), mModulus);
    }

    @Override
    public BigInteger getModulus() {
        return mModulus;
    }

    @Override
    public BigInteger getPublicExponent() {
        return mPublicExponent;
    }
}
