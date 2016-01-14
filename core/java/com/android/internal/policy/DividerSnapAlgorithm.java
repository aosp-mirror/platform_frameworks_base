/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.policy;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;

import java.util.ArrayList;

/**
 * Calculates the snap targets and the snap position given a position and a velocity. All positions
 * here are to be interpreted as the left/top edge of the divider rectangle.
 *
 * @hide
 */
public class DividerSnapAlgorithm {

    /**
     * 3 snap targets: left/top has 16:9 ratio (for videos), 1:1, and right/bottom has 16:9 ratio
     */
    private static final int SNAP_MODE_16_9 = 0;

    /**
     * 3 snap targets: fixed ratio, 1:1, (1 - fixed ratio)
     */
    private static final int SNAP_FIXED_RATIO = 1;

    /**
     * 1 snap target: 1:1
     */
    private static final int SNAP_ONLY_1_1 = 2;

    private final float mMinFlingVelocityPxPerSecond;
    private final int mDisplayWidth;
    private final int mDisplayHeight;
    private final int mDividerSize;
    private final ArrayList<SnapTarget> mTargets = new ArrayList<>();
    private final Rect mInsets = new Rect();
    private final int mSnapMode;
    private final float mFixedRatio;

    /** The first target which is still splitting the screen */
    private final SnapTarget mFirstSplitTarget;

    /** The last target which is still splitting the screen */
    private final SnapTarget mLastSplitTarget;

    private final SnapTarget mDismissStartTarget;
    private final SnapTarget mDismissEndTarget;
    private final SnapTarget mMiddleTarget;

    public DividerSnapAlgorithm(Resources res, float minFlingVelocityPxPerSecond,
            int displayWidth, int displayHeight, int dividerSize, boolean isHorizontalDivision,
            Rect insets) {
        mMinFlingVelocityPxPerSecond = minFlingVelocityPxPerSecond;
        mDividerSize = dividerSize;
        mDisplayWidth = displayWidth;
        mDisplayHeight = displayHeight;
        mInsets.set(insets);
        mSnapMode = res.getInteger(
                com.android.internal.R.integer.config_dockedStackDividerSnapMode);
        mFixedRatio = res.getFraction(
                com.android.internal.R.fraction.docked_stack_divider_fixed_ratio, 1, 1);
        calculateTargets(isHorizontalDivision);
        mFirstSplitTarget = mTargets.get(1);
        mLastSplitTarget = mTargets.get(mTargets.size() - 2);
        mDismissStartTarget = mTargets.get(0);
        mDismissEndTarget = mTargets.get(mTargets.size() - 1);
        mMiddleTarget = mTargets.get(mTargets.size() / 2);
    }

    public SnapTarget calculateSnapTarget(int position, float velocity) {
        if (Math.abs(velocity) < mMinFlingVelocityPxPerSecond) {
            return snap(position);
        }
        if (position < mFirstSplitTarget.position && velocity < 0) {
            return mDismissStartTarget;
        }
        if (position > mLastSplitTarget.position && velocity > 0) {
            return mDismissEndTarget;
        }
        if (velocity < 0) {
            return mFirstSplitTarget;
        } else {
            return mLastSplitTarget;
        }
    }

    public SnapTarget calculateNonDismissingSnapTarget(int position) {
        SnapTarget target = snap(position);
        if (target == mDismissStartTarget) {
            return mFirstSplitTarget;
        } else if (target == mDismissEndTarget) {
            return mLastSplitTarget;
        } else {
            return target;
        }
    }

    public float calculateDismissingFraction(int position) {
        if (position < mFirstSplitTarget.position) {
            return 1f - (float) position / mFirstSplitTarget.position;
        } else if (position > mLastSplitTarget.position) {
            return (float) (position - mLastSplitTarget.position)
                    / (mDismissEndTarget.position - mLastSplitTarget.position);
        }
        return 0f;
    }

    public SnapTarget getClosestDismissTarget(int position) {
        if (position - mDismissStartTarget.position < mDismissEndTarget.position - position) {
            return mDismissStartTarget;
        } else {
            return mDismissEndTarget;
        }
    }

