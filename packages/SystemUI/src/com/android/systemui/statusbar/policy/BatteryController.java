/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.annotation.Nullable;

import com.android.systemui.DemoMode;
import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public interface BatteryController extends DemoMode, Dumpable,
        CallbackController<BatteryStateChangeCallback> {
    /**
     * Prints the current state of the {@link BatteryController} to the given {@link PrintWriter}.
     */
    void dump(FileDescriptor fd, PrintWriter pw, String[] args);

    /**
     * Sets if the current device is in power save mode.
     */
    void setPowerSaveMode(boolean powerSave);

    /**
     * Returns {@code true} if the device is currently plugged in.
     */
    boolean isPluggedIn();

    /**
     * Returns {@code true} if the device is currently in power save mode.
     */
    boolean isPowerSave();

    /**
     * Returns {@code true} if AOD was disabled by power saving policies.
     */
    boolean isAodPowerSave();

    /**
     * Set reverse state.
     * @param isReverse true if turn on reverse, false otherwise
     */
    default void setReverseState(boolean isReverse) {}

    /**
     * A listener that will be notified whenever a change in battery level or power save mode has
     * occurred.
     */
    interface BatteryStateChangeCallback {

        default void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        }

        default void onPowerSaveChanged(boolean isPowerSave) {
        }

        default void onReverseChanged(boolean isReverse, int level, String name) {
        }
    }

    /**
     * If available, get the estimated battery time remaining as a string.
     *
     * @param completion A lambda that will be called with the result of fetching the estimate. The
     * first time this method is called may need to be dispatched to a background thread. The
     * completion is called on the main thread
     */
    default void getEstimatedTimeRemainingString(EstimateFetchCompletion completion) {}

    /**
     * Callback called when the estimated time remaining text is fetched.
     */
    public interface EstimateFetchCompletion {

        /**
         * The callback
         * @param estimate the estimate
         */
        void onBatteryRemainingEstimateRetrieved(@Nullable String estimate);
    }
}
