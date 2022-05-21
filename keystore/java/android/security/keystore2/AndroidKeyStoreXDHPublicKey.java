/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyMetadata;

import java.math.BigInteger;
import java.security.interfaces.XECPublicKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;

/**
 * {@link XECPublicKey} backed by keystore.
 * This class re-implements Conscrypt's OpenSSLX25519PublicKey. The reason is that
 * OpenSSLX25519PublicKey does not implement XECPublicKey and is not a part of Conscrypt's public
 * interface so it cannot be referred to.
 *
 * So the functionality is duplicated here until (likely Android U) one of the things mentioned
 * above is fixed.
 *
 * @hide
 */
public class AndroidKeyStoreXDHPublicKey extends AndroidKeyStorePublicKey implements XECPublicKey {
    private static final byte[] X509_PREAMBLE = new byte[] {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00,
    };

    private static final byte[] X509_PREAMBLE_WITH_NULL = new byte[] {
            0x30, 0x2C, 0x30, 0x07, 0x06, 0x03, 0x2B, 0x65, 0x6E, 0x05, 0x00, 0x03, 0x21, 0x00,
    };

    private static final int X25519_KEY_SIZE_BYTES = 32;

    private final byte[] mEncodedKey;
    private final int mPreambleLength;

    public AndroidKeyStoreXDHPublicKey(
            @NonNull KeyDescriptor descriptor,
            @NonNull KeyMetadata metadata,
            @NonNull String algorithm,
            @NonNull KeyStoreSecurityLevel iSecurityLevel,
            @NonNull byte[] encodedKey) {
        super(descriptor, metadata, encodedKey, algorithm, iSecurityLevel);
        mEncodedKey = encodedKey;
        if (mEncodedKey == null) {
            throw new IllegalArgumentException("empty encoded key.");
        }

        mPreambleLength = matchesPreamble(X509_PREAMBLE, mEncodedKey) | matchesPreamble(
                X509_PREAMBLE_WITH_NULL, mEncodedKey);
        if (mPreambleLength == 0) {
            throw new IllegalArgumentException("Key size is not correct size");
        }
    }

    private static int matchesPreamble(byte[] preamble, byte[] encoded) {
        if (encoded.length != (preamble.length + X25519_KEY_SIZE_BYTES)) {
            return 0;
        }

        if (Arrays.compare(preamble, 0, preamble.length, encoded, 0, preamble.length) != 0) {
            return 0;
        }
        return preamble.length;
    }

    @Override
    AndroidKeyStorePrivateKey getPrivateKey() {
        return new AndroidKeyStoreXDHPrivateKey(
                getUserKeyDescriptor(),
                getKeyIdDescriptor().nspace,
                getAuthorizations(),
                "x25519",
                getSecurityLevel());
    }

    @Override
    public BigInteger getU() {
        return new BigInteger(Arrays.copyOfRange(mEncodedKey, mPreambleLength, mEncodedKey.length));
    }

    @Override
    public byte[] getEncoded() {
        return mEncodedKey.clone();
    }

    @Override
    public String getAlgorithm() {
        return "XDH";
    }

    @Override
    public String getFormat() {
        return "x.509";
    }

    @Override
    public AlgorithmParameterSpec getParams() {
        return NamedParameterSpec.X25519;
    }
}

