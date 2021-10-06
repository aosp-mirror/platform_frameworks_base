/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors.face;

import android.content.Context;
import android.hardware.face.FaceManager;
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import java.io.PrintWriter;
import java.util.ArrayDeque;

/**
 * Keep a short historical buffer of stats, with an aggregated usage time.
 */

public class UsageStats {
    private static final int EVENT_LOG_SIZE = 100;

    /**
     * Events for bugreports.
     */
    public static final class AuthenticationEvent {
        private long mStartTime;
        private long mLatency;
        // Only valid if mError is 0
        private boolean mAuthenticated;
        private int mError;
        // Only valid if mError is ERROR_VENDOR
        private int mVendorError;
        private int mUser;

        public AuthenticationEvent(long startTime, long latency, boolean authenticated, int error,
                int vendorError, int user) {
            mStartTime = startTime;
            mLatency = latency;
            mAuthenticated = authenticated;
            mError = error;
            mVendorError = vendorError;
            mUser = user;
        }

        public String toString(Context context) {
            return "Start: " + mStartTime
                    + "\tLatency: " + mLatency
                    + "\tAuthenticated: " + mAuthenticated
                    + "\tError: " + mError
                    + "\tVendorCode: " + mVendorError
                    + "\tUser: " + mUser
                    + "\t" + FaceManager.getErrorString(context, mError, mVendorError);
        }
    }

    private Context mContext;
    private ArrayDeque<AuthenticationEvent> mAuthenticationEvents;

    private int mAcceptCount;
    private int mRejectCount;
    private SparseIntArray mErrorCount;

    private long mAcceptLatency;
    private long mRejectLatency;
    private SparseLongArray mErrorLatency;

    public UsageStats(Context context) {
        mAuthenticationEvents = new ArrayDeque<>();
        mErrorCount = new SparseIntArray();
        mErrorLatency = new SparseLongArray();
        mContext = context;
    }

    public void addEvent(AuthenticationEvent event) {
        if (mAuthenticationEvents.size() >= EVENT_LOG_SIZE) {
            mAuthenticationEvents.removeFirst();
        }
        mAuthenticationEvents.add(event);

        if (event.mAuthenticated) {
            mAcceptCount++;
            mAcceptLatency += event.mLatency;
        } else if (event.mError == 0) {
            mRejectCount++;
            mRejectLatency += event.mLatency;
        } else {
            mErrorCount.put(event.mError, mErrorCount.get(event.mError, 0) + 1);
            mErrorLatency.put(event.mError, mErrorLatency.get(event.mError, 0L) + event.mLatency);
        }
    }

    public void print(PrintWriter pw) {
        pw.println("Events since last reboot: " + mAuthenticationEvents.size());
        for (AuthenticationEvent event : mAuthenticationEvents) {
            pw.println(event.toString(mContext));
        }

        // Dump aggregated usage stats
        pw.println("Accept\tCount: " + mAcceptCount + "\tLatency: " + mAcceptLatency
                + "\tAverage: " + (mAcceptCount > 0 ? mAcceptLatency / mAcceptCount : 0));
        pw.println("Reject\tCount: " + mRejectCount + "\tLatency: " + mRejectLatency
                + "\tAverage: " + (mRejectCount > 0 ? mRejectLatency / mRejectCount : 0));

        for (int i = 0; i < mErrorCount.size(); i++) {
            final int key = mErrorCount.keyAt(i);
            final int count = mErrorCount.get(i);
            pw.println("Error" + key + "\tCount: " + count
                    + "\tLatency: " + mErrorLatency.get(key, 0L)
                    + "\tAverage: " + (count > 0 ? mErrorLatency.get(key, 0L) / count : 0)
                    + "\t" + FaceManager.getErrorString(mContext, key, 0 /* vendorCode */));
        }
    }
}
