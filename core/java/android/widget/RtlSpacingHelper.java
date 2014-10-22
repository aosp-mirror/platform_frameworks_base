/*
 * Copyright (C) 2014 The Android Open Source Project
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


package android.widget;

/**
 * RtlSpacingHelper manages the relationship between left/right and start/end for views
 * that need to maintain both absolute and relative settings for a form of spacing similar
 * to view padding.
 */
class RtlSpacingHelper {
    public static final int UNDEFINED = Integer.MIN_VALUE;

    private int mLeft = 0;
    private int mRight = 0;
    private int mStart = UNDEFINED;
    private int mEnd = UNDEFINED;
    private int mExplicitLeft = 0;
    private int mExplicitRight = 0;

    private boolean mIsRtl = false;
    private boolean mIsRelative = false;

    public int getLeft() {
        return mLeft;
    }

    public int getRight() {
        return mRight;
    }

    public int getStart() {
        return mIsRtl ? mRight : mLeft;
    }

    public int getEnd() {
        return mIsRtl ? mLeft : mRight;
    }

    public void setRelative(int start, int end) {
        mStart = start;
        mEnd = end;
        mIsRelative = true;
        if (mIsRtl) {
            if (end != UNDEFINED) mLeft = end;
            if (start != UNDEFINED) mRight = start;
        } else {
            if (start != UNDEFINED) mLeft = start;
            if (end != UNDEFINED) mRight = end;
        }
    }

    public void setAbsolute(int left, int right) {
        mIsRelative = false;
        if (left != UNDEFINED) mLeft = mExplicitLeft = left;
        if (right != UNDEFINED) mRight = mExplicitRight = right;
    }

    public void setDirection(boolean isRtl) {
        if (isRtl == mIsRtl) {
            return;
        }
        mIsRtl = isRtl;
        if (mIsRelative) {
            if (isRtl) {
                mLeft = mEnd != UNDEFINED ? mEnd : mExplicitLeft;
                mRight = mStart != UNDEFINED ? mStart : mExplicitRight;
            } else {
                mLeft = mStart != UNDEFINED ? mStart : mExplicitLeft;
                mRight = mEnd != UNDEFINED ? mEnd : mExplicitRight;
            }
        } else {
            mLeft = mExplicitLeft;
            mRight = mExplicitRight;
        }
    }
}
