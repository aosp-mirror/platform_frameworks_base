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

package com.android.server;

import android.os.SystemClock;

import android.annotation.UnsupportedAppUsage;
import android.os.ConditionVariable;

/**
 * Utility class that you can call on with a timeout, and get called back
 * after a given time, dealing correctly with restarting the timeout.
 *
 * <p>For example, this class is used by the android.os.Vibrator class.
 */
abstract class ResettableTimeout
{
    /**
     * Override this do what you need to do when it's starting
     * This is called with the monitor on this method held, so be careful.
     *
     * @param alreadyOn is true if it's currently running
     */
    public abstract void on(boolean alreadyOn);

    /**
     * Override this to do what you need to do when it's stopping.
     * This is called with the monitor on this method held, so be careful.
     */
    public abstract void off();

    /**
     * Does the following steps.
     * <p>1. Call on()</p>
     * <p>2. Start the timer.</p>
     * <p>3. At the timeout, calls off()<p>
     * <p>If you call this again, the timeout is reset to the new one</p>
     */
    public void go(long milliseconds)
    {
        synchronized (this) {
            mOffAt = SystemClock.uptimeMillis() + milliseconds;

            boolean alreadyOn;

            // By starting the thread first and waiting, we ensure that if the
            // thread to stop it can't start, we don't turn the vibrator on
            // forever.  This still isn't really sufficient, because we don't
            // have another processor watching us.  We really should have a
            // service for this in case our process crashes.
            if (mThread == null) {
                alreadyOn = false;
                mLock.close();
                mThread = new T();
                mThread.start();
                mLock.block();
                mOffCalled = false;
            } else {
                alreadyOn = true;
                // poke the thread so it gets the new timeout.
                mThread.interrupt();
            }
            on(alreadyOn);
        }
    }

    /**
     * Cancel the timeout and call off now.
     */
    public void cancel()
    {
        synchronized (this) {
            mOffAt = 0;
            if (mThread != null) {
                mThread.interrupt();
                mThread = null;
            }
            if (!mOffCalled) {
                mOffCalled = true;
                off();
            }
        }
    }

    private class T extends Thread
    {
        public void run()
        {
            mLock.open();
            while (true) {
                long diff;
                synchronized (this) {
                    diff = mOffAt - SystemClock.uptimeMillis();
                    if (diff <= 0) {
                        mOffCalled = true;
                        off();
                        mThread = null;
                        break;
                    }
                }
                try {
                    sleep(diff);
                }
                catch (InterruptedException e) {
                }
            }
        }
    }

    @UnsupportedAppUsage
    private ConditionVariable mLock = new ConditionVariable();

    // turn it off at this time.
    @UnsupportedAppUsage
    private volatile long mOffAt;
    private volatile boolean mOffCalled;

    private Thread mThread;

}

