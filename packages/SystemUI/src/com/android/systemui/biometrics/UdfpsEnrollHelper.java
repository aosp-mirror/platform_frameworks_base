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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.PointF;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Helps keep track of enrollment state and animates the progress bar accordingly.
 */
public class UdfpsEnrollHelper {
    private static final String TAG = "UdfpsEnrollHelper";

    private static final String SCALE_OVERRIDE =
            "com.android.systemui.biometrics.UdfpsEnrollHelper.scale";
    private static final float SCALE = 0.5f;

    private static final String NEW_COORDS_OVERRIDE =
            "com.android.systemui.biometrics.UdfpsNewCoords";

    static final int ENROLL_STAGE_COUNT = 4;

    // TODO(b/198928407): Consolidate with FingerprintEnrollEnrolling
    private static final int[] STAGE_THRESHOLDS = new int[] {
            2, // center
            18, // guided
            22, // fingertip
            38, // edges
    };

    interface Listener {
        void onEnrollmentProgress(int remaining, int totalSteps);
        void onEnrollmentHelp(int remaining, int totalSteps);
        void onLastStepAcquired();
    }

    @NonNull private final Context mContext;
    // IUdfpsOverlayController reason
    private final int mEnrollReason;
    private final boolean mAccessibilityEnabled;
    @NonNull private final List<PointF> mGuidedEnrollmentPoints;

    private int mTotalSteps = -1;
    private int mRemainingSteps = -1;

    // Note that this is actually not equal to "mTotalSteps - mRemainingSteps", because the
    // interface makes no promises about monotonically increasing by one each time.
    private int mLocationsEnrolled = 0;

    private int mCenterTouchCount = 0;

    @Nullable Listener mListener;

