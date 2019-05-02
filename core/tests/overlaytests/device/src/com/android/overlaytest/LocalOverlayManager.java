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

package com.android.overlaytest;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.app.UiAutomation;
import android.content.res.Resources;
import android.os.ParcelFileDescriptor;

import androidx.test.InstrumentationRegistry;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

class LocalOverlayManager {
    private static final long TIMEOUT = 30;

    public static void setEnabledAndWait(Executor executor, final String packageName,
            boolean enable) throws Exception {
        final String pattern = (enable ? "[x]" : "[ ]") + " " + packageName;
        if (executeShellCommand("cmd overlay list").contains(pattern)) {
            // nothing to do, overlay already in the requested state
            return;
        }

        final Resources res = InstrumentationRegistry.getContext().getResources();
        final String[] oldApkPaths = res.getAssets().getApkPaths();
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            while (true) {
                if (!Arrays.equals(oldApkPaths, res.getAssets().getApkPaths())) {
                    return true;
                }
                Thread.sleep(10);
            }
        });
        executor.execute(task);
        executeShellCommand("cmd overlay " + (enable ? "enable " : "disable ") + packageName);
        task.get(TIMEOUT, SECONDS);
    }

    private static String executeShellCommand(final String command)
            throws Exception {
        final UiAutomation uiAutomation =
                InstrumentationRegistry.getInstrumentation().getUiAutomation();
        final ParcelFileDescriptor pfd = uiAutomation.executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder str = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                str.append(line);
            }
            return str.toString();
        }
    }
}
