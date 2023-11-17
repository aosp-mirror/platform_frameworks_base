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

import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import java.util.Objects;

/**
 * A testing shim, which supports running two variants of an AppOpsServiceInterface at once,
 * and checking the results of both.
 */
public class AppOpsServiceTestingShim implements AppOpsCheckingServiceInterface {

    private AppOpsCheckingServiceInterface mOldImplementation;
    private AppOpsCheckingServiceInterface mNewImplementation;

    public AppOpsServiceTestingShim(AppOpsCheckingServiceInterface oldValImpl,
            AppOpsCheckingServiceInterface newImpl) {
        mOldImplementation = oldValImpl;
        mNewImplementation = newImpl;
    }

    private void signalImplDifference(String message) {
        //TODO b/252886104 implement
    }

    @Override
    public void writeState() {
        mOldImplementation.writeState();
        mNewImplementation.writeState();
    }

    @Override
    public void readState() {
        mOldImplementation.readState();
        mNewImplementation.readState();
    }

    @Override
    public void shutdown() {
        mOldImplementation.shutdown();
        mNewImplementation.shutdown();
    }

    @Override
    public void systemReady() {
        mOldImplementation.systemReady();
        mNewImplementation.systemReady();
    }

    @Override
    public SparseIntArray getNonDefaultUidModes(int uid) {
        SparseIntArray oldVal = mOldImplementation.getNonDefaultUidModes(uid);
        SparseIntArray newVal = mNewImplementation.getNonDefaultUidModes(uid);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getNonDefaultUidModes");
        }

        return newVal;
    }

    @Override
    public SparseIntArray getNonDefaultPackageModes(String packageName, int userId) {
        SparseIntArray oldVal = mOldImplementation.getNonDefaultPackageModes(packageName, userId);
        SparseIntArray newVal = mNewImplementation.getNonDefaultPackageModes(packageName, userId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getNonDefaultPackageModes");
        }

        return newVal;
    }

    @Override
    public int getUidMode(int uid, int op) {
        int oldVal = mOldImplementation.getUidMode(uid, op);
        int newVal = mNewImplementation.getUidMode(uid, op);

        if (oldVal != newVal) {
            signalImplDifference("getUidMode");
        }

        return newVal;
    }

    @Override
    public boolean setUidMode(int uid, int op, int mode) {
        boolean oldVal = mOldImplementation.setUidMode(uid, op, mode);
        boolean newVal = mNewImplementation.setUidMode(uid, op, mode);

        if (oldVal != newVal) {
            signalImplDifference("setUidMode");
        }

        return newVal;
    }

    @Override
    public int getPackageMode(String packageName, int op, int userId) {
        int oldVal = mOldImplementation.getPackageMode(packageName, op, userId);
        int newVal = mNewImplementation.getPackageMode(packageName, op, userId);

        if (oldVal != newVal) {
            signalImplDifference("getPackageMode");
        }

        return newVal;
    }

    @Override
    public void setPackageMode(String packageName, int op, int mode, int userId) {
        mOldImplementation.setPackageMode(packageName, op, mode, userId);
        mNewImplementation.setPackageMode(packageName, op, mode, userId);
    }

    @Override
    public boolean removePackage(String packageName, int userId) {
        boolean oldVal = mOldImplementation.removePackage(packageName, userId);
        boolean newVal = mNewImplementation.removePackage(packageName, userId);

        if (oldVal != newVal) {
            signalImplDifference("removePackage");
        }

        return newVal;
    }

    @Override
    public void removeUid(int uid) {
        mOldImplementation.removeUid(uid);
        mNewImplementation.removeUid(uid);
    }

    @Override
    public void clearAllModes() {
        mOldImplementation.clearAllModes();
        mNewImplementation.clearAllModes();
    }

    @Override
    public SparseBooleanArray getForegroundOps(int uid) {
        SparseBooleanArray oldVal = mOldImplementation.getForegroundOps(uid);
        SparseBooleanArray newVal = mNewImplementation.getForegroundOps(uid);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getForegroundOps");
        }

        return newVal;
    }

    @Override
    public SparseBooleanArray getForegroundOps(String packageName, int userId) {
        SparseBooleanArray oldVal = mOldImplementation.getForegroundOps(packageName, userId);
        SparseBooleanArray newVal = mNewImplementation.getForegroundOps(packageName, userId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getForegroundOps");
        }

        return newVal;
    }

    @Override
    public boolean addAppOpsModeChangedListener(AppOpsModeChangedListener listener) {
        boolean oldVal = mOldImplementation.addAppOpsModeChangedListener(listener);
        boolean newVal = mNewImplementation.addAppOpsModeChangedListener(listener);

        if (oldVal != newVal) {
            signalImplDifference("addAppOpsModeChangedListener");
        }

        return newVal;
    }

    @Override
    public boolean removeAppOpsModeChangedListener(AppOpsModeChangedListener listener) {
        boolean oldVal = mOldImplementation.removeAppOpsModeChangedListener(listener);
        boolean newVal = mNewImplementation.removeAppOpsModeChangedListener(listener);

        if (oldVal != newVal) {
            signalImplDifference("removeAppOpsModeChangedListener");
        }

        return newVal;
    }
}
