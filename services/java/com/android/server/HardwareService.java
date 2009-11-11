/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import com.android.internal.app.IBatteryStats;
import com.android.server.am.BatteryStatsService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Hardware;
import android.os.IHardwareService;
import android.os.Message;
import android.os.Power;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Binder;
import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedList;
import java.util.ListIterator;

public class HardwareService extends IHardwareService.Stub {
    private static final String TAG = "HardwareService";

    static final int LIGHT_ID_BACKLIGHT = 0;
    static final int LIGHT_ID_KEYBOARD = 1;
    static final int LIGHT_ID_BUTTONS = 2;
    static final int LIGHT_ID_BATTERY = 3;
    static final int LIGHT_ID_NOTIFICATIONS = 4;
    static final int LIGHT_ID_ATTENTION = 5;

    static final int LIGHT_FLASH_NONE = 0;
    static final int LIGHT_FLASH_TIMED = 1;
    static final int LIGHT_FLASH_HARDWARE = 2;

    /**
     * Light brightness is managed by a user setting.
     */
    static final int BRIGHTNESS_MODE_USER = 0;

    /**
     * Light brightness is managed by a light sensor.
     */
    static final int BRIGHTNESS_MODE_SENSOR = 1;

    private final LinkedList<Vibration> mVibrations;
    private Vibration mCurrentVibration;

    private boolean mAttentionLightOn;
    private boolean mPulsing;

    private class Vibration implements IBinder.DeathRecipient {
        private final IBinder mToken;
        private final long    mTimeout;
        private final long    mStartTime;
        private final long[]  mPattern;
        private final int     mRepeat;

        Vibration(IBinder token, long millis) {
            this(token, millis, null, 0);
        }

        Vibration(IBinder token, long[] pattern, int repeat) {
            this(token, 0, pattern, repeat);
        }

        private Vibration(IBinder token, long millis, long[] pattern,
                int repeat) {
            mToken = token;
            mTimeout = millis;
            mStartTime = SystemClock.uptimeMillis();
            mPattern = pattern;
            mRepeat = repeat;
        }

        public void binderDied() {
            synchronized (mVibrations) {
                mVibrations.remove(this);
                if (this == mCurrentVibration) {
                    doCancelVibrateLocked();
                    startNextVibrationLocked();
                }
            }
        }

        public boolean hasLongerTimeout(long millis) {
            if (mTimeout == 0) {
                // This is a pattern, return false to play the simple
                // vibration.
                return false;
            }
            if ((mStartTime + mTimeout)
                    < (SystemClock.uptimeMillis() + millis)) {
                // If this vibration will end before the time passed in, let
                // the new vibration play.
                return false;
            }
            return true;
        }
    }

