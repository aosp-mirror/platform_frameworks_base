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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Helper functions applicable to packages.
 * @hide
 */
public final class PackageUtils {
    private final static char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    private PackageUtils() {
        /* hide constructor */
    }

    /**
     * Computes the SHA256 digest of the signing cert for a package.
     * @param packageManager The package manager.
     * @param packageName The package for which to generate the digest.
     * @param userId The user for which to generate the digest.
     * @return The digest or null if the package does not exist for this user.
     */
    public static @Nullable String computePackageCertSha256Digest(
            @NonNull PackageManager packageManager,
            @NonNull String packageName, int userId) {
        final PackageInfo packageInfo;
        try {
            packageInfo = packageManager.getPackageInfoAsUser(packageName,
                    PackageManager.GET_SIGNATURES, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        return computeCertSha256Digest(packageInfo.signatures[0]);
    }

    /**
     * Computes the SHA256 digest of a cert.
     * @param signature The signature.
     * @return The digest or null if an error occurs.
     */
    public static @Nullable String computeCertSha256Digest(@NonNull Signature signature) {
        return computeSha256Digest(signature.toByteArray());
    }

    /**
     * Computes the SHA256 digest of some data.
     * @param data The data.
     * @return The digest or null if an error occurs.
     */
    public static @Nullable String computeSha256Digest(@NonNull byte[] data) {
        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("SHA256");
        } catch (NoSuchAlgorithmException e) {
            /* can't happen */
            return null;
        }

        messageDigest.update(data);

        final byte[] digest = messageDigest.digest();
        final int digestLength = digest.length;
        final int charCount = 2 * digestLength;

        final char[] chars = new char[charCount];
        for (int i = 0; i < digestLength; i++) {
            final int byteHex = digest[i] & 0xFF;
            chars[i * 2] = HEX_ARRAY[byteHex >>> 4];
            chars[i * 2 + 1] = HEX_ARRAY[byteHex & 0x0F];
        }
        return new String(chars);
    }
}
