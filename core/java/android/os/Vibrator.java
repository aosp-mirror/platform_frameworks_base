/*
 * Copyright (C) 2006 The Android Open Source Project
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
 * Class that operates the vibrator on the device.
 * <p>
 * If your process exits, any vibration you started with will stop.
 * </p>
 */
public class Vibrator
{
    private static final String TAG = "Vibrator";

    IVibratorService mService;
    private final Binder mToken = new Binder();

    /** @hide */
    public Vibrator()
    {
        mService = IVibratorService.Stub.asInterface(
                ServiceManager.getService("vibrator"));
    }

    /**
     * Check whether the hardware has a vibrator.  Returns true if a vibrator
     * exists, else false.
     */
    public boolean hasVibrator() {
        if (mService == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator service.");
            return false;
        }
        try {
            return mService.hasVibrator();
        } catch (RemoteException e) {
        }
        return false;
    }
    
    /**
     * Turn the vibrator on.
     *
     * @param milliseconds The number of milliseconds to vibrate.
     */
    public void vibrate(long milliseconds)
    {
        if (mService == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator service.");
            return;
        }
        try {
            mService.vibrate(milliseconds, mToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to vibrate.", e);
        }
    }

    /**
     * Vibrate with a given pattern.
     *
     * <p>
     * Pass in an array of ints that are the durations for which to turn on or off
     * the vibrator in milliseconds.  The first value indicates the number of milliseconds
     * to wait before turning the vibrator on.  The next value indicates the number of milliseconds
     * for which to keep the vibrator on before turning it off.  Subsequent values alternate
     * between durations in milliseconds to turn the vibrator off or to turn the vibrator on.
     * </p><p>
     * To cause the pattern to repeat, pass the index into the pattern array at which
     * to start the repeat, or -1 to disable repeating.
     * </p>
     *
     * @param pattern an array of longs of times for which to turn the vibrator on or off.
     * @param repeat the index into pattern at which to repeat, or -1 if
     *        you don't want to repeat.
     */
    public void vibrate(long[] pattern, int repeat)
    {
        if (mService == null) {
            Log.w(TAG, "Failed to vibrate; no vibrator service.");
            return;
        }
        // catch this here because the server will do nothing.  pattern may
        // not be null, let that be checked, because the server will drop it
        // anyway
        if (repeat < pattern.length) {
            try {
                mService.vibratePattern(pattern, repeat, mToken);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to vibrate.", e);
            }
        } else {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    /**
     * Turn the vibrator off.
     */
    public void cancel()
    {
        if (mService == null) {
            return;
        }
        try {
            mService.cancelVibrate(mToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to cancel vibration.", e);
        }
    }
}
