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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

class SupportedRefreshRatesVote implements Vote {

    final List<RefreshRates> mRefreshRates;

    SupportedRefreshRatesVote(List<RefreshRates> refreshRates) {
        mRefreshRates = Collections.unmodifiableList(refreshRates);
    }

    /**
     * Summary should have subset of supported modes.
     * If Vote1.refreshRates=(A,B), Vote2.refreshRates=(B,C)
     *  then summary.supportedRefreshRates=(B)
     * If summary.supportedRefreshRates==null then there is no restriction on supportedRefreshRates
     */
    @Override
    public void updateSummary(@NonNull VoteSummary summary) {
        if (summary.supportedRefreshRates == null) {
            summary.supportedRefreshRates = new ArrayList<>(mRefreshRates);
        } else {
            summary.supportedRefreshRates.retainAll(mRefreshRates);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SupportedRefreshRatesVote that)) return false;
        return mRefreshRates.equals(that.mRefreshRates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRefreshRates);
    }

    @Override
    public String toString() {
        return "SupportedRefreshRatesVote{ mSupportedModes=" + mRefreshRates + " }";
    }

    static class RefreshRates {
        final float mPeakRefreshRate;
        final float mVsyncRate;

        RefreshRates(float peakRefreshRate, float vsyncRate) {
            mPeakRefreshRate = peakRefreshRate;
            mVsyncRate = vsyncRate;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RefreshRates that)) return false;
            return Float.compare(that.mPeakRefreshRate, mPeakRefreshRate) == 0
                    && Float.compare(that.mVsyncRate, mVsyncRate) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPeakRefreshRate, mVsyncRate);
        }

        @Override
        public String toString() {
            return "RefreshRates{ mPeakRefreshRate=" + mPeakRefreshRate
                    + ", mVsyncRate=" + mVsyncRate + " }";
        }
    }
}
