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

    private boolean mAttentionLightOn;
    private boolean mPulsing;

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

        mBatteryStats = BatteryStatsService.getService();
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(mIntentReceiver, filter);
    }

    protected void finalize() throws Throwable {
        finalize_native(mNativePointer);
        super.finalize();
    }

    public void vibrate(long milliseconds) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.VIBRATE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires VIBRATE permission");
        }
        doCancelVibrate();
        vibratorOn(milliseconds);
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

            synchronized (this) {
                Death death = new Death(token);
                try {
                    token.linkToDeath(death, 0);
                } catch (RemoteException e) {
                    return;
                }

                Thread oldThread = mThread;

                if (oldThread != null) {
                    // stop the old one
                    synchronized (mThread) {
                        mThread.mDone = true;
                        mThread.notify();
                    }
                }

                if (mDeath != null) {
                    mToken.unlinkToDeath(mDeath, 0);
                }

                mDeath = death;
                mToken = token;

                // start the new thread
                mThread = new VibrateThread(pattern, repeat);
                mThread.start();
            }
        }
        finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void cancelVibrate() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.VIBRATE,
                "cancelVibrate");

        // so wakelock calls will succeed
        long identity = Binder.clearCallingIdentity();
        try {
            doCancelVibrate();
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

    public void setBacklights(int brightness) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.HARDWARE_TEST) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires HARDWARE_TEST permission");
        }
        // Don't let applications turn the screen all the way off
        brightness = Math.max(brightness, Power.BRIGHTNESS_DIM);
        setLightBrightness_UNCHECKED(LIGHT_ID_BACKLIGHT, brightness);
        setLightBrightness_UNCHECKED(LIGHT_ID_KEYBOARD, brightness);
        setLightBrightness_UNCHECKED(LIGHT_ID_BUTTONS, brightness);
        long identity = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteScreenBrightness(brightness);
        } catch (RemoteException e) {
            Log.w(TAG, "RemoteException calling noteScreenBrightness on BatteryStatsService", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void setLightOff_UNCHECKED(int light) {
        setLight_native(mNativePointer, light, 0, LIGHT_FLASH_NONE, 0, 0);
    }

    void setLightBrightness_UNCHECKED(int light, int brightness) {
        int b = brightness & 0x000000ff;
        b = 0xff000000 | (b << 16) | (b << 8) | b;
        setLight_native(mNativePointer, light, b, LIGHT_FLASH_NONE, 0, 0);
    }

    void setLightColor_UNCHECKED(int light, int color) {
        setLight_native(mNativePointer, light, color, LIGHT_FLASH_NONE, 0, 0);
    }

    void setLightFlashing_UNCHECKED(int light, int color, int mode, int onMS, int offMS) {
        setLight_native(mNativePointer, light, color, mode, onMS, offMS);
    }

    public void setAttentionLight(boolean on) {
        // Not worthy of a permission.  We shouldn't have a flashlight permission.
        synchronized (this) {
            mAttentionLightOn = on;
            mPulsing = false;
            setLight_native(mNativePointer, LIGHT_ID_ATTENTION, on ? 0xffffffff : 0,
                    LIGHT_FLASH_NONE, 0, 0);
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
                setLight_native(mNativePointer, LIGHT_ID_ATTENTION, 0xff101010,
                        LIGHT_FLASH_NONE, 0, 0);
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
                            LIGHT_FLASH_NONE, 0, 0);
                }
            }
        }
    };

    private void doCancelVibrate() {
        synchronized (this) {
            if (mThread != null) {
                synchronized (mThread) {
                    mThread.mDone = true;
                    mThread.notify();
                }
                mThread = null;
            }
            vibratorOff();
        }
    }

    private class VibrateThread extends Thread {
        long[] mPattern;
        int mRepeat;
        boolean mDone;
    
        VibrateThread(long[] pattern, int repeat) {
            mPattern = pattern;
            mRepeat = repeat;
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
                long[] pattern = mPattern;
                int len = pattern.length;
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
                        if (mRepeat < 0) {
                            break;
                        } else {
                            index = mRepeat;
                            duration = 0;
                        }
                    }
                }
                if (mDone) {
                    // make sure vibrator is off if we were cancelled.
                    // otherwise, it will turn off automatically 
                    // when the last timeout expires.
                    HardwareService.this.vibratorOff();
                }
                mWakeLock.release();
            }
            synchronized (HardwareService.this) {
                if (mThread == this) {
                    mThread = null;
                }
            }
        }
    };

    private class Death implements IBinder.DeathRecipient {
        IBinder mMe;

        Death(IBinder me) {
            mMe = me;
        }

        public void binderDied() {
            synchronized (HardwareService.this) {
                if (mMe == mToken) {
                    doCancelVibrate();
                }
            }
        }
    }

    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                doCancelVibrate();
            }
        }
    };
    
    private static native int init_native();
    private static native void finalize_native(int ptr);

    private static native void setLight_native(int ptr, int light, int color, int mode,
            int onMS, int offMS);

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;

    private final IBatteryStats mBatteryStats;
    
    volatile VibrateThread mThread;
    volatile Death mDeath;
    volatile IBinder mToken;

    private int mNativePointer;

    native static void vibratorOn(long milliseconds);
    native static void vibratorOff();
}
