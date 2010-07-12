/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.dumprendertree2;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 *
 */
public class FsUtils {
    public static final String LOG_TAG = "FsUtils";

    public static void writeDataToStorage(File file, byte[] bytes, boolean append) {
        Log.d(LOG_TAG + "::writeDataToStorage", file.getAbsolutePath());
        try {
            OutputStream outputStream = null;
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
                Log.d(LOG_TAG + "::writeDataToStorage", "File created.");
                outputStream = new FileOutputStream(file, append);
                outputStream.write(bytes);
            } finally {
                if (outputStream != null) {
                    outputStream.close();
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG + "::writeDataToStorage", e.getMessage());
        }
    }
}
