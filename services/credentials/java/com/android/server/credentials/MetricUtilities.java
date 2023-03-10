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

package com.android.server.credentials;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.credentials.metrics.ApiName;
import com.android.server.credentials.metrics.ApiStatus;
import com.android.server.credentials.metrics.CandidateBrowsingPhaseMetric;
import com.android.server.credentials.metrics.CandidatePhaseMetric;
import com.android.server.credentials.metrics.ChosenProviderFinalPhaseMetric;
import com.android.server.credentials.metrics.InitialPhaseMetric;

import java.util.List;
import java.util.Map;

/**
 * For all future metric additions, this will contain their names for local usage after importing
 * from {@link com.android.internal.util.FrameworkStatsLog}.
 */
public class MetricUtilities {

    private static final String TAG = "MetricUtilities";

    public static final int DEFAULT_INT_32 = -1;
    public static final int[] DEFAULT_REPEATED_INT_32 = new int[0];
    // Used for single count metric emits, such as singular amounts of various types
    public static final int UNIT = 1;
    // Used for zero count metric emits, such as zero amounts of various types
    public static final int ZERO = 0;

    /**
     * This retrieves the uid of any package name, given a context and a component name for the
     * package. By default, if the desired package uid cannot be found, it will fall back to a
     * bogus uid.
     *
     * @return the uid of a given package
     */
    protected static int getPackageUid(Context context, ComponentName componentName) {
        int sessUid = -1;
        try {
            // Only for T and above, which is fine for our use case
            sessUid = context.getPackageManager().getApplicationInfo(
                    componentName.getPackageName(),
                    PackageManager.ApplicationInfoFlags.of(0)).uid;
        } catch (Throwable t) {
            Log.i(TAG, "Couldn't find required uid");
        }
        return sessUid;
    }

    /**
     * Given any two timestamps in nanoseconds, this gets the difference and converts to
     * milliseconds. Assumes the difference is not larger than the maximum int size.
     *
     * @param t2 the final timestamp
     * @param t1 the initial timestamp
     * @return the timestamp difference converted to microseconds
     */
    protected static int getMetricTimestampDifferenceMicroseconds(long t2, long t1) {
        if (t2 - t1 > Integer.MAX_VALUE) {
            throw new ArithmeticException("Input timestamps are too far apart and unsupported");
        }
        return (int) ((t2 - t1) / 1000);
    }

