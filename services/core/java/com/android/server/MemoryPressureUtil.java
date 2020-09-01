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

package com.android.server;

import android.os.StrictMode;
import android.util.Slog;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;

/**
 * Utility method for memory pressure (PSI).
 */
public final class MemoryPressureUtil {
    private static final String FILE = "/proc/pressure/memory";
    private static final String TAG = "MemoryPressure";

    /**
     * @return a stanza about memory PSI to add to a report.
     */
    public static String currentPsiState() {
        final StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskReads();
        StringWriter contents = new StringWriter();
        try {
            if (new File(FILE).exists()) {
                contents.append("----- Output from /proc/pressure/memory -----\n");
                contents.append(IoUtils.readFileAsString(FILE));
                contents.append("----- End output from /proc/pressure/memory -----\n\n");
            }
        } catch (IOException e) {
            Slog.e(TAG, "Could not read " + FILE, e);
        } finally {
            StrictMode.setThreadPolicy(savedPolicy);
        }
        return contents.toString();
    }

    private MemoryPressureUtil(){}
}
