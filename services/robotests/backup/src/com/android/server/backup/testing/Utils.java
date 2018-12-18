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
 * limitations under the License
 */

package com.android.server.backup.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

public class Utils {
    public static final int BUFFER_SIZE = 8192;

    public static void transferStreamedData(InputStream in, OutputStream out) throws IOException {
        transferStreamedData(in, out, BUFFER_SIZE);
    }

    public static void transferStreamedData(InputStream in, OutputStream out, int bufferSize)
            throws IOException {
        byte[] buffer = new byte[bufferSize];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    public static <T> Iterable<T> oneTimeIterable(Iterator<T> iterator) {
        return () -> iterator;
    }

    public static boolean isFileNonEmpty(Path path) throws IOException {
        return Files.exists(path) && Files.size(path) > 0;
    }

    private Utils() {}
}
