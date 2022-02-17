/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.timezonedetector;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The real implementation of {@link DeviceActivityMonitor}.
 */
class DeviceActivityMonitorImpl implements DeviceActivityMonitor {

    private static final String LOG_TAG = TimeZoneDetectorService.TAG;
    private static final boolean DBG = TimeZoneDetectorService.DBG;

    static DeviceActivityMonitor create(@NonNull Context context, @NonNull Handler handler) {
        return new DeviceActivityMonitorImpl(context, handler);
    }

    @GuardedBy("this")
    @NonNull
    private final List<Listener> mListeners = new ArrayList<>();

    private DeviceActivityMonitorImpl(@NonNull Context context, @NonNull Handler handler) {
        // The way this "detects" a flight concluding is by the user explicitly turning off airplane
        // mode. Smarter heuristics would be nice.
        ContentResolver contentResolver = context.getContentResolver();
        ContentObserver airplaneModeObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean unused) {
                try {
                    int state = Settings.Global.getInt(
                            contentResolver, Settings.Global.AIRPLANE_MODE_ON);
                    if (state == 0) {
                        notifyFlightComplete();
                    }
                } catch (Settings.SettingNotFoundException e) {
                    Slog.e(LOG_TAG, "Unable to read airplane mode state", e);
                }
            }
        };
        contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON),
                true /* notifyForDescendants */,
                airplaneModeObserver);
    }

    @Override
    public synchronized void addListener(Listener listener) {
        Objects.requireNonNull(listener);
        mListeners.add(listener);
    }

    private synchronized void notifyFlightComplete() {
        if (DBG) {
            Slog.d(LOG_TAG, "notifyFlightComplete");
        }

        for (Listener listener : mListeners) {
            listener.onFlightComplete();
        }
    }

    @Override
    public void dump(IndentingPrintWriter pw, String[] args) {
        // No-op right now: no state to dump.
    }
}
