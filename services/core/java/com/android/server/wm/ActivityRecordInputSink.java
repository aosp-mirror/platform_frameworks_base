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

import android.app.ActivityOptions;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.os.Build;
import android.os.InputConfig;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.window.flags.Flags;

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

    // TODO(b/369605358) Update EnabledSince when SDK 36 version code is available.
    /**
     * If the app's target SDK is 36+, pass-through touches from a cross-uid overlaying activity is
     * blocked by default. The activity may opt in to receive pass-through touches using
     * {@link ActivityOptions#setAllowPassThroughOnTouchOutside}, which allows the to-be-launched
     * cross-uid overlaying activity and other activities in that app to pass through touches. The
     * activity needs to ensure that it trusts the overlaying app and its content is not vulnerable
     * to UI redressing attacks.
     *
     * @see ActivityOptions#setAllowPassThroughOnTouchOutside
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT)
    static final long ENABLE_OVERLAY_TOUCH_PASS_THROUGH_OPT_IN_ENFORCEMENT = 358129114L;

    private final ActivityRecord mActivityRecord;
    private final boolean mIsCompatEnabled;
    private final String mName;

    private InputWindowHandleWrapper mInputWindowHandleWrapper;
    private SurfaceControl mSurfaceControl;

    ActivityRecordInputSink(ActivityRecord activityRecord, ActivityRecord sourceRecord,
            boolean appOptInTouchPassThrough) {
        mActivityRecord = activityRecord;
        mIsCompatEnabled = CompatChanges.isChangeEnabled(ENABLE_TOUCH_OPAQUE_ACTIVITIES,
                mActivityRecord.getUid());
        mName = Integer.toHexString(System.identityHashCode(this)) + " ActivityRecordInputSink "
                + mActivityRecord.mActivityComponent.flattenToShortString();

        if (sourceRecord == null) {
            return;
        }
        // If the source activity has target sdk 36+, it is required to opt in to receive
        // pass-through touches from the overlaying activity.
        final boolean isTouchPassThroughOptInEnforced = CompatChanges.isChangeEnabled(
                ENABLE_OVERLAY_TOUCH_PASS_THROUGH_OPT_IN_ENFORCEMENT,
                sourceRecord.getUid());
        if (!Flags.touchPassThroughOptIn() || !isTouchPassThroughOptInEnforced
                || appOptInTouchPassThrough) {
            sourceRecord.mAllowedTouchUid = mActivityRecord.getUid();
        }
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
        // Don't block touches from passing through to an activity below us in the same task, if
        // that activity is either from the same uid or if that activity has launched an activity
        // in our uid.
        final ActivityRecord activityBelowInTask = mActivityRecord.getTask() != null
                ? mActivityRecord.getTask().getActivityBelow(mActivityRecord) : null;
        final boolean allowPassthrough = activityBelowInTask != null && (
                activityBelowInTask.mAllowedTouchUid == mActivityRecord.getUid()
                        || activityBelowInTask.isUid(mActivityRecord.getUid()));
        if (allowPassthrough || !mIsCompatEnabled || mActivityRecord.isInTransition()
                || !mActivityRecord.mActivityRecordInputSinkEnabled) {
            // Set to non-touchable, so the touch events can pass through.
            mInputWindowHandleWrapper.setInputConfigMasked(InputConfig.NOT_TOUCHABLE,
                    InputConfig.NOT_TOUCHABLE);
        } else {
            // Set to touchable, so it can block by intercepting the touch events.
            mInputWindowHandleWrapper.setInputConfigMasked(0, InputConfig.NOT_TOUCHABLE);
        }
        mInputWindowHandleWrapper.setDisplayId(mActivityRecord.getDisplayId());
        return mInputWindowHandleWrapper;
    }

    private InputWindowHandle createInputWindowHandle() {
        InputWindowHandle inputWindowHandle = new InputWindowHandle(null,
                mActivityRecord.getDisplayId());
        inputWindowHandle.replaceTouchableRegionWithCrop = true;
        inputWindowHandle.name = mName;
        inputWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
        inputWindowHandle.ownerPid = WindowManagerService.MY_PID;
        inputWindowHandle.ownerUid = WindowManagerService.MY_UID;
        inputWindowHandle.inputConfig = InputConfig.NOT_FOCUSABLE | InputConfig.NO_INPUT_CHANNEL;
        return inputWindowHandle;
    }

    void releaseSurfaceControl() {
        if (mSurfaceControl != null) {
            mSurfaceControl.release();
            mSurfaceControl = null;
        }
    }

}
