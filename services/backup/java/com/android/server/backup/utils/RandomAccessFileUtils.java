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

package com.android.server.backup.utils;

import static com.android.server.backup.BackupManagerService.TAG;

import android.util.Slog;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Utility methods useful for working with backup related RandomAccessFiles. */
public final class RandomAccessFileUtils {
    private static RandomAccessFile getRandomAccessFile(File file) throws FileNotFoundException {
        return new RandomAccessFile(file, "rwd");
    }

    /** Write a boolean to a File by wrapping it using a RandomAccessFile. */
    public static void writeBoolean(File file, boolean b) {
        try (RandomAccessFile af = getRandomAccessFile(file)) {
            af.writeBoolean(b);
        } catch (IOException e) {
            Slog.w(TAG, "Error writing file:" + file.getAbsolutePath(), e);
        }
    }

    /** Read a boolean from a File by wrapping it using a RandomAccessFile. */
    public static boolean readBoolean(File file, boolean def) {
        try (RandomAccessFile af = getRandomAccessFile(file)) {
            return af.readBoolean();
        } catch (IOException e) {
            Slog.w(TAG, "Error reading file:" + file.getAbsolutePath(), e);
        }
        return def;
    }
}
