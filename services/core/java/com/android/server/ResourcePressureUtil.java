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
import java.util.Arrays;
import java.util.List;

/**
 * Utility method for resource pressure (PSI).
 */
public final class ResourcePressureUtil {

    private static final String PSI_ROOT = "/proc/pressure";
    private static final String TAG = "ResourcePressureUtil";
    private static final List<String> PSI_FILES = Arrays.asList(
            PSI_ROOT + "/memory",
            PSI_ROOT + "/cpu",
            PSI_ROOT + "/io"
    );

    private static String readResourcePsiState(String filePath) {
        StringWriter contents = new StringWriter();
        try {
            if (new File(filePath).exists()) {
                contents.append("----- Output from " + filePath + " -----\n");
                contents.append(IoUtils.readFileAsString(filePath));
                contents.append("----- End output from " + filePath + " -----\n");
            }
        } catch (IOException e) {
            Slog.e(TAG, " could not read " + filePath, e);
        }
        return contents.toString();
    }

    /**
     * @return a stanza about PSI to add to a report.
     */
    public static String currentPsiState() {
        final StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskReads();
        StringWriter aggregatedState = new StringWriter();

        try {
            PSI_FILES.stream()
                .map(ResourcePressureUtil::readResourcePsiState)
                .forEach(aggregatedState::append);
        } finally {
            StrictMode.setThreadPolicy(savedPolicy);
        }

        String psiState = aggregatedState.toString();

        return psiState.length() > 0 ? psiState + "\n" : psiState;
    }

    private ResourcePressureUtil(){}
}
