/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.appop;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.os.Trace;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

/**
 * Surrounds all AppOpsCheckingServiceInterface method calls with Trace.traceBegin and
 * Trace.traceEnd. These traces are used for performance testing.
 */
public class AppOpsCheckingServiceTracingDecorator implements AppOpsCheckingServiceInterface {
    private static final long TRACE_TAG = Trace.TRACE_TAG_ACTIVITY_MANAGER;
    private final AppOpsCheckingServiceInterface mService;

    AppOpsCheckingServiceTracingDecorator(
            @NonNull AppOpsCheckingServiceInterface appOpsCheckingServiceInterface) {
        mService = appOpsCheckingServiceInterface;
    }

    @Override
    public void writeState() {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#writeState");
        try {
            mService.writeState();
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void readState() {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#readState");
        try {
            mService.readState();
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void shutdown() {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#shutdown");
        try {
            mService.shutdown();
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void systemReady() {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#systemReady");
        try {
            mService.systemReady();
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public SparseIntArray getNonDefaultUidModes(int uid, String persistentDeviceId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#getNonDefaultUidModes");
        try {
            return mService.getNonDefaultUidModes(uid, persistentDeviceId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public SparseIntArray getNonDefaultPackageModes(String packageName, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#getNonDefaultPackageModes");
        try {
            return mService.getNonDefaultPackageModes(packageName, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public int getUidMode(int uid, String persistentDeviceId, int op) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingAppOpsCheckingServiceInterfaceImpl#getUidMode");
        try {
            return mService.getUidMode(uid, persistentDeviceId, op);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean setUidMode(
            int uid, String persistentDeviceId, int op, @AppOpsManager.Mode int mode) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingAppOpsCheckingServiceInterfaceImpl#setUidMode");
        try {
            return mService.setUidMode(uid, persistentDeviceId, op, mode);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public int getPackageMode(@NonNull String packageName, int op, @UserIdInt int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#getPackageMode");
        try {
            return mService.getPackageMode(packageName, op, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void setPackageMode(@NonNull String packageName, int op, @AppOpsManager.Mode int mode,
            @UserIdInt int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#setPackageMode");
        try {
            mService.setPackageMode(packageName, op, mode, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean removePackage(@NonNull String packageName, @UserIdInt int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#removePackage");
        try {
            return mService.removePackage(packageName, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void removeUid(int uid) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#removeUid");
        try {
            mService.removeUid(uid);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void clearAllModes() {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#clearAllModes");
        try {
            mService.clearAllModes();
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public SparseBooleanArray getForegroundOps(int uid, String persistentDeviceId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#getForegroundOps");
        try {
            return mService.getForegroundOps(uid, persistentDeviceId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public SparseBooleanArray getForegroundOps(String packageName, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#getForegroundOps");
        try {
            return mService.getForegroundOps(packageName, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean addAppOpsModeChangedListener(AppOpsModeChangedListener listener) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#addAppOpsModeChangedListener");
        try {
            return mService.addAppOpsModeChangedListener(listener);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean removeAppOpsModeChangedListener(AppOpsModeChangedListener listener) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#removeAppOpsModeChangedListener");
        try {
            return mService.removeAppOpsModeChangedListener(listener);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }
}
