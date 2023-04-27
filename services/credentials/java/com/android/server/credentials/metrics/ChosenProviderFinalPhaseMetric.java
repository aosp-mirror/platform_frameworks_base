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
 * The central chosen provider metric object that mimics our defined metric setup. This is used
 * in the final phase of the flow and emits final status metrics.
 * Some types are redundant across these metric collectors, but that has debug use-cases as
 * these data-types are available at different moments of the flow (and typically, one can feed
 * into the next).
 */
public class ChosenProviderFinalPhaseMetric {
    private static final String TAG = "ChosenFinalPhaseMetric";
    // The session id associated with this API call, used to unite split emits, for the flow
    // where we know the calling app
    private final int mSessionIdTrackOne;
    // The session id associated with this API call, used to unite split emits, for the flow
    // where we know the provider apps
    private final int mSessionIdTrackTwo;
    // Reveals if the UI was returned, false by default
    private boolean mUiReturned = false;
    private int mChosenUid = -1;

    // Latency figures typically fed in from prior CandidateProviderMetric

    private int mPreQueryPhaseLatencyMicroseconds = -1;
    private int mQueryPhaseLatencyMicroseconds = -1;

    // Timestamps kept in raw nanoseconds. Expected to be converted to microseconds from using
    // reference 'mServiceBeganTimeNanoseconds' during metric log point

    // Kept for local reference purposes, the initial timestamp of the service called passed in
    private long mServiceBeganTimeNanoseconds = -1;
    // The first query timestamp, which upon emit is normalized to microseconds using the reference
    // start timestamp
    private long mQueryStartTimeNanoseconds = -1;
    // The timestamp at query end, which upon emit will be normalized to microseconds with reference
    private long mQueryEndTimeNanoseconds = -1;
    // The UI call timestamp, which upon emit will be normalized to microseconds using reference
    private long mUiCallStartTimeNanoseconds = -1;
    // The UI return timestamp, which upon emit will be normalized to microseconds using reference
    private long mUiCallEndTimeNanoseconds = -1;
    // The final finish timestamp, which upon emit will be normalized to microseconds with reference
    private long mFinalFinishTimeNanoseconds = -1;
    // The status of this provider after selection

    // Other General Information, such as final api status, provider status, entry info, etc...

    private int mChosenProviderStatus = -1;
    // Indicates if an exception was thrown by this provider, false by default
    private boolean mHasException = false;
    // Indicates a framework only exception that occurs in the final phase of the flow
    private String mFrameworkException = "";

    // Stores the response credential information, as well as the response entry information which
    // by default, contains empty info
    private ResponseCollective mResponseCollective = new ResponseCollective(Map.of(), Map.of());


    public ChosenProviderFinalPhaseMetric(int sessionIdTrackOne, int sessionIdTrackTwo) {
        mSessionIdTrackOne = sessionIdTrackOne;
        mSessionIdTrackTwo = sessionIdTrackTwo;
    }

    /* ------------------- UID ------------------- */

    public int getChosenUid() {
        return mChosenUid;
    }

    public void setChosenUid(int chosenUid) {
        mChosenUid = chosenUid;
    }

    /* ---------------- Latencies ------------------ */


    /* ----- Direct Delta Latencies for Local Utility ------- */

    /**
     * In order for a chosen provider to be selected, the call must have successfully begun.
     * Thus, the {@link InitialPhaseMetric} can directly pass this initial latency figure into
     * this chosen provider metric.
     *
     * @param preQueryPhaseLatencyMicroseconds the millisecond latency for the service start,
     *                                         typically passed in through the
     *                                         {@link InitialPhaseMetric}
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
     *                                      passed in through the {@link CandidatePhaseMetric}
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
        return (int) ((mUiCallEndTimeNanoseconds
                - mUiCallStartTimeNanoseconds) / 1000);
    }

    /**
     * Returns the full provider (invocation to response) latency in microseconds. Expects the
     * start time to be provided, such as from {@link CandidatePhaseMetric}.
     */
    public int getEntireProviderLatencyMicroseconds() {
        return (int) ((mFinalFinishTimeNanoseconds
                - mQueryStartTimeNanoseconds) / 1000);
    }

    /**
     * Returns the full (platform invoked to response) latency in microseconds. Expects the
     * start time to be provided, such as from {@link InitialPhaseMetric}.
     */
    public int getEntireLatencyMicroseconds() {
        return (int) ((mFinalFinishTimeNanoseconds
                - mServiceBeganTimeNanoseconds) / 1000);
    }

    /* ----- Timestamps for Latency ----- */

    /**
     * In order for a chosen provider to be selected, the call must have successfully begun.
     * Thus, the {@link InitialPhaseMetric} can directly pass this initial timestamp into this
     * chosen provider metric.
     *
     * @param serviceBeganTimeNanoseconds the timestamp moment when the platform was called,
     *                                    typically passed in through the {@link InitialPhaseMetric}
     */
    public void setServiceBeganTimeNanoseconds(long serviceBeganTimeNanoseconds) {
        mServiceBeganTimeNanoseconds = serviceBeganTimeNanoseconds;
    }

    public void setQueryStartTimeNanoseconds(long queryStartTimeNanoseconds) {
        mQueryStartTimeNanoseconds = queryStartTimeNanoseconds;
    }

    public void setQueryEndTimeNanoseconds(long queryEndTimeNanoseconds) {
        mQueryEndTimeNanoseconds = queryEndTimeNanoseconds;
    }

    public void setUiCallStartTimeNanoseconds(long uiCallStartTimeNanoseconds) {
        mUiCallStartTimeNanoseconds = uiCallStartTimeNanoseconds;
    }

    public void setUiCallEndTimeNanoseconds(long uiCallEndTimeNanoseconds) {
        mUiCallEndTimeNanoseconds = uiCallEndTimeNanoseconds;
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

    public long getQueryEndTimeNanoseconds() {
        return mQueryEndTimeNanoseconds;
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

    /* --- Time Stamp Conversion to Microseconds from Reference Point --- */

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

    /* ----------- Provider Status -------------- */

    public int getChosenProviderStatus() {
        return mChosenProviderStatus;
    }

    public void setChosenProviderStatus(int chosenProviderStatus) {
        mChosenProviderStatus = chosenProviderStatus;
    }

    /* ----------- Session ID -------------- */

    public int getSessionIdTrackTwo() {
        return mSessionIdTrackTwo;
    }

    /* ----------- UI Returned Successfully -------------- */

    public void setUiReturned(boolean uiReturned) {
        mUiReturned = uiReturned;
    }

    public boolean isUiReturned() {
        return mUiReturned;
    }

    /* -------------- Has Exception ---------------- */

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

    /* -------------- Framework Exception ---------------- */

    public void setFrameworkException(String frameworkException) {
        mFrameworkException = frameworkException;
    }

    public String getFrameworkException() {
        return mFrameworkException;
    }
}
