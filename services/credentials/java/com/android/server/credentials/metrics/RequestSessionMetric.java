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

import static com.android.server.credentials.MetricUtilities.DELTA_CUT;
import static com.android.server.credentials.MetricUtilities.generateMetricKey;
import static com.android.server.credentials.MetricUtilities.logApiCalledCandidatePhase;
import static com.android.server.credentials.MetricUtilities.logApiCalledFinalPhase;

import android.credentials.GetCredentialRequest;
import android.credentials.ui.UserSelectionDialogResult;
import android.os.IBinder;
import android.util.Log;

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

    protected final InitialPhaseMetric mInitialPhaseMetric = new InitialPhaseMetric();
    protected final ChosenProviderFinalPhaseMetric
            mChosenProviderFinalPhaseMetric = new ChosenProviderFinalPhaseMetric();
    // TODO(b/271135048) - Replace this with a new atom per each browsing emit (V4)
    protected List<CandidateBrowsingPhaseMetric> mCandidateBrowsingPhaseMetric = new ArrayList<>();

    public RequestSessionMetric() {
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
     * Upon starting the service, this fills the initial phase metric properly.
     *
     * @param timestampStarted the timestamp the service begins at
     * @param mRequestId       the IBinder used to retrieve a unique id
     * @param mCallingUid      the calling process's uid
     * @param metricCode       typically pulled from {@link ApiName}
     */
    public void collectInitialPhaseMetricInfo(long timestampStarted, IBinder mRequestId,
            int mCallingUid, int metricCode) {
        try {
            mInitialPhaseMetric.setCredentialServiceStartedTimeNanoseconds(timestampStarted);
            mInitialPhaseMetric.setSessionId(mRequestId.hashCode());
            mInitialPhaseMetric.setCallerUid(mCallingUid);
            mInitialPhaseMetric.setApiName(metricCode);
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
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
            Log.w(TAG, "Unexpected error during metric logging: " + e);
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
            Log.w(TAG, "Unexpected error during metric logging: " + e);
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
            Log.w(TAG, "Unexpected error during metric logging: " + e);
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
            Log.w(TAG, "Unexpected error during metric logging: " + e);
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
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    // Used by get flows to generate the unique request count maps
    private Map<String, Integer> getRequestCountMap(GetCredentialRequest request) {
        Map<String, Integer> uniqueRequestCounts = new LinkedHashMap<>();
        try {
            request.getCredentialOptions().forEach(option -> {
                String optionKey = generateMetricKey(option.getType(), DELTA_CUT);
                if (!uniqueRequestCounts.containsKey(optionKey)) {
                    uniqueRequestCounts.put(optionKey, 0);
                }
                uniqueRequestCounts.put(optionKey, uniqueRequestCounts.get(optionKey) + 1);
            });
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during get request metric logging: " + e);
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
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * During browsing, where multiple entries can be selected, this collects the browsing phase
     * metric information.
     * TODO(b/271135048) - modify asap to account for a new metric emit per browse response to
     * framework.
     *
     * @param selection                   contains the selected entry key type
     * @param selectedProviderPhaseMetric contains the utility information of the selected provider
     */
    public void collectMetricPerBrowsingSelect(UserSelectionDialogResult selection,
            CandidatePhaseMetric selectedProviderPhaseMetric) {
        try {
            CandidateBrowsingPhaseMetric browsingPhaseMetric = new CandidateBrowsingPhaseMetric();
            browsingPhaseMetric.setSessionId(mInitialPhaseMetric.getSessionId());
            browsingPhaseMetric.setEntryEnum(
                    EntryEnum.getMetricCodeFromString(selection.getEntryKey()));
            browsingPhaseMetric.setProviderUid(selectedProviderPhaseMetric.getCandidateUid());
            mCandidateBrowsingPhaseMetric.add(browsingPhaseMetric);
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * Updates the final phase metric with the designated bit
     *
     * @param exceptionBitFinalPhase represents if the final phase provider had an exception
     */
    private void setHasExceptionFinalPhase(boolean exceptionBitFinalPhase) {
        try {
            mChosenProviderFinalPhaseMetric.setHasException(exceptionBitFinalPhase);
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
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
            Log.w(TAG, "Unexpected error during metric logging: " + e);
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
            mChosenProviderFinalPhaseMetric.setSessionId(candidatePhaseMetric.getSessionId());
            mChosenProviderFinalPhaseMetric.setChosenUid(candidatePhaseMetric.getCandidateUid());

            mChosenProviderFinalPhaseMetric.setQueryPhaseLatencyMicroseconds(
                    candidatePhaseMetric.getQueryLatencyMicroseconds());

            mChosenProviderFinalPhaseMetric.setServiceBeganTimeNanoseconds(
                    candidatePhaseMetric.getServiceBeganTimeNanoseconds());
            mChosenProviderFinalPhaseMetric.setQueryStartTimeNanoseconds(
                    candidatePhaseMetric.getStartQueryTimeNanoseconds());
            mChosenProviderFinalPhaseMetric.setQueryEndTimeNanoseconds(candidatePhaseMetric
                    .getQueryFinishTimeNanoseconds());

            mChosenProviderFinalPhaseMetric.setNumEntriesTotal(candidatePhaseMetric
                    .getNumEntriesTotal());
            mChosenProviderFinalPhaseMetric.setCredentialEntryCount(candidatePhaseMetric
                    .getCredentialEntryCount());
            mChosenProviderFinalPhaseMetric.setCredentialEntryTypeCount(
                    candidatePhaseMetric.getCredentialEntryTypeCount());
            mChosenProviderFinalPhaseMetric.setActionEntryCount(candidatePhaseMetric
                    .getActionEntryCount());
            mChosenProviderFinalPhaseMetric.setRemoteEntryCount(candidatePhaseMetric
                    .getRemoteEntryCount());
            mChosenProviderFinalPhaseMetric.setAuthenticationEntryCount(
                    candidatePhaseMetric.getAuthenticationEntryCount());
            mChosenProviderFinalPhaseMetric.setAvailableEntries(candidatePhaseMetric
                    .getAvailableEntries());
            mChosenProviderFinalPhaseMetric.setFinalFinishTimeNanoseconds(System.nanoTime());
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * In the final phase, this helps log use cases that were either pure failures or user
     * canceled. It's expected that {@link #collectFinalPhaseProviderMetricStatus(boolean,
     * ProviderStatusForMetrics) collectFinalPhaseProviderMetricStatus} is called prior to this.
     * Otherwise, the logging will miss required bits
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
            Log.w(TAG, "Unexpected error during metric logging: " + e);
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
            logApiCalledCandidatePhase(providers, ++mSequenceCounter);
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * Handles the final logging for RequestSession context for the final phase.
     *
     * @param apiStatus the final status of the api being called
     */
    public void logApiCalledAtFinish(int apiStatus) {
        try {
            // TODO (b/270403549) - this browsing phase object is fine but also have a new emit
            // For the returned types by authentication entries - i.e. a CandidatePhase During
            // Browse
            // Possibly think of adding in more atoms for other APIs as well.
            logApiCalledFinalPhase(mChosenProviderFinalPhaseMetric, mCandidateBrowsingPhaseMetric,
                    apiStatus,
                    ++mSequenceCounter);
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

}
