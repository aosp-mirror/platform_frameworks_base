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
 * <p><b>Device battery life will be significantly affected by the use of this API.</b>  Do not
 * acquire WakeLocks unless you really need them, use the minimum levels possible, and be sure
 * to release it as soon as you can.
 * 
 * <p>You can obtain an instance of this class by calling 
 * {@link android.content.Context#getSystemService(java.lang.String) Context.getSystemService()}.
 * 
 * <p>The primary API you'll use is {@link #newWakeLock(int, String) newWakeLock()}.  This will
 * create a {@link PowerManager.WakeLock} object.  You can then use methods on this object to 
 * control the power state of the device.  In practice it's quite simple:
 * 
 * {@samplecode
 * PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
 * PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "My Tag");
 * wl.acquire();
 *   ..screen will stay on during this section..
 * wl.release();
 * }
 * 
 * <p>The following flags are defined, with varying effects on system power.  <i>These flags are
 * mutually exclusive - you may only specify one of them.</i>
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *
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
 * 
 * <p>*<i>If you hold a partial wakelock, the CPU will continue to run, irrespective of any timers 
 * and even after the user presses the power button.  In all other wakelocks, the CPU will run, but
 * the user can still put the device to sleep using the power button.</i>
 * 
 * <p>In addition, you can add two more flags, which affect behavior of the screen only.  <i>These
 * flags have no effect when combined with a {@link #PARTIAL_WAKE_LOCK}.</i>
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *
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
 * 
 * Any application using a WakeLock must request the {@code android.permission.WAKE_LOCK}
 * permission in an {@code &lt;uses-permission&gt;} element of the application's manifest.
 */
public class PowerManager
{
    private static final String TAG = "PowerManager";
    
    /**
     * These internal values define the underlying power elements that we might
     * want to control individually.  Eventually we'd like to expose them.
     */
    private static final int WAKE_BIT_CPU_STRONG = 1;
    private static final int WAKE_BIT_CPU_WEAK = 2;
    private static final int WAKE_BIT_SCREEN_DIM = 4;
    private static final int WAKE_BIT_SCREEN_BRIGHT = 8;
    private static final int WAKE_BIT_KEYBOARD_BRIGHT = 16;
    private static final int WAKE_BIT_PROXIMITY_SCREEN_OFF = 32;
    
    private static final int LOCK_MASK = WAKE_BIT_CPU_STRONG
                                        | WAKE_BIT_CPU_WEAK
                                        | WAKE_BIT_SCREEN_DIM
                                        | WAKE_BIT_SCREEN_BRIGHT
                                        | WAKE_BIT_KEYBOARD_BRIGHT
                                        | WAKE_BIT_PROXIMITY_SCREEN_OFF;

    /**
     * Wake lock that ensures that the CPU is running.  The screen might
     * not be on.
     */
    public static final int PARTIAL_WAKE_LOCK = WAKE_BIT_CPU_STRONG;

    /**
     * Wake lock that ensures that the screen and keyboard are on at
     * full brightness.
     *
     * <p class="note">Most applications should strongly consider using
     * {@link android.view.WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON}.
     * This window flag will be correctly managed by the platform
     * as the user moves between applications and doesn't require a special permission.</p>
     */
    public static final int FULL_WAKE_LOCK = WAKE_BIT_CPU_WEAK | WAKE_BIT_SCREEN_BRIGHT 
                                            | WAKE_BIT_KEYBOARD_BRIGHT;

    /**
     * @deprecated Most applications should use
     * {@link android.view.WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON} instead
     * of this type of wake lock, as it will be correctly managed by the platform
     * as the user moves between applications and doesn't require a special permission.
     *
     * Wake lock that ensures that the screen is on at full brightness;
     * the keyboard backlight will be allowed to go off.
     */
    @Deprecated
    public static final int SCREEN_BRIGHT_WAKE_LOCK = WAKE_BIT_CPU_WEAK | WAKE_BIT_SCREEN_BRIGHT;

    /**
     * Wake lock that ensures that the screen is on (but may be dimmed);
     * the keyboard backlight will be allowed to go off.
     */
    public static final int SCREEN_DIM_WAKE_LOCK = WAKE_BIT_CPU_WEAK | WAKE_BIT_SCREEN_DIM;

    /**
     * Wake lock that turns the screen off when the proximity sensor activates.
     * Since not all devices have proximity sensors, use
     * {@link #getSupportedWakeLockFlags() getSupportedWakeLockFlags()} to determine if
     * this wake lock mode is supported.
     *
     * {@hide}
     */
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = WAKE_BIT_PROXIMITY_SCREEN_OFF;

    /**
     * Flag for {@link WakeLock#release release(int)} to defer releasing a
     * {@link #WAKE_BIT_PROXIMITY_SCREEN_OFF} wakelock until the proximity sensor returns
     * a negative value.
     *
     * {@hide}
     */
    public static final int WAIT_FOR_PROXIMITY_NEGATIVE = 1;

    /**
     * Normally wake locks don't actually wake the device, they just cause
     * it to remain on once it's already on.  Think of the video player
     * app as the normal behavior.  Notifications that pop up and want
     * the device to be on are the exception; use this flag to be like them.
     * <p> 
     * Does not work with PARTIAL_WAKE_LOCKs.
     */
    public static final int ACQUIRE_CAUSES_WAKEUP = 0x10000000;

    /**
     * When this wake lock is released, poke the user activity timer
     * so the screen stays on for a little longer.
     * <p>
     * Will not turn the screen on if it is not already on.  See {@link #ACQUIRE_CAUSES_WAKEUP}
     * if you want that.
     * <p>
     * Does not work with PARTIAL_WAKE_LOCKs.
     */
    public static final int ON_AFTER_RELEASE = 0x20000000;
    
    /**
     * Class lets you say that you need to have the device on.
     * <p>
     * Call release when you are done and don't need the lock anymore.
     * <p>
     * Any application using a WakeLock must request the {@code android.permission.WAKE_LOCK}
     * permission in an {@code &lt;uses-permission&gt;} element of the application's manifest.
     */
    public class WakeLock
    {
        static final int RELEASE_WAKE_LOCK = 1;

        Runnable mReleaser = new Runnable() {
            public void run() {
                release();
            }
        };
	
        int mFlags;
        String mTag;
        IBinder mToken;
        int mCount = 0;
        boolean mRefCounted = true;
        boolean mHeld = false;
        WorkSource mWorkSource;

        WakeLock(int flags, String tag)
        {
            switch (flags & LOCK_MASK) {
            case PARTIAL_WAKE_LOCK:
            case SCREEN_DIM_WAKE_LOCK:
            case SCREEN_BRIGHT_WAKE_LOCK:
            case FULL_WAKE_LOCK:
            case PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                break;
            default:
                throw new IllegalArgumentException();
            }

            mFlags = flags;
            mTag = tag;
            mToken = new Binder();
        }

        /**
         * Sets whether this WakeLock is ref counted.
         *
         * <p>Wake locks are reference counted by default.
         *
         * @param value true for ref counted, false for not ref counted.
         */
        public void setReferenceCounted(boolean value)
        {
            mRefCounted = value;
        }

        /**
         * Makes sure the device is on at the level you asked when you created
         * the wake lock.
         */
        public void acquire()
        {
            synchronized (mToken) {
                acquireLocked();
            }
        }

        /**
         * Makes sure the device is on at the level you asked when you created
         * the wake lock. The lock will be released after the given timeout.
         * 
         * @param timeout Release the lock after the give timeout in milliseconds.
         */
        public void acquire(long timeout) {
            synchronized (mToken) {
                acquireLocked();
                mHandler.postDelayed(mReleaser, timeout);
            }
        }
        
        private void acquireLocked() {
            if (!mRefCounted || mCount++ == 0) {
                mHandler.removeCallbacks(mReleaser);
                try {
                    mService.acquireWakeLock(mFlags, mToken, mTag, mWorkSource);
                } catch (RemoteException e) {
                }
                mHeld = true;
            }
        }

        /**
         * Release your claim to the CPU or screen being on.
         *
         * <p>
         * It may turn off shortly after you release it, or it may not if there
         * are other wake locks held.
         */
        public void release() {
            release(0);
        }

        /**
         * Release your claim to the CPU or screen being on.
         * @param flags Combination of flag values to modify the release behavior.
         *              Currently only {@link #WAIT_FOR_PROXIMITY_NEGATIVE} is supported.
         *
         * <p>
         * It may turn off shortly after you release it, or it may not if there
         * are other wake locks held.
         *
         * {@hide}
         */
        public void release(int flags) {
            synchronized (mToken) {
                if (!mRefCounted || --mCount == 0) {
                    mHandler.removeCallbacks(mReleaser);
                    try {
                        mService.releaseWakeLock(mToken, flags);
                    } catch (RemoteException e) {
                    }
                    mHeld = false;
                }
                if (mCount < 0) {
                    throw new RuntimeException("WakeLock under-locked " + mTag);
                }
            }
        }

        public boolean isHeld()
        {
            synchronized (mToken) {
                return mHeld;
            }
        }

        public void setWorkSource(WorkSource ws) {
            synchronized (mToken) {
                if (ws != null && ws.size() == 0) {
                    ws = null;
                }
                boolean changed = true;
                if (ws == null) {
                    mWorkSource = null;
                } else if (mWorkSource == null) {
                    changed = mWorkSource != null;
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

        public String toString() {
            synchronized (mToken) {
                return "WakeLock{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " held=" + mHeld + ", refCount=" + mCount + "}";
            }
        }

        @Override
        protected void finalize() throws Throwable
        {
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
    }

    /**
     * Get a wake lock at the level of the flags parameter.  Call
     * {@link WakeLock#acquire() acquire()} on the object to acquire the
     * wake lock, and {@link WakeLock#release release()} when you are done.
     *
     * {@samplecode
     *PowerManager pm = (PowerManager)mContext.getSystemService(
     *                                          Context.POWER_SERVICE);
     *PowerManager.WakeLock wl = pm.newWakeLock(
     *                                      PowerManager.SCREEN_DIM_WAKE_LOCK
     *                                      | PowerManager.ON_AFTER_RELEASE,
     *                                      TAG);
     *wl.acquire();
     * // ...
     *wl.release();
     * }
     *
     * <p class="note">If using this to keep the screen on, you should strongly consider using
     * {@link android.view.WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON} instead.
     * This window flag will be correctly managed by the platform
     * as the user moves between applications and doesn't require a special permission.</p>
     *
     * @param flags Combination of flag values defining the requested behavior of the WakeLock.
     * @param tag Your class name (or other tag) for debugging purposes.
     *
     * @see WakeLock#acquire()
     * @see WakeLock#release()
     */
    public WakeLock newWakeLock(int flags, String tag)
    {
        if (tag == null) {
            throw new NullPointerException("tag is null in PowerManager.newWakeLock");
        }
        return new WakeLock(flags, tag);
    }

    /**
     * User activity happened.
     * <p>
     * Turns the device from whatever state it's in to full on, and resets
     * the auto-off timer.
     *
     * @param when is used to order this correctly with the wake lock calls.
     *          This time should be in the {@link SystemClock#uptimeMillis
     *          SystemClock.uptimeMillis()} time base.
     * @param noChangeLights should be true if you don't want the lights to
     *          turn on because of this event.  This is set when the power
     *          key goes down.  We want the device to stay on while the button
     *          is down, but we're about to turn off.  Otherwise the lights
     *          flash on and then off and it looks weird.
     */
    public void userActivity(long when, boolean noChangeLights)
    {
        try {
            mService.userActivity(when, noChangeLights);
        } catch (RemoteException e) {
        }
    }

   /**
     * Force the device to go to sleep. Overrides all the wake locks that are
     * held.
     * 
     * @param time is used to order this correctly with the wake lock calls. 
     *          The time  should be in the {@link SystemClock#uptimeMillis 
     *          SystemClock.uptimeMillis()} time base.
     */
    public void goToSleep(long time) 
    {
        try {
            mService.goToSleep(time);
        } catch (RemoteException e) {
        }
    }

    /**
     * sets the brightness of the backlights (screen, keyboard, button).
     *
     * @param brightness value from 0 to 255
     *
     * {@hide}
     */
    public void setBacklightBrightness(int brightness)
    {
        try {
            mService.setBacklightBrightness(brightness);
        } catch (RemoteException e) {
        }
    }

   /**
     * Returns the set of flags for {@link #newWakeLock(int, String) newWakeLock()}
     * that are supported on the device.
     * For example, to test to see if the {@link #PROXIMITY_SCREEN_OFF_WAKE_LOCK}
     * is supported:
     *
     * {@samplecode
     * PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
     * int supportedFlags = pm.getSupportedWakeLockFlags();
     *  boolean proximitySupported = ((supportedFlags & PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
     *                                  == PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK);
     * }
     *
     * @return the set of supported WakeLock flags.
     *
     * {@hide}
     */
    public int getSupportedWakeLockFlags()
    {
        try {
            return mService.getSupportedWakeLockFlags();
        } catch (RemoteException e) {
            return 0;
        }
    }

    /**
      * Returns whether the screen is currently on. The screen could be bright
      * or dim.
      *
      * {@samplecode
      * PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
      * boolean isScreenOn = pm.isScreenOn();
      * }
      *
      * @return whether the screen is on (bright or dim).
      */
    public boolean isScreenOn()
    {
        try {
            return mService.isScreenOn();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Reboot the device.  Will not return if the reboot is
     * successful.  Requires the {@link android.Manifest.permission#REBOOT}
     * permission.
     *
     * @param reason code to pass to the kernel (e.g., "recovery") to
     *               request special boot modes, or null.
     */
    public void reboot(String reason)
    {
        try {
            mService.reboot(reason);
        } catch (RemoteException e) {
        }
    }

    private PowerManager()
    {
    }

    /**
     * {@hide}
     */
    public PowerManager(IPowerManager service, Handler handler)
    {
        mService = service;
        mHandler = handler;
    }

    /**
     *  TODO: It would be nice to be able to set the poke lock here,
     *  but I'm not sure what would be acceptable as an interface -
     *  either a PokeLock object (like WakeLock) or, possibly just a
     *  method call to set the poke lock. 
     */
    
    IPowerManager mService;
    Handler mHandler;
}
