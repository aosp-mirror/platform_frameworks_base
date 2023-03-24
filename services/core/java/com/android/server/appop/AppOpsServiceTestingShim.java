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

import android.util.ArraySet;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import java.io.PrintWriter;
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
    public boolean areUidModesDefault(int uid) {
        boolean oldVal = mOldImplementation.areUidModesDefault(uid);
        boolean newVal = mNewImplementation.areUidModesDefault(uid);

        if (oldVal != newVal) {
            signalImplDifference("areUidModesDefault");
        }

        return newVal;
    }

    @Override
    public boolean arePackageModesDefault(String packageName, int userId) {
        boolean oldVal = mOldImplementation.arePackageModesDefault(packageName, userId);
        boolean newVal = mNewImplementation.arePackageModesDefault(packageName, userId);

        if (oldVal != newVal) {
            signalImplDifference("arePackageModesDefault");
        }

        return newVal;
    }

    @Override
    public void clearAllModes() {
        mOldImplementation.clearAllModes();
        mNewImplementation.clearAllModes();
    }

    @Override
    public void startWatchingOpModeChanged(OnOpModeChangedListener changedListener, int op) {
        mOldImplementation.startWatchingOpModeChanged(changedListener, op);
        mNewImplementation.startWatchingOpModeChanged(changedListener, op);
    }

    @Override
    public void startWatchingPackageModeChanged(OnOpModeChangedListener changedListener,
            String packageName) {
        mOldImplementation.startWatchingPackageModeChanged(changedListener, packageName);
        mNewImplementation.startWatchingPackageModeChanged(changedListener, packageName);
    }

    @Override
    public void removeListener(OnOpModeChangedListener changedListener) {
        mOldImplementation.removeListener(changedListener);
        mNewImplementation.removeListener(changedListener);
    }

    @Override
    public ArraySet<OnOpModeChangedListener> getOpModeChangedListeners(int op) {
        ArraySet<OnOpModeChangedListener> oldVal = mOldImplementation.getOpModeChangedListeners(op);
        ArraySet<OnOpModeChangedListener> newVal = mNewImplementation.getOpModeChangedListeners(op);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getOpModeChangedListeners");
        }

        return newVal;
    }

    @Override
    public ArraySet<OnOpModeChangedListener> getPackageModeChangedListeners(String packageName) {
        ArraySet<OnOpModeChangedListener> oldVal = mOldImplementation
                .getPackageModeChangedListeners(packageName);
        ArraySet<OnOpModeChangedListener> newVal = mNewImplementation
                .getPackageModeChangedListeners(packageName);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getPackageModeChangedListeners");
        }

        return newVal;
    }

    @Override
    public void notifyWatchersOfChange(int op, int uid) {
        mOldImplementation.notifyWatchersOfChange(op, uid);
        mNewImplementation.notifyWatchersOfChange(op, uid);
    }

    @Override
    public void notifyOpChanged(OnOpModeChangedListener changedListener, int op, int uid,
            String packageName) {
        mOldImplementation.notifyOpChanged(changedListener, op, uid, packageName);
        mNewImplementation.notifyOpChanged(changedListener, op, uid, packageName);
    }

    @Override
    public void notifyOpChangedForAllPkgsInUid(int op, int uid, boolean onlyForeground,
            OnOpModeChangedListener callbackToIgnore) {
        mOldImplementation
                .notifyOpChangedForAllPkgsInUid(op, uid, onlyForeground, callbackToIgnore);
        mNewImplementation
                .notifyOpChangedForAllPkgsInUid(op, uid, onlyForeground, callbackToIgnore);
    }

    @Override
    public SparseBooleanArray evalForegroundUidOps(int uid, SparseBooleanArray foregroundOps) {
        SparseBooleanArray oldVal = mOldImplementation.evalForegroundUidOps(uid, foregroundOps);
        SparseBooleanArray newVal = mNewImplementation.evalForegroundUidOps(uid, foregroundOps);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("evalForegroundUidOps");
        }

        return newVal;
    }

    @Override
    public SparseBooleanArray evalForegroundPackageOps(String packageName,
            SparseBooleanArray foregroundOps, int userId) {
        SparseBooleanArray oldVal = mOldImplementation
                .evalForegroundPackageOps(packageName, foregroundOps, userId);
        SparseBooleanArray newVal = mNewImplementation
                .evalForegroundPackageOps(packageName, foregroundOps, userId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("evalForegroundPackageOps");
        }

        return newVal;
    }

    @Override
    public boolean dumpListeners(int dumpOp, int dumpUid, String dumpPackage,
            PrintWriter printWriter) {
        boolean oldVal = mOldImplementation
                .dumpListeners(dumpOp, dumpUid, dumpPackage, printWriter);
        boolean newVal = mNewImplementation
                .dumpListeners(dumpOp, dumpUid, dumpPackage, printWriter);

        if (oldVal != newVal) {
            signalImplDifference("dumpListeners");
        }

        return newVal;
    }
}
