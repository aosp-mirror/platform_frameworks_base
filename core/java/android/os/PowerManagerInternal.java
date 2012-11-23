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

import java.util.function.Consumer;

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
     * Converts platform constants to proto enums.
     */
    public static int wakefulnessToProtoEnum(int wakefulness) {
        switch (wakefulness) {
            case WAKEFULNESS_ASLEEP:
                return PowerManagerInternalProto.WAKEFULNESS_ASLEEP;
            case WAKEFULNESS_AWAKE:
                return PowerManagerInternalProto.WAKEFULNESS_AWAKE;
            case WAKEFULNESS_DREAMING:
                return PowerManagerInternalProto.WAKEFULNESS_DREAMING;
            case WAKEFULNESS_DOZING:
                return PowerManagerInternalProto.WAKEFULNESS_DOZING;
            default:
                return wakefulness;
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
    public abstract void setMaximumScreenOffTimeoutFromDeviceAdmin(int userId, long timeMs);

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

    /**
     * Used by sidekick manager to tell the power manager if it shouldn't change the display state
     * when a draw wake lock is acquired. Some processes may grab such a wake lock to do some work
     * in a powered-up state, but we shouldn't give up sidekick control over the display until this
     * override is lifted.
     */
    public abstract void setDrawWakeLockOverrideFromSidekick(boolean keepState);

    public abstract PowerSaveState getLowPowerState(int serviceType);

    public abstract void registerLowPowerModeObserver(LowPowerModeListener listener);

    /**
     * Same as {@link #registerLowPowerModeObserver} but can take a lambda.
     */
    public void registerLowPowerModeObserver(int serviceType, Consumer<PowerSaveState> listener) {
        registerLowPowerModeObserver(new LowPowerModeListener() {
            @Override
            public int getServiceType() {
                return serviceType;
            }

            @Override
            public void onLowPowerModeChanged(PowerSaveState state) {
                listener.accept(state);
            }
        });
    }

    public interface LowPowerModeListener {
        int getServiceType();
        void onLowPowerModeChanged(PowerSaveState state);
    }

    public abstract boolean setDeviceIdleMode(boolean enabled);

    public abstract boolean setLightDeviceIdleMode(boolean enabled);

    public abstract void setDeviceIdleWhitelist(int[] appids);

    public abstract void setDeviceIdleTempWhitelist(int[] appids);

    public abstract void startUidChanges();

    public abstract void finishUidChanges();

    public abstract void updateUidProcState(int uid, int procState);

    public abstract void uidGone(int uid);

    public abstract void uidActive(int uid);

    public abstract void uidIdle(int uid);

    /**
     * The hintId sent through this method should be in-line with the
     * PowerHint defined in android/hardware/power/<version 1.0 & up>/IPower.h
     */
    public abstract void powerHint(int hintId, int data);

    /** Returns whether there hasn't been a user activity event for the given number of ms. */
    public abstract boolean wasDeviceIdleFor(long ms);

    /** Returns information about the last wakeup event. */
    public abstract PowerManager.WakeData getLastWakeup();

    public abstract boolean setPowerSaveMode(boolean mode);

    public abstract void setFeature(int featureId, int data);

    public abstract int getFeature(int featureId);
}
