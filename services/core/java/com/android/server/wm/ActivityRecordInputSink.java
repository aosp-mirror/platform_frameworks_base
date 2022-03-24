/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.os.InputConfig;
import android.os.Process;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.WindowManager;

/**
 * Creates a InputWindowHandle that catches all touches that would otherwise pass through an
 * Activity.
 */
class ActivityRecordInputSink {

    /**
     * Feature flag for making Activities consume all touches within their task bounds.
     */
    @ChangeId
    static final long ENABLE_TOUCH_OPAQUE_ACTIVITIES = 194480991L;

    private final ActivityRecord mActivityRecord;
    private final boolean mIsCompatEnabled;
    private final String mName;

    private InputWindowHandleWrapper mInputWindowHandleWrapper;
    private SurfaceControl mSurfaceControl;
    private boolean mDisabled = false;

    ActivityRecordInputSink(ActivityRecord activityRecord) {
        mActivityRecord = activityRecord;
        mIsCompatEnabled = CompatChanges.isChangeEnabled(ENABLE_TOUCH_OPAQUE_ACTIVITIES,
                mActivityRecord.getUid());
        mName = Integer.toHexString(System.identityHashCode(this)) + " ActivityRecordInputSink "
                + mActivityRecord.mActivityComponent.flattenToShortString();
    }

    public void applyChangesToSurfaceIfChanged(SurfaceControl.Transaction transaction) {
        InputWindowHandleWrapper inputWindowHandleWrapper = getInputWindowHandleWrapper();
        if (mSurfaceControl == null) {
            mSurfaceControl = createSurface(transaction);
        }
        if (inputWindowHandleWrapper.isChanged()) {
            inputWindowHandleWrapper.applyChangesToSurface(transaction, mSurfaceControl);
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

    private InputWindowHandleWrapper getInputWindowHandleWrapper() {
        if (mInputWindowHandleWrapper == null) {
            mInputWindowHandleWrapper = new InputWindowHandleWrapper(createInputWindowHandle());
        }
        if (mDisabled || !mIsCompatEnabled || mActivityRecord.isInTransition()) {
            // TODO(b/208662670): Investigate if we can have feature active during animations.
            mInputWindowHandleWrapper.setInputConfigMasked(InputConfig.NOT_TOUCHABLE,
                    InputConfig.NOT_TOUCHABLE);
        } else {
            mInputWindowHandleWrapper.setInputConfigMasked(0, InputConfig.NOT_TOUCHABLE);
        }
        return mInputWindowHandleWrapper;
    }

    private InputWindowHandle createInputWindowHandle() {
        InputWindowHandle inputWindowHandle = new InputWindowHandle(null,
                mActivityRecord.getDisplayId());
        inputWindowHandle.replaceTouchableRegionWithCrop = true;
        inputWindowHandle.name = mName;
        inputWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
        inputWindowHandle.ownerUid = Process.myUid();
        inputWindowHandle.ownerPid = Process.myPid();
        inputWindowHandle.inputConfig = InputConfig.NOT_FOCUSABLE | InputConfig.NO_INPUT_CHANNEL;
        return inputWindowHandle;
    }

}
