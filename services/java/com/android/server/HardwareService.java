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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Hardware;
import android.os.IHardwareService;
import android.os.Power;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Binder;
import android.os.SystemClock;
import android.util.Log;

public class HardwareService extends IHardwareService.Stub {
    private static final String TAG = "HardwareService";

    HardwareService(Context context) {
        mContext = context;
        PowerManager pm = (PowerManager)context.getSystemService(
                Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(true);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(mIntentReceiver, filter);
    }

    public void vibrate(long milliseconds) {
        vibratePattern(new long[] { 0, milliseconds }, -1,
                       new Binder());
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

    public void setScreenBacklight(int brightness) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.HARDWARE_TEST) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires HARDWARE_TEST permission");
        }
        // Don't let applications turn the screen all the way off
        brightness = Math.max(brightness, Power.BRIGHTNESS_DIM);
        Hardware.setScreenBacklight(brightness);
    }

    public void setKeyboardBacklight(boolean on) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.HARDWARE_TEST) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires HARDWARE_TEST permission");
        }
        Hardware.setKeyboardBacklight(on);
    }

    public void setButtonBacklight(boolean on) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.HARDWARE_TEST) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires HARDWARE_TEST permission");
        }
        Hardware.setButtonBacklight(on);
    }

    public void setLedState(int colorARGB, int onMS, int offMS) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.HARDWARE_TEST) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires HARDWARE_TEST permission");
        }
        Hardware.setLedState(colorARGB, onMS, offMS);
    }

    private void doCancelVibrate() {
        synchronized (this) {
            if (mThread != null) {
                synchronized (mThread) {
                    mThread.mDone = true;
                    mThread.notify();
                }
                mThread = null;
                off();
            }
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

        public void run() {
            synchronized (this) {
                int index = 0;
                boolean nextState = false;
                long[] pattern = mPattern;
                if (pattern[0] == 0) {
                    index++;
                    nextState = true;
                }
                int len = pattern.length;
                long start = SystemClock.uptimeMillis();

                while (!mDone) {
                    if (nextState) {
                        HardwareService.this.on();
                    } else {
                        HardwareService.this.off();
                    }
                    nextState = !nextState;
                    long bedtime = SystemClock.uptimeMillis();
                    long duration = pattern[index];
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
                    index++;
                    if (index >= len) {
                        if (mRepeat < 0) {
                            HardwareService.this.off();
                            break;
                        } else {
                            index = mRepeat;
                        }
                    }
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

    private Context mContext;
    private PowerManager.WakeLock mWakeLock;

    volatile VibrateThread mThread;
    volatile Death mDeath;
    volatile IBinder mToken;

    native static void on();
    native static void off();
}
