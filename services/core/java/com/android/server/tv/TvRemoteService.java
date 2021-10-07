/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.tv;

import android.content.Context;
import android.util.Slog;

import com.android.server.SystemService;
import com.android.server.Watchdog;

/**
 * TvRemoteService represents a system service that allows a connected
 * remote control (emote) service to inject allowlisted input events
 * and call other specified methods for functioning as an emote service.
 * <p/>
 * This service is intended for use only by allowlisted packages.
 */
public class TvRemoteService extends SystemService implements Watchdog.Monitor {
    private static final String TAG = "TvRemoteService";
    private static final boolean DEBUG = false;

    /**
     * All actions on input bridges are serialized using mLock.
     * This is necessary because {@link UInputBridge} is not thread-safe.
     */
    private final Object mLock = new Object();
    private final TvRemoteProviderWatcher mWatcher;

    public TvRemoteService(Context context) {
        super(context);
        mWatcher = new TvRemoteProviderWatcher(context, mLock);
        Watchdog.getInstance().addMonitor(this);
    }

    @Override
    public void onStart() {
        if (DEBUG) Slog.d(TAG, "onStart()");
    }

    @Override
    public void monitor() {
        synchronized (mLock) { /* check for deadlock */ }
    }

    @Override
    public void onBootPhase(int phase) {
        // All lifecycle methods are called from the system server's main looper thread.
        if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            if (DEBUG) Slog.d(TAG, "PHASE_THIRD_PARTY_APPS_CAN_START");

            mWatcher.start(); // Also schedules the start of all providers.
        }
    }
}
