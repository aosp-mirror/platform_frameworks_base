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
 * A part of the Candidate Phase, but emitted alongside {@link ChosenProviderFinalPhaseMetric}.
 * The user is shown various entries from the provider responses, and may selectively browse through
 * many entries. It is possible that the initial set of browsing is for a provider that is
 * ultimately not chosen. This metric will be gathered PER browsing click, and aggregated, so that
 * we can understand where user interaction is more cumbersome, informing us for future
 * improvements. This can only be complete when the browsing is finished, ending in a final user
 * choice, or possibly a cancellation. Thus, this will be collected and emitted in the final phase,
 * though collection will begin in the candidate phase when the user begins browsing options.
 */
public class CandidateBrowsingPhaseMetric {
    // The EntryEnum that was pressed, defaults to -1
    private int mEntryEnum = EntryEnum.UNKNOWN.getMetricCode();
    // The provider associated with the press, defaults to -1
    private int mProviderUid = -1;

    /* -- The Entry of this tap -- */

    public void setEntryEnum(int entryEnum) {
        mEntryEnum = entryEnum;
    }

    public int getEntryEnum() {
        return mEntryEnum;
    }

    /* -- The Provider UID of this Tap -- */

    public void setProviderUid(int providerUid) {
        mProviderUid = providerUid;
    }

    public int getProviderUid() {
        return mProviderUid;
    }
}
