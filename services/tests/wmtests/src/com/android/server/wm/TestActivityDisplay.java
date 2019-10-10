/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.any;

import android.hardware.display.DisplayManagerGlobal;
import android.view.Display;
import android.view.DisplayInfo;

class TestActivityDisplay extends ActivityDisplay {
    private final ActivityStackSupervisor mSupervisor;

    static TestActivityDisplay create(ActivityStackSupervisor supervisor) {
        return create(supervisor, SystemServicesTestRule.sNextDisplayId++);
    }

    static TestActivityDisplay create(ActivityStackSupervisor supervisor, DisplayInfo info) {
        return create(supervisor, SystemServicesTestRule.sNextDisplayId++, info);
    }

    static TestActivityDisplay create(ActivityStackSupervisor supervisor, int displayId) {
        final DisplayInfo info = new DisplayInfo();
        supervisor.mService.mContext.getDisplay().getDisplayInfo(info);
        return create(supervisor, displayId, info);
    }

    static TestActivityDisplay create(ActivityStackSupervisor supervisor, int displayId,
            DisplayInfo info) {
        if (displayId == DEFAULT_DISPLAY) {
            synchronized (supervisor.mService.mGlobalLock) {
                return new TestActivityDisplay(supervisor,
                        supervisor.mRootActivityContainer.mDisplayManager.getDisplay(displayId));
            }
        }
        final Display display = new Display(DisplayManagerGlobal.getInstance(), displayId,
                info, DEFAULT_DISPLAY_ADJUSTMENTS);

        synchronized (supervisor.mService.mGlobalLock) {
            return new TestActivityDisplay(supervisor, display);
        }
    }

    private TestActivityDisplay(ActivityStackSupervisor supervisor, Display display) {
        super(supervisor.mService.mRootActivityContainer, display);
        // Normally this comes from display-properties as exposed by WM. Without that, just
        // hard-code to FULLSCREEN for tests.
        setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        mSupervisor = supervisor;
        spyOn(this);
        spyOn(mDisplayContent);

        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();
        spyOn(displayRotation);
        doAnswer(invocation -> {
            // Bypass all the rotation animation and display freezing stuff for testing and just
            // set the rotation we want for the display
            final int oldRotation = displayRotation.getRotation();
            final int rotation = displayRotation.rotationForOrientation(
                    displayRotation.getLastOrientation(), oldRotation);
            if (oldRotation == rotation) {
                return false;
            }
            mDisplayContent.setLayoutNeeded();
            displayRotation.setRotation(rotation);
            return true;
        }).when(displayRotation).updateRotationUnchecked(anyBoolean());

        final InputMonitor inputMonitor = mDisplayContent.getInputMonitor();
        spyOn(inputMonitor);
        doNothing().when(inputMonitor).resumeDispatchingLw(any());
    }

    @SuppressWarnings("TypeParameterUnusedInFormals")
    @Override
    ActivityStack createStackUnchecked(int windowingMode, int activityType,
            int stackId, boolean onTop) {
        return new ActivityTestsBase.StackBuilder(mSupervisor.mRootActivityContainer)
                .setDisplay(this)
                .setWindowingMode(windowingMode)
                .setActivityType(activityType)
                .setStackId(stackId)
                .setOnTop(onTop)
                .setCreateActivity(false)
                .build();
    }
}
