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

import com.android.server.credentials.MetricUtilities;
import com.android.server.credentials.ProviderSession;
import com.android.server.credentials.metrics.shared.ResponseCollective;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This will generate most of its data via using the information of {@link CandidatePhaseMetric}
 * across all the providers. This belongs to the metric flow where the calling app is known. It
 * also contains {@link BrowsedAuthenticationMetric} data aggregated within.
 */
public class CandidateAggregateMetric {

    private static final String TAG = "CandidateTotalMetric";
    // The session id of this provider metric
    private final int mSessionIdProvider;
    // Indicates if this provider returned from the candidate query phase,
    // true if at least one provider returns validly, even if empty, default false
    private boolean mQueryReturned = false;
    // For reference, the initial log timestamp when the service started running the API call,
    // defaults to -1
    private long mServiceBeganTimeNanoseconds = -1;
    // Indicates the total number of providers this aggregate captures information for, default 0
    private int mNumProviders = 0;
    // Indicates if the authentication entry returned, true if at least one entry returns validly,
    // even if empty, default false
    private boolean mAuthReturned = false;
    // Indicates the total number of authentication entries that were tapped in aggregate, default 0
    private int mNumAuthEntriesTapped = 0;
    // The combined aggregate collective across the candidate get/create
    private ResponseCollective mAggregateCollectiveQuery =
            new ResponseCollective(Map.of(), Map.of());
    // The combined aggregate collective across the auth entry info
    private ResponseCollective mAggregateCollectiveAuth =
            new ResponseCollective(Map.of(), Map.of());
    // The minimum of all the providers query start time, defaults to -1
    private long mMinProviderTimestampNanoseconds = -1;
    // The maximum of all the providers query finish time, defaults to -1
    private long mMaxProviderTimestampNanoseconds = -1;
    // The total number of failures across all the providers, defaults to 0
    private int mTotalQueryFailures = 0;
    // The map of all seen framework exceptions and their counts across all providers, default empty
    private Map<String, Integer> mExceptionCountQuery = new LinkedHashMap<>();
    // The total number of failures across all auth entries, defaults to 0
    private int mTotalAuthFailures = 0;
    // The map of all seen framework exceptions and their counts across auth entries, default empty
    private Map<String, Integer> mExceptionCountAuth = new LinkedHashMap<>();

    public CandidateAggregateMetric(int sessionIdTrackOne) {
        mSessionIdProvider = sessionIdTrackOne;
    }

    public int getSessionIdProvider() {
        return mSessionIdProvider;
    }

    /**
     * This will take all the candidate data captured and aggregate that information.
     * @param providers the providers associated with the candidate flow
     */
    public void collectAverages(Map<String, ProviderSession> providers) {
        collectQueryAggregates(providers);
        collectAuthAggregates(providers);
    }

    private void collectQueryAggregates(Map<String, ProviderSession> providers) {
        mNumProviders = providers.size();
        Map<String, Integer> responseCountQuery = new LinkedHashMap<>();
        Map<EntryEnum, Integer> entryCountQuery = new LinkedHashMap<>();
        var providerSessions = providers.values();
        long min_query_start = Long.MAX_VALUE;
        long max_query_end = Long.MIN_VALUE;
        for (var session : providerSessions) {
            var sessionMetric = session.getProviderSessionMetric();
            var candidateMetric = sessionMetric.getCandidatePhasePerProviderMetric();
            if (candidateMetric.getCandidateUid() == MetricUtilities.DEFAULT_INT_32) {
                mNumProviders--;
                continue; // Do not aggregate this one and reduce the size of actual candidates
            }
            if (mServiceBeganTimeNanoseconds == -1) {
                mServiceBeganTimeNanoseconds = candidateMetric.getServiceBeganTimeNanoseconds();
            }
            mQueryReturned = mQueryReturned || candidateMetric.isQueryReturned();
            ResponseCollective candidateCollective = candidateMetric.getResponseCollective();
            ResponseCollective.combineTypeCountMaps(responseCountQuery,
                    candidateCollective.getResponseCountsMap());
            ResponseCollective.combineTypeCountMaps(entryCountQuery,
                    candidateCollective.getEntryCountsMap());
            min_query_start = Math.min(min_query_start,
                    candidateMetric.getStartQueryTimeNanoseconds());
            max_query_end = Math.max(max_query_end, candidateMetric
                    .getQueryFinishTimeNanoseconds());
            mTotalQueryFailures += (candidateMetric.isHasException() ? 1 : 0);
            if (!candidateMetric.getFrameworkException().isEmpty()) {
                mExceptionCountQuery.put(candidateMetric.getFrameworkException(),
                        mExceptionCountQuery.getOrDefault(
                                candidateMetric.getFrameworkException(), 0) + 1);
            }
        }
        mMinProviderTimestampNanoseconds = min_query_start;
        mMaxProviderTimestampNanoseconds = max_query_end;
        mAggregateCollectiveQuery = new ResponseCollective(responseCountQuery, entryCountQuery);
    }

