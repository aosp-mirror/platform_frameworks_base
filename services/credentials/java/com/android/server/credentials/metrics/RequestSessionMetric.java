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

import static com.android.server.credentials.MetricUtilities.DEFAULT_INT_32;
import static com.android.server.credentials.MetricUtilities.DELTA_EXCEPTION_CUT;
import static com.android.server.credentials.MetricUtilities.DELTA_RESPONSES_CUT;
import static com.android.server.credentials.MetricUtilities.generateMetricKey;
import static com.android.server.credentials.MetricUtilities.logApiCalledAuthenticationMetric;
import static com.android.server.credentials.MetricUtilities.logApiCalledCandidateGetMetric;
import static com.android.server.credentials.MetricUtilities.logApiCalledCandidatePhase;
import static com.android.server.credentials.MetricUtilities.logApiCalledFinalPhase;
import static com.android.server.credentials.MetricUtilities.logApiCalledNoUidFinal;

import android.annotation.NonNull;
import android.credentials.GetCredentialRequest;
import android.credentials.ui.UserSelectionDialogResult;
import android.util.Slog;

import com.android.server.credentials.ProviderSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides contextual metric collection for objects generated from classes such as
 * {@link com.android.server.credentials.GetRequestSession},
 * {@link com.android.server.credentials.CreateRequestSession},
 * and {@link com.android.server.credentials.ClearRequestSession} flows to isolate metric
 * collection from the core codebase. For any future additions to the RequestSession subclass
 * list, metric collection should be added to this file.
 */
public class RequestSessionMetric {
    private static final String TAG = "RequestSessionMetric";

    // As emits occur in sequential order, increment this counter and utilize
    protected int mSequenceCounter = 0;

    protected final InitialPhaseMetric mInitialPhaseMetric;
    protected final ChosenProviderFinalPhaseMetric
            mChosenProviderFinalPhaseMetric;
    protected List<CandidateBrowsingPhaseMetric> mCandidateBrowsingPhaseMetric = new ArrayList<>();
    // Specific aggregate candidate provider metric for the provider this session handles
    @NonNull
    protected final CandidateAggregateMetric mCandidateAggregateMetric;
    // Since track two is shared, this allows provider sessions to capture a metric-specific
    // session token for the flow where the provider is known
    private final int mSessionIdTrackTwo;

    public RequestSessionMetric(int sessionIdTrackOne, int sessionIdTrackTwo) {
        mSessionIdTrackTwo = sessionIdTrackTwo;
        mInitialPhaseMetric = new InitialPhaseMetric(sessionIdTrackOne);
        mCandidateAggregateMetric = new CandidateAggregateMetric(sessionIdTrackOne);
        mChosenProviderFinalPhaseMetric = new ChosenProviderFinalPhaseMetric(
                sessionIdTrackOne, sessionIdTrackTwo);
    }

    /**
     * Increments the metric emit sequence counter and returns the current state value of the
     * sequence.
     *
     * @return the current state value of the metric emit sequence.
     */
    public int returnIncrementSequence() {
        return ++mSequenceCounter;
    }


    /**
     * @return the initial metrics associated with the request session
     */
    public InitialPhaseMetric getInitialPhaseMetric() {
        return mInitialPhaseMetric;
    }

    /**
     * @return the aggregate candidate phase metrics associated with the request session
     */
    public CandidateAggregateMetric getCandidateAggregateMetric() {
        return mCandidateAggregateMetric;
    }

    /**
     * Upon starting the service, this fills the initial phase metric properly.
     *
     * @param timestampStarted the timestamp the service begins at
     * @param mCallingUid      the calling process's uid
     * @param metricCode       typically pulled from {@link ApiName}
     */
    public void collectInitialPhaseMetricInfo(long timestampStarted,
            int mCallingUid, int metricCode) {
        try {
            mInitialPhaseMetric.setCredentialServiceStartedTimeNanoseconds(timestampStarted);
            mInitialPhaseMetric.setCallerUid(mCallingUid);
            mInitialPhaseMetric.setApiName(metricCode);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error collecting initial metrics: " + e);
        }
    }

    /**
     * Collects whether the UI returned for metric purposes.
     *
     * @param uiReturned indicates whether the ui returns or not
     */
    public void collectUiReturnedFinalPhase(boolean uiReturned) {
        try {
            mChosenProviderFinalPhaseMetric.setUiReturned(uiReturned);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error collecting ui end time metric: " + e);
        }
    }

    /**
     * Sets the start time for the UI being called for metric purposes.
     *
     * @param uiCallStartTime the nanosecond time when the UI call began
     */
    public void collectUiCallStartTime(long uiCallStartTime) {
        try {
            mChosenProviderFinalPhaseMetric.setUiCallStartTimeNanoseconds(uiCallStartTime);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error collecting ui start metric: " + e);
        }
    }

