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

package com.android.server.wm;

import android.os.Process;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.WindowManager;

/**
 * Creates a InputWindowHandle that catches all touches that would otherwise pass through an
 * Activity.
 */
class ActivityRecordInputSink {

    private final ActivityRecord mActivityRecord;
    private final String mName;

    private InputWindowHandle mInputWindowHandle;
    private SurfaceControl mSurfaceControl;

    ActivityRecordInputSink(ActivityRecord activityRecord, ActivityRecord sourceRecord) {
        mActivityRecord = activityRecord;
        mName = Integer.toHexString(System.identityHashCode(this)) + " ActivityRecordInputSink "
                + mActivityRecord.mActivityComponent.flattenToShortString();
        if (sourceRecord != null) {
            sourceRecord.mAllowedTouchUid = mActivityRecord.getUid();
        }
    }

    public void applyChangesToSurfaceIfChanged(SurfaceControl.Transaction transaction) {
        boolean windowHandleChanged = updateInputWindowHandle();
        if (mSurfaceControl == null) {
            mSurfaceControl = createSurface(transaction);
        }
        if (windowHandleChanged) {
            transaction.setInputWindowInfo(mSurfaceControl, mInputWindowHandle);
        }
    }

    private SurfaceControl createSurface(SurfaceControl.Transaction t) {
        SurfaceControl surfaceControl = mActivityRecord.makeChildSurface(null)
                .setName(mName)
                .setHidden(false)
                .setCallsite("ActivityRecordInputSink.createSurface")
                .build();
        // Put layer below all siblings (and the parent surface too)
        t.setLayer(surfaceControl, Integer.MIN_VALUE);
        return surfaceControl;
    }

    private boolean updateInputWindowHandle() {
        boolean changed = false;
        if (mInputWindowHandle == null) {
            mInputWindowHandle = createInputWindowHandle();
            changed = true;
        }
        // Don't block touches from passing through to an activity below us in the same task, if
        // that activity is either from the same uid or if that activity has launched an activity
        // in our uid.
        final ActivityRecord activityBelowInTask =
                mActivityRecord.getTask().getActivityBelow(mActivityRecord);
        final boolean allowPassthrough = activityBelowInTask != null && (
                activityBelowInTask.mAllowedTouchUid == mActivityRecord.getUid()
                        || activityBelowInTask.isUid(mActivityRecord.getUid()));
        boolean notTouchable = (mInputWindowHandle.layoutParamsFlags
                & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
        if (allowPassthrough || mActivityRecord.isAppTransitioning()) {
            mInputWindowHandle.layoutParamsFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            changed |= !notTouchable;
        } else {
            mInputWindowHandle.layoutParamsFlags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            changed |= notTouchable;
        }
        return changed;
    }

    private InputWindowHandle createInputWindowHandle() {
        InputWindowHandle inputWindowHandle = new InputWindowHandle(null,
                mActivityRecord.getDisplayId());
        inputWindowHandle.replaceTouchableRegionWithCrop = true;
        inputWindowHandle.name = mName;
        inputWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
        inputWindowHandle.ownerUid = Process.myUid();
        inputWindowHandle.ownerPid = Process.myPid();
        inputWindowHandle.layoutParamsFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        inputWindowHandle.inputFeatures =
                WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
        return inputWindowHandle;
    }

    void releaseSurfaceControl() {
        if (mSurfaceControl != null) {
            mSurfaceControl.release();
            mSurfaceControl = null;
        }
    }

}
