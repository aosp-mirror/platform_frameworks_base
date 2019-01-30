/*
 * Copyright (C) 2018 The Android Open Source Project
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


package com.android.server.incident;

import android.os.Handler;
import android.os.IBinder;

import java.util.ArrayList;

/**
 * Class to enqueue work until the system is ready.
 */
class RequestQueue {
    /*
     * All fields are protected by synchronized (mPending)
     */

    /**
     * Requests that we can't start yet because system server isn't booted enough yet.
     * Set to null when we have started.
     */
    private ArrayList<Rec> mPending = new ArrayList();

    /**
     * Where to run the requests.
     */
    private final Handler mHandler;

    /**
     * Whether someone has called start() yet.
     */
    private boolean mStarted;

    /**
     * Queue item.
     */
    private class Rec {
        /**
         * Key for the record.
         */
        public final IBinder key;

        /**
         * True / false pairs will be elided by enqueue().
         */
        public final boolean value;

        /**
         * The runnable to run.
         */
        public final Runnable runnable;

        /**
         * Constructor
         */
        Rec(IBinder key, boolean value, Runnable runnable) {
            this.key = key;
            this.value = value;
            this.runnable = runnable;
        }
    }

    /**
     * Handler on the main thread.
     */
    private final Runnable mWorker = new Runnable() {
        @Override
        public void run() {
            ArrayList<Rec> copy = null;
            synchronized (mPending) {
                if (mPending.size() > 0) {
                    copy = new ArrayList<Rec>(mPending);
                    mPending.clear();
                }
            }
            if (copy != null) {
                final int size = copy.size();
                for (int i = 0; i < size; i++) {
                    copy.get(i).runnable.run();
                }
            }
        }
    };

    /**
     * Construct RequestQueue.
     *
     * @param handler Handler to use.
     */
    RequestQueue(Handler handler) {
        mHandler = handler;
    }

    /**
     * We're now ready to go.  Start any previously pending runnables.
     */
    public void start() {
        synchronized (mPending) {
            if (!mStarted) {
                if (mPending.size() > 0) {
                    mHandler.post(mWorker);
                }
                mStarted = true;
            }
        }
    }

    /**
     * If we can run this now, then do it on the Handler provided in the constructor.
     * If not, then enqueue it until start is called.
     *
     * The queue will elide keys with pairs of true/false values, so the user doesn't
     * see confirmations that were previously canceled.
     */
    public void enqueue(IBinder key, boolean value, Runnable runnable) {
        synchronized (mPending) {
            boolean skip = false;
            if (!value) {
                for (int i = mPending.size() - 1; i >= 0; i--) {
                    final Rec r = mPending.get(i);
                    if (r.key == key) {
                        if (r.value) {
                            skip = true;
                            mPending.remove(i);
                            break;
                        }
                    }
                }
            }
            if (!skip) {
                mPending.add(new Rec(key, value, runnable));
            }
            if (mStarted) {
                // Already started. Post now.
                mHandler.post(mWorker);
            }
        }
    }
}

