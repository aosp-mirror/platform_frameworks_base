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

package com.android.systemui.stackdivider;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.DisplayInfo;

import com.android.systemui.statusbar.FlingAnimationUtils;

import java.util.ArrayList;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;

/**
 * Calculates the snap targets and the snap position given a position and a velocity. All positions
 * here are to be interpreted as the left/top edge of the divider rectangle.
 */
public class DividerSnapAlgorithm {

    private final Context mContext;
    private final FlingAnimationUtils mFlingAnimationUtils;
    private final int mDividerSize;
    private final ArrayList<SnapTarget> mTargets;

    /** The first target which is still splitting the screen */
    private final SnapTarget mFirstSplitTarget;

    /** The last target which is still splitting the screen */
    private final SnapTarget mLastSplitTarget;

    private final SnapTarget mDismissStartTarget;
    private final SnapTarget mDismissEndTarget;

    public DividerSnapAlgorithm(Context ctx, FlingAnimationUtils flingAnimationUtils,
            int dividerSize, boolean isHorizontalDivision) {
        mContext = ctx;
        mFlingAnimationUtils = flingAnimationUtils;
        mDividerSize = dividerSize;
        mTargets = calculateTargets(isHorizontalDivision);
        mFirstSplitTarget = mTargets.get(1);
        mLastSplitTarget = mTargets.get(mTargets.size() - 2);
        mDismissStartTarget = mTargets.get(0);
        mDismissEndTarget = mTargets.get(mTargets.size() - 1);
    }

    public SnapTarget calculateSnapTarget(int position, float velocity) {
        if (Math.abs(velocity) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
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

    private ArrayList<SnapTarget> calculateTargets(boolean isHorizontalDivision) {
        ArrayList<SnapTarget> targets = new ArrayList<>();
        DisplayMetrics info = mContext.getResources().getDisplayMetrics();
        int dividerMax = isHorizontalDivision
                ? info.heightPixels
                : info.widthPixels;

        // TODO: Better calculation
        targets.add(new SnapTarget(-mDividerSize, SnapTarget.FLAG_DISMISS_START));
        targets.add(new SnapTarget((int) (0.3415f * dividerMax) - mDividerSize / 2,
                SnapTarget.FLAG_NONE));
        targets.add(new SnapTarget(dividerMax / 2 - mDividerSize / 2, SnapTarget.FLAG_NONE));
        targets.add(new SnapTarget((int) (0.6585f * dividerMax) - mDividerSize / 2,
                SnapTarget.FLAG_NONE));
        targets.add(new SnapTarget(dividerMax, SnapTarget.FLAG_DISMISS_END));
        return targets;
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
