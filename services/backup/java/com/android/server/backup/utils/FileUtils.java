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
import java.io.IOException;

/** Utility methods useful for working with backup related files. */
public final class FileUtils {
    /**
     * Ensure that the file exists in the file system. If an IOException is thrown, it is ignored.
     * This method is useful to avoid code duplication of the "try-catch-ignore exception" block.
     */
    public static File createNewFile(File file) {
        try {
            file.createNewFile();
        } catch (IOException e) {
            Slog.w(TAG, "Failed to create file:" + file.getAbsolutePath(), e);
        }
        return file;
    }
}
