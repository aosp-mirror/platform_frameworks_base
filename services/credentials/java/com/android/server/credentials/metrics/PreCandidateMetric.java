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

/**
 * This handles metrics collected prior to any remote calls to providers.
 * TODO(b/270403549) - iterate on this in V3+
 */
public class PreCandidateMetric {

    private static final String TAG = "PreCandidateMetric";

    // Raw timestamps in nanoseconds, *the only* one logged as such (i.e. 64 bits) since it is a
    // reference point.

    private long mCredentialServiceStartedTimeNanoseconds = -1;
    private long mCredentialServiceBeginQueryTimeNanoseconds = -1;

    public PreCandidateMetric() {
    }

    /* ---------- Latencies ---------- */

    /* -- Direct Latencies -- */

    public int getServiceStartToQueryLatencyMicroseconds() {
        return (int) ((this.mCredentialServiceStartedTimeNanoseconds
                - this.mCredentialServiceBeginQueryTimeNanoseconds) / 1000);
    }

    /* -- Timestamps -- */

    public void setCredentialServiceStartedTimeNanoseconds(
            long credentialServiceStartedTimeNanoseconds
    ) {
        this.mCredentialServiceStartedTimeNanoseconds = credentialServiceStartedTimeNanoseconds;
    }

    public void setCredentialServiceBeginQueryTimeNanoseconds(
            long credentialServiceBeginQueryTimeNanoseconds) {
        mCredentialServiceBeginQueryTimeNanoseconds = credentialServiceBeginQueryTimeNanoseconds;
    }

    public long getCredentialServiceStartedTimeNanoseconds() {
        return mCredentialServiceStartedTimeNanoseconds;
    }

    public long getCredentialServiceBeginQueryTimeNanoseconds() {
        return mCredentialServiceBeginQueryTimeNanoseconds;
    }
}
