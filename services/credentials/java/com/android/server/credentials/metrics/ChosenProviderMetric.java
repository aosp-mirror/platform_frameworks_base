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
 * The central chosen provider metric object that mimics our defined metric setup.
 */
public class ChosenProviderMetric {

    private int mChosenUid = -1;
    private long mStartTimeNanoseconds = -1;
    private long mQueryFinishTimeNanoseconds = -1;
    private long mFinalFinishTimeNanoseconds = -1;
    private int mChosenProviderStatus = -1;

    public ChosenProviderMetric() {}

    public int getChosenUid() {
        return mChosenUid;
    }

    public void setChosenUid(int chosenUid) {
        mChosenUid = chosenUid;
    }

    public long getStartTimeNanoseconds() {
        return mStartTimeNanoseconds;
    }

    public void setStartTimeNanoseconds(long startTimeNanoseconds) {
        mStartTimeNanoseconds = startTimeNanoseconds;
    }

    public long getQueryFinishTimeNanoseconds() {
        return mQueryFinishTimeNanoseconds;
    }

    public void setQueryFinishTimeNanoseconds(long queryFinishTimeNanoseconds) {
        mQueryFinishTimeNanoseconds = queryFinishTimeNanoseconds;
    }

    public long getFinalFinishTimeNanoseconds() {
        return mFinalFinishTimeNanoseconds;
    }

    public void setFinalFinishTimeNanoseconds(long finalFinishTimeNanoseconds) {
        mFinalFinishTimeNanoseconds = finalFinishTimeNanoseconds;
    }

    public int getChosenProviderStatus() {
        return mChosenProviderStatus;
    }

    public void setChosenProviderStatus(int chosenProviderStatus) {
        mChosenProviderStatus = chosenProviderStatus;
    }

    /**
     * Returns the full provider (invocation to response) latency in microseconds.
     */
    public int getEntireProviderLatencyMs() {
        return (int) ((this.getFinalFinishTimeNanoseconds()
                - this.getStartTimeNanoseconds()) / 1000);
    }

    // TODO get post click final phase and re-add the query phase time to metric

    /**
     * Returns the end of query to response phase latency in microseconds.
     */
    public int getFinalPhaseLatencyMs() {
        return (int) ((this.getFinalFinishTimeNanoseconds()
                - this.getQueryFinishTimeNanoseconds()) / 1000);
    }

}
