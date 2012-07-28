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
 * The following flags are defined, with varying effects on system power.
 * <i>These flags are mutually exclusive - you may only specify one of them.</i>
 *
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *     <thead>
 *     <tr><th>Flag Value</th> 
 *     <th>CPU</th> <th>Screen</th> <th>Keyboard</th></tr>
 *     </thead>
 *
 *     <tbody>
 *     <tr><th>{@link #PARTIAL_WAKE_LOCK}</th>
 *         <td>On*</td> <td>Off</td> <td>Off</td> 
 *     </tr>
 *     
 *     <tr><th>{@link #SCREEN_DIM_WAKE_LOCK}</th>
 *         <td>On</td> <td>Dim</td> <td>Off</td> 
 *     </tr>
 *
 *     <tr><th>{@link #SCREEN_BRIGHT_WAKE_LOCK}</th>
 *         <td>On</td> <td>Bright</td> <td>Off</td> 
 *     </tr>
 *     
 *     <tr><th>{@link #FULL_WAKE_LOCK}</th>
 *         <td>On</td> <td>Bright</td> <td>Bright</td> 
 *     </tr>
 *     </tbody>
 * </table>
 * </p><p>
 * *<i>If you hold a partial wake lock, the CPU will continue to run, regardless of any
 * display timeouts or the state of the screen and even after the user presses the power button.
 * In all other wake locks, the CPU will run, but the user can still put the device to sleep
 * using the power button.</i>
 * </p><p>
 * In addition, you can add two more flags, which affect behavior of the screen only.
 * <i>These flags have no effect when combined with a {@link #PARTIAL_WAKE_LOCK}.</i>
 *
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *     <thead>
 *     <tr><th>Flag Value</th> <th>Description</th></tr>
 *     </thead>
 *
 *     <tbody>
 *     <tr><th>{@link #ACQUIRE_CAUSES_WAKEUP}</th>
 *         <td>Normal wake locks don't actually turn on the illumination.  Instead, they cause
 *         the illumination to remain on once it turns on (e.g. from user activity).  This flag
 *         will force the screen and/or keyboard to turn on immediately, when the WakeLock is
 *         acquired.  A typical use would be for notifications which are important for the user to
 *         see immediately.</td> 
 *     </tr>
 *     
 *     <tr><th>{@link #ON_AFTER_RELEASE}</th>
 *         <td>If this flag is set, the user activity timer will be reset when the WakeLock is
 *         released, causing the illumination to remain on a bit longer.  This can be used to 
 *         reduce flicker if you are cycling between wake lock conditions.</td> 
 *     </tr>
 *     </tbody>
 * </table>
 * </p><p>
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
     * Since not all devices have proximity sensors, use {@link #getSupportedWakeLockFlags}
     * to determine whether this wake lock level is supported.
     * </p>
     *
     * {@hide}
     */
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 0x00000020;

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
     * Flag for {@link WakeLock#release release(int)} to defer releasing a
     * {@link #WAKE_BIT_PROXIMITY_SCREEN_OFF} wake lock until the proximity sensor returns
     * a negative value.
     *
     * {@hide}
     */
    public static final int WAIT_FOR_PROXIMITY_NEGATIVE = 1;

    /**
     * Brightness value to use when battery is low.
     * @hide
     */
    public static final int BRIGHTNESS_LOW_BATTERY = 10;

    /**
     * Brightness value for fully on.
     * @hide
     */
    public static final int BRIGHTNESS_ON = 255;

    /**
     * Brightness value for dim backlight.
     * @hide
     */
    public static final int BRIGHTNESS_DIM = 20;

    /**
     * Brightness value for fully off.
     * @hide
     */
    public static final int BRIGHTNESS_OFF = 0;

    // Note: Be sure to update android.os.BatteryStats and PowerManager.h
    // if adding or modifying user activity event constants.

    /**
     * User activity event type: Unspecified event type.
     * @hide
     */
    public static final int USER_ACTIVITY_EVENT_OTHER = 0;

    /**
     * User activity event type: Button or key pressed or released.
     * @hide
     */
    public static final int USER_ACTIVITY_EVENT_BUTTON = 1;

    /**
     * User activity event type: Touch down, move or up.
     * @hide
     */
    public static final int USER_ACTIVITY_EVENT_TOUCH = 2;

    final IPowerManager mService;
    final Handler mHandler;

    /**
     * {@hide}
     */
    public PowerManager(IPowerManager service, Handler handler) {
        mService = service;
        mHandler = handler;
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
     * @see #ACQUIRE_CAUSES_WAKEUP
     * @see #ON_AFTER_RELEASE
     */
    public WakeLock newWakeLock(int levelAndFlags, String tag) {
        validateWakeLockParameters(levelAndFlags, tag);
        return new WakeLock(levelAndFlags, tag);
    }

    /** @hide */
    public static void validateWakeLockParameters(int levelAndFlags, String tag) {
        switch (levelAndFlags & WAKE_LOCK_LEVEL_MASK) {
            case PARTIAL_WAKE_LOCK:
            case SCREEN_DIM_WAKE_LOCK:
            case SCREEN_BRIGHT_WAKE_LOCK:
            case FULL_WAKE_LOCK:
            case PROXIMITY_SCREEN_OFF_WAKE_LOCK:
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
     * Turns the device from whatever state it's in to full on, and resets
     * the auto-off timer.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param when The time of the user activity, in the {@link SystemClock#uptimeMillis()}
     * time base.  This timestamp is used to correctly order the user activity with
     * other power management functions.  It should be set
     * to the timestamp of the input event that caused the user activity.
     * @param noChangeLights If true, does not cause the keyboard backlight to turn on
     * because of this event.  This is set when the power key is pressed.
     * We want the device to stay on while the button is down, but we're about
     * to turn off the screen so we don't want the keyboard backlight to turn on again.
     * Otherwise the lights flash on and then off and it looks weird.
     */
    public void userActivity(long when, boolean noChangeLights) {
        try {
            mService.userActivity(when, noChangeLights);
        } catch (RemoteException e) {
        }
    }

   /**
     * Forces the device to go to sleep.
     * <p>
     * Overrides all the wake locks that are held.  This is what happen when the power
     * key is pressed to turn off the screen.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param time The time when the request to go to sleep was issued, in the
     * {@link SystemClock#uptimeMillis()} time base.  This timestamp is used to correctly
     * order the user activity with other power management functions.  It should be set
     * to the timestamp of the input event that caused the request to go to sleep.
     */
    public void goToSleep(long time) {
        try {
            mService.goToSleep(time);
        } catch (RemoteException e) {
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
     * {@hide}
     */
    public void setBacklightBrightness(int brightness) {
        try {
            mService.setBacklightBrightness(brightness);
        } catch (RemoteException e) {
        }
    }

   /**
     * Returns the set of wake lock levels and flags for {@link #newWakeLock}
     * that are supported on the device.
     * <p>
     * For example, to test to see if the {@link #PROXIMITY_SCREEN_OFF_WAKE_LOCK}
     * is supported:
     * {@samplecode
     * PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
     * int supportedFlags = pm.getSupportedWakeLockFlags();
     * boolean proximitySupported = ((supportedFlags & PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
     *         == PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK);
     * }
     * </p>
     *
     * @return The set of supported WakeLock flags.
     *
     * {@hide}
     */
    public int getSupportedWakeLockFlags() {
        try {
            return mService.getSupportedWakeLockFlags();
        } catch (RemoteException e) {
            return 0;
        }
    }

    /**
      * Returns whether the screen is currently on.
      * <p>
      * Only indicates whether the screen is on.  The screen could be either bright or dim.
      * </p><p>
      * {@samplecode
      * PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
      * boolean isScreenOn = pm.isScreenOn();
      * }
      * </p>
      *
      * @return whether the screen is on (bright or dim).
      */
    public boolean isScreenOn() {
        try {
            return mService.isScreenOn();
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
            mService.reboot(reason);
        } catch (RemoteException e) {
        }
    }

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
        private final int mFlags;
        private final String mTag;
        private final IBinder mToken;
        private int mCount;
        private boolean mRefCounted = true;
        private boolean mHeld;
        private WorkSource mWorkSource;

        private final Runnable mReleaser = new Runnable() {
            public void run() {
                release();
            }
        };

        WakeLock(int flags, String tag) {
            mFlags = flags;
            mTag = tag;
            mToken = new Binder();
        }

        @Override
        protected void finalize() throws Throwable {
            synchronized (mToken) {
                if (mHeld) {
                    Log.wtf(TAG, "WakeLock finalized while still held: " + mTag);
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
                try {
                    mService.acquireWakeLock(mFlags, mToken, mTag, mWorkSource);
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
         * Currently only {@link #WAIT_FOR_PROXIMITY_NEGATIVE} is supported.
         *
         * {@hide}
         */
        public void release(int flags) {
            synchronized (mToken) {
                if (!mRefCounted || --mCount == 0) {
                    mHandler.removeCallbacks(mReleaser);
                    if (mHeld) {
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
                        mService.updateWakeLockWorkSource(mToken, mWorkSource);
                    } catch (RemoteException e) {
                    }
                }
            }
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
