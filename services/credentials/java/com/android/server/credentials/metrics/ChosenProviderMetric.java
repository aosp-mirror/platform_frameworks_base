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

import android.util.Log;

import com.android.server.credentials.MetricUtilities;

/**
 * The central chosen provider metric object that mimics our defined metric setup.
 * TODO(b/270403549) - iterate on this in V3+
 */
public class ChosenProviderMetric {

    // TODO(b/270403549) - applies elsewhere, likely removed or replaced with a count-index (1,2,3)
    private static final String TAG = "ChosenProviderMetric";
    private int mChosenUid = -1;

    // Latency figures typically fed in from prior CandidateProviderMetric

    private int mPreQueryPhaseLatencyMicroseconds = -1;
    private int mQueryPhaseLatencyMicroseconds = -1;

    // Timestamps kept in raw nanoseconds. Expected to be converted to microseconds from using
    // reference 'mServiceBeganTimeNanoseconds' during metric log point.

    private long mServiceBeganTimeNanoseconds = -1;
    private long mQueryStartTimeNanoseconds = -1;
    private long mUiCallStartTimeNanoseconds = -1;
    private long mUiCallEndTimeNanoseconds = -1;
    private long mFinalFinishTimeNanoseconds = -1;
    private int mChosenProviderStatus = -1;

    public ChosenProviderMetric() {
    }

    /* ------------------- UID ------------------- */

    public int getChosenUid() {
        return mChosenUid;
    }

    public void setChosenUid(int chosenUid) {
        mChosenUid = chosenUid;
    }

    /* ---------------- Latencies ------------------ */


    /* ----- Direct Latencies ------- */

    /**
     * In order for a chosen provider to be selected, the call must have successfully begun.
     * Thus, the {@link PreCandidateMetric} can directly pass this initial latency figure into
     * this chosen provider metric.
     *
     * @param preQueryPhaseLatencyMicroseconds the millisecond latency for the service start,
     *                                         typically passed in through the
     *                                         {@link PreCandidateMetric}
     */
    public void setPreQueryPhaseLatencyMicroseconds(int preQueryPhaseLatencyMicroseconds) {
        mPreQueryPhaseLatencyMicroseconds = preQueryPhaseLatencyMicroseconds;
    }

    /**
     * In order for a chosen provider to be selected, a candidate provider must exist. The
     * candidate provider can directly pass the final latency figure into this chosen provider
     * metric.
     *
     * @param queryPhaseLatencyMicroseconds the millisecond latency for the query phase, typically
     *                                      passed in through the {@link CandidateProviderMetric}
     */
    public void setQueryPhaseLatencyMicroseconds(int queryPhaseLatencyMicroseconds) {
        mQueryPhaseLatencyMicroseconds = queryPhaseLatencyMicroseconds;
    }

    public int getPreQueryPhaseLatencyMicroseconds() {
        return mPreQueryPhaseLatencyMicroseconds;
    }

    public int getQueryPhaseLatencyMicroseconds() {
        return mQueryPhaseLatencyMicroseconds;
    }

    public int getUiPhaseLatencyMicroseconds() {
        return (int) ((this.mUiCallEndTimeNanoseconds
                - this.mUiCallStartTimeNanoseconds) / 1000);
    }

    /**
     * Returns the full provider (invocation to response) latency in microseconds. Expects the
     * start time to be provided, such as from {@link CandidateProviderMetric}.
     */
    public int getEntireProviderLatencyMicroseconds() {
        return (int) ((this.mFinalFinishTimeNanoseconds
                - this.mQueryStartTimeNanoseconds) / 1000);
    }

    /**
     * Returns the full (platform invoked to response) latency in microseconds. Expects the
     * start time to be provided, such as from {@link PreCandidateMetric}.
     */
    public int getEntireLatencyMicroseconds() {
        return (int) ((this.mFinalFinishTimeNanoseconds
                - this.mServiceBeganTimeNanoseconds) / 1000);
    }

    /* ----- Timestamps for Latency ----- */

    /**
     * In order for a chosen provider to be selected, the call must have successfully begun.
     * Thus, the {@link PreCandidateMetric} can directly pass this initial timestamp into this
     * chosen provider metric.
     *
     * @param serviceBeganTimeNanoseconds the timestamp moment when the platform was called,
     *                                    typically passed in through the {@link PreCandidateMetric}
     */
    public void setServiceBeganTimeNanoseconds(long serviceBeganTimeNanoseconds) {
        mServiceBeganTimeNanoseconds = serviceBeganTimeNanoseconds;
    }

    public void setQueryStartTimeNanoseconds(long queryStartTimeNanoseconds) {
        mQueryStartTimeNanoseconds = queryStartTimeNanoseconds;
    }

    public void setUiCallStartTimeNanoseconds(long uiCallStartTimeNanoseconds) {
        this.mUiCallStartTimeNanoseconds = uiCallStartTimeNanoseconds;
    }

    public void setUiCallEndTimeNanoseconds(long uiCallEndTimeNanoseconds) {
        this.mUiCallEndTimeNanoseconds = uiCallEndTimeNanoseconds;
    }

    public void setFinalFinishTimeNanoseconds(long finalFinishTimeNanoseconds) {
        mFinalFinishTimeNanoseconds = finalFinishTimeNanoseconds;
    }

    public long getServiceBeganTimeNanoseconds() {
        return mServiceBeganTimeNanoseconds;
    }

    public long getQueryStartTimeNanoseconds() {
        return mQueryStartTimeNanoseconds;
    }

    public long getUiCallStartTimeNanoseconds() {
        return mUiCallStartTimeNanoseconds;
    }

    public long getUiCallEndTimeNanoseconds() {
        return mUiCallEndTimeNanoseconds;
    }

    public long getFinalFinishTimeNanoseconds() {
        return mFinalFinishTimeNanoseconds;
    }

    /* --- Time Stamp Conversion to Microseconds --- */

    /**
     * We collect raw timestamps in nanoseconds for ease of collection. However, given the scope
     * of our logging timeframe, and size considerations of the metric, we require these to give us
     * the microsecond timestamps from the start reference point.
     *
     * @param specificTimestamp the timestamp to consider, must be greater than the reference
     * @return the microsecond integer timestamp from service start to query began
     */
    public int getTimestampFromReferenceStartMicroseconds(long specificTimestamp) {
        if (specificTimestamp < this.mServiceBeganTimeNanoseconds) {
            Log.i(TAG, "The timestamp is before service started, falling back to default int");
            return MetricUtilities.DEFAULT_INT_32;
        }
        return (int) ((specificTimestamp
                - this.mServiceBeganTimeNanoseconds) / 1000);
    }



    /* ----------- Provider Status -------------- */

    public int getChosenProviderStatus() {
        return mChosenProviderStatus;
    }

    public void setChosenProviderStatus(int chosenProviderStatus) {
        mChosenProviderStatus = chosenProviderStatus;
    }
}
