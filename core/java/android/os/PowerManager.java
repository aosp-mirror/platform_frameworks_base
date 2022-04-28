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
import android.annotation.CurrentTimeMillisLong;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.PropertyInvalidatedCache;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.service.dreams.Sandman;
import android.sysprop.InitProperties;
import android.util.ArrayMap;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.Display;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class lets you query and request control of aspects of the device's power state.
 */
@SystemService(Context.POWER_SERVICE)
public final class PowerManager {
    private static final String TAG = "PowerManager";

    /* NOTE: Wake lock levels were previously defined as a bit field, except that only a few
     * combinations were actually supported so the bit field was removed.  This explains
     * why the numbering scheme is so odd.  If adding a new wake lock level, any unused
     * value (in frameworks/proto_logging/stats/enums/os/enums.proto) can be used.
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
     * Normally wake locks don't actually wake the device, they just cause the screen to remain on
     * once it's already on. This flag will cause the device to wake up when the wake lock is
     * acquired.
     * </p><p>
     * Android TV playback devices attempt to turn on the HDMI-connected TV via HDMI-CEC on any
     * wake-up, including wake-ups triggered by wake locks.
     * </p><p>
     * Cannot be used with {@link #PARTIAL_WAKE_LOCK}.
     * </p>
     *
     * @deprecated Most applications should use {@link android.R.attr#turnScreenOn} or
     * {@link android.app.Activity#setTurnScreenOn(boolean)} instead, as this prevents the previous
     * foreground app from being resumed first when the screen turns on. Note that this flag may
     * require a permission in the future.
     */
    @Deprecated
    public static final int ACQUIRE_CAUSES_WAKEUP = 0x10000000;

    /**
     * Wake lock flag: When this wake lock is released, poke the user activity timer
     * so the screen stays on for a little longer.
     * <p>
     * This will not turn the screen on if it is not already on.
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
     * Wake lock flag: This wake lock should be held by the system.
     *
     * <p>Meant to allow tests to keep the device awake even when power restrictions are active.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    public static final int SYSTEM_WAKELOCK = 0x80000000;

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

    /**
     * Brightness value for an invalid value having been stored.
     * @hide
     */
    public static final int BRIGHTNESS_INVALID = -1;

    //Brightness values for new float implementation:
    /**
     * Brightness value for fully on as float.
     * @hide
     */
    public static final float BRIGHTNESS_MAX = 1.0f;

    /**
     * Brightness value for minimum valid brightness as float.
     * @hide
     */
    public static final float BRIGHTNESS_MIN = 0.0f;

    /**
     * Brightness value for fully off in float.
     * @hide
     */
    public static final float BRIGHTNESS_OFF_FLOAT = -1.0f;

    /**
     * Invalid brightness value.
     * @hide
     */
    public static final float BRIGHTNESS_INVALID_FLOAT = Float.NaN;

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
     * User activity event type: {@link com.android.server.power.FaceDownDetector} taking action
     * on behalf of user.
     * @hide
     */
    public static final int USER_ACTIVITY_EVENT_FACE_DOWN = 5;

    /**
     * User activity event type: There is a change in the device state.
     * @hide
     */
    public static final int USER_ACTIVITY_EVENT_DEVICE_STATE = 6;

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
     * Go to sleep reason code: Going to sleep due to user inattentiveness.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_INATTENTIVE = 9;

    /**
     * Go to sleep reason code: Going to sleep due to quiescent boot.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_QUIESCENT = 10;

    /**
     * Go to sleep reason code: The last powered on display group has been removed.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_DISPLAY_GROUP_REMOVED = 11;

    /**
     * Go to sleep reason code: Every display group has been turned off.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_DISPLAY_GROUPS_TURNED_OFF = 12;

    /**
     * Go to sleep reason code: A foldable device has been folded.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_DEVICE_FOLD = 13;

    /**
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_MAX =  GO_TO_SLEEP_REASON_DEVICE_FOLD;

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
            case GO_TO_SLEEP_REASON_INATTENTIVE: return "inattentive";
            case GO_TO_SLEEP_REASON_DISPLAY_GROUP_REMOVED: return "display_group_removed";
            case GO_TO_SLEEP_REASON_DISPLAY_GROUPS_TURNED_OFF: return "display_groups_turned_off";
            case GO_TO_SLEEP_REASON_DEVICE_FOLD: return "device_folded";
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
    @IntDef(prefix = { "BRIGHTNESS_CONSTRAINT_TYPE" }, value = {
            BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM,
            BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM,
            BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT,
            BRIGHTNESS_CONSTRAINT_TYPE_DIM,
            BRIGHTNESS_CONSTRAINT_TYPE_DOZE,
            BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM_VR,
            BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM_VR,
            BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT_VR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BrightnessConstraint{}

    /**
     * Brightness constraint type: minimum allowed value.
     * @hide
     */
    public static final int BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM = 0;
    /**
     * Brightness constraint type: minimum allowed value.
     * @hide
     */
    public static final int BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM = 1;

