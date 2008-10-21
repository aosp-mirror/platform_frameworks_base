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

package com.android.internal.os;

import android.os.Handler;
import android.os.HandlerInterface;
import android.os.Looper;

/**
 * Handy class for starting a new thread containing a Handler
 * @hide
 * @deprecated
 */
public class HandlerThread extends Thread
{
    Runnable mSetup;
    HandlerInterface mhi;
    Handler mh;
    Throwable mtr;
    final Object mMonitor = new Object();

    public
    HandlerThread(HandlerInterface h, Runnable setup, String name)
    {
        super(name);

        mhi = h;
        mSetup = setup;

        synchronized (mMonitor) {
            start();    
            while (mh == null && mtr == null) {
                try {
                    mMonitor.wait();
                } catch (InterruptedException ex) {
                }
            }
        }

        if (mtr != null) {
            throw new RuntimeException("exception while starting", mtr);
        }
    }

    @Override
    public void
    run()
    {
        synchronized(mMonitor) {
            try {
                Looper.prepare();
                mh = new HandlerHelper (mhi);

                if (mSetup != null) {
                    mSetup.run();
                    mSetup = null;
                }
            } catch (RuntimeException exc) {
                mtr = exc;
            }

            mMonitor.notify();
        }

        if (mtr == null) {
            Looper.loop();
        }
    }

    public Handler
    getHandler()
    {
        return mh;
    }

}

