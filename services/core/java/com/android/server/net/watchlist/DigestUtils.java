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

package com.android.server.net.watchlist;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utils for calculating digests.
 */
public class DigestUtils {

    private static final int FILE_READ_BUFFER_SIZE = 16 * 1024;

    private DigestUtils() {}

    /** @return SHA256 hash of the provided file */
    public static byte[] getSha256Hash(File apkFile) throws IOException, NoSuchAlgorithmException {
        try (InputStream stream = new FileInputStream(apkFile)) {
            return getSha256Hash(stream);
        }
    }

    /** @return SHA256 hash of data read from the provided input stream */
    public static byte[] getSha256Hash(InputStream stream)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digester = MessageDigest.getInstance("SHA256");

        int bytesRead;
        byte[] buf = new byte[FILE_READ_BUFFER_SIZE];
        while ((bytesRead = stream.read(buf)) >= 0) {
            digester.update(buf, 0, bytesRead);
        }
        return digester.digest();
    }
}