    /**
     * When the UI responds to the framework at the very final phase, this collects the timestamp
     * and status of the return for metric purposes.
     *
     * @param uiReturned     indicates whether the ui returns or not
     * @param uiEndTimestamp the nanosecond time when the UI call ended
     */
    public void collectUiResponseData(boolean uiReturned, long uiEndTimestamp) {
        try {
            mChosenProviderFinalPhaseMetric.setUiReturned(uiReturned);
            mChosenProviderFinalPhaseMetric.setUiCallEndTimeNanoseconds(uiEndTimestamp);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error collecting ui response metric: " + e);
        }
    }

    /**
     * Collects the final chosen provider status, with the status value coming from
     * {@link ApiStatus}.
     *
     * @param status the final status of the chosen provider
     */
    public void collectChosenProviderStatus(int status) {
        try {
            mChosenProviderFinalPhaseMetric.setChosenProviderStatus(status);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error setting chosen provider status metric: " + e);
        }
    }

    /**
     * Collects initializations for Create flow metrics.
     *
     * @param origin indicates if an origin was passed in or not
     */
    public void collectCreateFlowInitialMetricInfo(boolean origin) {
        try {
            mInitialPhaseMetric.setOriginSpecified(origin);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error collecting create flow metric: " + e);
        }
    }

    // Used by get flows to generate the unique request count maps
    private Map<String, Integer> getRequestCountMap(GetCredentialRequest request) {
        Map<String, Integer> uniqueRequestCounts = new LinkedHashMap<>();
        try {
            request.getCredentialOptions().forEach(option -> {
                String optionKey = generateMetricKey(option.getType(), DELTA_RESPONSES_CUT);
                uniqueRequestCounts.put(optionKey, uniqueRequestCounts.getOrDefault(optionKey,
                        0) + 1);
            });
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error during get request metric logging: " + e);
        }
        return uniqueRequestCounts;
    }

    /**
     * Collects initializations for Get flow metrics.
     *
     * @param request the get credential request containing information to parse for metrics
     */
    public void collectGetFlowInitialMetricInfo(GetCredentialRequest request) {
        try {
            mInitialPhaseMetric.setOriginSpecified(request.getOrigin() != null);
            mInitialPhaseMetric.setRequestCounts(getRequestCountMap(request));
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error collecting get flow metric: " + e);
        }
    }

    /**
     * During browsing, where multiple entries can be selected, this collects the browsing phase
     * metric information. This is emitted together with the final phase, and the recursive path
     * with authentication entries, which may occur in rare circumstances, are captured.
     *
     * @param selection                   contains the selected entry key type
     * @param selectedProviderPhaseMetric contains the utility information of the selected provider
     */
    public void collectMetricPerBrowsingSelect(UserSelectionDialogResult selection,
            CandidatePhaseMetric selectedProviderPhaseMetric) {
        try {
            CandidateBrowsingPhaseMetric browsingPhaseMetric = new CandidateBrowsingPhaseMetric();
            browsingPhaseMetric.setEntryEnum(
                    EntryEnum.getMetricCodeFromString(selection.getEntryKey()));
            browsingPhaseMetric.setProviderUid(selectedProviderPhaseMetric.getCandidateUid());
            mCandidateBrowsingPhaseMetric.add(browsingPhaseMetric);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error collecting browsing metric: " + e);
        }
    }

    /**
     * Updates the final phase metric with the designated bit.
     *
     * @param exceptionBitFinalPhase represents if the final phase provider had an exception
     */
    private void setHasExceptionFinalPhase(boolean exceptionBitFinalPhase) {
        try {
            mChosenProviderFinalPhaseMetric.setHasException(exceptionBitFinalPhase);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error setting final exception metric: " + e);
        }
    }

