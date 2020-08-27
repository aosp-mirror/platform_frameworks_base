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

//TODO (165884885): Make PerfettoTrigger more generic and move it to another package.
package com.android.internal.jank;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A trigger implementation with perfetto backend.
 * @hide
 */
public class PerfettoTrigger {
    private static final String TAG = PerfettoTrigger.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final String TRIGGER_COMMAND = "/system/bin/trigger_perfetto";
    private static final String[] TRIGGER_TYPE_NAMES = new String[] { "jank-tracker" };
    public static final int TRIGGER_TYPE_JANK = 0;

    /** @hide */
    @IntDef({
            TRIGGER_TYPE_JANK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TriggerType {}

    /**
     * @param type the trigger type
     */
    public static void trigger(@NonNull @TriggerType int type) {
        try {
            Token token = new Token(type, TRIGGER_TYPE_NAMES[type]);
            ProcessBuilder pb = new ProcessBuilder(TRIGGER_COMMAND, token.getName());
            if (DEBUG) {
                StringBuilder sb = new StringBuilder();
                for (String arg : pb.command()) {
                    sb.append(arg).append(" ");
                }
                Log.d(TAG, "Triggering " + sb.toString());
            }
            Process process = pb.start();
            if (DEBUG) {
                readConsoleOutput(process);
            }
        } catch (IOException | InterruptedException e) {
            Log.w(TAG, "Failed to trigger " + type, e);
        }
    }

    private static void readConsoleOutput(@NonNull Process process)
            throws IOException, InterruptedException {
        process.waitFor();
        try (BufferedReader errReader =
                     new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            StringBuilder errLine = new StringBuilder();
            String line;
            while ((line = errReader.readLine()) != null) {
                errLine.append(line).append("\n");
            }
            errLine.append(", code=").append(process.exitValue());
            Log.d(TAG, "err message=" + errLine.toString());
        }
    }

    /**
     * Token which is used to trigger perfetto.
     */
    public static class Token {
        private int mType;
        private String mName;

        Token(@TriggerType int type, String name) {
            mType = type;
            mName = name;
        }

        /**
         * Get trigger type.
         * @return trigger type, should be @TriggerType
         */
        public int getType() {
            return mType;
        }

        /**
         * Get name of this token as the argument while triggering perfetto.
         * @return name
         */
        public String getName() {
            return mName;
        }
    }

}
