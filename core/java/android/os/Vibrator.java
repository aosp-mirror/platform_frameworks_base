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

/**
 * Class that operates the vibrator on the device.
 * <p>
 * If your process exits, any vibration you started with will stop.
 */
public class Vibrator
{
    IVibratorService mService;
    private final Binder mToken = new Binder();

    /** @hide */
    public Vibrator()
    {
        mService = IVibratorService.Stub.asInterface(
                ServiceManager.getService("vibrator"));
    }

    /**
     * Turn the vibrator on.
     *
     * @param milliseconds How long to vibrate for.
     */
    public void vibrate(long milliseconds)
    {
        try {
            mService.vibrate(milliseconds, mToken);
        } catch (RemoteException e) {
        }
    }

    /**
     * Vibrate with a given pattern.
     *
     * <p>
     * Pass in an array of ints that are the times at which to turn on or off
     * the vibrator.  The first one is how long to wait before turning it on,
     * and then after that it alternates.  If you want to repeat, pass the
     * index into the pattern at which to start the repeat.
     *
     * @param pattern an array of longs of times to turn the vibrator on or off.
     * @param repeat the index into pattern at which to repeat, or -1 if
     *        you don't want to repeat.
     */
    public void vibrate(long[] pattern, int repeat)
    {
        // catch this here because the server will do nothing.  pattern may
        // not be null, let that be checked, because the server will drop it
        // anyway
        if (repeat < pattern.length) {
            try {
                mService.vibratePattern(pattern, repeat, mToken);
            } catch (RemoteException e) {
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
        try {
            mService.cancelVibrate(mToken);
        } catch (RemoteException e) {
        }
    }
}