    public UdfpsEnrollHelper(@NonNull Context context, int reason) {
        mContext = context;
        mEnrollReason = reason;

        final AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
        mAccessibilityEnabled = am.isEnabled();

        mGuidedEnrollmentPoints = new ArrayList<>();

        // Number of pixels per mm
        float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1,
                context.getResources().getDisplayMetrics());
        boolean useNewCoords = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                NEW_COORDS_OVERRIDE, 0,
                UserHandle.USER_CURRENT) != 0;
        if (useNewCoords && (Build.IS_ENG || Build.IS_USERDEBUG)) {
            Log.v(TAG, "Using new coordinates");
            mGuidedEnrollmentPoints.add(new PointF(-0.15f * px, -1.02f * px));
            mGuidedEnrollmentPoints.add(new PointF(-0.15f * px,  1.02f * px));
            mGuidedEnrollmentPoints.add(new PointF( 0.29f * px,  0.00f * px));
            mGuidedEnrollmentPoints.add(new PointF( 2.17f * px, -2.35f * px));
            mGuidedEnrollmentPoints.add(new PointF( 1.07f * px, -3.96f * px));
            mGuidedEnrollmentPoints.add(new PointF(-0.37f * px, -4.31f * px));
            mGuidedEnrollmentPoints.add(new PointF(-1.69f * px, -3.29f * px));
            mGuidedEnrollmentPoints.add(new PointF(-2.48f * px, -1.23f * px));
            mGuidedEnrollmentPoints.add(new PointF(-2.48f * px,  1.23f * px));
            mGuidedEnrollmentPoints.add(new PointF(-1.69f * px,  3.29f * px));
            mGuidedEnrollmentPoints.add(new PointF(-0.37f * px,  4.31f * px));
            mGuidedEnrollmentPoints.add(new PointF( 1.07f * px,  3.96f * px));
            mGuidedEnrollmentPoints.add(new PointF( 2.17f * px,  2.35f * px));
            mGuidedEnrollmentPoints.add(new PointF( 2.58f * px,  0.00f * px));
        } else {
            Log.v(TAG, "Using old coordinates");
            mGuidedEnrollmentPoints.add(new PointF( 2.00f * px,  0.00f * px));
            mGuidedEnrollmentPoints.add(new PointF( 0.87f * px, -2.70f * px));
            mGuidedEnrollmentPoints.add(new PointF(-1.80f * px, -1.31f * px));
            mGuidedEnrollmentPoints.add(new PointF(-1.80f * px,  1.31f * px));
            mGuidedEnrollmentPoints.add(new PointF( 0.88f * px,  2.70f * px));
            mGuidedEnrollmentPoints.add(new PointF( 3.94f * px, -1.06f * px));
            mGuidedEnrollmentPoints.add(new PointF( 2.90f * px, -4.14f * px));
            mGuidedEnrollmentPoints.add(new PointF(-0.52f * px, -5.95f * px));
            mGuidedEnrollmentPoints.add(new PointF(-3.33f * px, -3.33f * px));
            mGuidedEnrollmentPoints.add(new PointF(-3.99f * px, -0.35f * px));
            mGuidedEnrollmentPoints.add(new PointF(-3.62f * px,  2.54f * px));
            mGuidedEnrollmentPoints.add(new PointF(-1.49f * px,  5.57f * px));
            mGuidedEnrollmentPoints.add(new PointF( 2.29f * px,  4.92f * px));
            mGuidedEnrollmentPoints.add(new PointF( 3.82f * px,  1.78f * px));
        }
    }

    static int getStageThreshold(int index) {
        return STAGE_THRESHOLDS[index];
    }

    static int getLastStageThreshold() {
        return STAGE_THRESHOLDS[ENROLL_STAGE_COUNT - 1];
    }

    boolean shouldShowProgressBar() {
        return mEnrollReason == IUdfpsOverlayController.REASON_ENROLL_ENROLLING;
    }

    void onEnrollmentProgress(int remaining) {
        Log.d(TAG, "onEnrollmentProgress: remaining = " + remaining
                + ", mRemainingSteps = " + mRemainingSteps
                + ", mTotalSteps = " + mTotalSteps
                + ", mLocationsEnrolled = " + mLocationsEnrolled
                + ", mCenterTouchCount = " + mCenterTouchCount);

        if (remaining != mRemainingSteps) {
            mLocationsEnrolled++;
            if (isCenterEnrollmentStage()) {
                mCenterTouchCount++;
            }
        }

        if (mTotalSteps == -1) {
            mTotalSteps = remaining;

            // Allocate (or subtract) any extra steps for the first enroll stage.
            final int extraSteps = mTotalSteps - getLastStageThreshold();
            if (extraSteps != 0) {
                for (int stageIndex = 0; stageIndex < ENROLL_STAGE_COUNT; stageIndex++) {
                    STAGE_THRESHOLDS[stageIndex] =
                            Math.max(0, STAGE_THRESHOLDS[stageIndex] + extraSteps);
                }
            }
        }

        mRemainingSteps = remaining;

        if (mListener != null) {
            mListener.onEnrollmentProgress(remaining, mTotalSteps);
        }
    }

    void onEnrollmentHelp() {
        if (mListener != null) {
            mListener.onEnrollmentHelp(mRemainingSteps, mTotalSteps);
        }
    }

    void setListener(Listener listener) {
        mListener = listener;

        // Only notify during setListener if enrollment is already in progress, so the progress
        // bar can be updated. If enrollment has not started yet, the progress bar will be empty
        // anyway.
        if (mListener != null && mTotalSteps != -1) {
            mListener.onEnrollmentProgress(mRemainingSteps, mTotalSteps);
        }
    }

    boolean isCenterEnrollmentStage() {
        if (mTotalSteps == -1 || mRemainingSteps == -1) {
            return true;
        }
        return mTotalSteps - mRemainingSteps < STAGE_THRESHOLDS[0];
    }

    boolean isGuidedEnrollmentStage() {
        if (mAccessibilityEnabled || mTotalSteps == -1 || mRemainingSteps == -1) {
            return false;
        }
        final int progressSteps = mTotalSteps - mRemainingSteps;
        return progressSteps >= STAGE_THRESHOLDS[0] && progressSteps < STAGE_THRESHOLDS[1];
    }

    boolean isTipEnrollmentStage() {
        if (mTotalSteps == -1 || mRemainingSteps == -1) {
            return false;
        }
        final int progressSteps = mTotalSteps - mRemainingSteps;
        return progressSteps >= STAGE_THRESHOLDS[1] && progressSteps < STAGE_THRESHOLDS[2];
    }

    boolean isEdgeEnrollmentStage() {
        if (mTotalSteps == -1 || mRemainingSteps == -1) {
            return false;
        }
        return mTotalSteps - mRemainingSteps >= STAGE_THRESHOLDS[2];
    }

    @NonNull
    PointF getNextGuidedEnrollmentPoint() {
        if (mAccessibilityEnabled || !isGuidedEnrollmentStage()) {
            return new PointF(0f, 0f);
        }

        float scale = SCALE;
        if (Build.IS_ENG || Build.IS_USERDEBUG) {
            scale = Settings.Secure.getFloatForUser(mContext.getContentResolver(),
                    SCALE_OVERRIDE, SCALE,
                    UserHandle.USER_CURRENT);
        }
        final int index = mLocationsEnrolled - mCenterTouchCount;
        final PointF originalPoint = mGuidedEnrollmentPoints
                .get(index % mGuidedEnrollmentPoints.size());
        return new PointF(originalPoint.x * scale, originalPoint.y * scale);
    }

    void animateIfLastStep() {
        Log.d(TAG, "animateIfLastStep: mRemainingSteps = " + mRemainingSteps);
        if (mListener == null) {
            Log.e(TAG, "animateIfLastStep, null listener");
            return;
        }

        if (mRemainingSteps <= 2 && mRemainingSteps >= 0) {
            mListener.onLastStepAcquired();
        }
    }
}
