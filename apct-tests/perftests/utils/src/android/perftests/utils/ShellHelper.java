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
 * limitations under the License.
 */
package android.perftests.utils;

import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;

import java.io.FileInputStream;

/**
 * Provides Shell-based utilities such as running a command.
 */
public final class ShellHelper {

    /**
     * Runs a Shell command, returning a trimmed response.
     */
    @NonNull
    public static String runShellCommand(@NonNull String template, Object...args) {
        String command = String.format(template, args);
        return runShellCommandRaw(command);
    }

    /**
     * Runs a Shell command, returning a trimmed response.
     */
    @NonNull
    public static String runShellCommandRaw(@NonNull String command) {
        UiAutomation automan = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation();
        ParcelFileDescriptor pfd = automan.executeShellCommand(command);
        byte[] buf = new byte[512];
        int bytesRead;
        try(FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            StringBuilder stdout = new StringBuilder();
            while ((bytesRead = fis.read(buf)) != -1) {
                stdout.append(new String(buf, 0, bytesRead));
            }
            String result = stdout.toString();
            return TextUtils.isEmpty(result) ? "" : result.trim();
        } catch (Exception e) {
            throw new AndroidRuntimeException("Command '" + command + "' failed: ", e);
        } finally {
            // Must disconnect UI automation after every call, otherwise its accessibility service
            // skews the performance tests.
            automan.destroy();
        }
    }

    private ShellHelper() {
        throw new UnsupportedOperationException("contain static methods only");
    }
}
