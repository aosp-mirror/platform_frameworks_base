/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.doze;

import android.annotation.NonNull;

/**
 * Interface the doze service uses to communicate with the rest of system UI.
 */
public interface DozeHost {
    void addCallback(@NonNull Callback callback);
    void removeCallback(@NonNull Callback callback);
    void startDozing();
    void pulseWhileDozing(@NonNull PulseCallback callback, int reason);
    void stopDozing();
    void dozeTimeTick();
    boolean isPowerSaveActive();
    boolean isPulsingBlocked();
    boolean isProvisioned();

    /**
     * Makes a current pulse last for twice as long.
     * @param reason why we're extending it.
     */
    void extendPulse(int reason);

    void setAnimateWakeup(boolean animateWakeup);

    /**
     * Reports that a tap event happend on the Sensors Low Power Island.
     *
     * @param x Touch X, or -1 if sensor doesn't support touch location.
     * @param y Touch Y, or -1 if sensor doesn't support touch location.
     */
    void onSlpiTap(float x, float y);

    /**
     * Artificially dim down the the display by changing scrim opacities.
     * @param scrimOpacity opacity from 0 to 1.
     */
    default void setAodDimmingScrim(float scrimOpacity) {}

    /**
     * Sets the actual display brightness.
     * @param value from 0 to 255.
     */
    void setDozeScreenBrightness(int value);

    /**
     * Fade out screen before switching off the display power mode.
     * @param onDisplayOffCallback Executed when the display is black.
     */
    void prepareForGentleSleep(Runnable onDisplayOffCallback);

    /**
     * Cancel pending {@code onDisplayOffCallback} callback.
     * @see #prepareForGentleSleep(Runnable)
     */
    void cancelGentleSleep();

    void onIgnoreTouchWhilePulsing(boolean ignore);

    /**
     * Leaves pulsing state, going back to ambient UI.
     */
    void stopPulsing();

    /** Returns whether always-on-display is suppressed. This does not include suppressing
     * wake-up gestures. */
    boolean isAlwaysOnSuppressed();

    interface Callback {
        /**
         * Called when a high priority notification is added.
         * @param onPulseSuppressedListener A listener that is invoked if the pulse is being
         *                                  supressed.
         */
        default void onNotificationAlerted(Runnable onPulseSuppressedListener) {}

        /**
         * Called when battery state or power save mode changes.
         * @param active whether power save is active or not
         */
        default void onPowerSaveChanged(boolean active) {}

        /**
         * Called when the always on suppression state changes. See {@link #isAlwaysOnSuppressed()}.
         */
        default void onAlwaysOnSuppressedChanged(boolean suppressed) {}
    }

    interface PulseCallback {
        void onPulseStarted();
        void onPulseFinished();
    }
}