    /**
     * Brightness constraint type: minimum allowed value.
     * @hide
     */
    public static final int BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT = 2;

    /**
     * Brightness constraint type: minimum allowed value.
     * @hide
     */
    public static final int BRIGHTNESS_CONSTRAINT_TYPE_DIM = 3;

    /**
     * Brightness constraint type: minimum allowed value.
     * @hide
     */
    public static final int BRIGHTNESS_CONSTRAINT_TYPE_DOZE = 4;

    /**
     * Brightness constraint type: minimum allowed value.
     * @hide
     */
    public static final int BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM_VR = 5;

    /**
     * Brightness constraint type: minimum allowed value.
     * @hide
     */
    public static final int BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM_VR = 6;

    /**
     * Brightness constraint type: minimum allowed value.
     * @hide
     */
    public static final int BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT_VR = 7;

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
            WAKE_REASON_DISPLAY_GROUP_ADDED,
            WAKE_REASON_DISPLAY_GROUP_TURNED_ON,
            WAKE_REASON_UNFOLD_DEVICE,
            WAKE_REASON_DREAM_FINISHED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WakeReason{}

    /**
     * @hide
     */
    @IntDef(prefix = { "GO_TO_SLEEP_REASON_" }, value = {
            GO_TO_SLEEP_REASON_APPLICATION,
            GO_TO_SLEEP_REASON_DEVICE_ADMIN,
            GO_TO_SLEEP_REASON_TIMEOUT,
            GO_TO_SLEEP_REASON_LID_SWITCH,
            GO_TO_SLEEP_REASON_POWER_BUTTON,
            GO_TO_SLEEP_REASON_HDMI,
            GO_TO_SLEEP_REASON_SLEEP_BUTTON,
            GO_TO_SLEEP_REASON_ACCESSIBILITY,
            GO_TO_SLEEP_REASON_FORCE_SUSPEND,
            GO_TO_SLEEP_REASON_INATTENTIVE,
            GO_TO_SLEEP_REASON_QUIESCENT,
            GO_TO_SLEEP_REASON_DEVICE_FOLD
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GoToSleepReason{}

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
     * Wake up reason code: Waking up due to a user performed gesture (e.g. double tapping on the
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
     * Wake up reason code: Waking due to display group being added.
     * @hide
     */
    public static final int WAKE_REASON_DISPLAY_GROUP_ADDED = 10;

    /**
     * Wake up reason code: Waking due to display group being powered on.
     * @hide
     */
    public static final int WAKE_REASON_DISPLAY_GROUP_TURNED_ON = 11;

    /**
     * Wake up reason code: Waking the device due to unfolding of a foldable device.
     * @hide
     */
    public static final int WAKE_REASON_UNFOLD_DEVICE = 12;

    /**
     * Wake up reason code: Waking the device due to the dream finishing.
     * @hide
     */
    public static final int WAKE_REASON_DREAM_FINISHED = 13;

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
            case WAKE_REASON_DISPLAY_GROUP_ADDED: return "WAKE_REASON_DISPLAY_GROUP_ADDED";
            case WAKE_REASON_DISPLAY_GROUP_TURNED_ON: return "WAKE_REASON_DISPLAY_GROUP_TURNED_ON";
            case WAKE_REASON_UNFOLD_DEVICE: return "WAKE_REASON_UNFOLD_DEVICE";
            case WAKE_REASON_DREAM_FINISHED: return "WAKE_REASON_DREAM_FINISHED";
            default: return Integer.toString(wakeReason);
        }
    }

    /**
     * @hide
     */
    public static class WakeData {
        public WakeData(long wakeTime, @WakeReason int wakeReason, long sleepDuration) {
            this.wakeTime = wakeTime;
            this.wakeReason = wakeReason;
            this.sleepDuration = sleepDuration;
        }
        public long wakeTime;
        public @WakeReason int wakeReason;
        public long sleepDuration;

        @Override
        public boolean equals(@Nullable Object o) {
            if (o instanceof WakeData) {
                final WakeData other = (WakeData) o;
                return wakeTime == other.wakeTime && wakeReason == other.wakeReason
                        && sleepDuration == other.sleepDuration;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(wakeTime, wakeReason, sleepDuration);
        }
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
     * The 'reason' value used for rebooting userspace.
     * @hide
     */
    @SystemApi
    public static final String REBOOT_USERSPACE = "userspace";

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

    /**
     * In this mode, all active SoundTrigger recognitions are enabled by the SoundTrigger system
     * service.
     * @hide
     */
    @SystemApi
    public static final int SOUND_TRIGGER_MODE_ALL_ENABLED = 0;
    /**
     * In this mode, only privileged components of the SoundTrigger system service should be
     * enabled. This functionality is to be used to limit SoundTrigger recognitions to those only
     * deemed necessary by the system.
     * @hide
     */
    @SystemApi
    public static final int SOUND_TRIGGER_MODE_CRITICAL_ONLY = 1;
    /**
     * In this mode, all active SoundTrigger recognitions should be disabled by the SoundTrigger
     * system service.
     * @hide
     */
    @SystemApi
    public static final int SOUND_TRIGGER_MODE_ALL_DISABLED = 2;

    /** @hide */
    public static final int MIN_SOUND_TRIGGER_MODE = SOUND_TRIGGER_MODE_ALL_ENABLED;
    /** @hide */
    public static final int MAX_SOUND_TRIGGER_MODE = SOUND_TRIGGER_MODE_ALL_DISABLED;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SOUND_TRIGGER_MODE_"}, value = {
            SOUND_TRIGGER_MODE_ALL_ENABLED,
            SOUND_TRIGGER_MODE_CRITICAL_ONLY,
            SOUND_TRIGGER_MODE_ALL_DISABLED,
    })
    public @interface SoundTriggerPowerSaveMode {}

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

    private static final String CACHE_KEY_IS_POWER_SAVE_MODE_PROPERTY =
            "cache_key.is_power_save_mode";

    private static final String CACHE_KEY_IS_INTERACTIVE_PROPERTY = "cache_key.is_interactive";

    private static final int MAX_CACHE_ENTRIES = 1;

    private final PropertyInvalidatedCache<Void, Boolean> mPowerSaveModeCache =
            new PropertyInvalidatedCache<Void, Boolean>(MAX_CACHE_ENTRIES,
                CACHE_KEY_IS_POWER_SAVE_MODE_PROPERTY) {
                @Override
                public Boolean recompute(Void query) {
                    try {
                        return mService.isPowerSaveMode();
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            };

    private final PropertyInvalidatedCache<Void, Boolean> mInteractiveCache =
            new PropertyInvalidatedCache<Void, Boolean>(MAX_CACHE_ENTRIES,
                CACHE_KEY_IS_INTERACTIVE_PROPERTY) {
                @Override
                public Boolean recompute(Void query) {
                    try {
                        return mService.isInteractive();
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            };

    final Context mContext;
    @UnsupportedAppUsage
    final IPowerManager mService;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    final Handler mHandler;
    final IThermalService mThermalService;

    /** We lazily initialize it.*/
    private PowerExemptionManager mPowerExemptionManager;

    private final ArrayMap<OnThermalStatusChangedListener, IThermalStatusListener>
            mListenerMap = new ArrayMap<>();

    /**
     * {@hide}
     */
    public PowerManager(Context context, IPowerManager service, IThermalService thermalService,
            Handler handler) {
        mContext = context;
        mService = service;
        mThermalService = thermalService;
        mHandler = handler;
    }

    private PowerExemptionManager getPowerExemptionManager() {
        if (mPowerExemptionManager == null) {
            // No need for synchronization; getSystemService() will return the same object anyway.
            mPowerExemptionManager = mContext.getSystemService(PowerExemptionManager.class);
        }
        return mPowerExemptionManager;
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
     * Gets a float screen brightness setting.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public float getBrightnessConstraint(int constraint) {
        try {
            return mService.getBrightnessConstraint(constraint);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * </p>
     * <p>
     * The wake lock flags are: {@link #ACQUIRE_CAUSES_WAKEUP}
     * and {@link #ON_AFTER_RELEASE}.  Multiple flags can be combined as part of the
     * {@code levelAndFlags} parameters.
     * </p><p>
     * Call {@link WakeLock#acquire() acquire()} on the object to acquire the
     * wake lock, and {@link WakeLock#release release()} when you are done.
     * </p><p>
     * {@samplecode
     * PowerManager pm = mContext.getSystemService(PowerManager.class);
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
     *
     * </p><p>
     * <b>Device battery life will be significantly affected by the use of this API.</b>
     * Do not acquire {@link WakeLock}s unless you really need them, use the minimum levels
     * possible, and be sure to release them as soon as possible.
     * </p><p class="note">
     * If using this to keep the screen on, you should strongly consider using
     * {@link android.view.WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON} instead.
     * This window flag will be correctly managed by the platform
     * as the user moves between applications and doesn't require a special permission.
     * Additionally using the flag will keep only the appropriate screen on in a
     * multi-display scenario while using a wake lock will keep every screen powered on.
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
     * <li>never include personally identifiable information for privacy
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
        return new WakeLock(levelAndFlags, tag, mContext.getOpPackageName(),
                Display.INVALID_DISPLAY);
    }

    /**
     * Creates a new wake lock with the specified level and flags.
     * <p>
     * The wakelock will only apply to the {@link com.android.server.display.DisplayGroup} of the
     * provided {@code displayId}. If {@code displayId} is {@link Display#INVALID_DISPLAY} then it
     * will apply to all {@link com.android.server.display.DisplayGroup DisplayGroups}.
     *
     * @param levelAndFlags Combination of wake lock level and flag values defining
     * the requested behavior of the WakeLock.
     * @param tag Your class name (or other tag) for debugging purposes.
     * @param displayId The display id to which this wake lock is tied.
     *
     * @hide
     */
    public WakeLock newWakeLock(int levelAndFlags, String tag, int displayId) {
        validateWakeLockParameters(levelAndFlags, tag);
        return new WakeLock(levelAndFlags, tag, mContext.getOpPackageName(), displayId);
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
            mService.userActivity(mContext.getDisplayId(), when, event, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Forces the {@link android.view.Display#DEFAULT_DISPLAY_GROUP default display group}
     * to turn off.
     *
     * <p>If the {@link android.view.Display#DEFAULT_DISPLAY_GROUP default display group} is
     * turned on it will be turned off. If all displays are off as a result of this action the
     * device will be put to sleep. If the {@link android.view.Display#DEFAULT_DISPLAY_GROUP
     * default display group} is already off then nothing will happen.
     *
     * <p>If the device is an Android TV playback device and the current active source on the
     * HDMI-connected TV, it will attempt to turn off that TV via HDMI-CEC.
     *
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
     * Forces the {@link android.view.Display#DEFAULT_DISPLAY_GROUP default display group}
     * to turn off.
     *
     * <p>If the {@link android.view.Display#DEFAULT_DISPLAY_GROUP default display group} is
     * turned on it will be turned off. If all displays are off as a result of this action the
     * device will be put to sleep. If the {@link android.view.Display#DEFAULT_DISPLAY_GROUP
     * default display group} is already off then nothing will happen.
     *
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
     * Forces the {@link android.view.Display#DEFAULT_DISPLAY_GROUP default display group}
     * to turn on.
     *
     * <p>If the {@link android.view.Display#DEFAULT_DISPLAY_GROUP default display group} is
     * turned off it will be turned on. Additionally, if the device is asleep it will be awoken. If
     * the {@link android.view.Display#DEFAULT_DISPLAY_GROUP default display group} is already
     * on then nothing will happen.
     *
     * <p>
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
     * Forces the {@link android.view.Display#DEFAULT_DISPLAY_GROUP default display group}
     * to turn on.
     *
     * <p>If the {@link android.view.Display#DEFAULT_DISPLAY_GROUP default display group} is
     * turned off it will be turned on. Additionally, if the device is asleep it will be awoken. If
     * the {@link android.view.Display#DEFAULT_DISPLAY_GROUP default display group} is already
     * on then nothing will happen.
     *
     * <p>
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
     * Forces the {@link android.view.Display#DEFAULT_DISPLAY default display} to turn on.
     *
     * <p>If the {@link android.view.Display#DEFAULT_DISPLAY default display} is turned off it will
     * be turned on. Additionally, if the device is asleep it will be awoken. If the {@link
     * android.view.Display#DEFAULT_DISPLAY default display} is already on then nothing will happen.
     *
     * <p>If the device is an Android TV playback device, it will attempt to turn on the
     * HDMI-connected TV and become the current active source via the HDMI-CEC One Touch Play
     * feature.
     *
     * <p>
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
        return mInteractiveCache.query(null);
    }


    /**
     * Returns {@code true} if this device supports rebooting userspace.
     *
     * <p>This method exists solely for the sake of re-using same logic between {@code PowerManager}
     * and {@code PowerManagerService}.
     *
     * @hide
     */
    public static boolean isRebootingUserspaceSupportedImpl() {
        return InitProperties.is_userspace_reboot_supported().orElse(false);
    }

    /**
     * Returns {@code true} if this device supports rebooting userspace.
     */
    // TODO(b/138605180): add link to documentation once it's ready.
    public boolean isRebootingUserspaceSupported() {
        return isRebootingUserspaceSupportedImpl();
    }

    /**
     * Reboot the device.  Will not return if the reboot is successful.
     * <p>
     * Requires the {@link android.Manifest.permission#REBOOT} permission.
     * </p>
     * <p>
     * If the {@code reason} string contains ",quiescent", then the screen stays off during reboot
     * and is not turned on again until the user triggers the device to wake up (for example,
     * by pressing the power key).
     * This behavior applies to Android TV devices launched on Android 11 (API level 30) or higher.
     * </p>
     *
     * @param reason code to pass to the kernel (e.g., "recovery") to
     *               request special boot modes, or null.
     * @throws UnsupportedOperationException if userspace reboot was requested on a device that
     *                                       doesn't support it.
     */
    @RequiresPermission(permission.REBOOT)
    public void reboot(@Nullable String reason) {
        if (REBOOT_USERSPACE.equals(reason) && !isRebootingUserspaceSupported()) {
            throw new UnsupportedOperationException(
                    "Attempted userspace reboot on a device that doesn't support it");
        }
        try {
            mService.reboot(false, reason, true);
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
    @RequiresPermission(permission.REBOOT)
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
        return mPowerSaveModeCache.query(null);
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
     * Gets the current policy for full power save mode.
     *
     * @return The {@link BatterySaverPolicyConfig} which is currently set for the full power save
     *          policy level.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public BatterySaverPolicyConfig getFullPowerSavePolicy() {
        try {
            return mService.getFullPowerSavePolicy();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the policy for full power save mode.
     *
     * Any settings set by this API will persist for only one session of full battery saver mode.
     * The settings set by this API are cleared upon exit of full battery saver mode, and the
     * caller is expected to set the desired values again for the next full battery saver mode
     * session if desired.
     *
     * Use-cases:
     * 1. Set policy outside of full battery saver mode
     *     - full policy set -> enter BS -> policy setting applied -> exit BS -> setting cleared
     * 2. Set policy inside of full battery saver mode
     *     - enter BS -> full policy set -> policy setting applied -> exit BS -> setting cleared
     *
     * This API is intended to be used with {@link #getFullPowerSavePolicy()} API when a client only
     * wants to modify a specific setting(s) and leave the remaining policy attributes the same.
     * Example:
     * BatterySaverPolicyConfig newFullPolicyConfig =
     *     new BatterySaverPolicyConfig.Builder(powerManager.getFullPowerSavePolicy())
     *         .setSoundTriggerMode(PowerManager.SOUND_TRIGGER_MODE_ALL_DISABLED)
     *         .build();
     * powerManager.setFullPowerSavePolicy(newFullPolicyConfig);
     *
     * @return true if there was an effectual change. If full battery saver is enabled, then this
     * will return true.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.DEVICE_POWER,
            android.Manifest.permission.POWER_SAVER
    })
    public boolean setFullPowerSavePolicy(@NonNull BatterySaverPolicyConfig config) {
        try {
            return mService.setFullPowerSavePolicy(config);
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
     * <p>Note: Prior to Android version {@link Build.VERSION_CODES#S}, any app calling this method
     * was required to hold the {@link android.Manifest.permission#POWER_SAVER} permission. Starting
     * from Android version {@link Build.VERSION_CODES#S}, that permission is no longer required.
     *
     * @return The current value power saver mode for the system.
     *
     * @see AutoPowerSaveModeTriggers
     * @see PowerManager#getPowerSaveModeTrigger()
     * @hide
     */
    @AutoPowerSaveModeTriggers
    @SystemApi
    public int getPowerSaveModeTrigger() {
        try {
            return mService.getPowerSaveModeTrigger();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allows an app to tell the system how long it believes the battery will last and whether
     * this estimate is customized based on historical device usage or on a generic configuration.
     * These estimates will be displayed on system UI surfaces in place of the system computed
     * value.
     *
     * Calling this requires either the {@link android.Manifest.permission#DEVICE_POWER} or the
     * {@link android.Manifest.permission#BATTERY_PREDICTION} permissions.
     *
     * @param timeRemaining  The time remaining as a {@link Duration}.
     * @param isPersonalized true if personalized based on device usage history, false otherwise.
     * @throws IllegalStateException if the device is powered or currently charging
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.BATTERY_PREDICTION,
            android.Manifest.permission.DEVICE_POWER
    })
    public void setBatteryDischargePrediction(@NonNull Duration timeRemaining,
            boolean isPersonalized) {
        if (timeRemaining == null) {
            throw new IllegalArgumentException("time remaining must not be null");
        }
        try {
            mService.setBatteryDischargePrediction(new ParcelDuration(timeRemaining),
                    isPersonalized);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current battery life remaining estimate.
     *
     * @return The estimated battery life remaining as a {@link Duration}. Will be {@code null} if
     * the device is powered, charging, or an error was encountered.
     */
    @Nullable
    public Duration getBatteryDischargePrediction() {
        try {
            final ParcelDuration parcelDuration = mService.getBatteryDischargePrediction();
            if (parcelDuration == null) {
                return null;
            }
            return parcelDuration.getDuration();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the current battery life remaining estimate is personalized based on device
     * usage history or not. This value does not take a device's powered or charging state into
     * account.
     *
     * @return A boolean indicating if the current discharge estimate is personalized based on
     * historical device usage or not.
     */
    public boolean isBatteryDischargePredictionPersonalized() {
        try {
            return mService.isBatteryDischargePredictionPersonalized();
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
     * Returns how SoundTrigger features should behave when battery saver is on. When battery saver
     * is off, this will always return {@link #SOUND_TRIGGER_MODE_ALL_ENABLED}.
     *
     * <p>This API is normally only useful for components that provide use SoundTrigger features.
     *
     * @see #isPowerSaveMode()
     * @see #ACTION_POWER_SAVE_MODE_CHANGED
     *
     * @hide
     */
    @SoundTriggerPowerSaveMode
    public int getSoundTriggerPowerSaveMode() {
        final PowerSaveState powerSaveState = getPowerSaveState(ServiceType.SOUND);
        if (!powerSaveState.batterySaverEnabled) {
            return SOUND_TRIGGER_MODE_ALL_ENABLED;
        }
        return powerSaveState.soundTriggerMode;
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
     * this state with {@link #ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED}.
     *
     * @return Returns true if currently in active device light idle mode, else false.  This is
     * when light idle mode restrictions are being actively applied; it will return false if the
     * device is in a long-term idle mode but currently running a maintenance window where
     * restrictions have been lifted.
     */
    public boolean isDeviceLightIdleMode() {
        try {
            return mService.isLightDeviceIdleMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see #isDeviceLightIdleMode()
     * @deprecated
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.S,
            publicAlternatives = "Use {@link #isDeviceLightIdleMode()} instead.")
    public boolean isLightDeviceIdleMode() {
        return isDeviceLightIdleMode();
    }

    /**
     * Returns true if Low Power Standby is supported on this device.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_LOW_POWER_STANDBY,
            android.Manifest.permission.DEVICE_POWER
    })
    public boolean isLowPowerStandbySupported() {
        try {
            return mService.isLowPowerStandbySupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if Low Power Standby is enabled.
     *
     * <p>When Low Power Standby is enabled, apps (including apps running foreground services) are
     * subject to additional restrictions while the device is non-interactive, outside of device
     * idle maintenance windows: Their network access is disabled, and any wakelocks they hold are
     * ignored.
     *
     * <p>When Low Power Standby is enabled or disabled, a Intent with action
     * {@link #ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED} is broadcast to registered receivers.
     */
    public boolean isLowPowerStandbyEnabled() {
        try {
            return mService.isLowPowerStandbyEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether Low Power Standby is enabled.
     * Does nothing if Low Power Standby is not supported.
     *
     * @see #isLowPowerStandbySupported()
     * @see #isLowPowerStandbyEnabled()
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_LOW_POWER_STANDBY,
            android.Manifest.permission.DEVICE_POWER
    })
    public void setLowPowerStandbyEnabled(boolean enabled) {
        try {
            mService.setLowPowerStandbyEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether Low Power Standby should be active during doze maintenance mode.
     * Does nothing if Low Power Standby is not supported.
     *
     * @see #isLowPowerStandbySupported()
     * @see #isLowPowerStandbyEnabled()
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_LOW_POWER_STANDBY,
            android.Manifest.permission.DEVICE_POWER
    })
    public void setLowPowerStandbyActiveDuringMaintenance(boolean activeDuringMaintenance) {
        try {
            mService.setLowPowerStandbyActiveDuringMaintenance(activeDuringMaintenance);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Force Low Power Standby restrictions to be active.
     * Does nothing if Low Power Standby is not supported.
     *
     * @see #isLowPowerStandbySupported()
     * @hide
     */
    @TestApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_LOW_POWER_STANDBY,
            android.Manifest.permission.DEVICE_POWER
    })
    public void forceLowPowerStandbyActive(boolean active) {
        try {
            mService.forceLowPowerStandbyActive(active);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether the given application package name is on the device's power allowlist.
     * Apps can be placed on the allowlist through the settings UI invoked by
     * {@link android.provider.Settings#ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS}.
     * <p>Being on the power allowlist means that the system will not apply most power saving
     * features to the app. Guardrails for extreme cases may still be applied.
     */
    public boolean isIgnoringBatteryOptimizations(String packageName) {
        return getPowerExemptionManager().isAllowListed(packageName, true);
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
        try {
            return mThermalService.getCurrentThermalStatus();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
        Objects.requireNonNull(listener, "listener cannot be null");
        addThermalStatusListener(mContext.getMainExecutor(), listener);
    }

    /**
     * This function adds a listener for thermal status change.
     *
     * @param executor {@link Executor} to handle listener callback.
     * @param listener listener to be added.
     */
    public void addThermalStatusListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull OnThermalStatusChangedListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        Objects.requireNonNull(executor, "executor cannot be null");
        Preconditions.checkArgument(!mListenerMap.containsKey(listener),
                "Listener already registered: %s", listener);
        IThermalStatusListener internalListener = new IThermalStatusListener.Stub() {
            @Override
            public void onStatusChange(int status) {
                final long token = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> listener.onThermalStatusChanged(status));
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

    /**
     * This function removes a listener for thermal status change
     *
     * @param listener listener to be removed
     */
    public void removeThermalStatusListener(@NonNull OnThermalStatusChangedListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
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

    @CurrentTimeMillisLong
    private final AtomicLong mLastHeadroomUpdate = new AtomicLong(0L);
    private static final int MINIMUM_HEADROOM_TIME_MILLIS = 500;

    /**
     * Provides an estimate of how much thermal headroom the device currently has before hitting
     * severe throttling.
     *
     * Note that this only attempts to track the headroom of slow-moving sensors, such as the skin
     * temperature sensor. This means that there is no benefit to calling this function more
     * frequently than about once per second, and attempts to call significantly more frequently may
     * result in the function returning {@code NaN}.
     * <p>
     * In addition, in order to be able to provide an accurate forecast, the system does not attempt
     * to forecast until it has multiple temperature samples from which to extrapolate. This should
     * only take a few seconds from the time of the first call, but during this time, no forecasting
     * will occur, and the current headroom will be returned regardless of the value of
     * {@code forecastSeconds}.
     * <p>
     * The value returned is a non-negative float that represents how much of the thermal envelope
     * is in use (or is forecasted to be in use). A value of 1.0 indicates that the device is (or
     * will be) throttled at {@link #THERMAL_STATUS_SEVERE}. Such throttling can affect the CPU,
     * GPU, and other subsystems. Values may exceed 1.0, but there is no implied mapping to specific
     * thermal status levels beyond that point. This means that values greater than 1.0 may
     * correspond to {@link #THERMAL_STATUS_SEVERE}, but may also represent heavier throttling.
     * <p>
     * A value of 0.0 corresponds to a fixed distance from 1.0, but does not correspond to any
     * particular thermal status or temperature. Values on (0.0, 1.0] may be expected to scale
     * linearly with temperature, though temperature changes over time are typically not linear.
     * Negative values will be clamped to 0.0 before returning.
     *
     * @param forecastSeconds how many seconds in the future to forecast. Given that device
     *                        conditions may change at any time, forecasts from further in the
     *                        future will likely be less accurate than forecasts in the near future.
     * @return a value greater than or equal to 0.0 where 1.0 indicates the SEVERE throttling
     *         threshold, as described above. Returns NaN if the device does not support this
     *         functionality or if this function is called significantly faster than once per
     *         second.
     */
    public float getThermalHeadroom(@IntRange(from = 0, to = 60) int forecastSeconds) {
        // Rate-limit calls into the thermal service
        long now = SystemClock.elapsedRealtime();
        long timeSinceLastUpdate = now - mLastHeadroomUpdate.get();
        if (timeSinceLastUpdate < MINIMUM_HEADROOM_TIME_MILLIS) {
            return Float.NaN;
        }

        try {
            float forecast = mThermalService.getThermalHeadroom(forecastSeconds);
            mLastHeadroomUpdate.set(SystemClock.elapsedRealtime());
            return forecast;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
     * Returns true if ambient display is available on the device.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_DREAM_STATE)
    public boolean isAmbientDisplayAvailable() {
        try {
            return mService.isAmbientDisplayAvailable();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * If true, suppresses the current ambient display configuration and disables ambient display.
     *
     * <p>This method has no effect if {@link #isAmbientDisplayAvailable()} is false.
     *
     * @param token A persistable identifier for the ambient display suppression that is unique
     *              within the calling application.
     * @param suppress If set to {@code true}, ambient display will be suppressed. If set to
     *                 {@code false}, ambient display will no longer be suppressed for the given
     *                 token.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    public void suppressAmbientDisplay(@NonNull String token, boolean suppress) {
        try {
            mService.suppressAmbientDisplay(token, suppress);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if ambient display is suppressed by the calling app with the given
     * {@code token}.
     *
     * <p>This method will return false if {@link #isAmbientDisplayAvailable()} is false.
     *
     * @param token The identifier of the ambient display suppression.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_DREAM_STATE)
    public boolean isAmbientDisplaySuppressedForToken(@NonNull String token) {
        try {
            return mService.isAmbientDisplaySuppressedForToken(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if ambient display is suppressed by <em>any</em> app with <em>any</em> token.
     *
     * <p>This method will return false if {@link #isAmbientDisplayAvailable()} is false.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_DREAM_STATE)
    public boolean isAmbientDisplaySuppressed() {
        try {
            return mService.isAmbientDisplaySuppressed();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if ambient display is suppressed by the given {@code appUid} with the given
     * {@code token}.
     *
     * <p>This method will return false if {@link #isAmbientDisplayAvailable()} is false.
     *
     * @param token The identifier of the ambient display suppression.
     * @param appUid The uid of the app that suppressed ambient display.
     * @hide
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.READ_DREAM_STATE,
            android.Manifest.permission.READ_DREAM_SUPPRESSION })
    public boolean isAmbientDisplaySuppressedForTokenByApp(@NonNull String token, int appUid) {
        try {
            return mService.isAmbientDisplaySuppressedForTokenByApp(token, appUid);
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
     * Intent that is broadcast when the enhanced battery discharge prediction changes. The new
     * value can be retrieved via {@link #getBatteryDischargePrediction()}.
     * This broadcast is only sent to registered receivers.
     *
     * @hide
     */
    @TestApi
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ENHANCED_DISCHARGE_PREDICTION_CHANGED =
            "android.os.action.ENHANCED_DISCHARGE_PREDICTION_CHANGED";

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
     * Intent that is broadcast when the state of {@link #isDeviceLightIdleMode()} changes.
     * This broadcast is only sent to registered receivers.
     */
    @SuppressLint("ActionValue") // Need to do "LIGHT_DEVICE_IDLE..." for legacy reasons
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED =
            // Use the old string so we don't break legacy apps.
            "android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED";

    /**
     * @see #ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED
     * @deprecated
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553,
            publicAlternatives = "Use {@link #ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED} instead")
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED =
            ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED;

    /**
     * @hide Intent that is broadcast when the set of power save allowlist apps has changed.
     * This broadcast is only sent to registered receivers.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_POWER_SAVE_WHITELIST_CHANGED
            = "android.os.action.POWER_SAVE_WHITELIST_CHANGED";

    /**
     * @hide Intent that is broadcast when the set of temporarily allowlisted apps has changed.
     * This broadcast is only sent to registered receivers.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_POWER_SAVE_TEMP_WHITELIST_CHANGED
            = "android.os.action.POWER_SAVE_TEMP_WHITELIST_CHANGED";

    /**
     * Intent that is broadcast when Low Power Standby is enabled or disabled.
     * This broadcast is only sent to registered receivers.
     *
     * @see #isLowPowerStandbyEnabled()
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED =
            "android.os.action.LOW_POWER_STANDBY_ENABLED_CHANGED";

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
     * A listener interface to get notified when the wakelock is enabled/disabled.
     */
    public interface WakeLockStateListener {
        /**
         * Frameworks could disable the wakelock because either device's power allowlist has
         * changed, or the app's wakelock has exceeded its quota, or the app goes into cached
         * state.
         * <p>
         * This callback is called whenever the wakelock's state has changed.
         * </p>
         *
         * @param enabled true is enabled, false is disabled.
         */
        void onStateChanged(boolean enabled);
    }

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
        private final int mDisplayId;
        private WakeLockStateListener mListener;
        private IWakeLockCallback mCallback;

        private final Runnable mReleaser = () -> release(RELEASE_FLAG_TIMEOUT);

        WakeLock(int flags, String tag, String packageName, int displayId) {
            mFlags = flags;
            mTag = tag;
            mPackageName = packageName;
            mToken = new Binder();
            mTraceName = "WakeLock (" + mTag + ")";
            mDisplayId = displayId;
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
                            mHistoryTag, mDisplayId, mCallback);
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
        public void dumpDebug(ProtoOutputStream proto, long fieldId) {
            synchronized (mToken) {
                final long token = proto.start(fieldId);
                proto.write(PowerManagerProto.WakeLock.TAG, mTag);
                proto.write(PowerManagerProto.WakeLock.PACKAGE_NAME, mPackageName);
                proto.write(PowerManagerProto.WakeLock.HELD, mHeld);
                proto.write(PowerManagerProto.WakeLock.INTERNAL_COUNT, mInternalCount);
                if (mWorkSource != null) {
                    mWorkSource.dumpDebug(proto, PowerManagerProto.WakeLock.WORK_SOURCE);
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
        @SuppressLint("WakelockTimeout")
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

        /**
         * Set the listener to get notified when the wakelock is enabled/disabled.
         *
         * @param executor {@link Executor} to handle listener callback.
         * @param listener listener to be added, set the listener to null to cancel a listener.
         */
        public void setStateListener(@NonNull @CallbackExecutor Executor executor,
                @Nullable WakeLockStateListener listener) {
            Preconditions.checkNotNull(executor, "executor cannot be null");
            synchronized (mToken) {
                if (listener != mListener) {
                    mListener = listener;
                    if (listener != null) {
                        mCallback = new IWakeLockCallback.Stub() {
                            public void onStateChanged(boolean enabled) {
                                final long token = Binder.clearCallingIdentity();
                                try {
                                    executor.execute(() -> {
                                        listener.onStateChanged(enabled);
                                    });
                                } finally {
                                    Binder.restoreCallingIdentity(token);
                                }
                            }
                        };
                    } else {
                        mCallback = null;
                    }
                    if (mHeld) {
                        try {
                            mService.updateWakeLockCallback(mToken, mCallback);
                        } catch (RemoteException e) {
                            throw e.rethrowFromSystemServer();
                        }
                    }
                }
            }
        }
    }

    /**
     * @hide
     */
    public static void invalidatePowerSaveModeCaches() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_IS_POWER_SAVE_MODE_PROPERTY);
    }

    /**
     * @hide
     */
    public static void invalidateIsInteractiveCaches() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_IS_INTERACTIVE_PROPERTY);
    }
}
