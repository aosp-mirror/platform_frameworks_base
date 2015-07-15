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

import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.content.Context;
import android.util.Log;

/**
 * This class gives you control of the power state of the device.
 *
 * <p>
 * <b>Device battery life will be significantly affected by the use of this API.</b>
 * Do not acquire {@link WakeLock}s unless you really need them, use the minimum levels
 * possible, and be sure to release them as soon as possible.
 * </p><p>
 * You can obtain an instance of this class by calling
 * {@link android.content.Context#getSystemService(java.lang.String) Context.getSystemService()}.
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
 * permission in an {@code &lt;uses-permission&gt;} element of the application's manifest.
 * </p>
 */
public final class PowerManager {
    private static final String TAG = "PowerManager";

    /* NOTE: Wake lock levels were previously defined as a bit field, except that only a few
     * combinations were actually supported so the bit field was removed.  This explains
     * why the numbering scheme is so odd.  If adding a new wake lock level, any unused
     * value can be used.
     */

    /**
     * Wake lock level: Ensures that the CPU is running; the screen and keyboard
     * backlight will be allowed to go off.
     * <p>
     * If the user presses the power button, then the screen will be turned off
     * but the CPU will be kept on until all partial wake locks have been released.
     * </p>
     */
    public static final int PARTIAL_WAKE_LOCK = 0x00000001;

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
    public static final int SCREEN_DIM_WAKE_LOCK = 0x00000006;

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
    public static final int SCREEN_BRIGHT_WAKE_LOCK = 0x0000000a;

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
    public static final int FULL_WAKE_LOCK = 0x0000001a;

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
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 0x00000020;

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
    public static final int DOZE_WAKE_LOCK = 0x00000040;

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
    public static final int DRAW_WAKE_LOCK = 0x00000080;

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
    public static final int RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY = 1;

    /**
     * Brightness value for fully on.
     * @hide
     */
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
     * Go to sleep reason code: Going to sleep due by application request.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_APPLICATION = 0;

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
     * Go to sleep flag: Skip dozing state and directly go to full sleep.
     * @hide
     */
    public static final int GO_TO_SLEEP_FLAG_NO_DOZE = 1 << 0;

    /**
     * The value to pass as the 'reason' argument to reboot() to
     * reboot into recovery mode (for applying system updates, doing
     * factory resets, etc.).
     * <p>
     * Requires the {@link android.Manifest.permission#RECOVERY}
     * permission (in addition to
     * {@link android.Manifest.permission#REBOOT}).
     * </p>
     * @hide
     */
    public static final String REBOOT_RECOVERY = "recovery";
    
