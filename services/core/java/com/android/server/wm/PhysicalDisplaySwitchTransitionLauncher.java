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

import static android.view.WindowManager.TRANSIT_CHANGE;

import static com.android.internal.R.bool.config_unfoldTransitionEnabled;
import static com.android.server.wm.ActivityTaskManagerService.POWER_MODE_REASON_CHANGE_DISPLAY;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.devicestate.DeviceStateManager;
import android.os.HandlerExecutor;
import android.window.TransitionRequestInfo;

public class PhysicalDisplaySwitchTransitionLauncher {

    private final DisplayContent mDisplayContent;
    private final DeviceStateManager mDeviceStateManager;
    private final Context mContext;
    private final TransitionController mTransitionController;

    private DeviceStateListener mDeviceStateListener;

    /**
     * If on a foldable device represents whether the device is folded or not
     */
    private boolean mIsFolded;
    private Transition mTransition;

    public PhysicalDisplaySwitchTransitionLauncher(DisplayContent displayContent,
            TransitionController transitionController) {
        mDisplayContent = displayContent;
        mContext = mDisplayContent.mWmService.mContext;
        mTransitionController = transitionController;

        mDeviceStateManager = mContext.getSystemService(DeviceStateManager.class);

        if (mDeviceStateManager != null) {
            mDeviceStateListener = new DeviceStateListener(mContext);
            mDeviceStateManager
                    .registerCallback(new HandlerExecutor(mDisplayContent.mWmService.mH),
                            mDeviceStateListener);
        }
    }

    public void destroy() {
        if (mDeviceStateManager != null) {
            mDeviceStateManager.unregisterCallback(mDeviceStateListener);
        }
    }

    /**
     * Requests to start a transition for the physical display switch
     */
    public void requestDisplaySwitchTransitionIfNeeded(int displayId, int oldDisplayWidth,
            int oldDisplayHeight, int newDisplayWidth, int newDisplayHeight) {
        if (!mTransitionController.isShellTransitionsEnabled()) return;
        if (!mDisplayContent.getLastHasContent()) return;

        boolean shouldRequestUnfoldTransition = !mIsFolded
                && mContext.getResources().getBoolean(config_unfoldTransitionEnabled)
                && ValueAnimator.areAnimatorsEnabled();

        if (!shouldRequestUnfoldTransition) {
            return;
        }

        final TransitionRequestInfo.DisplayChange displayChange =
                new TransitionRequestInfo.DisplayChange(displayId);

        final Rect startAbsBounds = new Rect(0, 0, oldDisplayWidth, oldDisplayHeight);
        displayChange.setStartAbsBounds(startAbsBounds);
        final Rect endAbsBounds = new Rect(0, 0, newDisplayWidth, newDisplayHeight);
        displayChange.setEndAbsBounds(endAbsBounds);
        displayChange.setPhysicalDisplayChanged(true);

        final Transition t = mTransitionController.requestTransitionIfNeeded(TRANSIT_CHANGE,
                0 /* flags */,
                mDisplayContent, mDisplayContent, null /* remoteTransition */,
                displayChange);

        if (t != null) {
            mDisplayContent.mAtmService.startLaunchPowerMode(POWER_MODE_REASON_CHANGE_DISPLAY);
            mTransitionController.collectForDisplayChange(mDisplayContent, t);
            mTransition = t;
        }
    }

    public void onDisplayUpdated() {
        if (mTransition != null) {
            mTransition.setAllReady();
            mTransition = null;
        }
    }

    class DeviceStateListener extends DeviceStateManager.FoldStateListener {

        DeviceStateListener(Context context) {
            super(context, newIsFolded -> mIsFolded = newIsFolded);
        }
    }
}
