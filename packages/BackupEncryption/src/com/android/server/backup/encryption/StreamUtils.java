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

package com.android.server.backup.encryption;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Utility methods for dealing with Streams */
public class StreamUtils {
    private static final int MAX_COPY_BUFFER_SIZE = 1024; // 1k copy buffer size.

    /**
     * Close a Closeable and silently ignore any IOExceptions.
     *
     * @param closeable The closeable to close
     */
    public static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ioe) {
            // Silently ignore
        }
    }

    /**
     * Copy data from an InputStream to an OutputStream upto a given number of bytes.
     *
     * @param in The source InputStream
     * @param out The destination OutputStream
     * @param limit The maximum number of bytes to copy
     * @throws IOException Thrown if there is a problem performing the copy.
     */
    public static void copyStream(InputStream in, OutputStream out, int limit) throws IOException {
        int bufferSize = Math.min(MAX_COPY_BUFFER_SIZE, limit);
        byte[] buffer = new byte[bufferSize];

        int copied = 0;
        while (copied < limit) {
            int maxReadSize = Math.min(bufferSize, limit - copied);
            int read = in.read(buffer, 0, maxReadSize);
            if (read < 0) {
                return; // Reached the stream end before the limit
            }
            out.write(buffer, 0, read);
            copied += read;
        }
    }
}