    /**
     * This allows collecting the framework exception string for the final phase metric.
     * NOTE that this exception will be cut for space optimizations.
     *
     * @param exception the framework exception that is being recorded
     */
    public void collectFrameworkException(String exception) {
        try {
            mChosenProviderFinalPhaseMetric.setFrameworkException(
                    generateMetricKey(exception, DELTA_EXCEPTION_CUT));
        } catch (Exception e) {
            Slog.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * Allows encapsulating the overall final phase metric status from the chosen and final
     * provider.
     *
     * @param hasException represents if the final phase provider had an exception
     * @param finalStatus  represents the final status of the chosen provider
     */
    public void collectFinalPhaseProviderMetricStatus(boolean hasException,
            ProviderStatusForMetrics finalStatus) {
        try {
            mChosenProviderFinalPhaseMetric.setHasException(hasException);
            mChosenProviderFinalPhaseMetric.setChosenProviderStatus(
                    finalStatus.getMetricCode());
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * Called by RequestSessions upon chosen metric determination. It's expected that most bits
     * are transferred here. However, certain new information, such as the selected provider's final
     * exception bit, the framework to ui and back latency, or the ui response bit are set at other
     * locations. Other information, such browsing metrics, api_status, and the sequence id count
     * are combined during the final emit moment with the actual and official
     * {@link com.android.internal.util.FrameworkStatsLog} metric generation.
     *
     * @param candidatePhaseMetric the componentName to associate with a provider
     */
    public void collectChosenMetricViaCandidateTransfer(CandidatePhaseMetric candidatePhaseMetric) {
        try {
            mChosenProviderFinalPhaseMetric.setChosenUid(candidatePhaseMetric.getCandidateUid());

            mChosenProviderFinalPhaseMetric.setQueryPhaseLatencyMicroseconds(
                    candidatePhaseMetric.getQueryLatencyMicroseconds());

            mChosenProviderFinalPhaseMetric.setServiceBeganTimeNanoseconds(
                    candidatePhaseMetric.getServiceBeganTimeNanoseconds());
            mChosenProviderFinalPhaseMetric.setQueryStartTimeNanoseconds(
                    candidatePhaseMetric.getStartQueryTimeNanoseconds());
            mChosenProviderFinalPhaseMetric.setQueryEndTimeNanoseconds(candidatePhaseMetric
                    .getQueryFinishTimeNanoseconds());
            mChosenProviderFinalPhaseMetric.setResponseCollective(
                    candidatePhaseMetric.getResponseCollective());
            mChosenProviderFinalPhaseMetric.setFinalFinishTimeNanoseconds(System.nanoTime());
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error during metric candidate to final transfer: " + e);
        }
    }

    /**
     * In the final phase, this helps log use cases that were either pure failures or user
     * canceled. It's expected that {@link #collectFinalPhaseProviderMetricStatus(boolean,
     * ProviderStatusForMetrics) collectFinalPhaseProviderMetricStatus} is called prior to this.
     * Otherwise, the logging will miss required bits.
     *
     * @param isUserCanceledError a boolean indicating if the error was due to user cancelling
     */
    public void logFailureOrUserCancel(boolean isUserCanceledError) {
        try {
            if (isUserCanceledError) {
                setHasExceptionFinalPhase(/* has_exception */ false);
                logApiCalledAtFinish(
                        /* apiStatus */ ApiStatus.USER_CANCELED.getMetricCode());
            } else {
                logApiCalledAtFinish(
                        /* apiStatus */ ApiStatus.FAILURE.getMetricCode());
            }
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error during final metric failure emit: " + e);
        }
    }

    /**
     * Handles candidate phase metric emit in the RequestSession context, after the candidate phase
     * completes.
     *
     * @param providers a map with known providers and their held metric objects
     */
    public void logCandidatePhaseMetrics(Map<String, ProviderSession> providers) {
        try {
            logApiCalledCandidatePhase(providers, ++mSequenceCounter, mInitialPhaseMetric);
            logApiCalledCandidateGetMetric(providers, mSequenceCounter);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error during candidate metric emit: " + e);
        }
    }

    /**
     * Handles aggregate candidate phase metric emits in the RequestSession context, after the
     * candidate phase completes.
     *
     * @param providers a map with known providers and their held metric objects
     */
    public void logCandidateAggregateMetrics(Map<String, ProviderSession> providers) {
        try {
            mCandidateAggregateMetric.collectAverages(providers);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error during aggregate candidate logging " + e);
        }
    }

    /**
     * This logs the authentication entry when browsed. Combined with the known browsed clicks
     * in the {@link ChosenProviderFinalPhaseMetric}, this fully captures the authentication entry
     * logic for multiple loops. An auth entry may have default or missing data, but if a provider
     * was never assigned to an auth entry, this indicates an auth entry was never clicked.
     * This case is handled in this emit.
     *
     * @param browsedAuthenticationMetric the authentication metric information to emit
     */
    public void logAuthEntry(BrowsedAuthenticationMetric browsedAuthenticationMetric) {
        try {
            if (browsedAuthenticationMetric.getProviderUid() == DEFAULT_INT_32) {
                Slog.v(TAG, "An authentication entry was not clicked");
                return;
            }
            logApiCalledAuthenticationMetric(browsedAuthenticationMetric, ++mSequenceCounter);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error during metric logging: " + e);
        }

    }

    /**
     * Handles the final logging for RequestSession context for the final phase.
     *
     * @param apiStatus the final status of the api being called
     */
    public void logApiCalledAtFinish(int apiStatus) {
        try {
            logApiCalledFinalPhase(mChosenProviderFinalPhaseMetric, mCandidateBrowsingPhaseMetric,
                    apiStatus,
                    ++mSequenceCounter);
            logApiCalledNoUidFinal(mChosenProviderFinalPhaseMetric, mCandidateBrowsingPhaseMetric,
                    apiStatus,
                    ++mSequenceCounter);
        } catch (Exception e) {
            Slog.i(TAG, "Unexpected error during final metric emit: " + e);
        }
    }

    public int getSessionIdTrackTwo() {
        return mSessionIdTrackTwo;
    }
}
