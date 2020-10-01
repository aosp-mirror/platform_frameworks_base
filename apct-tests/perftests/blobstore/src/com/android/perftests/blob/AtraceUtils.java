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

package com.android.perftests.blob;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import android.perftests.utils.TraceMarkParser;
import android.perftests.utils.TraceMarkParser.TraceMarkSlice;
import android.support.test.uiautomator.UiDevice;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.function.BiConsumer;

// Copy of com.android.frameworks.perftests.am.util.AtraceUtils. TODO: avoid this duplication.
public class AtraceUtils {
    private static final String TAG = "AtraceUtils";
    private static final boolean VERBOSE = true;

    private static final String ATRACE_START = "atrace --async_start -b %d -c %s";
    private static final String ATRACE_DUMP = "atrace --async_dump";
    private static final String ATRACE_STOP = "atrace --async_stop";
    private static final int DEFAULT_ATRACE_BUF_SIZE = 1024;

    private UiAutomation mAutomation;
    private static AtraceUtils sUtils = null;
    private boolean mStarted = false;

    private AtraceUtils(Instrumentation instrumentation) {
        mAutomation = instrumentation.getUiAutomation();
    }

    public static AtraceUtils getInstance(Instrumentation instrumentation) {
        if (sUtils == null) {
            sUtils = new AtraceUtils(instrumentation);
        }
        return sUtils;
    }

    /**
     * @param categories The list of the categories to trace, separated with space.
     */
    public void startTrace(String categories) {
        synchronized (this) {
            if (mStarted) {
                throw new IllegalStateException("atrace already started");
            }
            runShellCommand(String.format(
                    ATRACE_START, DEFAULT_ATRACE_BUF_SIZE, categories));
            mStarted = true;
        }
    }

    public void stopTrace() {
        synchronized (this) {
            mStarted = false;
            runShellCommand(ATRACE_STOP);
        }
    }

    private String runShellCommand(String cmd) {
        try {
            return UiDevice.getInstance(
                    InstrumentationRegistry.getInstrumentation()).executeShellCommand(cmd);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param parser The function that can accept the buffer of atrace dump and parse it.
     * @param handler The parse result handler
     */
    public void performDump(TraceMarkParser parser,
            BiConsumer<String, List<TraceMarkSlice>> handler) {
        parser.reset();
        try {
            if (VERBOSE) {
                Log.i(TAG, "Collecting atrace dump...");
            }
            writeDataToBuf(mAutomation.executeShellCommand(ATRACE_DUMP), parser);
        } catch (IOException e) {
            Log.e(TAG, "Error in reading dump", e);
        }
        parser.forAllSlices(handler);
    }

    // The given file descriptor here will be closed by this function
    private void writeDataToBuf(ParcelFileDescriptor pfDescriptor,
            TraceMarkParser parser) throws IOException {
        InputStream inputStream = new ParcelFileDescriptor.AutoCloseInputStream(pfDescriptor);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parser.visit(line);
            }
        }
    }
}
