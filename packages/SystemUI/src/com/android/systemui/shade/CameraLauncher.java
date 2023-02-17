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

package com.android.systemui.shade;

import com.android.systemui.camera.CameraGestureHelper;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.phone.KeyguardBypassController;

import javax.inject.Inject;

/** Handles launching camera from Shade. */
@SysUISingleton
public class CameraLauncher {
    private final CameraGestureHelper mCameraGestureHelper;
    private final KeyguardBypassController mKeyguardBypassController;

    private boolean mLaunchingAffordance;

    @Inject
    public CameraLauncher(
            CameraGestureHelper cameraGestureHelper,
            KeyguardBypassController keyguardBypassController
    ) {
        mCameraGestureHelper = cameraGestureHelper;
        mKeyguardBypassController = keyguardBypassController;
    }

    /** Launches the camera. */
    public void launchCamera(int source, boolean isShadeFullyCollapsed) {
        if (!isShadeFullyCollapsed) {
            setLaunchingAffordance(true);
        }

        mCameraGestureHelper.launchCamera(source);
    }

    /**
     * Set whether we are currently launching an affordance. This is currently only set when
     * launched via a camera gesture.
     */
    public void setLaunchingAffordance(boolean launchingAffordance) {
        mLaunchingAffordance = launchingAffordance;
        mKeyguardBypassController.setLaunchingAffordance(launchingAffordance);
    }

    /**
     * Return true when a bottom affordance is launching an occluded activity with a splash screen.
     */
    public boolean isLaunchingAffordance() {
        return mLaunchingAffordance;
    }

    /**
     * Whether the camera application can be launched for the camera launch gesture.
     */
    public boolean canCameraGestureBeLaunched(int barState) {
        return mCameraGestureHelper.canCameraGestureBeLaunched(barState);
    }
}
