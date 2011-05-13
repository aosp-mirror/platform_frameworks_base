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
import android.os.Handler;
import android.os.IVibratorService;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Binder;
import android.os.SystemClock;
import android.os.WorkSource;
import android.util.Slog;

import java.util.LinkedList;
import java.util.ListIterator;

public class VibratorService extends IVibratorService.Stub {
    private static final String TAG = "VibratorService";

    private final LinkedList<Vibration> mVibrations;
    private Vibration mCurrentVibration;
    private final WorkSource mTmpWorkSource = new WorkSource();

    private class Vibration implements IBinder.DeathRecipient {
        private final IBinder mToken;
        private final long    mTimeout;
        private final long    mStartTime;
        private final long[]  mPattern;
        private final int     mRepeat;
        private final int     mUid;

        Vibration(IBinder token, long millis, int uid) {
            this(token, millis, null, 0, uid);
        }

        Vibration(IBinder token, long[] pattern, int repeat, int uid) {
            this(token, 0, pattern, repeat, uid);
        }

        private Vibration(IBinder token, long millis, long[] pattern,
                int repeat, int uid) {
            mToken = token;
            mTimeout = millis;
            mStartTime = SystemClock.uptimeMillis();
            mPattern = pattern;
            mRepeat = repeat;
            mUid = uid;
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

    VibratorService(Context context) {
        // Reset the hardware to a default state, in case this is a runtime
        // restart instead of a fresh boot.
        vibratorOff();

        mContext = context;
        PowerManager pm = (PowerManager)context.getSystemService(
                Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*vibrator*");
        mWakeLock.setReferenceCounted(true);

        mVibrations = new LinkedList<Vibration>();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(mIntentReceiver, filter);
    }

    public void vibrate(long milliseconds, IBinder token) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.VIBRATE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires VIBRATE permission");
        }
        int uid = Binder.getCallingUid();
        // We're running in the system server so we cannot crash. Check for a
        // timeout of 0 or negative. This will ensure that a vibration has
        // either a timeout of > 0 or a non-null pattern.
        if (milliseconds <= 0 || (mCurrentVibration != null
                && mCurrentVibration.hasLongerTimeout(milliseconds))) {
            // Ignore this vibration since the current vibration will play for
            // longer than milliseconds.
            return;
        }
        Vibration vib = new Vibration(token, milliseconds, uid);
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
        int uid = Binder.getCallingUid();
        // so wakelock calls will succeed
        long identity = Binder.clearCallingIdentity();
        try {
            if (false) {
                String s = "";
                int N = pattern.length;
                for (int i=0; i<N; i++) {
                    s += " " + pattern[i];
                }
                Slog.i(TAG, "vibrating with pattern: " + s);
            }

            // we're running in the server so we can't fail
            if (pattern == null || pattern.length == 0
                    || isAll0(pattern)
                    || repeat >= pattern.length || token == null) {
                return;
            }

            Vibration vib = new Vibration(token, pattern, repeat, uid);
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
            mCurrentVibration = null;
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
                unlinkVibration(vib);
                return vib;
            }
        }
        // We might be looking for a simple vibration which is only stored in
        // mCurrentVibration.
        if (mCurrentVibration != null && mCurrentVibration.mToken == token) {
            unlinkVibration(mCurrentVibration);
            return mCurrentVibration;
        }
        return null;
    }

    private void unlinkVibration(Vibration vib) {
        if (vib.mPattern != null) {
            // If Vibration object has a pattern,
            // the Vibration object has also been linkedToDeath.
            vib.mToken.unlinkToDeath(vib, 0);
        }
    }

    private class VibrateThread extends Thread {
        final Vibration mVibration;
        boolean mDone;

        VibrateThread(Vibration vib) {
            mVibration = vib;
            mTmpWorkSource.set(vib.mUid);
            mWakeLock.setWorkSource(mTmpWorkSource);
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
                            VibratorService.this.vibratorOn(duration);
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
                    unlinkVibration(mVibration);
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

                    int size = mVibrations.size();
                    for(int i = 0; i < size; i++) {
                        unlinkVibration(mVibrations.get(i));
                    }

                    mVibrations.clear();
                }
            }
        }
    };

    private Handler mH = new Handler();

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;

    volatile VibrateThread mThread;

    native static void vibratorOn(long milliseconds);
    native static void vibratorOff();
}
