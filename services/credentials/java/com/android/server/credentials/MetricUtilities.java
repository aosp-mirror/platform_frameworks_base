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
import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.credentials.metrics.ApiName;
import com.android.server.credentials.metrics.ApiStatus;
import com.android.server.credentials.metrics.BrowsedAuthenticationMetric;
import com.android.server.credentials.metrics.CandidateAggregateMetric;
import com.android.server.credentials.metrics.CandidateBrowsingPhaseMetric;
import com.android.server.credentials.metrics.CandidatePhaseMetric;
import com.android.server.credentials.metrics.ChosenProviderFinalPhaseMetric;
import com.android.server.credentials.metrics.EntryEnum;
import com.android.server.credentials.metrics.InitialPhaseMetric;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/**
 * For all future metric additions, this will contain their names for local usage after importing
 * from {@link com.android.internal.util.FrameworkStatsLog}.
 * TODO(b/271135048) - Emit all atoms, including all V4 atoms (specifically the rest of track 1).
 */
public class MetricUtilities {
    private static final boolean LOG_FLAG = true;

    private static final String TAG = "MetricUtilities";
    public static final String USER_CANCELED_SUBSTRING = "TYPE_USER_CANCELED";
    public static final int MIN_EMIT_WAIT_TIME_MS = 10;