    private void collectAuthAggregates(Map<String, ProviderSession> providers) {
        Map<String, Integer> responseCountAuth = new LinkedHashMap<>();
        Map<EntryEnum, Integer> entryCountAuth = new LinkedHashMap<>();
        var providerSessions = providers.values();
        for (var session : providerSessions) {
            var sessionMetric = session.getProviderSessionMetric();
            var authMetrics = sessionMetric.getBrowsedAuthenticationMetric();
            for (var authMetric : authMetrics) {
                if (authMetric.getProviderUid() == MetricUtilities.DEFAULT_INT_32) {
                    continue; // skip this unfilled base auth entry
                }
                mNumAuthEntriesTapped++;
                mAuthReturned = mAuthReturned || authMetric.isAuthReturned();
                ResponseCollective authCollective = authMetric.getAuthEntryCollective();
                ResponseCollective.combineTypeCountMaps(responseCountAuth,
                        authCollective.getResponseCountsMap());
                ResponseCollective.combineTypeCountMaps(entryCountAuth,
                        authCollective.getEntryCountsMap());
                mTotalQueryFailures += (authMetric.isHasException() ? 1 : 0);
                if (!authMetric.getFrameworkException().isEmpty()) {
                    mExceptionCountQuery.put(authMetric.getFrameworkException(),
                            mExceptionCountQuery.getOrDefault(
                                    authMetric.getFrameworkException(), 0) + 1);
                }
            }
        }
        mAggregateCollectiveAuth = new ResponseCollective(responseCountAuth, entryCountAuth);
    }

    public int getNumProviders() {
        return mNumProviders;
    }

    public boolean isQueryReturned() {
        return mQueryReturned;
    }


    public int getNumAuthEntriesTapped() {
        return mNumAuthEntriesTapped;
    }

    public ResponseCollective getAggregateCollectiveQuery() {
        return mAggregateCollectiveQuery;
    }

    public ResponseCollective getAggregateCollectiveAuth() {
        return mAggregateCollectiveAuth;
    }

    public boolean isAuthReturned() {
        return mAuthReturned;
    }

    public long getMaxProviderTimestampNanoseconds() {
        return mMaxProviderTimestampNanoseconds;
    }

    public long getMinProviderTimestampNanoseconds() {
        return mMinProviderTimestampNanoseconds;
    }

    public int getTotalQueryFailures() {
        return mTotalQueryFailures;
    }

    /**
     * Returns the unique, deduped, exception classtypes for logging associated with this provider.
     *
     * @return a string array for deduped exception classtypes
     */
    public String[] getUniqueExceptionStringsQuery() {
        String[] result = new String[mExceptionCountQuery.keySet().size()];
        mExceptionCountQuery.keySet().toArray(result);
        return result;
    }

    /**
     * Returns the unique, deduped, exception classtype counts for logging associated with this
     * provider.
     *
     * @return a string array for deduped classtype exception counts
     */
    public int[] getUniqueExceptionCountsQuery() {
        return mExceptionCountQuery.values().stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Returns the unique, deduped, exception classtypes for logging associated with this provider
     * for auth entries.
     *
     * @return a string array for deduped exception classtypes for auth entries
     */
    public String[] getUniqueExceptionStringsAuth() {
        String[] result = new String[mExceptionCountAuth.keySet().size()];
        mExceptionCountAuth.keySet().toArray(result);
        return result;
    }

    /**
     * Returns the unique, deduped, exception classtype counts for logging associated with this
     * provider for auth entries.
     *
     * @return a string array for deduped classtype exception counts for auth entries
     */
    public int[] getUniqueExceptionCountsAuth() {
        return mExceptionCountAuth.values().stream().mapToInt(Integer::intValue).toArray();
    }

    public long getServiceBeganTimeNanoseconds() {
        return mServiceBeganTimeNanoseconds;
    }

    public int getTotalAuthFailures() {
        return mTotalAuthFailures;
    }
}
