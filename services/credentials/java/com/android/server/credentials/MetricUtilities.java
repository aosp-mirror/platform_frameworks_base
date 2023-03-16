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
import com.android.server.credentials.metrics.CandidatePhaseMetric;
import com.android.server.credentials.metrics.ChosenProviderFinalPhaseMetric;
import com.android.server.credentials.metrics.InitialPhaseMetric;

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
     * A logging utility used primarily for the candidate phase of the current metric setup.
     *
     * @param providers            a map with known providers
     * @param emitSequenceId       an emitted sequence id for the current session
     */
    protected static void logApiCalled(Map<String, ProviderSession> providers,
            int emitSequenceId) {
        try {
            var providerSessions = providers.values();
            int providerSize = providerSessions.size();
            int[] candidateUidList = new int[providerSize];
            int[] candidateQueryRoundTripTimeList = new int[providerSize];
            int[] candidateStatusList = new int[providerSize];
            int index = 0;
            for (var session : providerSessions) {
                CandidatePhaseMetric metric = session.mCandidatePhasePerProviderMetric;
                candidateUidList[index] = metric.getCandidateUid();
                candidateQueryRoundTripTimeList[index] = metric.getQueryLatencyMicroseconds();
                candidateStatusList[index] = metric.getProviderQueryStatus();
                index++;
            }
            // TODO Handle the emit here
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * The most common logging helper, handles the overall status of the API request with the
     * provider status and latencies. Other versions of this method may be more useful depending
     * on the situation, as this is geared towards the logging of {@link ProviderSession} types.
     *
     * @param apiName              the api type to log
     * @param apiStatus            the api status to log
     * @param providers            a map with known providers
     * @param callingUid           the calling UID of the client app
     * @param chosenProviderFinalPhaseMetric the metric data type of the final chosen provider
     * TODO remove soon
     */
    protected static void logApiCalled(ApiName apiName, ApiStatus apiStatus,
            Map<String, ProviderSession> providers, int callingUid,
            ChosenProviderFinalPhaseMetric chosenProviderFinalPhaseMetric) {
        try {
            var providerSessions = providers.values();
            int providerSize = providerSessions.size();
            int[] candidateUidList = new int[providerSize];
            int[] candidateQueryRoundTripTimeList = new int[providerSize];
            int[] candidateStatusList = new int[providerSize];
            int index = 0;
            for (var session : providerSessions) {
                CandidatePhaseMetric metric = session.mCandidatePhasePerProviderMetric;
                candidateUidList[index] = metric.getCandidateUid();
                candidateQueryRoundTripTimeList[index] = metric.getQueryLatencyMicroseconds();
                candidateStatusList[index] = metric.getProviderQueryStatus();
                index++;
            }
            FrameworkStatsLog.write(FrameworkStatsLog.CREDENTIAL_MANAGER_API_CALLED,
                    /* api_name */apiName.getMetricCode(),
                    /* caller_uid */ callingUid,
                    /* api_status */ apiStatus.getMetricCode(),
                    /* repeated_candidate_provider_uid */ candidateUidList,
                    /* repeated_candidate_provider_round_trip_time_query_microseconds */
                    candidateQueryRoundTripTimeList,
                    /* repeated_candidate_provider_status */ candidateStatusList,
                    /* chosen_provider_uid */ chosenProviderFinalPhaseMetric.getChosenUid(),
                    /* chosen_provider_round_trip_time_overall_microseconds */
                    chosenProviderFinalPhaseMetric.getEntireProviderLatencyMicroseconds(),
                    /* chosen_provider_final_phase_microseconds (backwards compat only) */
                    DEFAULT_INT_32,
                    /* chosen_provider_status */ chosenProviderFinalPhaseMetric
                            .getChosenProviderStatus());
        } catch (Exception e) {
            Log.w(TAG, "Unexpected error during metric logging: " + e);
        }
    }

    /**
     * This is useful just to record an API calls' final event, and for no other purpose. It will
     * contain default values for all other optional parameters.
     *
     * TODO(b/271135048) - given space requirements, this may be a good candidate for another atom
     * TODO immediately remove and carry over TODO to new log for this setup
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
     */
    protected static void logApiCalled(InitialPhaseMetric initialPhaseMetric) {
        /*
        FrameworkStatsLog.write(FrameworkStatsLog.INITIAL_PHASE,
        .. session_id .. initialPhaseMetric.getSessionId(),
        ...
        TODO Immediately - Fill in asap now that the split atom is checked in.
         */
    }

}
