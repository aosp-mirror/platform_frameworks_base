/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * UDFPS enrollment progress bar.
 */
public class UdfpsEnrollProgressBarDrawable extends Drawable {
    private static final String TAG = "UdfpsProgressBar";

    private static final float SEGMENT_GAP_ANGLE = 12f;

    @NonNull private final Context mContext;

    @Nullable private UdfpsEnrollHelper mEnrollHelper;
    @NonNull private List<UdfpsEnrollProgressBarSegment> mSegments = new ArrayList<>();
    private int mTotalSteps = 1;
    private int mProgressSteps = 0;
    private boolean mIsShowingHelp = false;

    public UdfpsEnrollProgressBarDrawable(@NonNull Context context) {
        mContext = context;
    }

    void setEnrollHelper(@Nullable UdfpsEnrollHelper enrollHelper) {
        mEnrollHelper = enrollHelper;
        if (enrollHelper != null) {
            final int stageCount = enrollHelper.getStageCount();
            mSegments = new ArrayList<>(stageCount);
            float startAngle = SEGMENT_GAP_ANGLE / 2f;
            final float sweepAngle = (360f / stageCount) - SEGMENT_GAP_ANGLE;
            final Runnable invalidateRunnable = this::invalidateSelf;
            for (int index = 0; index < stageCount; index++) {
                mSegments.add(new UdfpsEnrollProgressBarSegment(mContext, getBounds(), startAngle,
                        sweepAngle, SEGMENT_GAP_ANGLE, invalidateRunnable));
                startAngle += sweepAngle + SEGMENT_GAP_ANGLE;
            }
            invalidateSelf();
        }
    }

    void onEnrollmentProgress(int remaining, int totalSteps) {
        mTotalSteps = totalSteps;
        updateState(getProgressSteps(remaining, totalSteps), false /* isShowingHelp */);
    }

    void onEnrollmentHelp(int remaining, int totalSteps) {
        updateState(getProgressSteps(remaining, totalSteps), true /* isShowingHelp */);
    }

    void onLastStepAcquired() {
        updateState(mTotalSteps, false /* isShowingHelp */);
    }

    private static int getProgressSteps(int remaining, int totalSteps) {
        // Show some progress for the initial touch.
        return Math.max(1, totalSteps - remaining);
    }

    private void updateState(int progressSteps, boolean isShowingHelp) {
        updateProgress(progressSteps);
        updateFillColor(isShowingHelp);
    }

    private void updateProgress(int progressSteps) {
        if (mProgressSteps == progressSteps) {
            return;
        }
        mProgressSteps = progressSteps;

        if (mEnrollHelper == null) {
            Log.e(TAG, "updateState: UDFPS enroll helper was null");
            return;
        }

        int index = 0;
        int prevThreshold = 0;
        while (index < mSegments.size()) {
            final UdfpsEnrollProgressBarSegment segment = mSegments.get(index);
            final int thresholdSteps = mEnrollHelper.getStageThresholdSteps(mTotalSteps, index);
            if (progressSteps >= thresholdSteps && segment.getProgress() < 1f) {
                segment.updateProgress(1f);
                break;
            } else if (progressSteps >= prevThreshold && progressSteps < thresholdSteps) {
                final int relativeSteps = progressSteps - prevThreshold;
                final int relativeThreshold = thresholdSteps - prevThreshold;
                final float segmentProgress = (float) relativeSteps / (float) relativeThreshold;
                segment.updateProgress(segmentProgress);
                break;
            }

            index++;
            prevThreshold = thresholdSteps;
        }

        if (progressSteps >= mTotalSteps) {
            for (final UdfpsEnrollProgressBarSegment segment : mSegments) {
                segment.startCompletionAnimation();
            }
        } else {
            for (final UdfpsEnrollProgressBarSegment segment : mSegments) {
                segment.cancelCompletionAnimation();
            }
        }
    }

    private void updateFillColor(boolean isShowingHelp) {
        if (mIsShowingHelp == isShowingHelp) {
            return;
        }
        mIsShowingHelp = isShowingHelp;

        for (final UdfpsEnrollProgressBarSegment segment : mSegments) {
            segment.updateFillColor(isShowingHelp);
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.save();

        // Progress starts from the top, instead of the right
        canvas.rotate(-90f, getBounds().centerX(), getBounds().centerY());

        // Draw each of the enroll segments.
        for (final UdfpsEnrollProgressBarSegment segment : mSegments) {
            segment.draw(canvas);
        }

        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return 0;
    }
}
