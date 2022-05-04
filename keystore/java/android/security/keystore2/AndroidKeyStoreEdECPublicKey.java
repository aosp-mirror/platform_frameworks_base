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
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Objects;

/**
 * {@link EdECPublicKey} backed by keystore.
 *
 * @hide
 */
public class AndroidKeyStoreEdECPublicKey extends AndroidKeyStorePublicKey
        implements EdECPublicKey {
    /**
     * DER sequence, as defined in https://datatracker.ietf.org/doc/html/rfc8410#section-4 and
     * https://datatracker.ietf.org/doc/html/rfc5280#section-4.1.
     * SEQUENCE (2 elem)
     *  SEQUENCE (1 elem)
     *    OBJECT IDENTIFIER 1.3.101.112 curveEd25519 (EdDSA 25519 signature algorithm)
     *    as defined in https://datatracker.ietf.org/doc/html/rfc8410#section-3
     *  BIT STRING (256 bit) as defined in
     *  https://datatracker.ietf.org/doc/html/rfc8032#section-5.1.2
     */
    private static final byte[] DER_KEY_PREFIX = new byte[] {
            0x30,
            0x2a,
            0x30,
            0x05,
            0x06,
            0x03,
            0x2b,
            0x65,
            0x70,
            0x03,
            0x21,
            0x00,
    };
    private static final int ED25519_KEY_SIZE_BYTES = 32;

    private byte[] mEncodedKey;
    private EdECPoint mPoint;

    public AndroidKeyStoreEdECPublicKey(
            @NonNull KeyDescriptor descriptor,
            @NonNull KeyMetadata metadata,
            @NonNull String algorithm,
            @NonNull KeyStoreSecurityLevel iSecurityLevel,
            @NonNull byte[] encodedKey) {
        super(descriptor, metadata, encodedKey, algorithm, iSecurityLevel);
        mEncodedKey = encodedKey;

        int preambleLength = matchesPreamble(DER_KEY_PREFIX, encodedKey);
        if (preambleLength == 0) {
            throw new IllegalArgumentException("Key size is not correct size");
        }

        mPoint = pointFromKeyByteArray(
                Arrays.copyOfRange(encodedKey, preambleLength, encodedKey.length));
    }

    @Override
    AndroidKeyStorePrivateKey getPrivateKey() {
        return new AndroidKeyStoreEdECPrivateKey(
                getUserKeyDescriptor(),
                getKeyIdDescriptor().nspace,
                getAuthorizations(),
                "EdDSA",
                getSecurityLevel());
    }

    @Override
    public NamedParameterSpec getParams() {
        return NamedParameterSpec.ED25519;
    }

    @Override
    public EdECPoint getPoint() {
        return mPoint;
    }

    private static int matchesPreamble(byte[] preamble, byte[] encoded) {
        if (encoded.length != (preamble.length + ED25519_KEY_SIZE_BYTES)) {
            return 0;
        }
        if (Arrays.compare(preamble, Arrays.copyOf(encoded, preamble.length)) != 0) {
            return 0;
        }
        return preamble.length;
    }

    private static EdECPoint pointFromKeyByteArray(byte[] coordinates) {
        Objects.requireNonNull(coordinates);

        // Oddity of the key is the most-significant bit of the last byte.
        boolean isOdd = (0x80 & coordinates[coordinates.length - 1]) != 0;
        // Zero out the oddity bit.
        coordinates[coordinates.length - 1] &= (byte) 0x7f;
        // Representation of Y is in little-endian, according to rfc8032 section-3.1.
        reverse(coordinates);
        // The integer representing Y starts from the first bit in the coordinates array.
        BigInteger y = new BigInteger(1, coordinates);
        return new EdECPoint(isOdd, y);
    }

    private static void reverse(byte[] coordinateArray) {
        int start = 0;
        int end = coordinateArray.length - 1;
        while (start < end) {
            byte tmp = coordinateArray[start];
            coordinateArray[start] = coordinateArray[end];
            coordinateArray[end] = tmp;
            start++;
            end--;
        }
    }

    @Override
    public byte[] getEncoded() {
        return mEncodedKey.clone();
    }
}
