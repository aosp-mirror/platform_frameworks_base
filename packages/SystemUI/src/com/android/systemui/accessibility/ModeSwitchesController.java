/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.accessibility;

import android.annotation.MainThread;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;

import javax.inject.Singleton;

/**
 * Class to control magnification mode switch button. Shows the button UI when both full-screen
 * and window magnification mode are capable, and when the magnification scale is changed. And
 * the button UI would automatically be dismissed after displaying for a period of time.
 */
@Singleton
public class ModeSwitchesController {

    private static final String TAG = "ModeSwitchesController";

    private final Context mContext;
    private final DisplayManager mDisplayManager;

    private final SparseArray<MagnificationModeSwitch> mDisplaysToSwitches =
            new SparseArray<>();

    public ModeSwitchesController(Context context) {
        mContext = context;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
    }

    /**
     * Shows a button that a user can click the button to switch magnification mode. And the
     * button would be dismissed automatically after the button is displayed for a period of time.
     *
     * @param displayId The logical display id
     * @param mode      The magnification mode
     *
     * @see android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
     * @see android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
     */
    @MainThread
    void showButton(int displayId, int mode) {
        if (mDisplaysToSwitches.get(displayId) == null) {
            final MagnificationModeSwitch magnificationModeSwitch =
                    createMagnificationSwitchController(displayId);
            if (magnificationModeSwitch == null) {
                return;
            }
        }
        mDisplaysToSwitches.get(displayId).showButton(mode);
    }

    /**
     * Removes magnification mode switch button immediately.
     *
     * @param displayId The logical display id
     */
    void removeButton(int displayId) {
        if (mDisplaysToSwitches.get(displayId) == null) {
            return;
        }
        mDisplaysToSwitches.get(displayId).removeButton();
    }

    private MagnificationModeSwitch createMagnificationSwitchController(int displayId) {
        if (mDisplayManager.getDisplay(displayId) == null) {
            Log.w(TAG, "createMagnificationSwitchController displayId is invalid.");
            return null;
        }
        final MagnificationModeSwitch
                magnificationModeSwitch = new MagnificationModeSwitch(
                getDisplayContext(displayId));
        mDisplaysToSwitches.put(displayId, magnificationModeSwitch);
        return magnificationModeSwitch;
    }

    private Context getDisplayContext(int displayId) {
        final Display display = mDisplayManager.getDisplay(displayId);
        final Context context = (displayId == Display.DEFAULT_DISPLAY)
                ? mContext
                : mContext.createDisplayContext(display);
        return context;
    }

}
