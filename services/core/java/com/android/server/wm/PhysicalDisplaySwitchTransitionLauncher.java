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
import static android.view.WindowManager.TRANSIT_FLAG_PHYSICAL_DISPLAY_SWITCH;

import static com.android.internal.R.bool.config_unfoldTransitionEnabled;
import static com.android.server.wm.ActivityTaskManagerService.POWER_MODE_REASON_CHANGE_DISPLAY;
import static com.android.server.wm.DeviceStateController.DeviceState.FOLDED;
import static com.android.server.wm.DeviceStateController.DeviceState.HALF_FOLDED;
import static com.android.server.wm.DeviceStateController.DeviceState.OPEN;

import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.window.DisplayAreaInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.ProtoLog;
import com.android.server.wm.DeviceStateController.DeviceState;

public class PhysicalDisplaySwitchTransitionLauncher {

    private final DisplayContent mDisplayContent;
    private final ActivityTaskManagerService mAtmService;
    private final Context mContext;
    private final TransitionController mTransitionController;

    /**
     * If on a foldable device represents whether we need to show unfold animation when receiving
     * a physical display switch event
     */
    private boolean mShouldRequestTransitionOnDisplaySwitch = false;
    /**
     * Current device state from {@link android.hardware.devicestate.DeviceStateManager}
     */
    private DeviceState mDeviceState = DeviceState.UNKNOWN;
    private Transition mTransition;

    public PhysicalDisplaySwitchTransitionLauncher(DisplayContent displayContent,
            TransitionController transitionController) {
        this(displayContent, displayContent.mWmService.mAtmService,
                displayContent.mWmService.mContext, transitionController);
    }

    @VisibleForTesting
    public PhysicalDisplaySwitchTransitionLauncher(DisplayContent displayContent,
            ActivityTaskManagerService service, Context context,
            TransitionController transitionController) {
        mDisplayContent = displayContent;
        mAtmService = service;
        mContext = context;
        mTransitionController = transitionController;
    }

    /**
     * Called by the display manager just before it applied the device state, it is guaranteed
     * that in case of physical display change the
     * {@link PhysicalDisplaySwitchTransitionLauncher#requestDisplaySwitchTransitionIfNeeded}
     * method will be invoked *after* this one.
     */
    void foldStateChanged(DeviceState newDeviceState) {
        boolean isUnfolding = mDeviceState == FOLDED
                && (newDeviceState == HALF_FOLDED || newDeviceState == OPEN);

        if (isUnfolding) {
            // Request transition only if we are unfolding the device
            mShouldRequestTransitionOnDisplaySwitch = true;
        } else if (newDeviceState != HALF_FOLDED && newDeviceState != OPEN) {
            // Cancel the transition request in case if we are folding or switching to back
            // to the rear display before the displays got switched
            mShouldRequestTransitionOnDisplaySwitch = false;
        }

        mDeviceState = newDeviceState;
    }

    /**
     * Requests to start a transition for the physical display switch
     */
    public void requestDisplaySwitchTransitionIfNeeded(int displayId, int oldDisplayWidth,
            int oldDisplayHeight, int newDisplayWidth, int newDisplayHeight) {
        if (!mShouldRequestTransitionOnDisplaySwitch) return;
        if (!mTransitionController.isShellTransitionsEnabled()) return;
        if (!mDisplayContent.getLastHasContent()) return;

        boolean shouldRequestUnfoldTransition = mContext.getResources()
                .getBoolean(config_unfoldTransitionEnabled) && ValueAnimator.areAnimatorsEnabled();

        if (!shouldRequestUnfoldTransition) {
            return;
        }

        mTransition = null;

        if (mTransitionController.isCollecting()) {
            // Add display container to the currently collecting transition
            mTransition = mTransitionController.getCollectingTransition();
            mTransition.collect(mDisplayContent);

            // Make sure that transition is not ready until we finish the remote display change
            mTransition.setReady(mDisplayContent, false);
            mTransition.addFlag(TRANSIT_FLAG_PHYSICAL_DISPLAY_SWITCH);

            ProtoLog.d(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                    "Adding display switch to existing collecting transition");
        } else {
            final TransitionRequestInfo.DisplayChange displayChange =
                    new TransitionRequestInfo.DisplayChange(displayId);

            final Rect startAbsBounds = new Rect(0, 0, oldDisplayWidth, oldDisplayHeight);
            displayChange.setStartAbsBounds(startAbsBounds);
            final Rect endAbsBounds = new Rect(0, 0, newDisplayWidth, newDisplayHeight);
            displayChange.setEndAbsBounds(endAbsBounds);
            displayChange.setPhysicalDisplayChanged(true);

            mTransition = mTransitionController.requestStartDisplayTransition(TRANSIT_CHANGE,
                    0 /* flags */, mDisplayContent, null /* remoteTransition */, displayChange);
            mTransition.collect(mDisplayContent);
        }

        if (mTransition != null) {
            mAtmService.startPowerMode(POWER_MODE_REASON_CHANGE_DISPLAY);
        }

        mShouldRequestTransitionOnDisplaySwitch = false;
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
            mAtmService.mWindowOrganizerController.applyTransaction(transaction);
        }

        markTransitionAsReady();
    }

    private void markTransitionAsReady() {
        if (mTransition == null) return;

        mTransition.setAllReady();
        mTransition = null;
    }

}
