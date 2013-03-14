/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.shell;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Preferences related to bug reports.
 */
public class BugreportPrefs {
    private static final String PREFS_BUGREPORT = "bugreports";

    private static final String KEY_WARNING_STATE = "warning-state";

    public static final int STATE_UNKNOWN = 0;
    public static final int STATE_SHOW = 1;
    public static final int STATE_HIDE = 2;

    public static int getWarningState(Context context, int def) {
        final SharedPreferences prefs = context.getSharedPreferences(
                PREFS_BUGREPORT, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_WARNING_STATE, def);
    }

    public static void setWarningState(Context context, int value) {
        final SharedPreferences prefs = context.getSharedPreferences(
                PREFS_BUGREPORT, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_WARNING_STATE, value).apply();
    }
}
