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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

class CombinedVote implements Vote {
    final List<Vote> mVotes;

    CombinedVote(List<Vote> votes) {
        mVotes = Collections.unmodifiableList(votes);
    }

    @Override
    public void updateSummary(VoteSummary summary) {
        mVotes.forEach(vote -> vote.updateSummary(summary));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CombinedVote that)) return false;
        return Objects.equals(mVotes, that.mVotes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mVotes);
    }

    @Override
    public String toString() {
        return "CombinedVote{ mVotes=" + mVotes + " }";
    }
}