    public static final int DEFAULT_INT_32 = -1;
    public static final String DEFAULT_STRING = "";
    public static final int[] DEFAULT_REPEATED_INT_32 = new int[0];
    public static final String[] DEFAULT_REPEATED_STR = new String[0];
    public static final boolean[] DEFAULT_REPEATED_BOOL = new boolean[0];
    // Used for single count metric emits, such as singular amounts of various types
    public static final int UNIT = 1;
    // Used for zero count metric emits, such as zero amounts of various types
    public static final int ZERO = 0;
    // The number of characters at the end of the string to use as a key
    public static final int DELTA_RESPONSES_CUT = 20;
    // The cut for exception strings from the end - used to keep metrics small
    public static final int DELTA_EXCEPTION_CUT = 30;

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
            Slog.i(TAG, "Couldn't find required uid");
        }
        return sessUid;
    }

    /**
     * Used to help generate random sequences for local sessions, in the time-scale of credential
     * manager flows.
     * @return a high entropy int useful to use in reasonable time-frame sessions.
     */
    public static int getHighlyUniqueInteger() {
        return new SecureRandom().nextInt();
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
     * Given the current design, we can designate how the strings in the backend should appear.
     * This helper method lets us cut strings for our class types.
     *
     * @param classtype the classtype string we want to cut to generate a key
     * @param deltaFromEnd the starting point from the end of the string we wish to begin at
     * @return the cut up string key we want to use for metric logs
     */
    public static String generateMetricKey(String classtype, int deltaFromEnd) {
        return classtype.substring(classtype.length() - deltaFromEnd);
    }

    /**
     * A logging utility used primarily for the final phase of the current metric setup, focused on
     * track 2, where the provider uid is known.
     *
     * @param finalPhaseMetric     the coalesced data of the chosen provider
     * @param browsingPhaseMetrics the coalesced data of the browsing phase
     * @param apiStatus            the final status of this particular api call
     * @param emitSequenceId       an emitted sequence id for the current session
     */
    public static void logApiCalledFinalPhase(ChosenProviderFinalPhaseMetric finalPhaseMetric,
            List<CandidateBrowsingPhaseMetric> browsingPhaseMetrics, int apiStatus,
            int emitSequenceId) {
        try {
            if (!LOG_FLAG) {
                return;
            }
            int browsedSize = browsingPhaseMetrics.size();
            int[] browsedClickedEntries = new int[browsedSize];
            int[] browsedProviderUid = new int[browsedSize];
            int index = 0;
            for (CandidateBrowsingPhaseMetric metric : browsingPhaseMetrics) {
                browsedClickedEntries[index] = metric.getEntryEnum();
                browsedProviderUid[index] = metric.getProviderUid();
                index++;
            }
            FrameworkStatsLog.write(FrameworkStatsLog.CREDENTIAL_MANAGER_FINAL_PHASE_REPORTED,
                    /* session_id */ finalPhaseMetric.getSessionIdProvider(),
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
                    /* chosen_provider_available_entries (deprecated) */ DEFAULT_REPEATED_INT_32,
                    /* chosen_provider_action_entry_count (deprecated) */ DEFAULT_INT_32,
                    /* chosen_provider_credential_entry_count (deprecated)*/DEFAULT_INT_32,
                    /* chosen_provider_credential_entry_type_count (deprecated) */ DEFAULT_INT_32,
                    /* chosen_provider_remote_entry_count (deprecated) */ DEFAULT_INT_32,
                    /* chosen_provider_authentication_entry_count (deprecated) */ DEFAULT_INT_32,
                    /* clicked_entries */ browsedClickedEntries,
                    /* provider_of_clicked_entry */ browsedProviderUid,
                    /* api_status */ apiStatus,
                    /* unique_entries */
                    finalPhaseMetric.getResponseCollective().getUniqueEntries(),
                    /* per_entry_counts */
                    finalPhaseMetric.getResponseCollective().getUniqueEntryCounts(),
                    /* unique_response_classtypes */
                    finalPhaseMetric.getResponseCollective().getUniqueResponseStrings(),
                    /* per_classtype_counts */
                    finalPhaseMetric.getResponseCollective().getUniqueResponseCounts(),
                    /* framework_exception_unique_classtype */
                    finalPhaseMetric.getFrameworkException(),
                    /* primary_indicated */ false
            );
        } catch (Exception e) {
            Slog.w(TAG, "Unexpected error during final provider uid emit: " + e);
        }
    }

    /**
     * This emits the authentication entry metrics for track 2, where the provider uid is known.
     *
     * @param authenticationMetric the authentication metric collection to emit with
     */
    public static void logApiCalledAuthenticationMetric(
            BrowsedAuthenticationMetric authenticationMetric) {
        // TODO(immediately) - Add in this emit
    }

    /**
     * A logging utility used primarily for the candidate phase's get responses in the current
     * metric setup. This helps avoid nested proto-files. This is primarily focused on track 2,
     * where the provider uid is known. It ensures to run in a separate thread while emitting
     * the multiple atoms to work with expected emit limits.
     *
     * @param providers      a map with known providers and their held metric objects
     * @param emitSequenceId an emitted sequence id for the current session, that matches the
     *                       candidate emit value, as these metrics belong with the candidates
     */
    public static void logApiCalledCandidateGetMetric(Map<String, ProviderSession> providers,
            int emitSequenceId) {
        try {
            // TODO(immediately) - Modify to a Static Queue of Ordered Functions and emit from
            //  queue to adhere to 10 second limit (thread removed given android safe-calling).
            var sessions = providers.values();
            for (var session : sessions) {
                try {
                    var metric = session.getProviderSessionMetric()
                            .getCandidatePhasePerProviderMetric();
                    FrameworkStatsLog.write(
                            FrameworkStatsLog.CREDENTIAL_MANAGER_GET_REPORTED,
                            /* session_id */ metric.getSessionIdProvider(),
                            /* sequence_num */ emitSequenceId,
                            /* candidate_provider_uid */ metric.getCandidateUid(),
                            /* response_unique_classtypes */
                            metric.getResponseCollective().getUniqueResponseStrings(),
                            /* per_classtype_counts */
                            metric.getResponseCollective().getUniqueResponseCounts()
                    );
                } catch (Exception e) {
                    Slog.w(TAG, "Unexpected exception during get metric logging" + e);
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unexpected error during candidate get metric logging: " + e);
        }
    }


    /**
     * A logging utility used primarily for the candidate phase of the current metric setup. This
     * will primarily focus on track 2, where the session id is associated with known providers,
     * but NOT the calling app.
     *
     * @param providers      a map with known providers and their held metric objects
     * @param emitSequenceId an emitted sequence id for the current session
     * @param initialPhaseMetric contains initial phase data to avoid repetition for candidate
     *                           phase, track 2, logging
     */
    public static void logApiCalledCandidatePhase(Map<String, ProviderSession> providers,
            int emitSequenceId, InitialPhaseMetric initialPhaseMetric) {
        try {
            if (!LOG_FLAG) {
                return;
            }
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
            String[] frameworkExceptionList = new String[providerSize];
            int index = 0;
            for (var session : providerSessions) {
                CandidatePhaseMetric metric = session.mProviderSessionMetric
                        .getCandidatePhasePerProviderMetric();
                if (sessionId == -1) {
                    sessionId = metric.getSessionIdProvider();
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
                candidateTotalEntryCountList[index] = metric.getResponseCollective()
                        .getNumEntriesTotal();
                candidateCredentialEntryCountList[index] = metric.getResponseCollective()
                        .getCountForEntry(EntryEnum.CREDENTIAL_ENTRY);
                candidateCredentialTypeCountList[index] = metric.getResponseCollective()
                        .getUniqueResponseStrings().length;
                candidateActionEntryCountList[index] = metric.getResponseCollective()
                        .getCountForEntry(EntryEnum.ACTION_ENTRY);
                candidateAuthEntryCountList[index] = metric.getResponseCollective()
                        .getCountForEntry(EntryEnum.AUTHENTICATION_ENTRY);
                candidateRemoteEntryCountList[index] = metric.getResponseCollective()
                        .getCountForEntry(EntryEnum.REMOTE_ENTRY);
                frameworkExceptionList[index] = metric.getFrameworkException();
                index++;
            }
            FrameworkStatsLog.write(FrameworkStatsLog.CREDENTIAL_MANAGER_CANDIDATE_PHASE_REPORTED,
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
                    /* candidate_provider_authentication_entry_count */
                    candidateAuthEntryCountList,
                    /* framework_exception_per_provider */
                    frameworkExceptionList,
                    /* origin_specified originSpecified */
                    initialPhaseMetric.isOriginSpecified(),
                    /* request_unique_classtypes */
                    initialPhaseMetric.getUniqueRequestStrings(),
                    /* per_classtype_counts */
                    initialPhaseMetric.getUniqueRequestCounts(),
                    /* api_name */
                    initialPhaseMetric.getApiName(),
                    /* primary_candidates_indicated */
                    DEFAULT_REPEATED_BOOL
            );
        } catch (Exception e) {
            Slog.w(TAG, "Unexpected error during candidate provider uid metric emit: " + e);
        }
    }

    /**
     * This is useful just to record an API calls' final event, and for no other purpose.
     *
     * @param apiName    the api name to log
     * @param apiStatus  the status to log
     * @param callingUid the calling uid
     */
    public static void logApiCalledSimpleV2(ApiName apiName, ApiStatus apiStatus,
            int callingUid) {
        try {
            if (!LOG_FLAG) {
                return;
            }
            FrameworkStatsLog.write(FrameworkStatsLog.CREDENTIAL_MANAGER_APIV2_CALLED,
                    /* api_name */apiName.getMetricCode(),
                    /* caller_uid */ callingUid,
                    /* api_status */ apiStatus.getMetricCode());
        } catch (Exception e) {
            Slog.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * Handles the metric emit for the initial phase.
     *
     * @param initialPhaseMetric contains all the data for this emit
     * @param sequenceNum        the sequence number for this api call session emit
     */
    public static void logApiCalledInitialPhase(InitialPhaseMetric initialPhaseMetric,
            int sequenceNum) {
        try {
            if (!LOG_FLAG) {
                return;
            }
            FrameworkStatsLog.write(FrameworkStatsLog.CREDENTIAL_MANAGER_INIT_PHASE_REPORTED,
                    /* api_name */ initialPhaseMetric.getApiName(),
                    /* caller_uid */ initialPhaseMetric.getCallerUid(),
                    /* session_id */ initialPhaseMetric.getSessionIdCaller(),
                    /* sequence_num */ sequenceNum,
                    /* initial_timestamp_reference_nanoseconds */
                    initialPhaseMetric.getCredentialServiceStartedTimeNanoseconds(),
                    /* count_credential_request_classtypes */
                    initialPhaseMetric.getCountRequestClassType(),
                    /* request_unique_classtypes */
                    initialPhaseMetric.getUniqueRequestStrings(),
                    /* per_classtype_counts */
                    initialPhaseMetric.getUniqueRequestCounts(),
                    /* origin_specified */
                    initialPhaseMetric.isOriginSpecified()
            );
        } catch (Exception e) {
            Slog.w(TAG, "Unexpected error during initial metric emit: " + e);
        }
    }

    /**
     * A logging utility focused on track 1, where the calling app is known. This captures all
     * aggregate information for the candidate phase.
     *
     * @param candidateAggregateMetric the aggregate candidate metric information collected
     * @param sequenceNum the sequence number for this api call session emit
     */
    public static void logApiCalledAggregateCandidate(
            CandidateAggregateMetric candidateAggregateMetric,
            int sequenceNum) {
        try {
            if (!LOG_FLAG) {
                FrameworkStatsLog.write(FrameworkStatsLog.CREDENTIAL_MANAGER_TOTAL_REPORTED,
                        /*session_id*/ candidateAggregateMetric.getSessionIdProvider(),
                        /*sequence_num*/ sequenceNum,
                        /*query_returned*/ candidateAggregateMetric.isQueryReturned(),
                        /*num_providers*/ candidateAggregateMetric.getNumProviders(),
                        /*min_query_start_timestamp_microseconds*/
                        DEFAULT_INT_32,
                        /*max_query_end_timestamp_microseconds*/
                        DEFAULT_INT_32,
                        /*query_response_unique_classtypes*/
                        candidateAggregateMetric.getAggregateCollectiveQuery()
                                .getUniqueResponseStrings(),
                        /*query_per_classtype_counts*/
                        candidateAggregateMetric.getAggregateCollectiveQuery()
                                .getUniqueResponseCounts(),
                        /*query_unique_entries*/
                        candidateAggregateMetric.getAggregateCollectiveQuery()
                                .getUniqueEntries(),
                        /*query_per_entry_counts*/
                        candidateAggregateMetric.getAggregateCollectiveQuery()
                                .getUniqueEntryCounts(),
                        /*query_total_candidate_failure*/
                        DEFAULT_INT_32,
                        /*query_framework_exception_unique_classtypes*/
                        DEFAULT_REPEATED_STR,
                        /*query_per_exception_classtype_counts*/
                        DEFAULT_REPEATED_INT_32,
                        /*auth_response_unique_classtypes*/
                        DEFAULT_REPEATED_STR,
                        /*auth_per_classtype_counts*/
                        DEFAULT_REPEATED_INT_32,
                        /*auth_unique_entries*/
                        DEFAULT_REPEATED_INT_32,
                        /*auth_per_entry_counts*/
                        DEFAULT_REPEATED_INT_32,
                        /*auth_total_candidate_failure*/
                        DEFAULT_INT_32,
                        /*auth_framework_exception_unique_classtypes*/
                        DEFAULT_REPEATED_STR,
                        /*auth_per_exception_classtype_counts*/
                        DEFAULT_REPEATED_INT_32,
                        /*num_auth_clicks*/
                        DEFAULT_INT_32,
                        /*auth_returned*/ false
                );
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * A logging utility used primarily for the final phase of the current metric setup for track 1.
     *
     * @param finalPhaseMetric     the coalesced data of the chosen provider
     * @param browsingPhaseMetrics the coalesced data of the browsing phase
     * @param apiStatus            the final status of this particular api call
     * @param emitSequenceId       an emitted sequence id for the current session
     */
    public static void logApiCalledNoUidFinal(ChosenProviderFinalPhaseMetric finalPhaseMetric,
            List<CandidateBrowsingPhaseMetric> browsingPhaseMetrics, int apiStatus,
            int emitSequenceId) {
        try {
            if (!LOG_FLAG) {
                return;
            }
            int browsedSize = browsingPhaseMetrics.size();
            int[] browsedClickedEntries = new int[browsedSize];
            int[] browsedProviderUid = new int[browsedSize];
            int index = 0;
            for (CandidateBrowsingPhaseMetric metric : browsingPhaseMetrics) {
                browsedClickedEntries[index] = metric.getEntryEnum();
                browsedProviderUid[index] = metric.getProviderUid();
                index++;
            }
            FrameworkStatsLog.write(FrameworkStatsLog.CREDENTIAL_MANAGER_FINALNOUID_REPORTED,
                    /* session_id */ finalPhaseMetric.getSessionIdCaller(),
                    /* sequence_num */ emitSequenceId,
                    /* ui_returned_final_start */ finalPhaseMetric.isUiReturned(),
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
                    /* unique_entries */
                    finalPhaseMetric.getResponseCollective().getUniqueEntries(),
                    /* per_entry_counts */
                    finalPhaseMetric.getResponseCollective().getUniqueEntryCounts(),
                    /* unique_response_classtypes */
                    finalPhaseMetric.getResponseCollective().getUniqueResponseStrings(),
                    /* per_classtype_counts */
                    finalPhaseMetric.getResponseCollective().getUniqueResponseCounts(),
                    /* framework_exception_unique_classtype */
                    finalPhaseMetric.getFrameworkException(),
                    /* clicked_entries */ browsedClickedEntries,
                    /* provider_of_clicked_entry */ browsedProviderUid,
                    /* api_status */ apiStatus,
                    /* primary_indicated */ false
            );
        } catch (Exception e) {
            Slog.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

}
