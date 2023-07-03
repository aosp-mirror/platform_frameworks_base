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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This handles metrics collected prior to any remote calls to providers.
 * Some types are redundant across these metric collectors, but that has debug use-cases as
 * these data-types are available at different moments of the flow (and typically, one can feed
 * into the next).
 */
public class InitialPhaseMetric {
    private static final String TAG = "InitialPhaseMetric";

    // The api being called, default set to unknown
    private int mApiName = ApiName.UNKNOWN.getMetricCode();
    // The caller uid of the calling application, default to -1
    private int mCallerUid = -1;
    // The session id to unite multiple atom emits
    private final int mSessionIdCaller;

    // Raw timestamps in nanoseconds, *the only* one logged as such (i.e. 64 bits) since it is a
    // reference point.
    private long mCredentialServiceStartedTimeNanoseconds = -1;

    // A reference point to give this object utility to capture latency. Can be directly handed
    // over to the next latency object.
    private long mCredentialServiceBeginQueryTimeNanoseconds = -1;

    // Indicates if the origin was specified when making this API request
    private boolean mOriginSpecified = false;

    // Stores the deduped request information, particularly {"req":5}
    private Map<String, Integer> mRequestCounts = new LinkedHashMap<>();


    public InitialPhaseMetric(int sessionIdTrackOne) {
        mSessionIdCaller = sessionIdTrackOne;
    }

    /* ---------- Latencies ---------- */

    /* -- Direct Latency Utility -- */

    public int getServiceStartToQueryLatencyMicroseconds() {
        return (int) ((mCredentialServiceStartedTimeNanoseconds
                - mCredentialServiceBeginQueryTimeNanoseconds) / 1000);
    }

    /* -- Timestamps -- */

    public void setCredentialServiceStartedTimeNanoseconds(
            long credentialServiceStartedTimeNanoseconds
    ) {
        mCredentialServiceStartedTimeNanoseconds = credentialServiceStartedTimeNanoseconds;
    }

    public void setCredentialServiceBeginQueryTimeNanoseconds(
            long credentialServiceBeginQueryTimeNanoseconds) {
        mCredentialServiceBeginQueryTimeNanoseconds = credentialServiceBeginQueryTimeNanoseconds;
    }

    public long getCredentialServiceStartedTimeNanoseconds() {
        return mCredentialServiceStartedTimeNanoseconds;
    }

    public long getCredentialServiceBeginQueryTimeNanoseconds() {
        return mCredentialServiceBeginQueryTimeNanoseconds;
    }

    /* ------ ApiName ------ */

    public void setApiName(int apiName) {
        mApiName = apiName;
    }

    public int getApiName() {
        return mApiName;
    }

    /* ------ CallerUid ------ */

    public void setCallerUid(int callerUid) {
        mCallerUid = callerUid;
    }

    public int getCallerUid() {
        return mCallerUid;
    }

    /* ------ SessionId ------ */

    public int getSessionIdCaller() {
        return mSessionIdCaller;
    }

    /* ------ Count Request Class Types ------ */

    public int getCountRequestClassType() {
        return mRequestCounts.size();
    }

    /* ------ Origin Specified ------ */

    public void setOriginSpecified(boolean originSpecified) {
        mOriginSpecified = originSpecified;
    }

    public boolean isOriginSpecified() {
        return mOriginSpecified;
    }

    /* ------ Unique Request Counts Map Information ------ */

    public void setRequestCounts(Map<String, Integer> requestCounts) {
        mRequestCounts = requestCounts;
    }

    /**
     * Returns the unique, deduped, request classtypes for logging.
     * @return a string array for deduped classtypes
     */
    public String[] getUniqueRequestStrings() {
        String[] result = new String[mRequestCounts.keySet().size()];
        mRequestCounts.keySet().toArray(result);
        return result;
    }

    /**
     * Returns the unique, deduped, request classtype counts for logging.
     * @return a string array for deduped classtype counts
     */
    public int[] getUniqueRequestCounts() {
        return mRequestCounts.values().stream().mapToInt(Integer::intValue).toArray();
    }
}
