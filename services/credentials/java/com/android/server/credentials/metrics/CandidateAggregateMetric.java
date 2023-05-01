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

import com.android.server.credentials.ProviderSession;

import java.util.Map;

/**
 * This will generate most of its data via using the information of {@link CandidatePhaseMetric}
 * across all the providers. This belongs to the metric flow where the calling app is known.
 */
public class CandidateAggregateMetric {

    private static final String TAG = "CandidateProviderMetric";
    // The session id of this provider metric
    private final int mSessionIdProvider;
    // Indicates if this provider returned from the query phase, default false
    private boolean mQueryReturned = false;
    // Indicates the total number of providers this aggregate captures information for, default 0
    private int mNumProviders = 0;
    // Indicates the total number of authentication entries that were tapped in aggregate, default 0
    private int mNumAuthEntriesTapped = 0;

    public CandidateAggregateMetric(int sessionIdTrackOne) {
        mSessionIdProvider = sessionIdTrackOne;
    }

    public int getSessionIdProvider() {
        return mSessionIdProvider;
    }

    /**
     * This will take all the candidate data captured and aggregate that information.
     * TODO(b/271135048) : Add on authentication entry outputs from track 2 here as well once
     * generated
     * @param providers the providers associated with the candidate flow
     */
    public void collectAverages(Map<String, ProviderSession> providers) {
        // TODO(b/271135048) : Complete this method
        mNumProviders = providers.size();
        var providerSessions = providers.values();
        for (var session : providerSessions) {
            var metric = session.getProviderSessionMetric();
            mQueryReturned = mQueryReturned || metric
                    .mCandidatePhasePerProviderMetric.isQueryReturned();
        }
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
}
