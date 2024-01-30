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

import java.util.Objects;

class BaseModeRefreshRateVote implements Vote {

    /**
     * The preferred refresh rate selected by the app. It is used to validate that the summary
     * refresh rate ranges include this value, and are not restricted by a lower priority vote.
     */
    final float mAppRequestBaseModeRefreshRate;

    BaseModeRefreshRateVote(float baseModeRefreshRate) {
        mAppRequestBaseModeRefreshRate = baseModeRefreshRate;
    }

    @Override
    public void updateSummary(VoteSummary summary) {
        if (summary.appRequestBaseModeRefreshRate == 0f
                && mAppRequestBaseModeRefreshRate > 0f) {
            summary.appRequestBaseModeRefreshRate = mAppRequestBaseModeRefreshRate;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseModeRefreshRateVote that)) return false;
        return Float.compare(that.mAppRequestBaseModeRefreshRate,
                mAppRequestBaseModeRefreshRate) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAppRequestBaseModeRefreshRate);
    }

    @Override
    public String toString() {
        return "BaseModeRefreshRateVote{ mAppRequestBaseModeRefreshRate="
                + mAppRequestBaseModeRefreshRate + " }";
    }
}