    final Context mContext;
    final IPowerManager mService;
    final Handler mHandler;

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
    public int getMaximumScreenBrightnessSetting() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMaximum);
    }

    /**
     * Gets the default screen brightness setting.
     * @hide
     */
    public int getDefaultScreenBrightnessSetting() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingDefault);
    }

    /**
     * Returns true if the twilight service should be used to adjust screen brightness
     * policy.  This setting is experimental and disabled by default.
     * @hide
     */
    public static boolean useTwilightAdjustmentFeature() {
        return SystemProperties.getBoolean("persist.power.usetwilightadj", false);
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
    public void userActivity(long when, int event, int flags) {
        try {
            mService.userActivity(when, event, flags);
        } catch (RemoteException e) {
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
    public void goToSleep(long time, int reason, int flags) {
        try {
            mService.goToSleep(time, reason, flags);
        } catch (RemoteException e) {
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
     * @removed Requires signature permission.
     */
    public void wakeUp(long time) {
        try {
            mService.wakeUp(time, "wakeUp", mContext.getOpPackageName());
        } catch (RemoteException e) {
        }
    }

    /**
     * @hide
     */
    public void wakeUp(long time, String reason) {
        try {
            mService.wakeUp(time, reason, mContext.getOpPackageName());
        } catch (RemoteException e) {
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
        }
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
        }
    }

    /**
     * Returns whether the screen brightness is currently boosted to maximum, caused by a call
     * to {@link #boostScreenBrightness(long)}.
     * @return {@code True} if the screen brightness is currently boosted. {@code False} otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean isScreenBrightnessBoosted() {
        try {
            return mService.isScreenBrightnessBoosted();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Sets the brightness of the backlights (screen, keyboard, button).
     * <p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param brightness The brightness value from 0 to 255.
     *
     * @hide Requires signature permission.
     */
    public void setBacklightBrightness(int brightness) {
        try {
            mService.setTemporaryScreenBrightnessSettingOverride(brightness);
        } catch (RemoteException e) {
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
            return false;
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
            return false;
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
            return false;
        }
    }

    /**
     * Set the current power save mode.
     *
     * @return True if the set was allowed.
     *
     * @see #isPowerSaveMode()
     *
     * @hide
     */
    public boolean setPowerSaveMode(boolean mode) {
        try {
            return mService.setPowerSaveMode(mode);
        } catch (RemoteException e) {
            return false;
        }
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
            return false;
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
            return false;
        }
    }

    /**
     * Turn off the device.
     *
     * @param confirm If true, shows a shutdown confirmation dialog.
     * @param wait If true, this call waits for the shutdown to complete and does not return.
     *
     * @hide
     */
    public void shutdown(boolean confirm, boolean wait) {
        try {
            mService.shutdown(confirm, wait);
        } catch (RemoteException e) {
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
     * Intent that is broadcast when the state of {@link #isDeviceIdleMode()} changes.
     * This broadcast is only sent to registered receivers.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_IDLE_MODE_CHANGED
            = "android.os.action.DEVICE_IDLE_MODE_CHANGED";

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
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_POWER_SAVE_MODE_CHANGING
            = "android.os.action.POWER_SAVE_MODE_CHANGING";

    /** @hide */
    public static final String EXTRA_POWER_SAVE_MODE = "mode";

    /**
     * Intent that is broadcast when the state of {@link #isScreenBrightnessBoosted()} has changed.
     * This broadcast is only sent to registered receivers.
     *
     * @hide
     **/
    @SystemApi
    public static final String ACTION_SCREEN_BRIGHTNESS_BOOST_CHANGED
            = "android.os.action.SCREEN_BRIGHTNESS_BOOST_CHANGED";

    /**
     * A wake lock is a mechanism to indicate that your application needs
     * to have the device stay on.
     * <p>
     * Any application using a WakeLock must request the {@code android.permission.WAKE_LOCK}
     * permission in an {@code &lt;uses-permission&gt;} element of the application's manifest.
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
        private int mFlags;
        private String mTag;
        private final String mPackageName;
        private final IBinder mToken;
        private int mCount;
        private boolean mRefCounted = true;
        private boolean mHeld;
        private WorkSource mWorkSource;
        private String mHistoryTag;
        private final String mTraceName;

        private final Runnable mReleaser = new Runnable() {
            public void run() {
                release();
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
            if (!mRefCounted || mCount++ == 0) {
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
                if (!mRefCounted || --mCount == 0) {
                    mHandler.removeCallbacks(mReleaser);
                    if (mHeld) {
                        Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, mTraceName, 0);
                        try {
                            mService.releaseWakeLock(mToken, flags);
                        } catch (RemoteException e) {
                        }
                        mHeld = false;
                    }
                }
                if (mCount < 0) {
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
         * @param ws The work source, or null if none.
         */
        public void setWorkSource(WorkSource ws) {
            synchronized (mToken) {
                if (ws != null && ws.size() == 0) {
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
                    changed = mWorkSource.diff(ws);
                    if (changed) {
                        mWorkSource.set(ws);
                    }
                }

                if (changed && mHeld) {
                    try {
                        mService.updateWakeLockWorkSource(mToken, mWorkSource, mHistoryTag);
                    } catch (RemoteException e) {
                    }
                }
            }
        }

        /** @hide */
        public void setTag(String tag) {
            mTag = tag;
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
                    + " held=" + mHeld + ", refCount=" + mCount + "}";
            }
        }
    }
}
