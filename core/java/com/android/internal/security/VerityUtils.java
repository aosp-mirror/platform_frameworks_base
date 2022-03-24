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

package com.android.internal.security;

import android.annotation.NonNull;
import android.os.Build;
import android.os.SystemProperties;
import android.system.Os;
import android.system.OsConstants;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Provides fsverity related operations. */
public abstract class VerityUtils {
    private static final String TAG = "VerityUtils";

    /**
     * File extension of the signature file. For example, foo.apk.fsv_sig is the signature file of
     * foo.apk.
     */
    public static final String FSVERITY_SIGNATURE_FILE_EXTENSION = ".fsv_sig";

    /** The maximum size of signature file.  This is just to avoid potential abuse. */
    private static final int MAX_SIGNATURE_FILE_SIZE_BYTES = 8192;

    /** SHA256 hash size. */
    private static final int HASH_SIZE_BYTES = 32;

    public static boolean isFsVeritySupported() {
        return Build.VERSION.DEVICE_INITIAL_SDK_INT >= Build.VERSION_CODES.R
                || SystemProperties.getInt("ro.apk_verity.mode", 0) == 2;
    }

    /** Returns true if the given file looks like containing an fs-verity signature. */
    public static boolean isFsveritySignatureFile(File file) {
        return file.getName().endsWith(FSVERITY_SIGNATURE_FILE_EXTENSION);
    }

    /** Returns the fs-verity signature file path of the given file. */
    public static String getFsveritySignatureFilePath(String filePath) {
        return filePath + FSVERITY_SIGNATURE_FILE_EXTENSION;
    }

    /** Enables fs-verity for the file with a PKCS#7 detached signature file. */
    public static void setUpFsverity(@NonNull String filePath, @NonNull String signaturePath)
            throws IOException {
        if (Files.size(Paths.get(signaturePath)) > MAX_SIGNATURE_FILE_SIZE_BYTES) {
            throw new SecurityException("Signature file is unexpectedly large: " + signaturePath);
        }
        setUpFsverity(filePath, Files.readAllBytes(Paths.get(signaturePath)));
    }

    /** Enables fs-verity for the file with a PKCS#7 detached signature bytes. */
    public static void setUpFsverity(@NonNull String filePath, @NonNull byte[] pkcs7Signature)
            throws IOException {
        // This will fail if the public key is not already in .fs-verity kernel keyring.
        int errno = enableFsverityNative(filePath, pkcs7Signature);
        if (errno != 0) {
            throw new IOException("Failed to enable fs-verity on " + filePath + ": "
                    + Os.strerror(errno));
        }
    }

    /** Returns whether the file has fs-verity enabled. */
    public static boolean hasFsverity(@NonNull String filePath) {
        int retval = statxForFsverityNative(filePath);
        if (retval < 0) {
            Slog.e(TAG, "Failed to check whether fs-verity is enabled, errno " + -retval + ": "
                    + filePath);
            return false;
        }
        return (retval == 1);
    }

    /** Returns hash of a root node for the fs-verity enabled file. */
    public static byte[] getFsverityRootHash(@NonNull String filePath) {
        byte[] result = new byte[HASH_SIZE_BYTES];
        int retval = measureFsverityNative(filePath, result);
        if (retval < 0) {
            if (retval != -OsConstants.ENODATA) {
                Slog.e(TAG, "Failed to measure fs-verity, errno " + -retval + ": " + filePath);
            }
            return null;
        }
        return result;
    }

    private static native int enableFsverityNative(@NonNull String filePath,
            @NonNull byte[] pkcs7Signature);
    private static native int measureFsverityNative(@NonNull String filePath,
            @NonNull byte[] digest);
    private static native int statxForFsverityNative(@NonNull String filePath);
}