    HardwareService(Context context) {
        // Reset the hardware to a default state, in case this is a runtime
        // restart instead of a fresh boot.
        vibratorOff();

        mNativePointer = init_native();

        mContext = context;
        PowerManager pm = (PowerManager)context.getSystemService(
                Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(true);

        mVibrations = new LinkedList<Vibration>();

        mBatteryStats = BatteryStatsService.getService();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(mIntentReceiver, filter);
    }

    protected void finalize() throws Throwable {
        finalize_native(mNativePointer);
        super.finalize();
    }

    public void vibrate(long milliseconds, IBinder token) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.VIBRATE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires VIBRATE permission");
        }
        // We're running in the system server so we cannot crash. Check for a
        // timeout of 0 or negative. This will ensure that a vibration has
        // either a timeout of > 0 or a non-null pattern.
        if (milliseconds <= 0 || (mCurrentVibration != null
                && mCurrentVibration.hasLongerTimeout(milliseconds))) {
            // Ignore this vibration since the current vibration will play for
            // longer than milliseconds.
            return;
        }
        Vibration vib = new Vibration(token, milliseconds);
        synchronized (mVibrations) {
            removeVibrationLocked(token);
            doCancelVibrateLocked();
            mCurrentVibration = vib;
            startVibrationLocked(vib);
        }
    }

    private boolean isAll0(long[] pattern) {
        int N = pattern.length;
        for (int i = 0; i < N; i++) {
            if (pattern[i] != 0) {
                return false;
            }
        }
        return true;
    }

    public void vibratePattern(long[] pattern, int repeat, IBinder token) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.VIBRATE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires VIBRATE permission");
        }
        // so wakelock calls will succeed
        long identity = Binder.clearCallingIdentity();
        try {
            if (false) {
                String s = "";
                int N = pattern.length;
                for (int i=0; i<N; i++) {
                    s += " " + pattern[i];
                }
                Log.i(TAG, "vibrating with pattern: " + s);
            }

            // we're running in the server so we can't fail
            if (pattern == null || pattern.length == 0
                    || isAll0(pattern)
                    || repeat >= pattern.length || token == null) {
                return;
            }

            Vibration vib = new Vibration(token, pattern, repeat);
            try {
                token.linkToDeath(vib, 0);
            } catch (RemoteException e) {
                return;
            }

            synchronized (mVibrations) {
                removeVibrationLocked(token);
                doCancelVibrateLocked();
                if (repeat >= 0) {
                    mVibrations.addFirst(vib);
                    startNextVibrationLocked();
                } else {
                    // A negative repeat means that this pattern is not meant
                    // to repeat. Treat it like a simple vibration.
                    mCurrentVibration = vib;
                    startVibrationLocked(vib);
                }
            }
        }
        finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void cancelVibrate(IBinder token) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.VIBRATE,
                "cancelVibrate");

        // so wakelock calls will succeed
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mVibrations) {
                final Vibration vib = removeVibrationLocked(token);
                if (vib == mCurrentVibration) {
                    doCancelVibrateLocked();
                    startNextVibrationLocked();
                }
            }
        }
        finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public boolean getFlashlightEnabled() {
        return Hardware.getFlashlightEnabled();
    }

    public void setFlashlightEnabled(boolean on) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.FLASHLIGHT)
                != PackageManager.PERMISSION_GRANTED &&
                mContext.checkCallingOrSelfPermission(android.Manifest.permission.HARDWARE_TEST)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires FLASHLIGHT or HARDWARE_TEST permission");
        }
        Hardware.setFlashlightEnabled(on);
    }

    public void enableCameraFlash(int milliseconds) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED &&
                mContext.checkCallingOrSelfPermission(android.Manifest.permission.HARDWARE_TEST)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires CAMERA or HARDWARE_TEST permission");
        }
        Hardware.enableCameraFlash(milliseconds);
    }

    void setLightOff_UNCHECKED(int light) {
        setLight_native(mNativePointer, light, 0, LIGHT_FLASH_NONE, 0, 0, 0);
    }

    void setLightBrightness_UNCHECKED(int light, int brightness, int brightnessMode) {
        int b = brightness & 0x000000ff;
        b = 0xff000000 | (b << 16) | (b << 8) | b;
        setLight_native(mNativePointer, light, b, LIGHT_FLASH_NONE, 0, 0, brightnessMode);
    }

    void setLightColor_UNCHECKED(int light, int color) {
        setLight_native(mNativePointer, light, color, LIGHT_FLASH_NONE, 0, 0, 0);
    }

    void setLightFlashing_UNCHECKED(int light, int color, int mode, int onMS, int offMS) {
        setLight_native(mNativePointer, light, color, mode, onMS, offMS, 0);
    }

    public void setAttentionLight(boolean on, int color) {
        // Not worthy of a permission.  We shouldn't have a flashlight permission.
        synchronized (this) {
            mAttentionLightOn = on;
            mPulsing = false;
            setLight_native(mNativePointer, LIGHT_ID_ATTENTION, color,
                    LIGHT_FLASH_HARDWARE, on ? 3 : 0, 0, 0);
        }
    }

    public void pulseBreathingLight() {
        synchronized (this) {
            // HACK: Added at the last minute of cupcake -- design this better;
            // Don't reuse the attention light -- make another one.
            if (false) {
                Log.d(TAG, "pulseBreathingLight mAttentionLightOn=" + mAttentionLightOn
                        + " mPulsing=" + mPulsing);
            }
            if (!mAttentionLightOn && !mPulsing) {
                mPulsing = true;
                setLight_native(mNativePointer, LIGHT_ID_ATTENTION, 0x00ffffff,
                        LIGHT_FLASH_HARDWARE, 7, 0, 0);
                mH.sendMessageDelayed(Message.obtain(mH, 1), 3000);
            }
        }
    }

    private Handler mH = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            synchronized (this) {
                if (false) {
                    Log.d(TAG, "pulse cleanup handler firing mPulsing=" + mPulsing);
                }
                if (mPulsing) {
                    mPulsing = false;
                    setLight_native(mNativePointer, LIGHT_ID_ATTENTION,
                            mAttentionLightOn ? 0xffffffff : 0,
                            LIGHT_FLASH_NONE, 0, 0, 0);
                }
            }
        }
    };

    private final Runnable mVibrationRunnable = new Runnable() {
        public void run() {
            synchronized (mVibrations) {
                doCancelVibrateLocked();
                startNextVibrationLocked();
            }
        }
    };

    // Lock held on mVibrations
    private void doCancelVibrateLocked() {
        if (mThread != null) {
            synchronized (mThread) {
                mThread.mDone = true;
                mThread.notify();
            }
            mThread = null;
        }
        vibratorOff();
        mH.removeCallbacks(mVibrationRunnable);
    }

    // Lock held on mVibrations
    private void startNextVibrationLocked() {
        if (mVibrations.size() <= 0) {
            return;
        }
        mCurrentVibration = mVibrations.getFirst();
        startVibrationLocked(mCurrentVibration);
    }

    // Lock held on mVibrations
    private void startVibrationLocked(final Vibration vib) {
        if (vib.mTimeout != 0) {
            vibratorOn(vib.mTimeout);
            mH.postDelayed(mVibrationRunnable, vib.mTimeout);
        } else {
            // mThread better be null here. doCancelVibrate should always be
            // called before startNextVibrationLocked or startVibrationLocked.
            mThread = new VibrateThread(vib);
            mThread.start();
        }
    }

    // Lock held on mVibrations
    private Vibration removeVibrationLocked(IBinder token) {
        ListIterator<Vibration> iter = mVibrations.listIterator(0);
        while (iter.hasNext()) {
            Vibration vib = iter.next();
            if (vib.mToken == token) {
                iter.remove();
                return vib;
            }
        }
        // We might be looking for a simple vibration which is only stored in
        // mCurrentVibration.
        if (mCurrentVibration != null && mCurrentVibration.mToken == token) {
            return mCurrentVibration;
        }
        return null;
    }

    private class VibrateThread extends Thread {
        final Vibration mVibration;
        boolean mDone;

        VibrateThread(Vibration vib) {
            mVibration = vib;
            mWakeLock.acquire();
        }

        private void delay(long duration) {
            if (duration > 0) {
                long bedtime = SystemClock.uptimeMillis();
                do {
                    try {
                        this.wait(duration);
                    }
                    catch (InterruptedException e) {
                    }
                    if (mDone) {
                        break;
                    }
                    duration = duration
                            - SystemClock.uptimeMillis() - bedtime;
                } while (duration > 0);
            }
        }

        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            synchronized (this) {
                int index = 0;
                long[] pattern = mVibration.mPattern;
                int len = pattern.length;
                int repeat = mVibration.mRepeat;
                long duration = 0;

                while (!mDone) {
                    // add off-time duration to any accumulated on-time duration
                    if (index < len) {
                        duration += pattern[index++];
                    }

                    // sleep until it is time to start the vibrator
                    delay(duration);
                    if (mDone) {
                        break;
                    }

                    if (index < len) {
                        // read on-time duration and start the vibrator
                        // duration is saved for delay() at top of loop
                        duration = pattern[index++];
                        if (duration > 0) {
                            HardwareService.this.vibratorOn(duration);
                        }
                    } else {
                        if (repeat < 0) {
                            break;
                        } else {
                            index = repeat;
                            duration = 0;
                        }
                    }
                }
                mWakeLock.release();
            }
            synchronized (mVibrations) {
                if (mThread == this) {
                    mThread = null;
                }
                if (!mDone) {
                    // If this vibration finished naturally, start the next
                    // vibration.
                    mVibrations.remove(mVibration);
                    startNextVibrationLocked();
                }
            }
        }
    };

    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                synchronized (mVibrations) {
                    doCancelVibrateLocked();
                    mVibrations.clear();
                }
            }
        }
    };

    private static native int init_native();
    private static native void finalize_native(int ptr);

    private static native void setLight_native(int ptr, int light, int color, int mode,
            int onMS, int offMS, int brightnessMode);

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;

    private final IBatteryStats mBatteryStats;

    volatile VibrateThread mThread;

    private int mNativePointer;

    native static void vibratorOn(long milliseconds);
    native static void vibratorOff();
}
