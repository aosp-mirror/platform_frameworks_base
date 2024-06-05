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
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

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
    public void writeState() {
        Log.i(LOG_TAG, "writeState()");
        mService.writeState();
    }

    @Override
    public void readState() {
        Log.i(LOG_TAG, "readState()");
        mService.readState();
    }

    @Override
    public void shutdown() {
        Log.i(LOG_TAG, "shutdown()");
        mService.shutdown();
    }

    @Override
    public void systemReady() {
        Log.i(LOG_TAG, "systemReady()");
        mService.systemReady();
    }

    @Override
    public SparseIntArray getNonDefaultUidModes(int uid, String persistentDeviceId) {
        Log.i(LOG_TAG, "getNonDefaultUidModes(uid = " + uid + ")");
        return mService.getNonDefaultUidModes(uid, persistentDeviceId);
    }

    @Override
    public SparseIntArray getNonDefaultPackageModes(String packageName, int userId) {
        Log.i(LOG_TAG, "getNonDefaultPackageModes("
                + "packageName = " + packageName + ", userId = " + userId + ") ");
        return mService.getNonDefaultPackageModes(packageName, userId);
    }

    @Override
    public int getUidMode(int uid, String persistentDeviceId, int op) {
        Log.i(LOG_TAG, "getUidMode(uid = " + uid + ", op = " + op + ")");
        return mService.getUidMode(uid, persistentDeviceId, op);
    }

    @Override
    public boolean setUidMode(int uid, String persistentDeviceId, int op, int mode) {
        Log.i(LOG_TAG, "setUidMode(uid = " + uid + ", op = " + op + ", mode = " + mode + ")");
        return mService.setUidMode(uid, persistentDeviceId, op, mode);
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
    public void clearAllModes() {
        Log.i(LOG_TAG, "clearAllModes()");
        mService.clearAllModes();
    }

    @Override
    public SparseBooleanArray getForegroundOps(int uid, String persistentDeviceId) {
        Log.i(LOG_TAG, "getForegroundOps(uid = " + uid + ")");
        return mService.getForegroundOps(uid, persistentDeviceId);
    }

    @Override
    public SparseBooleanArray getForegroundOps(String packageName, int userId) {
        Log.i(LOG_TAG, "getForegroundOps(packageName = " + packageName + ", userId = " + userId
                + ")");
        return mService.getForegroundOps(packageName, userId);
    }

    @Override
    public boolean addAppOpsModeChangedListener(AppOpsModeChangedListener listener) {
        Log.i(LOG_TAG, "addAppOpsModeChangedListener(listener = " + listener + ")");
        return mService.addAppOpsModeChangedListener(listener);
    }

    @Override
    public boolean removeAppOpsModeChangedListener(AppOpsModeChangedListener listener) {
        Log.i(LOG_TAG, "removeAppOpsModeChangedListener(listener = " + listener + ")");
        return mService.removeAppOpsModeChangedListener(listener);
    }
}
