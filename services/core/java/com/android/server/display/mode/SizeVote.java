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

class SizeVote implements Vote {

    /**
     * The requested width of the display in pixels;
     */
    final int mWidth;

    /**
     * The requested height of the display in pixels;
     */
    final int mHeight;

    /**
     * Min requested width of the display in pixels;
     */
    final int mMinWidth;

    /**
     * Min requested height of the display in pixels;
     */
    final int mMinHeight;

    SizeVote(int width, int height, int minWidth, int minHeight) {
        mWidth = width;
        mHeight = height;
        mMinWidth = minWidth;
        mMinHeight = minHeight;
    }

    @Override
    public void updateSummary(VoteSummary summary) {
        if (mHeight > 0 && mWidth > 0) {
            // For display size, disable refresh rate switching and base mode refresh rate use
            // only the first vote we come across (i.e. the highest priority vote that includes
            // the attribute).
            if (summary.width == Vote.INVALID_SIZE && summary.height == Vote.INVALID_SIZE) {
                summary.width = mWidth;
                summary.height = mHeight;
                summary.minWidth = mMinWidth;
                summary.minHeight = mMinHeight;
            } else if (summary.mIsDisplayResolutionRangeVotingEnabled) {
                summary.width = Math.min(summary.width, mWidth);
                summary.height = Math.min(summary.height, mHeight);
                summary.minWidth = Math.max(summary.minWidth, mMinWidth);
                summary.minHeight = Math.max(summary.minHeight, mMinHeight);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SizeVote sizeVote)) return false;
        return mWidth == sizeVote.mWidth && mHeight == sizeVote.mHeight
                && mMinWidth == sizeVote.mMinWidth && mMinHeight == sizeVote.mMinHeight;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mWidth, mHeight, mMinWidth, mMinHeight);
    }

    @Override
    public String toString() {
        return "SizeVote{ mWidth=" + mWidth + ", mHeight=" + mHeight
                + ", mMinWidth=" + mMinWidth + ", mMinHeight=" + mMinHeight + " }";
    }
}
