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

import android.util.Slog;

/**
 * Logging for android.view.textclassifier package.
 */
final class Log {

    /**
     * true: Enables full logging.
     * false: Limits logging to debug level.
     */
    private static final boolean ENABLE_FULL_LOGGING = false;

    private Log() {}

    public static void d(String tag, String msg) {
        Slog.d(tag, msg);
    }

    public static void w(String tag, String msg) {
        Slog.w(tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (ENABLE_FULL_LOGGING) {
            Slog.e(tag, msg, tr);
        } else {
            final String trString = (tr != null) ? tr.getClass().getSimpleName() : "??";
            Slog.d(tag, String.format("%s (%s)", msg, trString));
        }
    }
}
