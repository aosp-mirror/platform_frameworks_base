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
 */
public class CandidateProviderMetric {

    private int mCandidateUid = -1;
    private long mStartTimeNanoseconds = -1;
    private long mQueryFinishTimeNanoseconds = -1;

    private int mProviderQueryStatus = -1;

    public CandidateProviderMetric(long startTime, long queryFinishTime, int providerQueryStatus,
            int candidateUid) {
        this.mStartTimeNanoseconds = startTime;
        this.mQueryFinishTimeNanoseconds = queryFinishTime;
        this.mProviderQueryStatus = providerQueryStatus;
        this.mCandidateUid = candidateUid;
    }

    public CandidateProviderMetric(){}

    public void setStartTimeNanoseconds(long startTimeNanoseconds) {
        this.mStartTimeNanoseconds = startTimeNanoseconds;
    }

    public void setQueryFinishTimeNanoseconds(long queryFinishTimeNanoseconds) {
        this.mQueryFinishTimeNanoseconds = queryFinishTimeNanoseconds;
    }

    public void setProviderQueryStatus(int providerQueryStatus) {
        this.mProviderQueryStatus = providerQueryStatus;
    }

    public void setCandidateUid(int candidateUid) {
        this.mCandidateUid = candidateUid;
    }

    public long getStartTimeNanoseconds() {
        return this.mStartTimeNanoseconds;
    }

    public long getQueryFinishTimeNanoseconds() {
        return this.mQueryFinishTimeNanoseconds;
    }

    public int getProviderQueryStatus() {
        return this.mProviderQueryStatus;
    }

    public int getCandidateUid() {
        return this.mCandidateUid;
    }

    /**
     * Returns the latency in microseconds for the query phase.
     */
    public int getQueryLatencyMs() {
        return (int) ((this.getQueryFinishTimeNanoseconds()
                - this.getStartTimeNanoseconds()) / 1000);
    }

}
