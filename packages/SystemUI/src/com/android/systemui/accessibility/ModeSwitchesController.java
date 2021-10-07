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

import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;

import android.annotation.MainThread;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;

/**
 * A class to control {@link MagnificationModeSwitch}. It should show the button UI with following
 * conditions:
 * <ol>
 *   <li> Both full-screen and window magnification mode are capable.</li>
 *   <li> The magnification scale is changed by a user.</li>
 * <ol>
 */
@SysUISingleton
public class ModeSwitchesController {

    private final DisplayIdIndexSupplier<MagnificationModeSwitch> mSwitchSupplier;

    public ModeSwitchesController(Context context) {
        mSwitchSupplier = new SwitchSupplier(context,
                context.getSystemService(DisplayManager.class));
    }

    @VisibleForTesting
    ModeSwitchesController(DisplayIdIndexSupplier<MagnificationModeSwitch> switchSupplier) {
        mSwitchSupplier = switchSupplier;
    }

    /**
     * Shows a button that a user can click the button to switch magnification mode. And the
     * button would be dismissed automatically after the button is displayed for a period of time.
     *
     * @param displayId The logical display id
     * @param mode      The magnification mode
     * @see android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
     * @see android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
     */
    @MainThread
    void showButton(int displayId, int mode) {
        final MagnificationModeSwitch magnificationModeSwitch =
                mSwitchSupplier.get(displayId);
        if (magnificationModeSwitch == null) {
            return;
        }
        magnificationModeSwitch.showButton(mode);
    }

    /**
     * Removes magnification mode switch button immediately.
     *
     * @param displayId The logical display id
     */
    void removeButton(int displayId) {
        final MagnificationModeSwitch magnificationModeSwitch =
                mSwitchSupplier.get(displayId);
        if (magnificationModeSwitch == null) {
            return;
        }
        magnificationModeSwitch.removeButton();
    }

    /**
     * Called when the configuration has changed, and it updates magnification button UI.
     *
     * @param configDiff a bit mask of the differences between the configurations
     */
    @MainThread
    void onConfigurationChanged(int configDiff) {
        mSwitchSupplier.forEach(
                switchController -> switchController.onConfigurationChanged(configDiff));
    }

    private static class SwitchSupplier extends DisplayIdIndexSupplier<MagnificationModeSwitch> {

        private final Context mContext;

        /**
         * @param context        Context
         * @param displayManager DisplayManager
         */
        SwitchSupplier(Context context, DisplayManager displayManager) {
            super(displayManager);
            mContext = context;
        }

        @Override
        protected MagnificationModeSwitch createInstance(Display display) {
            final Context uiContext = mContext.createWindowContext(display,
                    TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY, /* options */ null);
            return new MagnificationModeSwitch(uiContext);
        }
    }
}
