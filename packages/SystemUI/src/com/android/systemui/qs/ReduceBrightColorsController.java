/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs;

import android.content.res.Resources;

import com.android.systemui.statusbar.policy.CallbackController;

public interface ReduceBrightColorsController extends
        CallbackController<ReduceBrightColorsController.Listener> {

    /** Returns {@code true} if Reduce Bright Colors is activated */
    boolean isReduceBrightColorsActivated();

    /** Sets the activation state of Reduce Bright Colors */
    void setReduceBrightColorsActivated(boolean activated);

    /** Gets whether Reduce Bright Colors is being transitioned to Even Dimmer */
    boolean isInUpgradeMode(Resources resources);
    /**
     * Listener invoked whenever the Reduce Bright Colors settings are changed.
     */
    interface Listener {
        /**
         * Listener invoked when the activated state changes.
         *
         * @param activated {@code true} if Reduce Bright Colors is activated.
         */
        default void onActivated(boolean activated) {
        }
    }
}