    public SnapTarget getFirstSplitTarget() {
        return mFirstSplitTarget;
    }

    public SnapTarget getLastSplitTarget() {
        return mLastSplitTarget;
    }

    public SnapTarget getDismissStartTarget() {
        return mDismissStartTarget;
    }

    public SnapTarget getDismissEndTarget() {
        return mDismissEndTarget;
    }

    private SnapTarget snap(int position) {
        int minIndex = -1;
        int minDistance = Integer.MAX_VALUE;
        int size = mTargets.size();
        for (int i = 0; i < size; i++) {
            int distance = Math.abs(position - mTargets.get(i).position);
            if (distance < minDistance) {
                minIndex = i;
                minDistance = distance;
            }
        }
        return mTargets.get(minIndex);
    }

    private void calculateTargets(boolean isHorizontalDivision) {
        mTargets.clear();
        int dividerMax = isHorizontalDivision
                ? mDisplayHeight
                : mDisplayWidth;
        mTargets.add(new SnapTarget(-mDividerSize, SnapTarget.FLAG_DISMISS_START));
        switch (mSnapMode) {
            case SNAP_MODE_16_9:
                addRatio16_9Targets(isHorizontalDivision);
                break;
            case SNAP_FIXED_RATIO:
                addFixedDivisionTargets(isHorizontalDivision);
                break;
            case SNAP_ONLY_1_1:
                addMiddleTarget(isHorizontalDivision);
                break;
        }
        mTargets.add(new SnapTarget(dividerMax, SnapTarget.FLAG_DISMISS_END));
    }

    private void addFixedDivisionTargets(boolean isHorizontalDivision) {
        int start = isHorizontalDivision ? mInsets.top : mInsets.left;
        int end = isHorizontalDivision
                ? mDisplayHeight - mInsets.bottom
                : mDisplayWidth - mInsets.right;
        mTargets.add(new SnapTarget((int) (start + mFixedRatio * (end - start)) - mDividerSize / 2,
                SnapTarget.FLAG_NONE));
        addMiddleTarget(isHorizontalDivision);
        mTargets.add(new SnapTarget((int) (start + (1 - mFixedRatio) * (end - start))
                - mDividerSize / 2, SnapTarget.FLAG_NONE));
    }

    private void addRatio16_9Targets(boolean isHorizontalDivision) {
        int start = isHorizontalDivision ? mInsets.top : mInsets.left;
        int end = isHorizontalDivision
                ? mDisplayHeight - mInsets.bottom
                : mDisplayWidth - mInsets.right;
        int startOther = isHorizontalDivision ? mInsets.left : mInsets.top;
        int endOther = isHorizontalDivision
                ? mDisplayWidth - mInsets.right
                : mDisplayHeight - mInsets.bottom;
        float size = 9.0f / 16.0f * (endOther - startOther);
        int sizeInt = (int) Math.floor(size);
        mTargets.add(new SnapTarget(start + sizeInt, SnapTarget.FLAG_NONE));
        addMiddleTarget(isHorizontalDivision);
        mTargets.add(new SnapTarget(end - sizeInt - mDividerSize, SnapTarget.FLAG_NONE));
    }

    private void addMiddleTarget(boolean isHorizontalDivision) {
        int start = isHorizontalDivision ? mInsets.top : mInsets.left;
        int end = isHorizontalDivision
                ? mDisplayHeight - mInsets.bottom
                : mDisplayWidth - mInsets.right;
        mTargets.add(new SnapTarget(start + (end - start) / 2 - mDividerSize / 2,
                SnapTarget.FLAG_NONE));
    }

    public SnapTarget getMiddleTarget() {
        return mMiddleTarget;
    }

    /**
     * Represents a snap target for the divider.
     */
    public static class SnapTarget {
        public static final int FLAG_NONE = 0;

        /** If the divider reaches this value, the left/top task should be dismissed. */
        public static final int FLAG_DISMISS_START = 1;

        /** If the divider reaches this value, the right/bottom task should be dismissed */
        public static final int FLAG_DISMISS_END = 2;

        public final int position;
        public final int flag;

        public SnapTarget(int position, int flag) {
            this.position = position;
            this.flag = flag;
        }
    }
}