    /**
     * A logging utility used primarily for the final phase of the current metric setup.
     *
     * @param finalPhaseMetric     the coalesced data of the chosen provider
     * @param browsingPhaseMetrics the coalesced data of the browsing phase
     * @param apiStatus            the final status of this particular api call
     * @param emitSequenceId       an emitted sequence id for the current session
     */
    protected static void logApiCalled(ChosenProviderFinalPhaseMetric finalPhaseMetric,
            List<CandidateBrowsingPhaseMetric> browsingPhaseMetrics, int apiStatus,
            int emitSequenceId) {
        try {
            int browsedSize = browsingPhaseMetrics.size();
            int[] browsedClickedEntries = new int[browsedSize];
            int[] browsedProviderUid = new int[browsedSize];
            int index = 0;
            for (CandidateBrowsingPhaseMetric metric : browsingPhaseMetrics) {
                browsedClickedEntries[index] = metric.getEntryEnum();
                browsedProviderUid[index] = metric.getProviderUid();
                index++;
            }
            FrameworkStatsLog.write(FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE,
                    /* session_id */ finalPhaseMetric.getSessionId(),
                    /* sequence_num */ emitSequenceId,
                    /* ui_returned_final_start */ finalPhaseMetric.isUiReturned(),
                    /* chosen_provider_uid */ finalPhaseMetric.getChosenUid(),
                    /* chosen_provider_query_start_timestamp_microseconds */
                    finalPhaseMetric.getTimestampFromReferenceStartMicroseconds(finalPhaseMetric
                            .getQueryStartTimeNanoseconds()),
                    /* chosen_provider_query_end_timestamp_microseconds */
                    finalPhaseMetric.getTimestampFromReferenceStartMicroseconds(finalPhaseMetric
                            .getQueryEndTimeNanoseconds()),
                    /* chosen_provider_ui_invoked_timestamp_microseconds */
                    finalPhaseMetric.getTimestampFromReferenceStartMicroseconds(finalPhaseMetric
                            .getUiCallStartTimeNanoseconds()),
                    /* chosen_provider_ui_finished_timestamp_microseconds */
                    finalPhaseMetric.getTimestampFromReferenceStartMicroseconds(finalPhaseMetric
                            .getUiCallEndTimeNanoseconds()),
                    /* chosen_provider_finished_timestamp_microseconds */
                    finalPhaseMetric.getTimestampFromReferenceStartMicroseconds(finalPhaseMetric
                            .getFinalFinishTimeNanoseconds()),
                    /* chosen_provider_status */ finalPhaseMetric.getChosenProviderStatus(),
                    /* chosen_provider_has_exception */ finalPhaseMetric.isHasException(),
                    /* chosen_provider_available_entries */ finalPhaseMetric.getAvailableEntries()
                            .stream().mapToInt(i -> i).toArray(),
                    /* chosen_provider_action_entry_count */ finalPhaseMetric.getActionEntryCount(),
                    /* chosen_provider_credential_entry_count */
                    finalPhaseMetric.getCredentialEntryCount(),
                    /* chosen_provider_credential_entry_type_count */
                    finalPhaseMetric.getCredentialEntryTypeCount(),
                    /* chosen_provider_remote_entry_count */
                    finalPhaseMetric.getRemoteEntryCount(),
                    /* chosen_provider_authentication_entry_count */
                    finalPhaseMetric.getAuthenticationEntryCount(),
                    /* clicked_entries */ browsedClickedEntries,
                    /* provider_of_clicked_entry */ browsedProviderUid,
                    /* api_status */ apiStatus
            );
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * A logging utility used primarily for the candidate phase of the current metric setup.
     *
     * @param providers      a map with known providers and their held metric objects
     * @param emitSequenceId an emitted sequence id for the current session
     */
    protected static void logApiCalled(Map<String, ProviderSession> providers,
            int emitSequenceId) {
        try {
            var providerSessions = providers.values();
            int providerSize = providerSessions.size();
            int sessionId = -1;
            boolean queryReturned = false;
            int[] candidateUidList = new int[providerSize];
            int[] candidateQueryStartTimeStampList = new int[providerSize];
            int[] candidateQueryEndTimeStampList = new int[providerSize];
            int[] candidateStatusList = new int[providerSize];
            boolean[] candidateHasExceptionList = new boolean[providerSize];
            int[] candidateTotalEntryCountList = new int[providerSize];
            int[] candidateCredentialEntryCountList = new int[providerSize];
            int[] candidateCredentialTypeCountList = new int[providerSize];
            int[] candidateActionEntryCountList = new int[providerSize];
            int[] candidateAuthEntryCountList = new int[providerSize];
            int[] candidateRemoteEntryCountList = new int[providerSize];
            int index = 0;
            for (var session : providerSessions) {
                CandidatePhaseMetric metric = session.mCandidatePhasePerProviderMetric;
                if (sessionId == -1) {
                    sessionId = metric.getSessionId();
                }
                if (!queryReturned) {
                    queryReturned = metric.isQueryReturned();
                }
                candidateUidList[index] = metric.getCandidateUid();
                candidateQueryStartTimeStampList[index] =
                        metric.getTimestampFromReferenceStartMicroseconds(
                                metric.getStartQueryTimeNanoseconds());
                candidateQueryEndTimeStampList[index] =
                        metric.getTimestampFromReferenceStartMicroseconds(
                                metric.getQueryFinishTimeNanoseconds());
                candidateStatusList[index] = metric.getProviderQueryStatus();
                candidateHasExceptionList[index] = metric.isHasException();
                candidateTotalEntryCountList[index] = metric.getNumEntriesTotal();
                candidateCredentialEntryCountList[index] = metric.getCredentialEntryCount();
                candidateCredentialTypeCountList[index] = metric.getCredentialEntryTypeCount();
                candidateActionEntryCountList[index] = metric.getActionEntryCount();
                candidateAuthEntryCountList[index] = metric.getAuthenticationEntryCount();
                candidateRemoteEntryCountList[index] = metric.getRemoteEntryCount();
                index++;
            }
            FrameworkStatsLog.write(FrameworkStatsLog.CREDENTIAL_MANAGER_CANDIDATE_PHASE,
                    /* session_id */ sessionId,
                    /* sequence_num */ emitSequenceId,
                    /* query_returned */ queryReturned,
                    /* candidate_provider_uid_list */ candidateUidList,
                    /* candidate_provider_query_start_timestamp_microseconds */
                    candidateQueryStartTimeStampList,
                    /* candidate_provider_query_end_timestamp_microseconds */
                    candidateQueryEndTimeStampList,
                    /* candidate_provider_status */ candidateStatusList,
                    /* candidate_provider_has_exception */ candidateHasExceptionList,
                    /* candidate_provider_num_entries */ candidateTotalEntryCountList,
                    /* candidate_provider_action_entry_count */ candidateActionEntryCountList,
                    /* candidate_provider_credential_entry_count */
                    candidateCredentialEntryCountList,
                    /* candidate_provider_credential_entry_type_count */
                    candidateCredentialTypeCountList,
                    /* candidate_provider_remote_entry_count */ candidateRemoteEntryCountList,
                    /* candidate_provider_authentication_entry_count */ candidateAuthEntryCountList
            );
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * This is useful just to record an API calls' final event, and for no other purpose. It will
     * contain default values for all other optional parameters.
     *
     * TODO(b/271135048) - given space requirements, this may be a good candidate for another atom
     *
     * @param apiName    the api name to log
     * @param apiStatus  the status to log
     * @param callingUid the calling uid
     */
    protected static void logApiCalled(ApiName apiName, ApiStatus apiStatus,
            int callingUid) {
        try {
            FrameworkStatsLog.write(FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED,
                    /* api_name */apiName.getMetricCode(),
                    /* caller_uid */ callingUid,
                    /* api_status */ apiStatus.getMetricCode(),
                    /* repeated_candidate_provider_uid */  DEFAULT_REPEATED_INT_32,
                    /* repeated_candidate_provider_round_trip_time_query_microseconds */
                    DEFAULT_REPEATED_INT_32,
                    /* repeated_candidate_provider_status */ DEFAULT_REPEATED_INT_32,
                    /* chosen_provider_uid */ DEFAULT_INT_32,
                    /* chosen_provider_round_trip_time_overall_microseconds */
                    DEFAULT_INT_32,
                    /* chosen_provider_final_phase_microseconds */
                    DEFAULT_INT_32,
                    /* chosen_provider_status */ DEFAULT_INT_32);
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * Handles the metric emit for the initial phase.
     *
     * @param initialPhaseMetric contains all the data for this emit
     * @param sequenceNum        the sequence number for this api call session emit
     */
    protected static void logApiCalled(InitialPhaseMetric initialPhaseMetric, int sequenceNum) {
        try {
            FrameworkStatsLog.write(FrameworkStatsLog.CREDENTIAL_MANAGER_INIT_PHASE,
                    /* api_name */ initialPhaseMetric.getApiName(),
                    /* caller_uid */ initialPhaseMetric.getCallerUid(),
                    /* session_id */ initialPhaseMetric.getSessionId(),
                    /* sequence_num */ sequenceNum,
                    /* initial_timestamp_reference_nanoseconds */
                    initialPhaseMetric.getCredentialServiceStartedTimeNanoseconds(),
                    /* count_credential_request_classtypes */
                    initialPhaseMetric.getCountRequestClassType()
                    // TODO(b/271135048) - add total count of request options
            );
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

}
