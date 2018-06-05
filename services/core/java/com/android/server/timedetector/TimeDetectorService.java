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

import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import android.app.timedetector.ITimeDetectorService;
import android.content.Context;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;

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

    private static TimeDetectorService create(Context context) {
        return new TimeDetectorService(context);
    }

    public TimeDetectorService(Context context) {
        mContext = context;
    }

    @Override
    public void stubbedCall() {
        // Empty call for initial tests.
        Slog.d(TAG, "stubbedCall() called");
        // TODO(nfuller): Remove when there are real methods.
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        // TODO(nfuller): Implement when there is state.
    }
}