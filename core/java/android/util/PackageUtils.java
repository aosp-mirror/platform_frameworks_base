/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.Signature;
import android.text.TextUtils;

import libcore.util.HexEncoding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Helper functions applicable to packages.
 * @hide
 */
public final class PackageUtils {

    private PackageUtils() {
        /* hide constructor */
    }

    /**
     * @see #computeSignaturesSha256Digests(Signature[], String)
     */
    public static @NonNull String[] computeSignaturesSha256Digests(
            @NonNull Signature[] signatures) {
        return computeSignaturesSha256Digests(signatures, null);
    }

    /**
     * Computes the SHA256 digests of a list of signatures. Items in the
     * resulting array of hashes correspond to the signatures in the
     * input array.
     * @param signatures The signatures.
     * @param separator Separator between each pair of characters, such as a colon, or null to omit.
     * @return The digest array.
     */
    public static @NonNull String[] computeSignaturesSha256Digests(
            @NonNull Signature[] signatures, @Nullable String separator) {
        final int signatureCount = signatures.length;
        final String[] digests = new String[signatureCount];
        for (int i = 0; i < signatureCount; i++) {
            digests[i] = computeSha256Digest(signatures[i].toByteArray(), separator);
        }
        return digests;
    }
    /**
     * Computes a SHA256 digest of the signatures' SHA256 digests. First,
     * individual hashes for each signature is derived in a hexademical
     * form, then these strings are sorted based the natural ordering, and
     * finally a hash is derived from these strings' bytes.
     * @param signatures The signatures.
     * @return The digest.
     */
    public static @NonNull String computeSignaturesSha256Digest(
            @NonNull Signature[] signatures) {
        // Shortcut for optimization - most apps singed by a single cert
        if (signatures.length == 1) {
            return computeSha256Digest(signatures[0].toByteArray(), null);
        }

        // Make sure these are sorted to handle reversed certificates
        final String[] sha256Digests = computeSignaturesSha256Digests(signatures, null);
        return computeSignaturesSha256Digest(sha256Digests);
    }

    /**
     * Computes a SHA256 digest in of the signatures SHA256 digests. First,
     * the strings are sorted based the natural ordering, and then a hash is
     * derived from these strings' bytes.
     * @param sha256Digests Signature SHA256 hashes in hexademical form.
     * @return The digest.
     */
    public static @NonNull String computeSignaturesSha256Digest(
            @NonNull String[] sha256Digests) {
        // Shortcut for optimization - most apps singed by a single cert
        if (sha256Digests.length == 1) {
            return sha256Digests[0];
        }

        // Make sure these are sorted to handle reversed certificates
        Arrays.sort(sha256Digests);

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String sha256Digest : sha256Digests) {
            try {
                bytes.write(sha256Digest.getBytes());
            } catch (IOException e) {
                /* ignore - can't happen */
            }
        }
        return computeSha256Digest(bytes.toByteArray(), null);
    }

    /**
     * Computes the SHA256 digest of some data.
     * @param data The data.
     * @return The digest or null if an error occurs.
     */
    public static @Nullable byte[] computeSha256DigestBytes(@NonNull byte[] data) {
        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("SHA256");
        } catch (NoSuchAlgorithmException e) {
            /* can't happen */
            return null;
        }

        messageDigest.update(data);

        return messageDigest.digest();
    }

    /**
     * @see #computeSha256Digest(byte[], String)
     */
    public static @Nullable String computeSha256Digest(@NonNull byte[] data) {
        return computeSha256Digest(data, null);
    }
    /**
     * Computes the SHA256 digest of some data.
     * @param data The data.
     * @param separator Separator between each pair of characters, such as a colon, or null to omit.
     * @return The digest or null if an error occurs.
     */
    public static @Nullable String computeSha256Digest(@NonNull byte[] data,
            @Nullable String separator) {
        byte[] sha256DigestBytes = computeSha256DigestBytes(data);
        if (sha256DigestBytes == null) {
            return null;
        }

        if (separator == null) {
            return HexEncoding.encodeToString(sha256DigestBytes, true /* uppercase */);
        }

        int length = sha256DigestBytes.length;
        String[] pieces = new String[length];
        for (int index = 0; index < length; index++) {
            pieces[index] = HexEncoding.encodeToString(sha256DigestBytes[index], true);
        }

        return TextUtils.join(separator, pieces);
    }
}
