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

package androidx.window.extensions.area;

import android.content.Context;
import android.hardware.devicestate.DeviceStateRequest;
import android.hardware.display.DisplayManager;
import android.view.Display;

import androidx.annotation.NonNull;

/**
 * Callback class to be notified of updates to a {@link DeviceStateRequest} for the rear display
 * presentation state. This class notifies the {@link RearDisplayPresentationController} when the
 * device is ready to enable the rear display presentation feature.
 */
public class RearDisplayPresentationRequestCallback implements DeviceStateRequest.Callback {

    private static final String TAG = RearDisplayPresentationRequestCallback.class.getSimpleName();

    @NonNull
    private final DisplayManager mDisplayManager;
    @NonNull
    private final DisplayManager.DisplayListener mRearDisplayListener = new RearDisplayListener();
    @NonNull
    private final RearDisplayPresentationController mRearDisplayPresentationController;
    private boolean mWaitingForRearDisplay = false;

    public RearDisplayPresentationRequestCallback(@NonNull Context context,
            @NonNull RearDisplayPresentationController rearDisplayPresentationController) {
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mRearDisplayListener,
                context.getMainThreadHandler(), DisplayManager.EVENT_FLAG_DISPLAY_ADDED
                        | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED);

        mRearDisplayPresentationController = rearDisplayPresentationController;
    }

    @Override
    public void onRequestActivated(@NonNull DeviceStateRequest request) {
        Display[] rearDisplays = mDisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_REAR);
        if (rearDisplays.length == 0) {
            // No rear facing display found, marking waiting for display flag as true.
            mWaitingForRearDisplay = true;
            return;
        }
        mDisplayManager.unregisterDisplayListener(mRearDisplayListener);
        mRearDisplayPresentationController.startSession(rearDisplays[0]);
    }

    @Override
    public void onRequestCanceled(@NonNull DeviceStateRequest request) {
        mDisplayManager.unregisterDisplayListener(mRearDisplayListener);
        mRearDisplayPresentationController.endSession();
    }

    /**
     * {@link DisplayManager.DisplayListener} to be used if a rear facing {@link Display} isn't
     * available synchronously when the device is entered into the rear display presentation state.
     * A rear facing {@link Display} is a {@link Display} that is categorized as
     * {@link DisplayManager#DISPLAY_CATEGORY_REAR}. This can occur if {@link DisplayManager} is
     * still in the process of configuring itself for this state when
     * {@link DeviceStateRequest.Callback#onRequestActivated} is called.
     *
     * The {@link DisplayManager.DisplayListener} removes itself when a rear facing display is
     * found.
     */
    private class RearDisplayListener implements DisplayManager.DisplayListener {
        @Override
        public void onDisplayAdded(int displayId) {
            Display display = mDisplayManager.getDisplay(displayId);
            if (mWaitingForRearDisplay && (display.getFlags() & Display.FLAG_REAR) != 0) {
                startRearDisplayPresentation(display);
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            Display display = mDisplayManager.getDisplay(displayId);
            if (mWaitingForRearDisplay && (display.getFlags() & Display.FLAG_REAR) != 0) {
                startRearDisplayPresentation(display);
            }
        }

        /**
         * Starts a new {@link RearDisplayPresentation} with the updated {@link Display} with a
         * category of {@link DisplayManager#DISPLAY_CATEGORY_REAR}.
         */
        private void startRearDisplayPresentation(Display rearDisplay) {
            // We have been notified of a change to a rear display, we can unregister the
            // callback and stop waiting for a display
            mDisplayManager.unregisterDisplayListener(this);
            mWaitingForRearDisplay = false;

            mRearDisplayPresentationController.startSession(rearDisplay);
        }
    }
}
