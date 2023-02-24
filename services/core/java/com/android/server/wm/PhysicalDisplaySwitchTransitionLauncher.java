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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.devicestate.DeviceStateManager;
import android.os.HandlerExecutor;
import android.window.DisplayAreaInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

public class PhysicalDisplaySwitchTransitionLauncher {

    private final DisplayContent mDisplayContent;
    private final WindowManagerService mService;
    private final DeviceStateManager mDeviceStateManager;
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
        mService = displayContent.mWmService;
        mTransitionController = transitionController;

        mDeviceStateManager = mService.mContext.getSystemService(DeviceStateManager.class);

        if (mDeviceStateManager != null) {
            mDeviceStateListener = new DeviceStateListener(mService.mContext);
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
                && mService.mContext.getResources().getBoolean(config_unfoldTransitionEnabled)
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
            mTransition = t;
        }
    }

    /**
     * Called when physical display is getting updated, this could happen e.g. on foldable
     * devices when the physical underlying display is replaced.
     *
     * @param fromRotation rotation before the display change
     * @param toRotation rotation after the display change
     * @param newDisplayAreaInfo display area info after the display change
     */
    public void onDisplayUpdated(int fromRotation, int toRotation,
            @NonNull DisplayAreaInfo newDisplayAreaInfo) {
        if (mTransition == null) return;

        final boolean started = mDisplayContent.mRemoteDisplayChangeController
                .performRemoteDisplayChange(fromRotation, toRotation, newDisplayAreaInfo,
                        this::continueDisplayUpdate);

        if (!started) {
            markTransitionAsReady();
        }
    }

    private void continueDisplayUpdate(@Nullable WindowContainerTransaction transaction) {
        if (mTransition == null) return;

        if (transaction != null) {
            mService.mAtmService.mWindowOrganizerController.applyTransaction(transaction);
        }

        markTransitionAsReady();
    }

    private void markTransitionAsReady() {
        if (mTransition == null) return;

        mTransition.setAllReady();
        mTransition = null;
    }

    class DeviceStateListener extends DeviceStateManager.FoldStateListener {

        DeviceStateListener(Context context) {
            super(context, newIsFolded -> mIsFolded = newIsFolded);
        }
    }
}
