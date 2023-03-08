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

/**
 * The central candidate provider metric object that mimics our defined metric setup.
 * Some types are redundant across these metric collectors, but that has debug use-cases as
 * these data-types are available at different moments of the flow (and typically, one can feed
 * into the next).
 * TODO(b/270403549) - iterate on this in V3+
 */
public class CandidateProviderMetric {

    private static final String TAG = "CandidateProviderMetric";
    private int mCandidateUid = -1;

    // Raw timestamp in nanoseconds, will be converted to microseconds for logging

    private long mStartQueryTimeNanoseconds = -1;
    private long mQueryFinishTimeNanoseconds = -1;

    private int mProviderQueryStatus = -1;

    public CandidateProviderMetric() {
    }

    /* ---------- Latencies ---------- */

    public void setStartQueryTimeNanoseconds(long startQueryTimeNanoseconds) {
        this.mStartQueryTimeNanoseconds = startQueryTimeNanoseconds;
    }

    public void setQueryFinishTimeNanoseconds(long queryFinishTimeNanoseconds) {
        this.mQueryFinishTimeNanoseconds = queryFinishTimeNanoseconds;
    }

    public long getStartQueryTimeNanoseconds() {
        return this.mStartQueryTimeNanoseconds;
    }

    public long getQueryFinishTimeNanoseconds() {
        return this.mQueryFinishTimeNanoseconds;
    }

    /**
     * Returns the latency in microseconds for the query phase.
     */
    public int getQueryLatencyMicroseconds() {
        return (int) ((this.getQueryFinishTimeNanoseconds()
                - this.getStartQueryTimeNanoseconds()) / 1000);
    }

    // TODO (in direct next dependent CL, so this is transient) - add reference timestamp in micro
    // seconds for this too.

    /* ------------- Provider Query Status ------------ */

    public void setProviderQueryStatus(int providerQueryStatus) {
        this.mProviderQueryStatus = providerQueryStatus;
    }

    public int getProviderQueryStatus() {
        return this.mProviderQueryStatus;
    }

    /* -------------- Candidate Uid ---------------- */

    public void setCandidateUid(int candidateUid) {
        this.mCandidateUid = candidateUid;
    }

    public int getCandidateUid() {
        return this.mCandidateUid;
    }
}
