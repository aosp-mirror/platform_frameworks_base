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

package android.net.http;

import android.content.Context;
import android.os.SystemClock;

import java.lang.Thread;

/**
 * {@hide}
 */
class ConnectionThread extends Thread {

    static final int WAIT_TIMEOUT = 5000;
    static final int WAIT_TICK = 1000;

    // Performance probe
    long mCurrentThreadTime;
    long mTotalThreadTime;

    private boolean mWaiting;
    private volatile boolean mRunning = true;
    private Context mContext;
    private RequestQueue.ConnectionManager mConnectionManager;
    private RequestFeeder mRequestFeeder;

    private int mId;
    Connection mConnection;

    ConnectionThread(Context context,
                     int id,
                     RequestQueue.ConnectionManager connectionManager,
                     RequestFeeder requestFeeder) {
        super();
        mContext = context;
        setName("http" + id);
        mId = id;
        mConnectionManager = connectionManager;
        mRequestFeeder = requestFeeder;
    }

    void requestStop() {
        synchronized (mRequestFeeder) {
            mRunning = false;
            mRequestFeeder.notify();
        }
    }

    /**
     * Loop until app shutdown. Runs connections in priority
     * order.
     */
    public void run() {
        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_DEFAULT +
                android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);

        // these are used to get performance data. When it is not in the timing,
        // mCurrentThreadTime is 0. When it starts timing, mCurrentThreadTime is
        // first set to -1, it will be set to the current thread time when the
        // next request starts.
        mCurrentThreadTime = 0;
        mTotalThreadTime = 0;

        while (mRunning) {
            if (mCurrentThreadTime == -1) {
                mCurrentThreadTime = SystemClock.currentThreadTimeMillis();
            }

            Request request;

            /* Get a request to process */
            request = mRequestFeeder.getRequest();

            /* wait for work */
            if (request == null) {
                synchronized(mRequestFeeder) {
                    if (HttpLog.LOGV) HttpLog.v("ConnectionThread: Waiting for work");
                    mWaiting = true;
                    try {
                        mRequestFeeder.wait();
                    } catch (InterruptedException e) {
                    }
                    mWaiting = false;
                    if (mCurrentThreadTime != 0) {
                        mCurrentThreadTime = SystemClock
                                .currentThreadTimeMillis();
                    }
                }
            } else {
                if (HttpLog.LOGV) HttpLog.v("ConnectionThread: new request " +
                                            request.mHost + " " + request );

                mConnection = mConnectionManager.getConnection(mContext,
                        request.mHost);
                mConnection.processRequests(request);
                if (mConnection.getCanPersist()) {
                    if (!mConnectionManager.recycleConnection(mConnection)) {
                        mConnection.closeConnection();
                    }
                } else {
                    mConnection.closeConnection();
                }
                mConnection = null;

                if (mCurrentThreadTime > 0) {
                    long start = mCurrentThreadTime;
                    mCurrentThreadTime = SystemClock.currentThreadTimeMillis();
                    mTotalThreadTime += mCurrentThreadTime - start;
                }
            }

        }
    }

    public synchronized String toString() {
        String con = mConnection == null ? "" : mConnection.toString();
        String active = mWaiting ? "w" : "a";
        return "cid " + mId + " " + active + " "  + con;
    }

}
