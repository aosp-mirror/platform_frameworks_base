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
    // The session id of this provider, default set to -1
    private int mSessionId = -1;

    public void setSessionId(int sessionId) {
        mSessionId = sessionId;
    }

    public int getSessionId() {
        return mSessionId;
    }

    /**
     * This will take all the candidate data captured and aggregate that information.
     * TODO(b/271135048) : Add on authentication entry outputs from track 2 here as well once
     * generated
     * @param providers a map that associates with all provider sessions
     */
    public void collectAverages(Map<String, ProviderSession> providers) {
        // TODO(b/271135048) : Complete this method
    }
}
