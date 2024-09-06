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

import android.annotation.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class SupportedModesVote implements Vote {

    final List<Integer> mModeIds;

    SupportedModesVote(List<Integer> modeIds) {
        mModeIds = Collections.unmodifiableList(modeIds);
    }
    @Override
    public void updateSummary(@NonNull VoteSummary summary) {
        if (summary.supportedModeIds == null) {
            summary.supportedModeIds = mModeIds;
        } else {
            summary.supportedModeIds.retainAll(mModeIds);
        }
    }

    @Override
    public String toString() {
        return "SupportedModesVote{ mModeIds=" + mModeIds + " }";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SupportedModesVote that)) return false;
        return mModeIds.equals(that.mModeIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mModeIds);
    }
}
