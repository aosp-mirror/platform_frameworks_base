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
import android.content.Context;
import android.os.Binder;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

public final class TimeDetectorService extends ITimeDetectorService.Stub {

    private static final String TAG = "timedetector.TimeDetectorService";

    public static class Lifecycle extends SystemService {

        public Lifecycle(Context context) {
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

    private final Context mContext;
    private final TimeDetectorStrategy mTimeDetectorStrategy;

    private static TimeDetectorService create(Context context) {
        TimeDetectorStrategy timeDetector = new SimpleTimeDetectorStrategy();
        timeDetector.initialize(new TimeDetectorStrategyCallbackImpl(context));
        return new TimeDetectorService(context, timeDetector);
    }

    @VisibleForTesting
    public TimeDetectorService(@NonNull Context context,
            @NonNull TimeDetectorStrategy timeDetectorStrategy) {
        mContext = Objects.requireNonNull(context);
        mTimeDetectorStrategy = Objects.requireNonNull(timeDetectorStrategy);
    }

    @Override
    public void suggestTime(@NonNull TimeSignal timeSignal) {
        enforceSetTimePermission();

        long callerIdToken = Binder.clearCallingIdentity();
        try {
            mTimeDetectorStrategy.suggestTime(timeSignal);
        } finally {
            Binder.restoreCallingIdentity(callerIdToken);
        }
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        mTimeDetectorStrategy.dump(fd, pw, args);
    }

    private void enforceSetTimePermission() {
        mContext.enforceCallingPermission(android.Manifest.permission.SET_TIME, "set time");
    }
}