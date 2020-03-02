/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.utils.blob;

import android.app.blob.BlobStoreManager;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Utils {
    public static final int BUFFER_SIZE_BYTES = 16 * 1024;

    public static void copy(InputStream in, OutputStream out, long lengthBytes)
            throws IOException {
        final byte[] buffer = new byte[BUFFER_SIZE_BYTES];
        long bytesWrittern = 0;
        while (bytesWrittern < lengthBytes) {
            final int toWrite = (bytesWrittern + buffer.length <= lengthBytes)
                    ? buffer.length : (int) (lengthBytes - bytesWrittern);
            in.read(buffer, 0, toWrite);
            out.write(buffer, 0, toWrite);
            bytesWrittern += toWrite;
        }
    }

    public static void writeToSession(BlobStoreManager.Session session, ParcelFileDescriptor input,
            long lengthBytes) throws IOException {
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(input)) {
            writeToSession(session, in, 0, lengthBytes);
        }
    }

    public static void writeToSession(BlobStoreManager.Session session, FileInputStream in,
            long offsetBytes, long lengthBytes) throws IOException {
        in.getChannel().position(offsetBytes);
        try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(
                session.openWrite(offsetBytes, lengthBytes))) {
            copy(in, out, lengthBytes);
        }
    }
}
