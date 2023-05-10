/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.credentials.metrics;

import android.util.Slog;

import com.android.server.credentials.MetricUtilities;
import com.android.server.credentials.metrics.shared.ResponseCollective;

import java.util.Map;

/**
 * The central candidate provider metric object that mimics our defined metric setup.
 * Some types are redundant across these metric collectors, but that has debug use-cases as
 * these data-types are available at different moments of the flow (and typically, one can feed
 * into the next).
 */
public class CandidatePhaseMetric {

    private static final String TAG = "CandidateProviderMetric";
    // The session id of this provider metric
    private final int mSessionIdProvider;
    // Indicates if this provider returned from the query phase, default false
    private boolean mQueryReturned = false;

    // The candidate provider uid
    private int mCandidateUid = -1;

    // Raw timestamp in nanoseconds, will be converted to microseconds for logging

    //For reference, the initial log timestamp when the service started running the API call
    private long mServiceBeganTimeNanoseconds = -1;
    // The moment when the query phase began
    private long mStartQueryTimeNanoseconds = -1;
    // The moment when the query phase ended
    private long mQueryFinishTimeNanoseconds = -1;

    // The status of this particular provider
    private int mProviderQueryStatus = -1;
    // Indicates if an exception was thrown by this provider, false by default
    private boolean mHasException = false;
    // Indicates the framework only exception belonging to this provider
    private String mFrameworkException = "";

    // Stores the response credential information, as well as the response entry information which
    // by default, contains empty info
    private ResponseCollective mResponseCollective = new ResponseCollective(Map.of(), Map.of());

    public CandidatePhaseMetric(int sessionIdTrackTwo) {
        mSessionIdProvider = sessionIdTrackTwo;
    }

    /* ---------- Latencies ---------- */

    /* -- Timestamps -- */

    public void setServiceBeganTimeNanoseconds(long serviceBeganTimeNanoseconds) {
        mServiceBeganTimeNanoseconds = serviceBeganTimeNanoseconds;
    }

    public void setStartQueryTimeNanoseconds(long startQueryTimeNanoseconds) {
        mStartQueryTimeNanoseconds = startQueryTimeNanoseconds;
    }

    public void setQueryFinishTimeNanoseconds(long queryFinishTimeNanoseconds) {
        mQueryFinishTimeNanoseconds = queryFinishTimeNanoseconds;
    }

    public long getServiceBeganTimeNanoseconds() {
        return mServiceBeganTimeNanoseconds;
    }

    public long getStartQueryTimeNanoseconds() {
        return mStartQueryTimeNanoseconds;
    }

    public long getQueryFinishTimeNanoseconds() {
        return mQueryFinishTimeNanoseconds;
    }

    /* -- Actual time delta latencies (for local utility) -- */

    /**
     * Returns the latency in microseconds for the query phase.
     */
    public int getQueryLatencyMicroseconds() {
        return (int) ((getQueryFinishTimeNanoseconds()
                - getStartQueryTimeNanoseconds()) / 1000);
    }

    /* --- Time Stamp Conversion to Microseconds from Reference --- */

    /**
     * We collect raw timestamps in nanoseconds for ease of collection. However, given the scope
     * of our logging timeframe, and size considerations of the metric, we require these to give us
     * the microsecond timestamps from the start reference point.
     *
     * @param specificTimestamp the timestamp to consider, must be greater than the reference
     * @return the microsecond integer timestamp from service start to query began
     */
    public int getTimestampFromReferenceStartMicroseconds(long specificTimestamp) {
        if (specificTimestamp < mServiceBeganTimeNanoseconds) {
            Slog.i(TAG, "The timestamp is before service started, falling back to default int");
            return MetricUtilities.DEFAULT_INT_32;
        }
        return (int) ((specificTimestamp
                - mServiceBeganTimeNanoseconds) / 1000);
    }

    /* ------------- Provider Query Status ------------ */

    public void setProviderQueryStatus(int providerQueryStatus) {
        mProviderQueryStatus = providerQueryStatus;
    }

    public int getProviderQueryStatus() {
        return mProviderQueryStatus;
    }

    /* -------------- Candidate Uid ---------------- */

    public void setCandidateUid(int candidateUid) {
        mCandidateUid = candidateUid;
    }

    public int getCandidateUid() {
        return mCandidateUid;
    }

    /* -------------- Session Id ---------------- */

    public int getSessionIdProvider() {
        return mSessionIdProvider;
    }

    /* -------------- Query Returned Status ---------------- */

    public void setQueryReturned(boolean queryReturned) {
        mQueryReturned = queryReturned;
    }

    public boolean isQueryReturned() {
        return mQueryReturned;
    }

    /* -------------- Has Exception Status ---------------- */

    public void setHasException(boolean hasException) {
        mHasException = hasException;
    }

    public boolean isHasException() {
        return mHasException;
    }

    /* -------------- The Entries and Responses Gathered ---------------- */
    public void setResponseCollective(ResponseCollective responseCollective) {
        mResponseCollective = responseCollective;
    }

    public ResponseCollective getResponseCollective() {
        return mResponseCollective;
    }

    /* ------ Framework Exception for this Candidate ------ */

    public void setFrameworkException(String frameworkException) {
        mFrameworkException = frameworkException;
    }

    public String getFrameworkException() {
        return mFrameworkException;
    }
}
