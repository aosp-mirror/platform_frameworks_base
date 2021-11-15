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

    @NonNull private final List<UdfpsEnrollProgressBarSegment> mSegments;

    public UdfpsEnrollProgressBarDrawable(@NonNull Context context) {
        mSegments = new ArrayList<>(UdfpsEnrollHelper.ENROLL_STAGE_COUNT);
        float startAngle = SEGMENT_GAP_ANGLE / 2f;
        final float sweepAngle = (360f / UdfpsEnrollHelper.ENROLL_STAGE_COUNT) - SEGMENT_GAP_ANGLE;
        final Runnable invalidateRunnable = this::invalidateSelf;
        for (int index = 0; index < UdfpsEnrollHelper.ENROLL_STAGE_COUNT; index++) {
            mSegments.add(new UdfpsEnrollProgressBarSegment(context, getBounds(), startAngle,
                    sweepAngle, SEGMENT_GAP_ANGLE, invalidateRunnable));
            startAngle += sweepAngle + SEGMENT_GAP_ANGLE;
        }
    }

    void setEnrollmentProgress(int remaining, int totalSteps) {
        if (remaining == totalSteps) {
            // Show some progress for the initial touch.
            setEnrollmentProgress(1);
        } else {
            setEnrollmentProgress(totalSteps - remaining);
        }
    }

    private void setEnrollmentProgress(int progressSteps) {
        Log.d(TAG, "setEnrollmentProgress: progressSteps = " + progressSteps);

        int segmentIndex = 0;
        int prevThreshold = 0;
        while (segmentIndex < mSegments.size()) {
            final UdfpsEnrollProgressBarSegment segment = mSegments.get(segmentIndex);
            final int threshold = UdfpsEnrollHelper.getStageThreshold(segmentIndex);

            if (progressSteps >= threshold && !segment.isFilledOrFilling()) {
                Log.d(TAG, "setEnrollmentProgress: segment[" + segmentIndex + "] complete");
                segment.updateProgress(1f);
                break;
            } else if (progressSteps >= prevThreshold && progressSteps < threshold) {
                final int relativeSteps = progressSteps - prevThreshold;
                final int relativeThreshold = threshold - prevThreshold;
                final float segmentProgress = (float) relativeSteps / (float) relativeThreshold;
                Log.d(TAG, "setEnrollmentProgress: segment[" + segmentIndex + "] progress = "
                        + segmentProgress);
                segment.updateProgress(segmentProgress);
                break;
            }

            segmentIndex++;
            prevThreshold = threshold;
        }

        if (progressSteps >= UdfpsEnrollHelper.getLastStageThreshold()) {
            Log.d(TAG, "setEnrollmentProgress: startCompletionAnimation");
            for (final UdfpsEnrollProgressBarSegment segment : mSegments) {
                segment.startCompletionAnimation();
            }
        } else {
            Log.d(TAG, "setEnrollmentProgress: cancelCompletionAnimation");
            for (final UdfpsEnrollProgressBarSegment segment : mSegments) {
                segment.cancelCompletionAnimation();
            }
        }
    }

    void onLastStepAcquired() {
        Log.d(TAG, "setEnrollmentProgress: onLastStepAcquired");
        setEnrollmentProgress(UdfpsEnrollHelper.getLastStageThreshold());
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Log.d(TAG, "setEnrollmentProgress: draw");

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
