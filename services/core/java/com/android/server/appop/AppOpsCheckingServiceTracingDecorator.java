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
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.os.Trace;
import android.util.ArraySet;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import java.io.PrintWriter;

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
    public SparseIntArray getNonDefaultUidModes(int uid) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#getNonDefaultUidModes");
        try {
            return mService.getNonDefaultUidModes(uid);
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
    public int getUidMode(int uid, int op) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingAppOpsCheckingServiceInterfaceImpl#getUidMode");
        try {
            return mService.getUidMode(uid, op);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean setUidMode(int uid, int op, @AppOpsManager.Mode int mode) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingAppOpsCheckingServiceInterfaceImpl#setUidMode");
        try {
            return mService.setUidMode(uid, op, mode);
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
    public boolean areUidModesDefault(int uid) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#areUidModesDefault");
        try {
            return mService.areUidModesDefault(uid);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean arePackageModesDefault(String packageName, @UserIdInt int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#arePackageModesDefault");
        try {
            return mService.arePackageModesDefault(packageName, userId);
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
    public void startWatchingOpModeChanged(@NonNull OnOpModeChangedListener changedListener,
            int op) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#startWatchingOpModeChanged");
        try {
            mService.startWatchingOpModeChanged(changedListener, op);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void startWatchingPackageModeChanged(@NonNull OnOpModeChangedListener changedListener,
            @NonNull String packageName) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#startWatchingPackageModeChanged");
        try {
            mService.startWatchingPackageModeChanged(changedListener, packageName);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void removeListener(@NonNull OnOpModeChangedListener changedListener) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#removeListener");
        try {
            mService.removeListener(changedListener);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public ArraySet<OnOpModeChangedListener> getOpModeChangedListeners(int op) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#getOpModeChangedListeners");
        try {
            return mService.getOpModeChangedListeners(op);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public ArraySet<OnOpModeChangedListener> getPackageModeChangedListeners(
            @NonNull String packageName) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#getPackageModeChangedListeners");
        try {
            return mService.getPackageModeChangedListeners(packageName);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void notifyWatchersOfChange(int op, int uid) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#notifyWatchersOfChange");
        try {
            mService.notifyWatchersOfChange(op, uid);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void notifyOpChanged(@NonNull OnOpModeChangedListener changedListener, int op, int uid,
            @Nullable String packageName) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#notifyOpChanged");
        try {
            mService.notifyOpChanged(changedListener, op, uid, packageName);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void notifyOpChangedForAllPkgsInUid(int op, int uid, boolean onlyForeground,
            @Nullable OnOpModeChangedListener callbackToIgnore) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#notifyOpChangedForAllPkgsInUid");
        try {
            mService.notifyOpChangedForAllPkgsInUid(op, uid, onlyForeground, callbackToIgnore);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public SparseBooleanArray evalForegroundUidOps(int uid, SparseBooleanArray foregroundOps) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#evalForegroundUidOps");
        try {
            return mService.evalForegroundUidOps(uid, foregroundOps);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public SparseBooleanArray evalForegroundPackageOps(String packageName,
            SparseBooleanArray foregroundOps, @UserIdInt int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#evalForegroundPackageOps");
        try {
            return mService.evalForegroundPackageOps(packageName, foregroundOps, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean dumpListeners(int dumpOp, int dumpUid, String dumpPackage,
            PrintWriter printWriter) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingAppOpsCheckingServiceInterfaceImpl#dumpListeners");
        try {
            return mService.dumpListeners(dumpOp, dumpUid, dumpPackage, printWriter);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }
}
