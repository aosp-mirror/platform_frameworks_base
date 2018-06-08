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

package com.android.server.timedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.timedetector.TimeSignal;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A placeholder implementation of TimeDetectorStrategy that passes NITZ suggestions immediately
 * to {@link AlarmManager}.
 */
public final class SimpleTimeDetectorStrategy implements TimeDetectorStrategy {

    private final static String TAG = "timedetector.SimpleTimeDetectorStrategy";

    private Callback mHelper;

    @Override
    public void initialize(@NonNull Callback callback) {
        mHelper = callback;
    }

    @Override
    public void suggestTime(@NonNull TimeSignal timeSignal) {
        if (!TimeSignal.SOURCE_ID_NITZ.equals(timeSignal.getSourceId())) {
            Slog.w(TAG, "Ignoring signal from unknown source: " + timeSignal);
            return;
        }

        mHelper.setTime(timeSignal.getUtcTime());
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @Nullable String[] args) {
        // No state to dump.
    }
}
