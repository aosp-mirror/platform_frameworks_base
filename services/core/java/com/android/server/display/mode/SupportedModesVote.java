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

package com.android.server.display.mode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

class SupportedModesVote implements Vote {

    final List<SupportedMode> mSupportedModes;

    SupportedModesVote(List<SupportedMode> supportedModes) {
        mSupportedModes = Collections.unmodifiableList(supportedModes);
    }

    /**
     * Summary should have subset of supported modes.
     * If Vote1.supportedModes=(A,B), Vote2.supportedModes=(B,C) then summary.supportedModes=(B)
     * If summary.supportedModes==null then there is no restriction on supportedModes
     */
    @Override
    public void updateSummary(VoteSummary summary) {
        if (summary.supportedModes == null) {
            summary.supportedModes = new ArrayList<>(mSupportedModes);
        } else {
            summary.supportedModes.retainAll(mSupportedModes);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SupportedModesVote that)) return false;
        return mSupportedModes.equals(that.mSupportedModes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSupportedModes);
    }

    @Override
    public String toString() {
        return "SupportedModesVote{ mSupportedModes=" + mSupportedModes + " }";
    }

    static class SupportedMode {
        final float mPeakRefreshRate;
        final float mVsyncRate;


        SupportedMode(float peakRefreshRate, float vsyncRate) {
            mPeakRefreshRate = peakRefreshRate;
            mVsyncRate = vsyncRate;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SupportedMode that)) return false;
            return Float.compare(that.mPeakRefreshRate, mPeakRefreshRate) == 0
                    && Float.compare(that.mVsyncRate, mVsyncRate) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPeakRefreshRate, mVsyncRate);
        }

        @Override
        public String toString() {
            return "SupportedMode{ mPeakRefreshRate=" + mPeakRefreshRate
                    + ", mVsyncRate=" + mVsyncRate + " }";
        }
    }
}
