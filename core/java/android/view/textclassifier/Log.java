/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.textclassifier;

/**
 * Logging for android.view.textclassifier package.
 * <p>
 * To enable full log:
 * 1. adb shell setprop log.tag.androidtc VERBOSE
 * 2. adb shell stop && adb shell start
 */
final class Log {

    /**
     * true: Enables full logging.
     * false: Limits logging to debug level.
     */
    static final boolean ENABLE_FULL_LOGGING =
            android.util.Log.isLoggable(TextClassifier.DEFAULT_LOG_TAG, android.util.Log.VERBOSE);

    private Log() {
    }

    public static void v(String tag, String msg) {
        if (ENABLE_FULL_LOGGING) {
            android.util.Log.v(tag, msg);
        }
    }

    public static void d(String tag, String msg) {
        android.util.Log.d(tag, msg);
    }

    public static void w(String tag, String msg) {
        android.util.Log.w(tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (ENABLE_FULL_LOGGING) {
            android.util.Log.e(tag, msg, tr);
        } else {
            final String trString = (tr != null) ? tr.getClass().getSimpleName() : "??";
            android.util.Log.d(tag, String.format("%s (%s)", msg, trString));
        }
    }
}
