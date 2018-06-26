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
import android.app.timedetector.ITimeDetectorService;
import android.app.timedetector.TimeSignal;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Binder;
import android.provider.Settings;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.SystemService;
import com.android.server.timedetector.TimeDetectorStrategy.Callback;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

public final class TimeDetectorService extends ITimeDetectorService.Stub {
    private static final String TAG = "timedetector.TimeDetectorService";

    public static class Lifecycle extends SystemService {

        public Lifecycle(@NonNull Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            TimeDetectorService service = TimeDetectorService.create(getContext());

            // Publish the binder service so it can be accessed from other (appropriately
            // permissioned) processes.
            publishBinderService(Context.TIME_DETECTOR_SERVICE, service);
        }
    }

    @NonNull private final Context mContext;
    @NonNull private final Callback mCallback;

    // The lock used when call the strategy to ensure thread safety.
    @NonNull private final Object mStrategyLock = new Object();

    @GuardedBy("mStrategyLock")
    @NonNull private final TimeDetectorStrategy mTimeDetectorStrategy;

    private static TimeDetectorService create(@NonNull Context context) {
        final TimeDetectorStrategy timeDetector = new SimpleTimeDetectorStrategy();
        final TimeDetectorStrategyCallbackImpl callback =
                new TimeDetectorStrategyCallbackImpl(context);
        timeDetector.initialize(callback);

        TimeDetectorService timeDetectorService =
                new TimeDetectorService(context, callback, timeDetector);

        // Wire up event listening.
        ContentResolver contentResolver = context.getContentResolver();
        contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME), true,
                new ContentObserver(FgThread.getHandler()) {
                    public void onChange(boolean selfChange) {
                        timeDetectorService.handleAutoTimeDetectionToggle();
                    }
                });

        return timeDetectorService;
    }

    @VisibleForTesting
    public TimeDetectorService(@NonNull Context context, @NonNull Callback callback,
            @NonNull TimeDetectorStrategy timeDetectorStrategy) {
        mContext = Objects.requireNonNull(context);
        mCallback = Objects.requireNonNull(callback);
        mTimeDetectorStrategy = Objects.requireNonNull(timeDetectorStrategy);
    }

    @Override
    public void suggestTime(@NonNull TimeSignal timeSignal) {
        enforceSetTimePermission();
        Objects.requireNonNull(timeSignal);

        long idToken = Binder.clearCallingIdentity();
        try {
            synchronized (mStrategyLock) {
                mTimeDetectorStrategy.suggestTime(timeSignal);
            }
        } finally {
            Binder.restoreCallingIdentity(idToken);
        }
    }

    @VisibleForTesting
    public void handleAutoTimeDetectionToggle() {
        synchronized (mStrategyLock) {
            final boolean timeDetectionEnabled = mCallback.isTimeDetectionEnabled();
            mTimeDetectorStrategy.handleAutoTimeDetectionToggle(timeDetectionEnabled);
        }
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        synchronized (mStrategyLock) {
            mTimeDetectorStrategy.dump(pw, args);
        }
    }

    private void enforceSetTimePermission() {
        mContext.enforceCallingPermission(android.Manifest.permission.SET_TIME, "set time");
    }
}