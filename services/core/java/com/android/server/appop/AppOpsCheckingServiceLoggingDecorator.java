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
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import java.io.PrintWriter;

/**
 * Logging decorator for {@link AppOpsCheckingServiceInterface}.
 */
public class AppOpsCheckingServiceLoggingDecorator implements AppOpsCheckingServiceInterface {
    private static final String LOG_TAG =
            AppOpsCheckingServiceLoggingDecorator.class.getSimpleName();

    @NonNull
    private final AppOpsCheckingServiceInterface mService;

    public AppOpsCheckingServiceLoggingDecorator(@NonNull AppOpsCheckingServiceInterface service) {
        mService = service;
    }

    @Override
    public SparseIntArray getNonDefaultUidModes(int uid) {
        Log.i(LOG_TAG, "getNonDefaultUidModes(uid = " + uid + ")");
        return mService.getNonDefaultUidModes(uid);
    }

    @Override
    public int getUidMode(int uid, int op) {
        Log.i(LOG_TAG, "getUidMode(uid = " + uid + ", op = " + op + ")");
        return mService.getUidMode(uid, op);
    }

    @Override
    public boolean setUidMode(int uid, int op, int mode) {
        Log.i(LOG_TAG, "setUidMode(uid = " + uid + ", op = " + op + ", mode = " + mode + ")");
        return mService.setUidMode(uid, op, mode);
    }

    @Override
    public int getPackageMode(@NonNull String packageName, int op, int userId) {
        Log.i(LOG_TAG, "getPackageMode(packageName = " + packageName + ", op = " + op
                + ", userId = " + userId + ")");
        return mService.getPackageMode(packageName, op, userId);
    }

    @Override
    public void setPackageMode(@NonNull String packageName, int op, int mode, int userId) {
        Log.i(LOG_TAG, "setPackageMode(packageName = " + packageName + ", op = " + op + ", mode = "
                + mode + ", userId = " + userId + ")");
        mService.setPackageMode(packageName, op, mode, userId);
    }

    @Override
    public boolean removePackage(@NonNull String packageName, int userId) {
        Log.i(LOG_TAG, "removePackage(packageName = " + packageName + ", userId = " + userId + ")");
        return mService.removePackage(packageName, userId);
    }

    @Override
    public void removeUid(int uid) {
        Log.i(LOG_TAG, "removeUid(uid = " + uid + ")");
        mService.removeUid(uid);
    }

    @Override
    public boolean areUidModesDefault(int uid) {
        Log.i(LOG_TAG, "areUidModesDefault(uid = " + uid + ")");
        return mService.areUidModesDefault(uid);
    }

    @Override
    public boolean arePackageModesDefault(String packageName, int userId) {
        Log.i(LOG_TAG, "arePackageModesDefault(packageName = " + packageName + ", userId = "
                + userId + ")");
        return mService.arePackageModesDefault(packageName, userId);
    }

    @Override
    public void clearAllModes() {
        Log.i(LOG_TAG, "clearAllModes()");
        mService.clearAllModes();
    }

    @Override
    public void startWatchingOpModeChanged(@NonNull OnOpModeChangedListener changedListener,
            int op) {
        Log.i(LOG_TAG, "startWatchingOpModeChanged(changedListener = " + changedListener + ", op = "
                + op + ")");
        mService.startWatchingOpModeChanged(changedListener, op);
    }

    @Override
    public void startWatchingPackageModeChanged(@NonNull OnOpModeChangedListener changedListener,
            @NonNull String packageName) {
        Log.i(LOG_TAG, "startWatchingPackageModeChanged(changedListener = " + changedListener
                + ", packageName = " + packageName + ")");
        mService.startWatchingPackageModeChanged(changedListener, packageName);
    }

    @Override
    public void removeListener(@NonNull OnOpModeChangedListener changedListener) {
        Log.i(LOG_TAG, "removeListener(changedListener = " + changedListener + ")");
        mService.removeListener(changedListener);
    }

    @Override
    public ArraySet<OnOpModeChangedListener> getOpModeChangedListeners(int op) {
        Log.i(LOG_TAG, "getOpModeChangedListeners(op = " + op + ")");
        return mService.getOpModeChangedListeners(op);
    }

    @Override
    public ArraySet<OnOpModeChangedListener> getPackageModeChangedListeners(
            @NonNull String packageName) {
        Log.i(LOG_TAG, "getPackageModeChangedListeners(packageName = " + packageName + ")");
        return mService.getPackageModeChangedListeners(packageName);
    }

    @Override
    public void notifyWatchersOfChange(int op, int uid) {
        Log.i(LOG_TAG, "notifyWatchersOfChange(op = " + op + ", uid = " + uid + ")");
        mService.notifyWatchersOfChange(op, uid);
    }

    @Override
    public void notifyOpChanged(@NonNull OnOpModeChangedListener changedListener, int op, int uid,
            @Nullable String packageName) {
        Log.i(LOG_TAG, "notifyOpChanged(changedListener = " + changedListener + ", op = " + op
                + ", uid = " + uid + ", packageName = " + packageName + ")");
        mService.notifyOpChanged(changedListener, op, uid, packageName);
    }

    @Override
    public void notifyOpChangedForAllPkgsInUid(int op, int uid, boolean onlyForeground,
            @Nullable OnOpModeChangedListener callbackToIgnore) {
        Log.i(LOG_TAG, "notifyOpChangedForAllPkgsInUid(op = " + op + ", uid = " + uid
                + ", onlyForeground = " + onlyForeground + ", callbackToIgnore = "
                + callbackToIgnore + ")");
        mService.notifyOpChangedForAllPkgsInUid(op, uid, onlyForeground, callbackToIgnore);
    }

    @Override
    public SparseBooleanArray evalForegroundUidOps(int uid, SparseBooleanArray foregroundOps) {
        Log.i(LOG_TAG, "evalForegroundUidOps(uid = " + uid + ", foregroundOps = " + foregroundOps
                + ")");
        return mService.evalForegroundUidOps(uid, foregroundOps);
    }

    @Override
    public SparseBooleanArray evalForegroundPackageOps(String packageName,
            SparseBooleanArray foregroundOps, int userId) {
        Log.i(LOG_TAG, "evalForegroundPackageOps(packageName = " + packageName
                + ", foregroundOps = " + foregroundOps + ", userId = " + userId + ")");
        return mService.evalForegroundPackageOps(packageName, foregroundOps, userId);
    }

    @Override
    public boolean dumpListeners(int dumpOp, int dumpUid, String dumpPackage,
            PrintWriter printWriter) {
        Log.i(LOG_TAG, "dumpListeners(dumpOp = " + dumpOp + ", dumpUid = " + dumpUid
                + ", dumpPackage = " + dumpPackage + ", printWriter = " + printWriter + ")");
        return mService.dumpListeners(dumpOp, dumpUid, dumpPackage, printWriter);
    }
}
