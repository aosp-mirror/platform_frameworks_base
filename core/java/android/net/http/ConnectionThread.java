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

import org.apache.http.HttpHost;

import java.lang.Thread;

/**
 * {@hide}
 */
class ConnectionThread extends Thread {

    static final int WAIT_TIMEOUT = 5000;
    static final int WAIT_TICK = 1000;

    // Performance probe
    long mStartThreadTime;
    long mCurrentThreadTime;

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
                android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);

        mStartThreadTime = -1;
        mCurrentThreadTime = SystemClock.currentThreadTimeMillis();

        while (mRunning) {
            Request request;

            /* Get a request to process */
            request = mRequestFeeder.getRequest();

            /* wait for work */
            if (request == null) {
                synchronized(mRequestFeeder) {
                    if (HttpLog.LOGV) HttpLog.v("ConnectionThread: Waiting for work");
                    mWaiting = true;
                    try {
                        if (mStartThreadTime != -1) {
                            mCurrentThreadTime = SystemClock
                                    .currentThreadTimeMillis();
                        }
                        mRequestFeeder.wait();
                    } catch (InterruptedException e) {
                    }
                    mWaiting = false;
                }
            } else {
                if (HttpLog.LOGV) HttpLog.v("ConnectionThread: new request " +
                                            request.mHost + " " + request );

                HttpHost proxy = mConnectionManager.getProxyHost();

                HttpHost host;
                if (false) {
                    // Allow https proxy
                    host = proxy == null ? request.mHost : proxy;
                } else {
                    // Disallow https proxy -- tmob proxy server
                    // serves a request loop for https reqs
                    host = (proxy == null ||
                            request.mHost.getSchemeName().equals("https")) ?
                            request.mHost : proxy;
                }
                mConnection = mConnectionManager.getConnection(mContext, host);
                mConnection.processRequests(request);
                if (mConnection.getCanPersist()) {
                    if (!mConnectionManager.recycleConnection(host,
                                mConnection)) {
                        mConnection.closeConnection();
                    }
                } else {
                    mConnection.closeConnection();
                }
                mConnection = null;
            }

        }
    }

    public synchronized String toString() {
        String con = mConnection == null ? "" : mConnection.toString();
        String active = mWaiting ? "w" : "a";
        return "cid " + mId + " " + active + " "  + con;
    }

}
