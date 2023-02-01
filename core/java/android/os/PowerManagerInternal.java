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
import android.view.KeyEvent;

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
     * @param brightness The overridden brightness, or Float.NaN to disable the override.
     */
    public abstract void setScreenBrightnessOverrideFromWindowManager(float brightness);

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

    /**
     * Updates the Low Power Standby allowlist.
     *
     * @param uids UIDs that are exempt from Low Power Standby restrictions
     */
    public abstract void setLowPowerStandbyAllowlist(int[] uids);

    /**
     * Used by LowPowerStandbyController to notify the power manager that Low Power Standby's
     * active state has changed.
     *
     * @param active {@code true} to activate Low Power Standby, {@code false} to turn it off.
     */
    public abstract void setLowPowerStandbyActive(boolean active);

    public abstract void startUidChanges();

    public abstract void finishUidChanges();

    public abstract void updateUidProcState(int uid, int procState);

    public abstract void uidGone(int uid);

    public abstract void uidActive(int uid);

    public abstract void uidIdle(int uid);

    /**
     * Boost: It is sent when user interacting with the device, for example,
     * touchscreen events are incoming.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Boost.aidl
     */
    public static final int BOOST_INTERACTION = 0;

    /**
     * Boost: It indicates that the framework is likely to provide a new display
     * frame soon. This implies that the device should ensure that the display
     * processing path is powered up and ready to receive that update.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Boost.aidl
     */
    public static final int BOOST_DISPLAY_UPDATE_IMMINENT = 1;

    /**
     * SetPowerBoost() indicates the device may need to boost some resources, as
     * the load is likely to increase before the kernel governors can react.
     * Depending on the boost, it may be appropriate to raise the frequencies of
     * CPU, GPU, memory subsystem, or stop CPU from going into deep sleep state.
     *
     * @param boost Boost which is to be set with a timeout.
     * @param durationMs The expected duration of the user's interaction, if
     *        known, or 0 if the expected duration is unknown.
     *        a negative value indicates canceling previous boost.
     *        A given platform can choose to boost some time based on durationMs,
     *        and may also pick an appropriate timeout for 0 case.
     */
    public abstract void setPowerBoost(int boost, int durationMs);

    /**
     * Mode: It indicates that the device is to allow wake up when the screen
     * is tapped twice.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_DOUBLE_TAP_TO_WAKE = 0;

    /**
     * Mode: It indicates Low power mode is activated or not. Low power mode
     * is intended to save battery at the cost of performance.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_LOW_POWER = 1;

    /**
     * Mode: It indicates Sustained Performance mode is activated or not.
     * Sustained performance mode is intended to provide a consistent level of
     * performance for a prolonged amount of time.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_SUSTAINED_PERFORMANCE = 2;

    /**
     * Mode: It sets the device to a fixed performance level which can be sustained
     * under normal indoor conditions for at least 10 minutes.
     * Fixed performance mode puts both upper and lower bounds on performance such
     * that any workload run while in a fixed performance mode should complete in
     * a repeatable amount of time.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_FIXED_PERFORMANCE = 3;

    /**
     * Mode: It indicates VR Mode is activated or not. VR mode is intended to
     * provide minimum guarantee for performance for the amount of time the device
     * can sustain it.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_VR = 4;

    /**
     * Mode: It indicates that an application has been launched.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_LAUNCH = 5;

    /**
     * Mode: It indicates that the device is about to enter a period of expensive
     * rendering.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_EXPENSIVE_RENDERING = 6;

    /**
     * Mode: It indicates that the device is about entering/leaving interactive
     * state or on-interactive state.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_INTERACTIVE = 7;

    /**
     * Mode: It indicates the device is in device idle, externally known as doze.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_DEVICE_IDLE = 8;

    /**
     * Mode: It indicates that display is either off or still on but is optimized
     * for low power.
     * Defined in hardware/interfaces/power/aidl/android/hardware/power/Mode.aidl
     */
    public static final int MODE_DISPLAY_INACTIVE = 9;

    /**
     * SetPowerMode() is called to enable/disable specific hint mode, which
     * may result in adjustment of power/performance parameters of the
     * cpufreq governor and other controls on device side.
     *
     * @param mode Mode which is to be enable/disable.
     * @param enabled true to enable, false to disable the mode.
     */
    public abstract void setPowerMode(int mode, boolean enabled);

    /** Returns whether there hasn't been a user activity event for the given number of ms. */
    public abstract boolean wasDeviceIdleFor(long ms);

    /** Returns information about the last wakeup event. */
    public abstract PowerManager.WakeData getLastWakeup();

    /** Returns information about the last event to go to sleep. */
    public abstract PowerManager.SleepData getLastGoToSleep();

    /** Allows power button to intercept a power key button press. */
    public abstract boolean interceptPowerKeyDown(KeyEvent event);
}
