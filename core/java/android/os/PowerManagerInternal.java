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
     * Wakefulness: The device is asleep.  It can only be awoken by a call to wakeUp().
     * The screen should be off or in the process of being turned off by the display controller.
     * The device typically passes through the dozing state first.
     */
    public static final int WAKEFULNESS_ASLEEP = 0;

    /**
     * Wakefulness: The device is fully awake.  It can be put to sleep by a call to goToSleep().
     * When the user activity timeout expires, the device may start dreaming or go to sleep.
     */
    public static final int WAKEFULNESS_AWAKE = 1;

    /**
     * Wakefulness: The device is dreaming.  It can be awoken by a call to wakeUp(),
     * which ends the dream.  The device goes to sleep when goToSleep() is called, when
     * the dream ends or when unplugged.
     * User activity may brighten the screen but does not end the dream.
     */
    public static final int WAKEFULNESS_DREAMING = 2;

    /**
     * Wakefulness: The device is dozing.  It is almost asleep but is allowing a special
     * low-power "doze" dream to run which keeps the display on but lets the application
     * processor be suspended.  It can be awoken by a call to wakeUp() which ends the dream.
     * The device fully goes to sleep if the dream cannot be started or ends on its own.
     */
    public static final int WAKEFULNESS_DOZING = 3;


    /**
     * Power hint:
     * Interaction: The user is interacting with the device. The corresponding data field must be
     * the expected duration of the fling, or 0 if unknown.
     *
     * Sustained Performance Mode: Enable/Disables Sustained Performance Mode.
     *
     * These must be kept in sync with the values in hardware/libhardware/include/hardware/power.h
     */
    public static final int POWER_HINT_INTERACTION = 2;
    public static final int POWER_HINT_SUSTAINED_PERFORMANCE_MODE = 6;

    public static String wakefulnessToString(int wakefulness) {
        switch (wakefulness) {
            case WAKEFULNESS_ASLEEP:
                return "Asleep";
            case WAKEFULNESS_AWAKE:
                return "Awake";
            case WAKEFULNESS_DREAMING:
                return "Dreaming";
            case WAKEFULNESS_DOZING:
                return "Dozing";
            default:
                return Integer.toString(wakefulness);
        }
    }

    /**
     * Returns true if the wakefulness state represents an interactive state
     * as defined by {@link android.os.PowerManager#isInteractive}.
     */
    public static boolean isInteractive(int wakefulness) {
        return wakefulness == WAKEFULNESS_AWAKE || wakefulness == WAKEFULNESS_DREAMING;
    }

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
     * Used by the window manager to tell the power manager that the user is no longer actively
     * using the device.
     */
    public abstract void setUserInactiveOverrideFromWindowManager();

    /**
     * Used by device administration to set the maximum screen off timeout.
     *
     * This method must only be called by the device administration policy manager.
     */
    public abstract void setMaximumScreenOffTimeoutFromDeviceAdmin(int timeMs);

    /**
     * Used by the dream manager to override certain properties while dozing.
     *
     * @param screenState The overridden screen state, or {@link Display#STATE_UNKNOWN}
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

    public abstract boolean setDeviceIdleMode(boolean enabled);

    public abstract boolean setLightDeviceIdleMode(boolean enabled);

    public abstract void setDeviceIdleWhitelist(int[] appids);

    public abstract void setDeviceIdleTempWhitelist(int[] appids);

    public abstract void updateUidProcState(int uid, int procState);

    public abstract void uidGone(int uid);

    public abstract void powerHint(int hintId, int data);
}
