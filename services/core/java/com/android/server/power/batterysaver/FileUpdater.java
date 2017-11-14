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
package com.android.server.power.batterysaver;

import android.content.Context;
import android.util.ArrayMap;
import android.util.Slog;

/**
 * Used by {@link BatterySaverController} to write values to /sys/ (and possibly /proc/ too) files
 * with retry and to restore the original values.
 *
 * TODO Implement it
 */
public class FileUpdater {
    private static final String TAG = BatterySaverController.TAG;

    private static final boolean DEBUG = BatterySaverController.DEBUG;

    private final Object mLock = new Object();
    private final Context mContext;

    public FileUpdater(Context context) {
        mContext = context;
    }

    public void writeFiles(ArrayMap<String, String> fileValues) {
        if (DEBUG) {
            final int size = fileValues.size();
            for (int i = 0; i < size; i++) {
                Slog.d(TAG, "Writing '" + fileValues.valueAt(i)
                        + "' to '" + fileValues.keyAt(i) + "'");
            }
        }
    }

    public void restoreDefault() {
        if (DEBUG) {
            Slog.d(TAG, "Resetting file default values");
        }
    }
}
