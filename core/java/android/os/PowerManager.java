/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.Manifest.permission;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.service.dreams.Sandman;
import android.util.ArrayMap;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * This class gives you control of the power state of the device.
 *
 * <p>
 * <b>Device battery life will be significantly affected by the use of this API.</b>
 * Do not acquire {@link WakeLock}s unless you really need them, use the minimum levels
 * possible, and be sure to release them as soon as possible.
 * </p><p>
 * The primary API you'll use is {@link #newWakeLock(int, String) newWakeLock()}.
 * This will create a {@link PowerManager.WakeLock} object.  You can then use methods
 * on the wake lock object to control the power state of the device.
 * </p><p>
 * In practice it's quite simple:
 * {@samplecode
 * PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
 * PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "My Tag");
 * wl.acquire();
 *   ..screen will stay on during this section..
 * wl.release();
 * }
 * </p><p>
 * The following wake lock levels are defined, with varying effects on system power.
 * <i>These levels are mutually exclusive - you may only specify one of them.</i>
 *
 * <table>
 *     <tr><th>Flag Value</th>
 *     <th>CPU</th> <th>Screen</th> <th>Keyboard</th></tr>
 *
 *     <tr><td>{@link #PARTIAL_WAKE_LOCK}</td>
 *         <td>On*</td> <td>Off</td> <td>Off</td>
 *     </tr>
 *
 *     <tr><td>{@link #SCREEN_DIM_WAKE_LOCK}</td>
 *         <td>On</td> <td>Dim</td> <td>Off</td>
 *     </tr>
 *
 *     <tr><td>{@link #SCREEN_BRIGHT_WAKE_LOCK}</td>
 *         <td>On</td> <td>Bright</td> <td>Off</td>
 *     </tr>
 *
 *     <tr><td>{@link #FULL_WAKE_LOCK}</td>
 *         <td>On</td> <td>Bright</td> <td>Bright</td>
 *     </tr>
 * </table>
 * </p><p>
 * *<i>If you hold a partial wake lock, the CPU will continue to run, regardless of any
 * display timeouts or the state of the screen and even after the user presses the power button.
 * In all other wake locks, the CPU will run, but the user can still put the device to sleep
 * using the power button.</i>
 * </p><p>
 * In addition, you can add two more flags, which affect behavior of the screen only.
 * <i>These flags have no effect when combined with a {@link #PARTIAL_WAKE_LOCK}.</i></p>
 *
 * <table>
 *     <tr><th>Flag Value</th> <th>Description</th></tr>
 *
 *     <tr><td>{@link #ACQUIRE_CAUSES_WAKEUP}</td>
 *         <td>Normal wake locks don't actually turn on the illumination.  Instead, they cause
 *         the illumination to remain on once it turns on (e.g. from user activity).  This flag
 *         will force the screen and/or keyboard to turn on immediately, when the WakeLock is
 *         acquired.  A typical use would be for notifications which are important for the user to
 *         see immediately.</td>
 *     </tr>
 *
 *     <tr><td>{@link #ON_AFTER_RELEASE}</td>
 *         <td>If this flag is set, the user activity timer will be reset when the WakeLock is
 *         released, causing the illumination to remain on a bit longer.  This can be used to
 *         reduce flicker if you are cycling between wake lock conditions.</td>
 *     </tr>
 * </table>
 * <p>
 * Any application using a WakeLock must request the {@code android.permission.WAKE_LOCK}
 * permission in an {@code <uses-permission>} element of the application's manifest.
 * </p>
 */
@SystemService(Context.POWER_SERVICE)
public final class PowerManager {
    private static final String TAG = "PowerManager";

    /* NOTE: Wake lock levels were previously defined as a bit field, except that only a few
     * combinations were actually supported so the bit field was removed.  This explains
     * why the numbering scheme is so odd.  If adding a new wake lock level, any unused
     * value (in frameworks/base/core/proto/android/os/enums.proto) can be used.
     */

    /**
     * Wake lock level: Ensures that the CPU is running; the screen and keyboard
     * backlight will be allowed to go off.
     * <p>
     * If the user presses the power button, then the screen will be turned off
     * but the CPU will be kept on until all partial wake locks have been released.
     * </p>
     */
    public static final int PARTIAL_WAKE_LOCK = OsProtoEnums.PARTIAL_WAKE_LOCK; // 0x00000001

    /**
     * Wake lock level: Ensures that the screen is on (but may be dimmed);
     * the keyboard backlight will be allowed to go off.
     * <p>
     * If the user presses the power button, then the {@link #SCREEN_DIM_WAKE_LOCK} will be
     * implicitly released by the system, causing both the screen and the CPU to be turned off.
     * Contrast with {@link #PARTIAL_WAKE_LOCK}.
     * </p>
     *
     * @deprecated Most applications should use
     * {@link android.view.WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON} instead
     * of this type of wake lock, as it will be correctly managed by the platform
     * as the user moves between applications and doesn't require a special permission.
     */
    @Deprecated
    public static final int SCREEN_DIM_WAKE_LOCK = OsProtoEnums.SCREEN_DIM_WAKE_LOCK; // 0x00000006

    /**
     * Wake lock level: Ensures that the screen is on at full brightness;
     * the keyboard backlight will be allowed to go off.
     * <p>
     * If the user presses the power button, then the {@link #SCREEN_BRIGHT_WAKE_LOCK} will be
     * implicitly released by the system, causing both the screen and the CPU to be turned off.
     * Contrast with {@link #PARTIAL_WAKE_LOCK}.
     * </p>
     *
     * @deprecated Most applications should use
     * {@link android.view.WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON} instead
     * of this type of wake lock, as it will be correctly managed by the platform
     * as the user moves between applications and doesn't require a special permission.
     */
    @Deprecated
    public static final int SCREEN_BRIGHT_WAKE_LOCK =
            OsProtoEnums.SCREEN_BRIGHT_WAKE_LOCK; // 0x0000000a

    /**
     * Wake lock level: Ensures that the screen and keyboard backlight are on at
     * full brightness.
     * <p>
     * If the user presses the power button, then the {@link #FULL_WAKE_LOCK} will be
     * implicitly released by the system, causing both the screen and the CPU to be turned off.
     * Contrast with {@link #PARTIAL_WAKE_LOCK}.
     * </p>
     *
     * @deprecated Most applications should use
     * {@link android.view.WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON} instead
     * of this type of wake lock, as it will be correctly managed by the platform
     * as the user moves between applications and doesn't require a special permission.
     */
    @Deprecated
    public static final int FULL_WAKE_LOCK = OsProtoEnums.FULL_WAKE_LOCK; // 0x0000001a

    /**
     * Wake lock level: Turns the screen off when the proximity sensor activates.
     * <p>
     * If the proximity sensor detects that an object is nearby, the screen turns off
     * immediately.  Shortly after the object moves away, the screen turns on again.
     * </p><p>
     * A proximity wake lock does not prevent the device from falling asleep
     * unlike {@link #FULL_WAKE_LOCK}, {@link #SCREEN_BRIGHT_WAKE_LOCK} and
     * {@link #SCREEN_DIM_WAKE_LOCK}.  If there is no user activity and no other
     * wake locks are held, then the device will fall asleep (and lock) as usual.
     * However, the device will not fall asleep while the screen has been turned off
     * by the proximity sensor because it effectively counts as ongoing user activity.
     * </p><p>
     * Since not all devices have proximity sensors, use {@link #isWakeLockLevelSupported}
     * to determine whether this wake lock level is supported.
     * </p><p>
     * Cannot be used with {@link #ACQUIRE_CAUSES_WAKEUP}.
     * </p>
     */
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK =
            OsProtoEnums.PROXIMITY_SCREEN_OFF_WAKE_LOCK; // 0x00000020

    /**
     * Wake lock level: Put the screen in a low power state and allow the CPU to suspend
     * if no other wake locks are held.
     * <p>
     * This is used by the dream manager to implement doze mode.  It currently
     * has no effect unless the power manager is in the dozing state.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * {@hide}
     */
    public static final int DOZE_WAKE_LOCK = OsProtoEnums.DOZE_WAKE_LOCK; // 0x00000040

    /**
     * Wake lock level: Keep the device awake enough to allow drawing to occur.
     * <p>
     * This is used by the window manager to allow applications to draw while the
     * system is dozing.  It currently has no effect unless the power manager is in
     * the dozing state.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * {@hide}
     */
    public static final int DRAW_WAKE_LOCK = OsProtoEnums.DRAW_WAKE_LOCK; // 0x00000080

    /**
     * Mask for the wake lock level component of a combined wake lock level and flags integer.
     *
     * @hide
     */
    public static final int WAKE_LOCK_LEVEL_MASK = 0x0000ffff;

    /**
     * Wake lock flag: Turn the screen on when the wake lock is acquired.
     * <p>
     * Normally wake locks don't actually wake the device, they just cause
     * the screen to remain on once it's already on.  Think of the video player
     * application as the normal behavior.  Notifications that pop up and want
     * the device to be on are the exception; use this flag to be like them.
     * </p><p>
     * Cannot be used with {@link #PARTIAL_WAKE_LOCK}.
     * </p>
     */
    public static final int ACQUIRE_CAUSES_WAKEUP = 0x10000000;

    /**
     * Wake lock flag: When this wake lock is released, poke the user activity timer
     * so the screen stays on for a little longer.
     * <p>
     * Will not turn the screen on if it is not already on.
     * See {@link #ACQUIRE_CAUSES_WAKEUP} if you want that.
     * </p><p>
     * Cannot be used with {@link #PARTIAL_WAKE_LOCK}.
     * </p>
     */
    public static final int ON_AFTER_RELEASE = 0x20000000;

    /**
     * Wake lock flag: This wake lock is not important for logging events.  If a later
     * wake lock is acquired that is important, it will be considered the one to log.
     * @hide
     */
    public static final int UNIMPORTANT_FOR_LOGGING = 0x40000000;

    /**
     * Flag for {@link WakeLock#release WakeLock.release(int)}: Defer releasing a
     * {@link #PROXIMITY_SCREEN_OFF_WAKE_LOCK} wake lock until the proximity sensor
     * indicates that an object is not in close proximity.
     */
    public static final int RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY = 1 << 0;

    /**
     * Flag for {@link WakeLock#release(int)} when called due to timeout.
     * @hide
     */
    public static final int RELEASE_FLAG_TIMEOUT = 1 << 16;

    /**
     * Brightness value for fully on.
     * @hide
     */
    @UnsupportedAppUsage
    public static final int BRIGHTNESS_ON = 255;

    /**
     * Brightness value for fully off.
     * @hide
     */
    public static final int BRIGHTNESS_OFF = 0;

    /**
     * Brightness value for default policy handling by the system.
     * @hide
     */
    public static final int BRIGHTNESS_DEFAULT = -1;

    // Note: Be sure to update android.os.BatteryStats and PowerManager.h
    // if adding or modifying user activity event constants.

    /**
     * User activity event type: Unspecified event type.
     * @hide
     */
    @SystemApi
    public static final int USER_ACTIVITY_EVENT_OTHER = 0;

    /**
     * User activity event type: Button or key pressed or released.
     * @hide
     */
    @SystemApi
    public static final int USER_ACTIVITY_EVENT_BUTTON = 1;

    /**
     * User activity event type: Touch down, move or up.
     * @hide
     */
    @SystemApi
    public static final int USER_ACTIVITY_EVENT_TOUCH = 2;

    /**
     * User activity event type: Accessibility taking action on behalf of user.
     * @hide
     */
    @SystemApi
    public static final int USER_ACTIVITY_EVENT_ACCESSIBILITY = 3;

    /**
     * User activity event type: {@link android.service.attention.AttentionService} taking action
     * on behalf of user.
     * @hide
     */
    public static final int USER_ACTIVITY_EVENT_ATTENTION = 4;

    /**
     * User activity flag: If already dimmed, extend the dim timeout
     * but do not brighten.  This flag is useful for keeping the screen on
     * a little longer without causing a visible change such as when
     * the power key is pressed.
     * @hide
     */
    @SystemApi
    public static final int USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS = 1 << 0;

    /**
     * User activity flag: Note the user activity as usual but do not
     * reset the user activity timeout.  This flag is useful for applying
     * user activity power hints when interacting with the device indirectly
     * on a secondary screen while allowing the primary screen to go to sleep.
     * @hide
     */
    @SystemApi
    public static final int USER_ACTIVITY_FLAG_INDIRECT = 1 << 1;

    /**
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_MIN = 0;

    /**
     * Go to sleep reason code: Going to sleep due by application request.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_APPLICATION = GO_TO_SLEEP_REASON_MIN;

    /**
     * Go to sleep reason code: Going to sleep due by request of the
     * device administration policy.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_DEVICE_ADMIN = 1;

    /**
     * Go to sleep reason code: Going to sleep due to a screen timeout.
     * @hide
     */
    @UnsupportedAppUsage
    public static final int GO_TO_SLEEP_REASON_TIMEOUT = 2;

    /**
     * Go to sleep reason code: Going to sleep due to the lid switch being closed.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_LID_SWITCH = 3;

    /**
     * Go to sleep reason code: Going to sleep due to the power button being pressed.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_POWER_BUTTON = 4;

    /**
     * Go to sleep reason code: Going to sleep due to HDMI.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_HDMI = 5;

    /**
     * Go to sleep reason code: Going to sleep due to the sleep button being pressed.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_SLEEP_BUTTON = 6;

    /**
     * Go to sleep reason code: Going to sleep by request of an accessibility service
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_ACCESSIBILITY = 7;

    /**
     * Go to sleep reason code: Going to sleep due to force-suspend.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_FORCE_SUSPEND = 8;

    /**
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_MAX = GO_TO_SLEEP_REASON_FORCE_SUSPEND;

    /**
     * @hide
     */
    public static String sleepReasonToString(int sleepReason) {
        switch (sleepReason) {
            case GO_TO_SLEEP_REASON_APPLICATION: return "application";
            case GO_TO_SLEEP_REASON_DEVICE_ADMIN: return "device_admin";
            case GO_TO_SLEEP_REASON_TIMEOUT: return "timeout";
            case GO_TO_SLEEP_REASON_LID_SWITCH: return "lid_switch";
            case GO_TO_SLEEP_REASON_POWER_BUTTON: return "power_button";
            case GO_TO_SLEEP_REASON_HDMI: return "hdmi";
            case GO_TO_SLEEP_REASON_SLEEP_BUTTON: return "sleep_button";
            case GO_TO_SLEEP_REASON_ACCESSIBILITY: return "accessibility";
            case GO_TO_SLEEP_REASON_FORCE_SUSPEND: return "force_suspend";
            default: return Integer.toString(sleepReason);
        }
    }

    /**
     * Go to sleep flag: Skip dozing state and directly go to full sleep.
     * @hide
     */
    public static final int GO_TO_SLEEP_FLAG_NO_DOZE = 1 << 0;

    /**
     * @hide
     */
    @IntDef(prefix = { "WAKE_REASON_" }, value = {
            WAKE_REASON_UNKNOWN,
            WAKE_REASON_POWER_BUTTON,
            WAKE_REASON_APPLICATION,
            WAKE_REASON_PLUGGED_IN,
            WAKE_REASON_GESTURE,
            WAKE_REASON_CAMERA_LAUNCH,
            WAKE_REASON_WAKE_KEY,
            WAKE_REASON_WAKE_MOTION,
            WAKE_REASON_HDMI,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WakeReason{}

    /**
     * Wake up reason code: Waking for an unknown reason.
     * @hide
     */
    public static final int WAKE_REASON_UNKNOWN = 0;

    /**
     * Wake up reason code: Waking up due to power button press.
     * @hide
     */
    public static final int WAKE_REASON_POWER_BUTTON = 1;

    /**
     * Wake up reason code: Waking up because an application requested it.
     * @hide
     */
    public static final int WAKE_REASON_APPLICATION = 2;

    /**
     * Wake up reason code: Waking up due to being plugged in or docked on a wireless charger.
     * @hide
     */
    public static final int WAKE_REASON_PLUGGED_IN = 3;

    /**
     * Wake up reason code: Waking up due to a user performed gesture (e.g. douple tapping on the
     * screen).
     * @hide
     */
    public static final int WAKE_REASON_GESTURE = 4;

    /**
     * Wake up reason code: Waking up due to the camera being launched.
     * @hide
     */
    public static final int WAKE_REASON_CAMERA_LAUNCH = 5;

    /**
     * Wake up reason code: Waking up because a wake key other than power was pressed.
     * @hide
     */
    public static final int WAKE_REASON_WAKE_KEY = 6;

    /**
     * Wake up reason code: Waking up because a wake motion was performed.
     *
     * For example, a trackball that was set to wake the device up was spun.
     * @hide
     */
    public static final int WAKE_REASON_WAKE_MOTION = 7;

    /**
     * Wake up reason code: Waking due to HDMI.
     * @hide
     */
    public static final int WAKE_REASON_HDMI = 8;

    /**
     * Wake up reason code: Waking due to the lid being opened.
     * @hide
     */
    public static final int WAKE_REASON_LID = 9;

    /**
     * Convert the wake reason to a string for debugging purposes.
     * @hide
     */
    public static String wakeReasonToString(@WakeReason int wakeReason) {
        switch (wakeReason) {
            case WAKE_REASON_UNKNOWN: return "WAKE_REASON_UNKNOWN";
            case WAKE_REASON_POWER_BUTTON: return "WAKE_REASON_POWER_BUTTON";
            case WAKE_REASON_APPLICATION: return "WAKE_REASON_APPLICATION";
            case WAKE_REASON_PLUGGED_IN: return "WAKE_REASON_PLUGGED_IN";
            case WAKE_REASON_GESTURE: return "WAKE_REASON_GESTURE";
            case WAKE_REASON_CAMERA_LAUNCH: return "WAKE_REASON_CAMERA_LAUNCH";
            case WAKE_REASON_WAKE_KEY: return "WAKE_REASON_WAKE_KEY";
            case WAKE_REASON_WAKE_MOTION: return "WAKE_REASON_WAKE_MOTION";
            case WAKE_REASON_HDMI: return "WAKE_REASON_HDMI";
            case WAKE_REASON_LID: return "WAKE_REASON_LID";
            default: return Integer.toString(wakeReason);
        }
    }

    /**
     * @hide
     */
    public static class WakeData {
        public WakeData(long wakeTime, @WakeReason int wakeReason) {
            this.wakeTime = wakeTime;
            this.wakeReason = wakeReason;
        }
        public long wakeTime;
        public @WakeReason int wakeReason;
    }

    /**
     * The value to pass as the 'reason' argument to reboot() to reboot into
     * recovery mode for tasks other than applying system updates, such as
     * doing factory resets.
     * <p>
     * Requires the {@link android.Manifest.permission#RECOVERY}
     * permission (in addition to
     * {@link android.Manifest.permission#REBOOT}).
     * </p>
     * @hide
     */
    public static final String REBOOT_RECOVERY = "recovery";

    /**
     * The value to pass as the 'reason' argument to reboot() to reboot into
     * recovery mode for applying system updates.
     * <p>
     * Requires the {@link android.Manifest.permission#RECOVERY}
     * permission (in addition to
     * {@link android.Manifest.permission#REBOOT}).
     * </p>
     * @hide
     */
    public static final String REBOOT_RECOVERY_UPDATE = "recovery-update";

    /**
     * The value to pass as the 'reason' argument to reboot() to
     * reboot into bootloader mode
     * @hide
     */
    public static final String REBOOT_BOOTLOADER = "bootloader";

    /**
     * The value to pass as the 'reason' argument to reboot() to
     * reboot into download mode
     * @hide
     */
    public static final String REBOOT_DOWNLOAD = "download";

    /**
     * The value to pass as the 'reason' argument to reboot() when device owner requests a reboot on
     * the device.
     * @hide
     */
    public static final String REBOOT_REQUESTED_BY_DEVICE_OWNER = "deviceowner";

    /**
     * The 'reason' value used when rebooting in safe mode
     * @hide
     */
    public static final String REBOOT_SAFE_MODE = "safemode";

    /**
     * The 'reason' value used when rebooting the device without turning on the screen.
     * @hide
     */
    public static final String REBOOT_QUIESCENT = "quiescent";

    /**
     * The value to pass as the 'reason' argument to android_reboot().
     * @hide
     */
    public static final String SHUTDOWN_USER_REQUESTED = "userrequested";

    /**
     * The value to pass as the 'reason' argument to android_reboot() when battery temperature
     * is too high.
     * @hide
     */
    public static final String SHUTDOWN_BATTERY_THERMAL_STATE = "thermal,battery";

    /**
     * The value to pass as the 'reason' argument to android_reboot() when device temperature
     * is too high.
     * @hide
     */
    public static final String SHUTDOWN_THERMAL_STATE = "thermal";

    /**
     * The value to pass as the 'reason' argument to android_reboot() when device is running
     * critically low on battery.
     * @hide
     */
    public static final String SHUTDOWN_LOW_BATTERY = "battery";

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SHUTDOWN_REASON_" }, value = {
            SHUTDOWN_REASON_UNKNOWN,
            SHUTDOWN_REASON_SHUTDOWN,
            SHUTDOWN_REASON_REBOOT,
            SHUTDOWN_REASON_USER_REQUESTED,
            SHUTDOWN_REASON_THERMAL_SHUTDOWN,
            SHUTDOWN_REASON_LOW_BATTERY,
            SHUTDOWN_REASON_BATTERY_THERMAL
    })
    public @interface ShutdownReason {}

    /**
     * constant for shutdown reason being unknown.
     * @hide
     */
    public static final int SHUTDOWN_REASON_UNKNOWN = 0;

    /**
     * constant for shutdown reason being normal shutdown.
     * @hide
     */
    public static final int SHUTDOWN_REASON_SHUTDOWN = 1;

    /**
     * constant for shutdown reason being reboot.
     * @hide
     */
    public static final int SHUTDOWN_REASON_REBOOT = 2;

    /**
     * constant for shutdown reason being user requested.
     * @hide
     */
    public static final int SHUTDOWN_REASON_USER_REQUESTED = 3;

    /**
     * constant for shutdown reason being overheating.
     * @hide
     */
    public static final int SHUTDOWN_REASON_THERMAL_SHUTDOWN = 4;

    /**
     * constant for shutdown reason being low battery.
     * @hide
     */
    public static final int SHUTDOWN_REASON_LOW_BATTERY = 5;

    /**
     * constant for shutdown reason being critical battery thermal state.
     * @hide
     */
    public static final int SHUTDOWN_REASON_BATTERY_THERMAL = 6;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ServiceType.LOCATION,
            ServiceType.VIBRATION,
            ServiceType.ANIMATION,
            ServiceType.FULL_BACKUP,
            ServiceType.KEYVALUE_BACKUP,
            ServiceType.NETWORK_FIREWALL,
            ServiceType.SCREEN_BRIGHTNESS,
            ServiceType.SOUND,
            ServiceType.BATTERY_STATS,
            ServiceType.DATA_SAVER,
            ServiceType.FORCE_ALL_APPS_STANDBY,
            ServiceType.FORCE_BACKGROUND_CHECK,
            ServiceType.OPTIONAL_SENSORS,
            ServiceType.AOD,
            ServiceType.QUICK_DOZE,
            ServiceType.NIGHT_MODE,
    })
    public @interface ServiceType {
        int NULL = 0;
        int LOCATION = 1;
        int VIBRATION = 2;
        int ANIMATION = 3;
        int FULL_BACKUP = 4;
        int KEYVALUE_BACKUP = 5;
        int NETWORK_FIREWALL = 6;
        int SCREEN_BRIGHTNESS = 7;
        int SOUND = 8;
        int BATTERY_STATS = 9;
        int DATA_SAVER = 10;
        int AOD = 14;

        /**
         * Whether to enable force-app-standby on all apps or not.
         */
        int FORCE_ALL_APPS_STANDBY = 11;

        /**
         * Whether to enable background check on all apps or not.
         */
        int FORCE_BACKGROUND_CHECK = 12;

        /**
         * Whether to disable non-essential sensors. (e.g. edge sensors.)
         */
        int OPTIONAL_SENSORS = 13;

        /**
         * Whether to go into Deep Doze as soon as the screen turns off or not.
         */
        int QUICK_DOZE = 15;

        /**
         * Whether to enable night mode when battery saver is enabled.
         */
        int NIGHT_MODE = 16;
    }

    /**
     * Either the location providers shouldn't be affected by battery saver,
     * or battery saver is off.
     */
    public static final int LOCATION_MODE_NO_CHANGE = 0;

    /**
     * In this mode, the GPS based location provider should be disabled when battery saver is on and
     * the device is non-interactive.
     */
    public static final int LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF = 1;

    /**
     * All location providers should be disabled when battery saver is on and
     * the device is non-interactive.
     */
    public static final int LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF = 2;

    /**
     * In this mode, all the location providers will be kept available, but location fixes
     * should only be provided to foreground apps.
     */
    public static final int LOCATION_MODE_FOREGROUND_ONLY = 3;

    /**
     * In this mode, location will not be turned off, but LocationManager will throttle all
     * requests to providers when the device is non-interactive.
     */
    public static final int LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF = 4;

    /** @hide */
    public static final int MIN_LOCATION_MODE = LOCATION_MODE_NO_CHANGE;
    /** @hide */
    public static final int MAX_LOCATION_MODE = LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"LOCATION_MODE_"}, value = {
            LOCATION_MODE_NO_CHANGE,
            LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
            LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
            LOCATION_MODE_FOREGROUND_ONLY,
            LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF,
    })
    public @interface LocationPowerSaveMode {}

    /** @hide */
    public static String locationPowerSaveModeToString(@LocationPowerSaveMode int mode) {
        switch (mode) {
            case LOCATION_MODE_NO_CHANGE:
                return "NO_CHANGE";
            case LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF:
                return "GPS_DISABLED_WHEN_SCREEN_OFF";
            case LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF:
                return "ALL_DISABLED_WHEN_SCREEN_OFF";
            case LOCATION_MODE_FOREGROUND_ONLY:
                return "FOREGROUND_ONLY";
            case LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF:
                return "THROTTLE_REQUESTS_WHEN_SCREEN_OFF";
            default:
                return Integer.toString(mode);
        }
    }

    final Context mContext;
    @UnsupportedAppUsage
    final IPowerManager mService;
    final Handler mHandler;

    IThermalService mThermalService;
    private final ArrayMap<OnThermalStatusChangedListener, IThermalStatusListener>
            mListenerMap = new ArrayMap<>();

    IDeviceIdleController mIDeviceIdleController;

    /**
     * {@hide}
     */
    public PowerManager(Context context, IPowerManager service, Handler handler) {
        mContext = context;
        mService = service;
        mHandler = handler;
    }

    /**
     * Gets the minimum supported screen brightness setting.
     * The screen may be allowed to become dimmer than this value but
     * this is the minimum value that can be set by the user.
     * @hide
     */
    @UnsupportedAppUsage
    public int getMinimumScreenBrightnessSetting() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMinimum);
    }

    /**
     * Gets the maximum supported screen brightness setting.
     * The screen may be allowed to become dimmer than this value but
     * this is the maximum value that can be set by the user.
     * @hide
     */
    @UnsupportedAppUsage
    public int getMaximumScreenBrightnessSetting() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMaximum);
    }

    /**
     * Gets the default screen brightness setting.
     * @hide
     */
    @UnsupportedAppUsage
    public int getDefaultScreenBrightnessSetting() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingDefault);
    }

    /**
     * Gets the minimum supported screen brightness setting for VR Mode.
     * @hide
     */
    public int getMinimumScreenBrightnessForVrSetting() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessForVrSettingMinimum);
    }

    /**
     * Gets the maximum supported screen brightness setting for VR Mode.
     * The screen may be allowed to become dimmer than this value but
     * this is the maximum value that can be set by the user.
     * @hide
     */
    public int getMaximumScreenBrightnessForVrSetting() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessForVrSettingMaximum);
    }

    /**
     * Gets the default screen brightness for VR setting.
     * @hide
     */
    public int getDefaultScreenBrightnessForVrSetting() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessForVrSettingDefault);
    }

    /**
     * Gets the default button brightness value.
     * @hide
     */
    public int getDefaultButtonBrightness() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_buttonBrightnessSettingDefault);
    }

    /**
     * Creates a new wake lock with the specified level and flags.
     * <p>
     * The {@code levelAndFlags} parameter specifies a wake lock level and optional flags
     * combined using the logical OR operator.
     * </p><p>
     * The wake lock levels are: {@link #PARTIAL_WAKE_LOCK},
     * {@link #FULL_WAKE_LOCK}, {@link #SCREEN_DIM_WAKE_LOCK}
     * and {@link #SCREEN_BRIGHT_WAKE_LOCK}.  Exactly one wake lock level must be
     * specified as part of the {@code levelAndFlags} parameter.
     * </p><p>
     * The wake lock flags are: {@link #ACQUIRE_CAUSES_WAKEUP}
     * and {@link #ON_AFTER_RELEASE}.  Multiple flags can be combined as part of the
     * {@code levelAndFlags} parameters.
     * </p><p>
     * Call {@link WakeLock#acquire() acquire()} on the object to acquire the
     * wake lock, and {@link WakeLock#release release()} when you are done.
     * </p><p>
     * {@samplecode
     * PowerManager pm = (PowerManager)mContext.getSystemService(
     *                                          Context.POWER_SERVICE);
     * PowerManager.WakeLock wl = pm.newWakeLock(
     *                                      PowerManager.SCREEN_DIM_WAKE_LOCK
     *                                      | PowerManager.ON_AFTER_RELEASE,
     *                                      TAG);
     * wl.acquire();
     * // ... do work...
     * wl.release();
     * }
     * </p><p>
     * Although a wake lock can be created without special permissions,
     * the {@link android.Manifest.permission#WAKE_LOCK} permission is
     * required to actually acquire or release the wake lock that is returned.
     * </p><p class="note">
     * If using this to keep the screen on, you should strongly consider using
     * {@link android.view.WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON} instead.
     * This window flag will be correctly managed by the platform
     * as the user moves between applications and doesn't require a special permission.
     * </p>
     *
     * <p>
     * Recommended naming conventions for tags to make debugging easier:
     * <ul>
     * <li>use a unique prefix delimited by a colon for your app/library (e.g.
     * gmail:mytag) to make it easier to understand where the wake locks comes
     * from. This namespace will also avoid collision for tags inside your app
     * coming from different libraries which will make debugging easier.
     * <li>use constants (e.g. do not include timestamps in the tag) to make it
     * easier for tools to aggregate similar wake locks. When collecting
     * debugging data, the platform only monitors a finite number of tags,
     * using constants will help tools to provide better debugging data.
     * <li>avoid using Class#getName() or similar method since this class name
     * can be transformed by java optimizer and obfuscator tools.
     * <li>avoid wrapping the tag or a prefix to avoid collision with wake lock
     * tags from the platform (e.g. *alarm*).
     * <li>never include personnally identifiable information for privacy
     * reasons.
     * </ul>
     * </p>
     *
     * @param levelAndFlags Combination of wake lock level and flag values defining
     * the requested behavior of the WakeLock.
     * @param tag Your class name (or other tag) for debugging purposes.
     *
     * @see WakeLock#acquire()
     * @see WakeLock#release()
     * @see #PARTIAL_WAKE_LOCK
     * @see #FULL_WAKE_LOCK
     * @see #SCREEN_DIM_WAKE_LOCK
     * @see #SCREEN_BRIGHT_WAKE_LOCK
     * @see #PROXIMITY_SCREEN_OFF_WAKE_LOCK
     * @see #ACQUIRE_CAUSES_WAKEUP
     * @see #ON_AFTER_RELEASE
     */
    public WakeLock newWakeLock(int levelAndFlags, String tag) {
        validateWakeLockParameters(levelAndFlags, tag);
        return new WakeLock(levelAndFlags, tag, mContext.getOpPackageName());
    }

    /** @hide */
    @UnsupportedAppUsage
    public static void validateWakeLockParameters(int levelAndFlags, String tag) {
        switch (levelAndFlags & WAKE_LOCK_LEVEL_MASK) {
            case PARTIAL_WAKE_LOCK:
            case SCREEN_DIM_WAKE_LOCK:
            case SCREEN_BRIGHT_WAKE_LOCK:
            case FULL_WAKE_LOCK:
            case PROXIMITY_SCREEN_OFF_WAKE_LOCK:
            case DOZE_WAKE_LOCK:
            case DRAW_WAKE_LOCK:
                break;
            default:
                throw new IllegalArgumentException("Must specify a valid wake lock level.");
        }
        if (tag == null) {
            throw new IllegalArgumentException("The tag must not be null.");
        }
    }

    /**
     * Notifies the power manager that user activity happened.
     * <p>
     * Resets the auto-off timer and brightens the screen if the device
     * is not asleep.  This is what happens normally when a key or the touch
     * screen is pressed or when some other user activity occurs.
     * This method does not wake up the device if it has been put to sleep.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param when The time of the user activity, in the {@link SystemClock#uptimeMillis()}
     * time base.  This timestamp is used to correctly order the user activity request with
     * other power management functions.  It should be set
     * to the timestamp of the input event that caused the user activity.
     * @param noChangeLights If true, does not cause the keyboard backlight to turn on
     * because of this event.  This is set when the power key is pressed.
     * We want the device to stay on while the button is down, but we're about
     * to turn off the screen so we don't want the keyboard backlight to turn on again.
     * Otherwise the lights flash on and then off and it looks weird.
     *
     * @see #wakeUp
     * @see #goToSleep
     *
     * @removed Requires signature or system permission.
     * @deprecated Use {@link #userActivity(long, int, int)}.
     */
    @Deprecated
    public void userActivity(long when, boolean noChangeLights) {
        userActivity(when, USER_ACTIVITY_EVENT_OTHER,
                noChangeLights ? USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS : 0);
    }

    /**
     * Notifies the power manager that user activity happened.
     * <p>
     * Resets the auto-off timer and brightens the screen if the device
     * is not asleep.  This is what happens normally when a key or the touch
     * screen is pressed or when some other user activity occurs.
     * This method does not wake up the device if it has been put to sleep.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} or
     * {@link android.Manifest.permission#USER_ACTIVITY} permission.
     * </p>
     *
     * @param when The time of the user activity, in the {@link SystemClock#uptimeMillis()}
     * time base.  This timestamp is used to correctly order the user activity request with
     * other power management functions.  It should be set
     * to the timestamp of the input event that caused the user activity.
     * @param event The user activity event.
     * @param flags Optional user activity flags.
     *
     * @see #wakeUp
     * @see #goToSleep
     *
     * @hide Requires signature or system permission.
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.DEVICE_POWER,
            android.Manifest.permission.USER_ACTIVITY
    })
    public void userActivity(long when, int event, int flags) {
        try {
            mService.userActivity(when, event, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

   /**
     * Forces the device to go to sleep.
     * <p>
     * Overrides all the wake locks that are held.
     * This is what happens when the power key is pressed to turn off the screen.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param time The time when the request to go to sleep was issued, in the
     * {@link SystemClock#uptimeMillis()} time base.  This timestamp is used to correctly
     * order the go to sleep request with other power management functions.  It should be set
     * to the timestamp of the input event that caused the request to go to sleep.
     *
     * @see #userActivity
     * @see #wakeUp
     *
     * @removed Requires signature permission.
     */
    public void goToSleep(long time) {
        goToSleep(time, GO_TO_SLEEP_REASON_APPLICATION, 0);
    }

    /**
     * Forces the device to go to sleep.
     * <p>
     * Overrides all the wake locks that are held.
     * This is what happens when the power key is pressed to turn off the screen.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param time The time when the request to go to sleep was issued, in the
     * {@link SystemClock#uptimeMillis()} time base.  This timestamp is used to correctly
     * order the go to sleep request with other power management functions.  It should be set
     * to the timestamp of the input event that caused the request to go to sleep.
     * @param reason The reason the device is going to sleep.
     * @param flags Optional flags to apply when going to sleep.
     *
     * @see #userActivity
     * @see #wakeUp
     *
     * @hide Requires signature permission.
     */
    @UnsupportedAppUsage
    public void goToSleep(long time, int reason, int flags) {
        try {
            mService.goToSleep(time, reason, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Forces the device to wake up from sleep.
     * <p>
     * If the device is currently asleep, wakes it up, otherwise does nothing.
     * This is what happens when the power key is pressed to turn on the screen.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param time The time when the request to wake up was issued, in the
     * {@link SystemClock#uptimeMillis()} time base.  This timestamp is used to correctly
     * order the wake up request with other power management functions.  It should be set
     * to the timestamp of the input event that caused the request to wake up.
     *
     * @see #userActivity
     * @see #goToSleep
     *
     * @deprecated Use {@link #wakeUp(long, int, String)} instead.
     * @removed Requires signature permission.
     */
    @Deprecated
    public void wakeUp(long time) {
        wakeUp(time, WAKE_REASON_UNKNOWN, "wakeUp");
    }

    /**
     * Forces the device to wake up from sleep.
     * <p>
     * If the device is currently asleep, wakes it up, otherwise does nothing.
     * This is what happens when the power key is pressed to turn on the screen.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param time The time when the request to wake up was issued, in the
     * {@link SystemClock#uptimeMillis()} time base.  This timestamp is used to correctly
     * order the wake up request with other power management functions.  It should be set
     * to the timestamp of the input event that caused the request to wake up.
     *
     * @param details A free form string to explain the specific details behind the wake up for
     *                debugging purposes.
     *
     * @see #userActivity
     * @see #goToSleep
     *
     * @deprecated Use {@link #wakeUp(long, int, String)} instead.
     * @hide
     */
    @UnsupportedAppUsage
    @Deprecated
    public void wakeUp(long time, String details) {
        wakeUp(time, WAKE_REASON_UNKNOWN, details);
    }

    /**
     * Forces the device to wake up from sleep.
     * <p>
     * If the device is currently asleep, wakes it up, otherwise does nothing.
     * This is what happens when the power key is pressed to turn on the screen.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param time The time when the request to wake up was issued, in the
     * {@link SystemClock#uptimeMillis()} time base.  This timestamp is used to correctly
     * order the wake up request with other power management functions.  It should be set
     * to the timestamp of the input event that caused the request to wake up.
     *
     * @param reason The reason for the wake up.
     *
     * @param details A free form string to explain the specific details behind the wake up for
     *                debugging purposes.
     *
     * @see #userActivity
     * @see #goToSleep
     * @hide
     */
    public void wakeUp(long time, @WakeReason int reason, String details) {
        try {
            mService.wakeUp(time, reason, details, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Forces the device to wake up from sleep only if
     * nothing is blocking the proximity sensor
     *
     * @see #wakeUp
     *
     * @hide
     */
    public void wakeUpWithProximityCheck(long time, @WakeReason int reason, String details) {
        try {
            mService.wakeUpWithProximityCheck(time, reason, details, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Forces the device to start napping.
     * <p>
     * If the device is currently awake, starts dreaming, otherwise does nothing.
     * When the dream ends or if the dream cannot be started, the device will
     * either wake up or go to sleep depending on whether there has been recent
     * user activity.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param time The time when the request to nap was issued, in the
     * {@link SystemClock#uptimeMillis()} time base.  This timestamp is used to correctly
     * order the nap request with other power management functions.  It should be set
     * to the timestamp of the input event that caused the request to nap.
     *
     * @see #wakeUp
     * @see #goToSleep
     *
     * @hide Requires signature permission.
     */
    public void nap(long time) {
        try {
            mService.nap(time);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the device to start dreaming.
     * <p>
     * If dream can not be started, for example if another {@link PowerManager} transition is in
     * progress, does nothing. Unlike {@link #nap(long)}, this does not put device to sleep when
     * dream ends.
     * </p><p>
     * Requires the {@link android.Manifest.permission#READ_DREAM_STATE} and
     * {@link android.Manifest.permission#WRITE_DREAM_STATE} permissions.
     * </p>
     *
     * @param time The time when the request to nap was issued, in the
     * {@link SystemClock#uptimeMillis()} time base.  This timestamp may be used to correctly
     * order the dream request with other power management functions.  It should be set
     * to the timestamp of the input event that caused the request to dream.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.READ_DREAM_STATE,
            android.Manifest.permission.WRITE_DREAM_STATE })
    public void dream(long time) {
        Sandman.startDreamByUserRequest(mContext);
    }

    /**
     * Boosts the brightness of the screen to maximum for a predetermined
     * period of time.  This is used to make the screen more readable in bright
     * daylight for a short duration.
     * <p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param time The time when the request to boost was issued, in the
     * {@link SystemClock#uptimeMillis()} time base.  This timestamp is used to correctly
     * order the boost request with other power management functions.  It should be set
     * to the timestamp of the input event that caused the request to boost.
     *
     * @hide Requires signature permission.
     */
    public void boostScreenBrightness(long time) {
        try {
            mService.boostScreenBrightness(time);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the screen brightness is currently boosted to maximum, caused by a call
     * to {@link #boostScreenBrightness(long)}.
     * @return {@code True} if the screen brightness is currently boosted. {@code False} otherwise.
     *
     * @deprecated This call is rarely used and will be phased out soon.
     * @hide
     * @removed
     */
    @SystemApi @Deprecated
    public boolean isScreenBrightnessBoosted() {
        return false;
    }

   /**
     * Returns true if the specified wake lock level is supported.
     *
     * @param level The wake lock level to check.
     * @return True if the specified wake lock level is supported.
     */
    public boolean isWakeLockLevelSupported(int level) {
        try {
            return mService.isWakeLockLevelSupported(level);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
      * Returns true if the device is in an interactive state.
      * <p>
      * For historical reasons, the name of this method refers to the power state of
      * the screen but it actually describes the overall interactive state of
      * the device.  This method has been replaced by {@link #isInteractive}.
      * </p><p>
      * The value returned by this method only indicates whether the device is
      * in an interactive state which may have nothing to do with the screen being
      * on or off.  To determine the actual state of the screen,
      * use {@link android.view.Display#getState}.
      * </p>
      *
      * @return True if the device is in an interactive state.
      *
      * @deprecated Use {@link #isInteractive} instead.
      */
    @Deprecated
    public boolean isScreenOn() {
        return isInteractive();
    }

    /**
     * Returns true if the device is in an interactive state.
     * <p>
     * When this method returns true, the device is awake and ready to interact
     * with the user (although this is not a guarantee that the user is actively
     * interacting with the device just this moment).  The main screen is usually
     * turned on while in this state.  Certain features, such as the proximity
     * sensor, may temporarily turn off the screen while still leaving the device in an
     * interactive state.  Note in particular that the device is still considered
     * to be interactive while dreaming (since dreams can be interactive) but not
     * when it is dozing or asleep.
     * </p><p>
     * When this method returns false, the device is dozing or asleep and must
     * be awoken before it will become ready to interact with the user again.  The
     * main screen is usually turned off while in this state.  Certain features,
     * such as "ambient mode" may cause the main screen to remain on (albeit in a
     * low power state) to display system-provided content while the device dozes.
     * </p><p>
     * The system will send a {@link android.content.Intent#ACTION_SCREEN_ON screen on}
     * or {@link android.content.Intent#ACTION_SCREEN_OFF screen off} broadcast
     * whenever the interactive state of the device changes.  For historical reasons,
     * the names of these broadcasts refer to the power state of the screen
     * but they are actually sent in response to changes in the overall interactive
     * state of the device, as described by this method.
     * </p><p>
     * Services may use the non-interactive state as a hint to conserve power
     * since the user is not present.
     * </p>
     *
     * @return True if the device is in an interactive state.
     *
     * @see android.content.Intent#ACTION_SCREEN_ON
     * @see android.content.Intent#ACTION_SCREEN_OFF
     */
    public boolean isInteractive() {
        try {
            return mService.isInteractive();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reboot the device.  Will not return if the reboot is successful.
     * <p>
     * Requires the {@link android.Manifest.permission#REBOOT} permission.
     * </p>
     *
     * @param reason code to pass to the kernel (e.g., "recovery") to
     *               request special boot modes, or null.
     */
    public void reboot(String reason) {
        try {
            mService.reboot(false, reason, true);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reboot the device.  Will not return if the reboot is successful.
     * <p>
     * Requires the {@link android.Manifest.permission#REBOOT} permission.
     * </p>
     *
     * @param reason code to pass to the kernel (e.g., "recovery", "bootloader", "download") to
     *               request special boot modes, or null.
     * @hide
     */
    public void rebootCustom(String reason) {
        try {
            mService.rebootCustom(false, reason, true);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reboot the device. Will not return if the reboot is successful.
     * <p>
     * Requires the {@link android.Manifest.permission#REBOOT} permission.
     * </p>
     * @hide
     */
    public void rebootSafeMode() {
        try {
            mService.rebootSafeMode(false, true);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the device is currently in power save mode.  When in this mode,
     * applications should reduce their functionality in order to conserve battery as
     * much as possible.  You can monitor for changes to this state with
     * {@link #ACTION_POWER_SAVE_MODE_CHANGED}.
     *
     * @return Returns true if currently in low power mode, else false.
     */
    public boolean isPowerSaveMode() {
        try {
            return mService.isPowerSaveMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the current power save mode.
     *
     * @return True if the set was allowed.
     *
     * @hide
     * @see #isPowerSaveMode()
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.DEVICE_POWER,
            android.Manifest.permission.POWER_SAVER
    })
    public boolean setPowerSaveModeEnabled(boolean mode) {
        try {
            return mService.setPowerSaveModeEnabled(mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the current state of dynamic power savings and disable threshold. This is
     * a signal to the system which an app can update to serve as an indicator that
     * the user will be in a battery critical situation before being able to plug in.
     * Only apps with the {@link android.Manifest.permission#POWER_SAVER} permission may do this.
     * This is a device global state, not a per user setting.
     *
     * <p>When enabled, the system may enact various measures for reducing power consumption in
     * order to help ensure that the user will make it to their next charging point. The most
     * visible of these will be the automatic enabling of battery saver if the user has set
     * their battery saver mode to "automatic". Note
     * that this is NOT simply an on/off switch for features, but rather a hint for the
     * system to consider enacting these power saving features, some of which have additional
     * logic around when to activate based on this signal.
     *
     * <p>The provided threshold is the percentage the system should consider itself safe at given
     * the current state of the device. The value is an integer representing a battery level.
     *
     * <p>The threshold is meant to set an explicit stopping point for dynamic power savings
     * functionality so that the dynamic power savings itself remains a signal rather than becoming
     * an on/off switch for a subset of features.
     * @hide
     *
     * @param powerSaveHint A signal indicating to the system if it believes the
     * dynamic power savings behaviors should be activated.
     * @param disableThreshold When the suggesting app believes it would be safe to disable dynamic
     * power savings behaviors.
     * @return True if the update was allowed and succeeded.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(permission.POWER_SAVER)
    public boolean setDynamicPowerSaveHint(boolean powerSaveHint, int disableThreshold) {
        try {
            return mService.setDynamicPowerSaveHint(powerSaveHint, disableThreshold);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the policy for adaptive power save.
     *
     * @return true if there was an effectual change. If full battery saver is enabled or the
     * adaptive policy is not enabled, then this will return false.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.DEVICE_POWER,
            android.Manifest.permission.POWER_SAVER
    })
    public boolean setAdaptivePowerSavePolicy(@NonNull BatterySaverPolicyConfig config) {
        try {
            return mService.setAdaptivePowerSavePolicy(config);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables adaptive power save.
     *
     * @return true if there was an effectual change. If full battery saver is enabled, then this
     * will return false.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.DEVICE_POWER,
            android.Manifest.permission.POWER_SAVER
    })
    public boolean setAdaptivePowerSaveEnabled(boolean enabled) {
        try {
            return mService.setAdaptivePowerSaveEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Indicates automatic battery saver toggling by the system will be based on percentage.
     *
     * @see PowerManager#getPowerSaveModeTrigger()
     *
     *  @hide
     */
    @SystemApi
    @TestApi
    public static final int POWER_SAVE_MODE_TRIGGER_PERCENTAGE = 0;

    /**
     * Indicates automatic battery saver toggling by the system will be based on the state
     * of the dynamic power savings signal.
     *
     * @see PowerManager#setDynamicPowerSaveHint(boolean, int)
     * @see PowerManager#getPowerSaveModeTrigger()
     *
     *  @hide
     */
    @SystemApi
    @TestApi
    public static final int POWER_SAVE_MODE_TRIGGER_DYNAMIC = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
        POWER_SAVE_MODE_TRIGGER_PERCENTAGE,
        POWER_SAVE_MODE_TRIGGER_DYNAMIC

    })
    public @interface AutoPowerSaveModeTriggers {}


    /**
     * Returns the current battery saver control mode. Values it may return are defined in
     * AutoPowerSaveModeTriggers. Note that this is a global device state, not a per user setting.
     *
     * @return The current value power saver mode for the system.
     *
     * @see AutoPowerSaveModeTriggers
     * @see PowerManager#getPowerSaveModeTrigger()
     * @hide
     */
    @AutoPowerSaveModeTriggers
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.POWER_SAVER)
    public int getPowerSaveModeTrigger() {
        try {
            return mService.getPowerSaveModeTrigger();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get data about the battery saver mode for a specific service
     * @param serviceType unique key for the service, one of {@link ServiceType}
     * @return Battery saver state data.
     *
     * @hide
     * @see com.android.server.power.batterysaver.BatterySaverPolicy
     * @see PowerSaveState
     */
    public PowerSaveState getPowerSaveState(@ServiceType int serviceType) {
        try {
            return mService.getPowerSaveState(serviceType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns how location features should behave when battery saver is on. When battery saver
     * is off, this will always return {@link #LOCATION_MODE_NO_CHANGE}.
     *
     * <p>This API is normally only useful for components that provide location features.
     *
     * @see #isPowerSaveMode()
     * @see #ACTION_POWER_SAVE_MODE_CHANGED
     */
    @LocationPowerSaveMode
    public int getLocationPowerSaveMode() {
        final PowerSaveState powerSaveState = getPowerSaveState(ServiceType.LOCATION);
        if (!powerSaveState.batterySaverEnabled) {
            return LOCATION_MODE_NO_CHANGE;
        }
        return powerSaveState.locationMode;
    }

    /**
     * Returns true if the device is currently in idle mode.  This happens when a device
     * has been sitting unused and unmoving for a sufficiently long period of time, so that
     * it decides to go into a lower power-use state.  This may involve things like turning
     * off network access to apps.  You can monitor for changes to this state with
     * {@link #ACTION_DEVICE_IDLE_MODE_CHANGED}.
     *
     * @return Returns true if currently in active device idle mode, else false.  This is
     * when idle mode restrictions are being actively applied; it will return false if the
     * device is in a long-term idle mode but currently running a maintenance window where
     * restrictions have been lifted.
     */
    public boolean isDeviceIdleMode() {
        try {
            return mService.isDeviceIdleMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the device is currently in light idle mode.  This happens when a device
     * has had its screen off for a short time, switching it into a batching mode where we
     * execute jobs, syncs, networking on a batching schedule.  You can monitor for changes to
     * this state with {@link #ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED}.
     *
     * @return Returns true if currently in active light device idle mode, else false.  This is
     * when light idle mode restrictions are being actively applied; it will return false if the
     * device is in a long-term idle mode but currently running a maintenance window where
     * restrictions have been lifted.
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isLightDeviceIdleMode() {
        try {
            return mService.isLightDeviceIdleMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether the given application package name is on the device's power whitelist.
     * Apps can be placed on the whitelist through the settings UI invoked by
     * {@link android.provider.Settings#ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS}.
     */
    public boolean isIgnoringBatteryOptimizations(String packageName) {
        synchronized (this) {
            if (mIDeviceIdleController == null) {
                mIDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                        ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
            }
        }
        try {
            return mIDeviceIdleController.isPowerSaveWhitelistApp(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Turn off the device.
     *
     * @param confirm If true, shows a shutdown confirmation dialog.
     * @param reason code to pass to android_reboot() (e.g. "userrequested"), or null.
     * @param wait If true, this call waits for the shutdown to complete and does not return.
     *
     * @hide
     */
    public void shutdown(boolean confirm, String reason, boolean wait) {
        try {
            mService.shutdown(confirm, reason, wait);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This function checks if the device has implemented Sustained Performance
     * Mode. This needs to be checked only once and is constant for a particular
     * device/release.
     *
     * Sustained Performance Mode is intended to provide a consistent level of
     * performance for prolonged amount of time.
     *
     * Applications should check if the device supports this mode, before using
     * {@link android.view.Window#setSustainedPerformanceMode}.
     *
     * @return Returns True if the device supports it, false otherwise.
     *
     * @see android.view.Window#setSustainedPerformanceMode
     */
    public boolean isSustainedPerformanceModeSupported() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sustainedPerformanceModeSupported);
    }

    /**
     * Thermal status code: Not under throttling.
     */
    public static final int THERMAL_STATUS_NONE = Temperature.THROTTLING_NONE;

    /**
     * Thermal status code: Light throttling where UX is not impacted.
     */
    public static final int THERMAL_STATUS_LIGHT = Temperature.THROTTLING_LIGHT;

    /**
     * Thermal status code: Moderate throttling where UX is not largely impacted.
     */
    public static final int THERMAL_STATUS_MODERATE = Temperature.THROTTLING_MODERATE;

    /**
     * Thermal status code: Severe throttling where UX is largely impacted.
     */
    public static final int THERMAL_STATUS_SEVERE = Temperature.THROTTLING_SEVERE;

    /**
     * Thermal status code: Platform has done everything to reduce power.
     */
    public static final int THERMAL_STATUS_CRITICAL = Temperature.THROTTLING_CRITICAL;

    /**
     * Thermal status code: Key components in platform are shutting down due to thermal condition.
     * Device functionalities will be limited.
     */
    public static final int THERMAL_STATUS_EMERGENCY = Temperature.THROTTLING_EMERGENCY;

    /**
     * Thermal status code: Need shutdown immediately.
     */
    public static final int THERMAL_STATUS_SHUTDOWN = Temperature.THROTTLING_SHUTDOWN;

    /** @hide */
    @IntDef(prefix = { "THERMAL_STATUS_" }, value = {
            THERMAL_STATUS_NONE,
            THERMAL_STATUS_LIGHT,
            THERMAL_STATUS_MODERATE,
            THERMAL_STATUS_SEVERE,
            THERMAL_STATUS_CRITICAL,
            THERMAL_STATUS_EMERGENCY,
            THERMAL_STATUS_SHUTDOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ThermalStatus {}

    /**
     * This function returns the current thermal status of the device.
     *
     * @return thermal status as int, {@link #THERMAL_STATUS_NONE} if device in not under
     * thermal throttling.
     */
    public @ThermalStatus int getCurrentThermalStatus() {
        synchronized (this) {
            if (mThermalService == null) {
                mThermalService = IThermalService.Stub.asInterface(
                        ServiceManager.getService(Context.THERMAL_SERVICE));
            }
            try {
                return mThermalService.getCurrentThermalStatus();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

    }

    /**
     * Listener passed to
     * {@link PowerManager#addThermalStatusListener} and
     * {@link PowerManager#removeThermalStatusListener}
     * to notify caller of thermal status has changed.
     */
    public interface OnThermalStatusChangedListener {

        /**
         * Called when overall thermal throttling status changed.
         * @param status defined in {@link android.os.Temperature}.
         */
        void onThermalStatusChanged(@ThermalStatus int status);
    }


    /**
     * This function adds a listener for thermal status change, listen call back will be
     * enqueued tasks on the main thread
     *
     * @param listener listener to be added,
     */
    public void addThermalStatusListener(@NonNull OnThermalStatusChangedListener listener) {
        Preconditions.checkNotNull(listener, "listener cannot be null");
        synchronized (this) {
            if (mThermalService == null) {
                mThermalService = IThermalService.Stub.asInterface(
                        ServiceManager.getService(Context.THERMAL_SERVICE));
            }
            this.addThermalStatusListener(mContext.getMainExecutor(), listener);
        }
    }

    /**
     * This function adds a listener for thermal status change.
     *
     * @param executor {@link Executor} to handle listener callback.
     * @param listener listener to be added.
     */
    public void addThermalStatusListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull OnThermalStatusChangedListener listener) {
        Preconditions.checkNotNull(listener, "listener cannot be null");
        Preconditions.checkNotNull(executor, "executor cannot be null");
        synchronized (this) {
            if (mThermalService == null) {
                mThermalService = IThermalService.Stub.asInterface(
                        ServiceManager.getService(Context.THERMAL_SERVICE));
            }
            Preconditions.checkArgument(!mListenerMap.containsKey(listener),
                    "Listener already registered: " + listener);
            IThermalStatusListener internalListener = new IThermalStatusListener.Stub() {
                @Override
                public void onStatusChange(int status) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> {
                            listener.onThermalStatusChanged(status);
                        });
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            };
            try {
                if (mThermalService.registerThermalStatusListener(internalListener)) {
                    mListenerMap.put(listener, internalListener);
                } else {
                    throw new RuntimeException("Listener failed to set");
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * This function removes a listener for thermal status change
     *
     * @param listener listener to be removed
     */
    public void removeThermalStatusListener(@NonNull OnThermalStatusChangedListener listener) {
        Preconditions.checkNotNull(listener, "listener cannot be null");
        synchronized (this) {
            if (mThermalService == null) {
                mThermalService = IThermalService.Stub.asInterface(
                        ServiceManager.getService(Context.THERMAL_SERVICE));
            }
            IThermalStatusListener internalListener = mListenerMap.get(listener);
            Preconditions.checkArgument(internalListener != null, "Listener was not added");
            try {
                if (mThermalService.unregisterThermalStatusListener(internalListener)) {
                    mListenerMap.remove(listener);
                } else {
                    throw new RuntimeException("Listener failed to remove");
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * If true, the doze component is not started until after the screen has been
     * turned off and the screen off animation has been performed.
     * @hide
     */
    public void setDozeAfterScreenOff(boolean dozeAfterScreenOf) {
        try {
            mService.setDozeAfterScreenOff(dozeAfterScreenOf);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the reason the phone was last shutdown. Calling app must have the
     * {@link android.Manifest.permission#DEVICE_POWER} permission to request this information.
     * @return Reason for shutdown as an int, {@link #SHUTDOWN_REASON_UNKNOWN} if the file could
     * not be accessed.
     * @hide
     */
    @ShutdownReason
    public int getLastShutdownReason() {
        try {
            return mService.getLastShutdownReason();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the reason the device last went to sleep (i.e. the last value of
     * the second argument of {@link #goToSleep(long, int, int) goToSleep}).
     *
     * @return One of the {@code GO_TO_SLEEP_REASON_*} constants.
     *
     * @hide
     */
    public int getLastSleepReason() {
        try {
            return mService.getLastSleepReason();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Forces the device to go to suspend, even if there are currently wakelocks being held.
     * <b>Caution</b>
     * This is a very dangerous command as it puts the device to sleep immediately. Apps and parts
     * of the system will not be notified and will not have an opportunity to save state prior to
     * the device going to suspend.
     * This method should only be used in very rare circumstances where the device is intended
     * to appear as completely off to the user and they have a well understood, reliable way of
     * re-enabling it.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @return true on success, false otherwise.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public boolean forceSuspend() {
        try {
            return mService.forceSuspend();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Intent that is broadcast when the state of {@link #isPowerSaveMode()} changes.
     * This broadcast is only sent to registered receivers.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_POWER_SAVE_MODE_CHANGED
            = "android.os.action.POWER_SAVE_MODE_CHANGED";

    /**
     * Intent that is broadcast when the state of {@link #isPowerSaveMode()} changes.
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_POWER_SAVE_MODE_CHANGED_INTERNAL
            = "android.os.action.POWER_SAVE_MODE_CHANGED_INTERNAL";

    /**
     * Intent that is broadcast when the state of {@link #isDeviceIdleMode()} changes.
     * This broadcast is only sent to registered receivers.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_IDLE_MODE_CHANGED
            = "android.os.action.DEVICE_IDLE_MODE_CHANGED";

    /**
     * Intent that is broadcast when the state of {@link #isLightDeviceIdleMode()} changes.
     * This broadcast is only sent to registered receivers.
     * @hide
     */
    @UnsupportedAppUsage
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED
            = "android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED";

    /**
     * @hide Intent that is broadcast when the set of power save whitelist apps has changed.
     * This broadcast is only sent to registered receivers.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_POWER_SAVE_WHITELIST_CHANGED
            = "android.os.action.POWER_SAVE_WHITELIST_CHANGED";

    /**
     * @hide Intent that is broadcast when the set of temporarily whitelisted apps has changed.
     * This broadcast is only sent to registered receivers.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_POWER_SAVE_TEMP_WHITELIST_CHANGED
            = "android.os.action.POWER_SAVE_TEMP_WHITELIST_CHANGED";

    /**
     * Intent that is broadcast when the state of {@link #isPowerSaveMode()} is about to change.
     * This broadcast is only sent to registered receivers.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_POWER_SAVE_MODE_CHANGING
            = "android.os.action.POWER_SAVE_MODE_CHANGING";

    /** @hide */
    @UnsupportedAppUsage
    public static final String EXTRA_POWER_SAVE_MODE = "mode";

    /**
     * Intent that is broadcast when the state of {@link #isScreenBrightnessBoosted()} has changed.
     * This broadcast is only sent to registered receivers.
     *
     * @deprecated This intent is rarely used and will be phased out soon.
     * @hide
     * @removed
     **/
    @SystemApi @Deprecated
    public static final String ACTION_SCREEN_BRIGHTNESS_BOOST_CHANGED
            = "android.os.action.SCREEN_BRIGHTNESS_BOOST_CHANGED";

    /**
     * Constant for PreIdleTimeout normal mode (default mode, not short nor extend timeout) .
     * @hide
     */
    public static final int PRE_IDLE_TIMEOUT_MODE_NORMAL = 0;

    /**
     * Constant for PreIdleTimeout long mode (extend timeout to keep in inactive mode
     * longer).
     * @hide
     */
    public static final int PRE_IDLE_TIMEOUT_MODE_LONG = 1;

    /**
     * Constant for PreIdleTimeout short mode (short timeout to go to doze mode quickly)
     * @hide
     */
    public static final int PRE_IDLE_TIMEOUT_MODE_SHORT = 2;

    /**
     * A wake lock is a mechanism to indicate that your application needs
     * to have the device stay on.
     * <p>
     * Any application using a WakeLock must request the {@code android.permission.WAKE_LOCK}
     * permission in an {@code <uses-permission>} element of the application's manifest.
     * Obtain a wake lock by calling {@link PowerManager#newWakeLock(int, String)}.
     * </p><p>
     * Call {@link #acquire()} to acquire the wake lock and force the device to stay
     * on at the level that was requested when the wake lock was created.
     * </p><p>
     * Call {@link #release()} when you are done and don't need the lock anymore.
     * It is very important to do this as soon as possible to avoid running down the
     * device's battery excessively.
     * </p>
     */
    public final class WakeLock {
        @UnsupportedAppUsage
        private int mFlags;
        @UnsupportedAppUsage
        private String mTag;
        private final String mPackageName;
        private final IBinder mToken;
        private int mInternalCount;
        private int mExternalCount;
        private boolean mRefCounted = true;
        private boolean mHeld;
        private WorkSource mWorkSource;
        private String mHistoryTag;
        private final String mTraceName;

        private final Runnable mReleaser = new Runnable() {
            public void run() {
                release(RELEASE_FLAG_TIMEOUT);
            }
        };

        WakeLock(int flags, String tag, String packageName) {
            mFlags = flags;
            mTag = tag;
            mPackageName = packageName;
            mToken = new Binder();
            mTraceName = "WakeLock (" + mTag + ")";
        }

        @Override
        protected void finalize() throws Throwable {
            synchronized (mToken) {
                if (mHeld) {
                    Log.wtf(TAG, "WakeLock finalized while still held: " + mTag);
                    Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, mTraceName, 0);
                    try {
                        mService.releaseWakeLock(mToken, 0);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
        }

        /**
         * Sets whether this WakeLock is reference counted.
         * <p>
         * Wake locks are reference counted by default.  If a wake lock is
         * reference counted, then each call to {@link #acquire()} must be
         * balanced by an equal number of calls to {@link #release()}.  If a wake
         * lock is not reference counted, then one call to {@link #release()} is
         * sufficient to undo the effect of all previous calls to {@link #acquire()}.
         * </p>
         *
         * @param value True to make the wake lock reference counted, false to
         * make the wake lock non-reference counted.
         */
        public void setReferenceCounted(boolean value) {
            synchronized (mToken) {
                mRefCounted = value;
            }
        }

        /**
         * Acquires the wake lock.
         * <p>
         * Ensures that the device is on at the level requested when
         * the wake lock was created.
         * </p>
         */
        public void acquire() {
            synchronized (mToken) {
                acquireLocked();
            }
        }

        /**
         * Acquires the wake lock with a timeout.
         * <p>
         * Ensures that the device is on at the level requested when
         * the wake lock was created.  The lock will be released after the given timeout
         * expires.
         * </p>
         *
         * @param timeout The timeout after which to release the wake lock, in milliseconds.
         */
        public void acquire(long timeout) {
            synchronized (mToken) {
                acquireLocked();
                mHandler.postDelayed(mReleaser, timeout);
            }
        }

        private void acquireLocked() {
            mInternalCount++;
            mExternalCount++;
            if (!mRefCounted || mInternalCount == 1) {
                // Do this even if the wake lock is already thought to be held (mHeld == true)
                // because non-reference counted wake locks are not always properly released.
                // For example, the keyguard's wake lock might be forcibly released by the
                // power manager without the keyguard knowing.  A subsequent call to acquire
                // should immediately acquire the wake lock once again despite never having
                // been explicitly released by the keyguard.
                mHandler.removeCallbacks(mReleaser);
                Trace.asyncTraceBegin(Trace.TRACE_TAG_POWER, mTraceName, 0);
                try {
                    mService.acquireWakeLock(mToken, mFlags, mTag, mPackageName, mWorkSource,
                            mHistoryTag);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                mHeld = true;
            }
        }

        /**
         * Releases the wake lock.
         * <p>
         * This method releases your claim to the CPU or screen being on.
         * The screen may turn off shortly after you release the wake lock, or it may
         * not if there are other wake locks still held.
         * </p>
         */
        public void release() {
            release(0);
        }

        /**
         * Releases the wake lock with flags to modify the release behavior.
         * <p>
         * This method releases your claim to the CPU or screen being on.
         * The screen may turn off shortly after you release the wake lock, or it may
         * not if there are other wake locks still held.
         * </p>
         *
         * @param flags Combination of flag values to modify the release behavior.
         * Currently only {@link #RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY} is supported.
         * Passing 0 is equivalent to calling {@link #release()}.
         */
        public void release(int flags) {
            synchronized (mToken) {
                if (mInternalCount > 0) {
                    // internal count must only be decreased if it is > 0 or state of
                    // the WakeLock object is broken.
                    mInternalCount--;
                }
                if ((flags & RELEASE_FLAG_TIMEOUT) == 0) {
                    mExternalCount--;
                }
                if (!mRefCounted || mInternalCount == 0) {
                    mHandler.removeCallbacks(mReleaser);
                    if (mHeld) {
                        Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, mTraceName, 0);
                        try {
                            mService.releaseWakeLock(mToken, flags);
                        } catch (RemoteException e) {
                            throw e.rethrowFromSystemServer();
                        }
                        mHeld = false;
                    }
                }
                if (mRefCounted && mExternalCount < 0) {
                    throw new RuntimeException("WakeLock under-locked " + mTag);
                }
            }
        }

        /**
         * Returns true if the wake lock has been acquired but not yet released.
         *
         * @return True if the wake lock is held.
         */
        public boolean isHeld() {
            synchronized (mToken) {
                return mHeld;
            }
        }

        /**
         * Sets the work source associated with the wake lock.
         * <p>
         * The work source is used to determine on behalf of which application
         * the wake lock is being held.  This is useful in the case where a
         * service is performing work on behalf of an application so that the
         * cost of that work can be accounted to the application.
         * </p>
         *
         * <p>
         * Make sure to follow the tag naming convention when using WorkSource
         * to make it easier for app developers to understand wake locks
         * attributed to them. See {@link PowerManager#newWakeLock(int, String)}
         * documentation.
         * </p>
         *
         * @param ws The work source, or null if none.
         */
        public void setWorkSource(WorkSource ws) {
            synchronized (mToken) {
                if (ws != null && ws.isEmpty()) {
                    ws = null;
                }

                final boolean changed;
                if (ws == null) {
                    changed = mWorkSource != null;
                    mWorkSource = null;
                } else if (mWorkSource == null) {
                    changed = true;
                    mWorkSource = new WorkSource(ws);
                } else {
                    changed = !mWorkSource.equals(ws);
                    if (changed) {
                        mWorkSource.set(ws);
                    }
                }

                if (changed && mHeld) {
                    try {
                        mService.updateWakeLockWorkSource(mToken, mWorkSource, mHistoryTag);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
        }

        /** @hide */
        public void setTag(String tag) {
            mTag = tag;
        }

        /** @hide */
        public String getTag() {
            return mTag;
        }

        /** @hide */
        public void setHistoryTag(String tag) {
            mHistoryTag = tag;
        }

        /** @hide */
        public void setUnimportantForLogging(boolean state) {
            if (state) mFlags |= UNIMPORTANT_FOR_LOGGING;
            else mFlags &= ~UNIMPORTANT_FOR_LOGGING;
        }

        @Override
        public String toString() {
            synchronized (mToken) {
                return "WakeLock{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " held=" + mHeld + ", refCount=" + mInternalCount + "}";
            }
        }

        /** @hide */
        public void writeToProto(ProtoOutputStream proto, long fieldId) {
            synchronized (mToken) {
                final long token = proto.start(fieldId);
                proto.write(PowerManagerProto.WakeLock.TAG, mTag);
                proto.write(PowerManagerProto.WakeLock.PACKAGE_NAME, mPackageName);
                proto.write(PowerManagerProto.WakeLock.HELD, mHeld);
                proto.write(PowerManagerProto.WakeLock.INTERNAL_COUNT, mInternalCount);
                if (mWorkSource != null) {
                    mWorkSource.writeToProto(proto, PowerManagerProto.WakeLock.WORK_SOURCE);
                }
                proto.end(token);
            }
        }

        /**
         * Wraps a Runnable such that this method immediately acquires the wake lock and then
         * once the Runnable is done the wake lock is released.
         *
         * <p>Example:
         *
         * <pre>
         * mHandler.post(mWakeLock.wrap(() -> {
         *     // do things on handler, lock is held while we're waiting for this
         *     // to get scheduled and until the runnable is done executing.
         * });
         * </pre>
         *
         * <p>Note: you must make sure that the Runnable eventually gets executed, otherwise you'll
         *    leak the wakelock!
         *
         * @hide
         */
        public Runnable wrap(Runnable r) {
            acquire();
            return () -> {
                try {
                    r.run();
                } finally {
                    release();
                }
            };
        }
    }
}
