/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.integrity.parser;

import android.annotation.Nullable;

/**
 * A wrapper class to represent an indexing range that is identified by the {@link
 * RuleIndexingController}.
 */
public class RuleIndexRange {
    private int mStartIndex;
    private int mEndIndex;

    /** Constructor with start and end indexes. */
    public RuleIndexRange(int startIndex, int endIndex) {
        this.mStartIndex = startIndex;
        this.mEndIndex = endIndex;
    }

    /** Returns the startIndex. */
    public int getStartIndex() {
        return mStartIndex;
    }

    /** Returns the end index. */
    public int getEndIndex() {
        return mEndIndex;
    }

    @Override
    public boolean equals(@Nullable Object object) {
        return mStartIndex == ((RuleIndexRange) object).getStartIndex()
                && mEndIndex == ((RuleIndexRange) object).getEndIndex();
    }

    @Override
    public String toString() {
        return String.format("Range{%d, %d}", mStartIndex, mEndIndex);
    }
}
