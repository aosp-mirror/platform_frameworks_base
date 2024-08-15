/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.mode;

import android.annotation.NonNull;

import java.util.Objects;

class RequestedRefreshRateVote implements Vote {
    final float mRefreshRate;

    RequestedRefreshRateVote(float refreshRate) {
        this.mRefreshRate = refreshRate;
    }

    @Override
    public void updateSummary(@NonNull VoteSummary summary) {
        summary.requestedRefreshRates.add(mRefreshRate);
    }

    @Override
    public String toString() {
        return "RequestedRefreshRateVote{ refreshRate=" + mRefreshRate + " }";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RequestedRefreshRateVote that)) return false;
        return Float.compare(mRefreshRate, that.mRefreshRate) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRefreshRate);
    }
}
