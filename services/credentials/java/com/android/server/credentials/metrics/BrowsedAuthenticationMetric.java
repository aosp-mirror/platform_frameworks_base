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
 * Encapsulates an authentication entry click atom, as a part of track 2.
 * Contains information about what was collected from the authentication entry output.
 */
public class BrowsedAuthenticationMetric {
    // The session id of this provider known flow related metric
    private final int mSessionIdProvider;
    // TODO(b/271135048) - Match the atom and provide a clean per provider session metric
    // encapsulation.

    public BrowsedAuthenticationMetric(int sessionIdProvider) {
        mSessionIdProvider = sessionIdProvider;
    }

    public int getSessionIdProvider() {
        return mSessionIdProvider;
    }
}
