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
 * limitations under the License.
 */

package android.os;

import android.view.Display;

/**
 * Power manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class PowerManagerInternal {
    /**
     * Used by the window manager to override the screen brightness based on the
     * current foreground activity.
     *
     * This method must only be called by the window manager.
     *
     * @param brightness The overridden brightness, or -1 to disable the override.
     */
    public abstract void setScreenBrightnessOverrideFromWindowManager(int brightness);

    /**
     * Used by the window manager to override the button brightness based on the
     * current foreground activity.
     *
     * This method must only be called by the window manager.
     *
     * @param brightness The overridden brightness, or -1 to disable the override.
     */
    public abstract void setButtonBrightnessOverrideFromWindowManager(int brightness);

    /**
     * Used by the window manager to override the user activity timeout based on the
     * current foreground activity.  It can only be used to make the timeout shorter
     * than usual, not longer.
     *
     * This method must only be called by the window manager.
     *
     * @param timeoutMillis The overridden timeout, or -1 to disable the override.
     */
    public abstract void setUserActivityTimeoutOverrideFromWindowManager(long timeoutMillis);

    /**
     * Used by the dream manager to override certain properties while dozing.
     *
     * @param screenState The overridden screen state, or {@link Display.STATE_UNKNOWN}
     * to disable the override.
     * @param screenBrightness The overridden screen brightness, or
     * {@link PowerManager#BRIGHTNESS_DEFAULT} to disable the override.
     */
    public abstract void setDozeOverrideFromDreamManager(
            int screenState, int screenBrightness);

    public abstract boolean getLowPowerModeEnabled();

    public abstract void registerLowPowerModeObserver(LowPowerModeListener listener);

    public interface LowPowerModeListener {
        public void onLowPowerModeChanged(boolean enabled);
    }
}
