/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.server.policy.WindowManagerPolicy.USER_ROTATION_LOCKED;

import android.annotation.Nullable;
import android.content.ActivityInfoProto;
import android.view.Surface;

import com.android.internal.protolog.ProtoLog;

/**
 * Defines the behavior of reversion from device rotation overrides.
 *
 * <p>There are 3 override types:
 * <ol>
 *  <li>The top application has {@link SCREEN_ORIENTATION_NOSENSOR} set and is rotated to
 *  {@link ROTATION_0}.
 *  <li>Camera compat treatment has rotated the app {@link DisplayRotationCompatPolicy}.
 *  <li>The device is half-folded and has auto-rotate is temporarily enabled.
 * </ol>
 *
 * <p>Before an override is enabled, a component should call {@code beforeOverrideApplied}. When
 * it wishes to revert, it should call {@code revertOverride}. The user rotation will be restored
 * if there are no other overrides present.
 */
final class DisplayRotationReversionController {

    static final int REVERSION_TYPE_NOSENSOR = 0;
    static final int REVERSION_TYPE_CAMERA_COMPAT = 1;
    static final int REVERSION_TYPE_HALF_FOLD = 2;
    private static final int NUM_SLOTS = 3;

    @Surface.Rotation
    private int mUserRotationOverridden = ROTATION_UNDEFINED;

    private final boolean[] mSlots = new boolean[NUM_SLOTS];
    private final DisplayContent mDisplayContent;

    DisplayRotationReversionController(DisplayContent content) {
        mDisplayContent = content;
    }

    boolean isRotationReversionEnabled() {
        return mDisplayContent.mAppCompatCameraPolicy.hasDisplayRotationCompatPolicy()
                || mDisplayContent.getDisplayRotation().mFoldController != null
                || mDisplayContent.getIgnoreOrientationRequest();
    }

    void beforeOverrideApplied(int slotIndex) {
        if (mSlots[slotIndex]) return;
        maybeSaveUserRotation();
        mSlots[slotIndex] = true;
    }

    boolean isOverrideActive(int slotIndex) {
        return mSlots[slotIndex];
    }

    @Nullable
    boolean[] getSlotsCopy() {
        return isRotationReversionEnabled() ? mSlots.clone() : null;
    }

    void updateForNoSensorOverride() {
        if (!mSlots[REVERSION_TYPE_NOSENSOR]) {
            if (isTopFullscreenActivityNoSensor()) {
                ProtoLog.v(WM_DEBUG_ORIENTATION, "NOSENSOR override detected");
                beforeOverrideApplied(REVERSION_TYPE_NOSENSOR);
            }
        } else {
            if (!isTopFullscreenActivityNoSensor()) {
                ProtoLog.v(WM_DEBUG_ORIENTATION, "NOSENSOR override is absent: reverting");
                revertOverride(REVERSION_TYPE_NOSENSOR);
            }
        }
    }

    boolean isAnyOverrideActive() {
        for (int i = 0; i < NUM_SLOTS; ++i) {
            if (mSlots[i]) {
                return true;
            }
        }
        return false;
    }

    boolean revertOverride(int slotIndex) {
        if (!mSlots[slotIndex]) return false;
        mSlots[slotIndex] = false;
        if (isAnyOverrideActive()) {
            ProtoLog.v(WM_DEBUG_ORIENTATION,
                    "Other orientation overrides are in place: not reverting");
            return false;
        }
        // Only override if the rotation is frozen and there are no other active slots.
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();
        if (mUserRotationOverridden != ROTATION_UNDEFINED
                && displayRotation.getUserRotationMode() == USER_ROTATION_LOCKED) {
            displayRotation.setUserRotation(USER_ROTATION_LOCKED, mUserRotationOverridden,
                /* caller= */ "DisplayRotationReversionController#revertOverride");
            mUserRotationOverridden = ROTATION_UNDEFINED;
            return true;
        } else {
            return false;
        }
    }

    private void maybeSaveUserRotation() {
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();
        if (!isAnyOverrideActive()
                && displayRotation.getUserRotationMode() == USER_ROTATION_LOCKED) {
            mUserRotationOverridden = displayRotation.getUserRotation();
        }
    }

    private boolean isTopFullscreenActivityNoSensor() {
        final Task topFullscreenTask =
                mDisplayContent.getTask(
                        t -> t.getWindowingMode() == WINDOWING_MODE_FULLSCREEN);
        if (topFullscreenTask != null) {
            final ActivityRecord topActivity =
                    topFullscreenTask.topRunningActivity();
            return topActivity != null && topActivity.getOrientation()
                    == ActivityInfoProto.SCREEN_ORIENTATION_NOSENSOR;
        }
        return false;
    }
}
