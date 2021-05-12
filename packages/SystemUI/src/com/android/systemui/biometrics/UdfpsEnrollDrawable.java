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
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * UDFPS fingerprint drawable that is shown when enrolling
 */
public class UdfpsEnrollDrawable extends UdfpsDrawable {
    private static final String TAG = "UdfpsAnimationEnroll";

    static final float PROGRESS_BAR_RADIUS = 360.f;

    @NonNull private final Drawable mMovingTargetFpIcon;
    @NonNull private final Paint mSensorOutlinePaint;
    @NonNull private final Paint mBlueFill;
    @NonNull private final Paint mBlueStroke;

    @Nullable private RectF mSensorRect;
    @Nullable private UdfpsEnrollHelper mEnrollHelper;

    UdfpsEnrollDrawable(@NonNull Context context) {
        super(context);

        mSensorOutlinePaint = new Paint(0 /* flags */);
        mSensorOutlinePaint.setAntiAlias(true);
        mSensorOutlinePaint.setColor(mContext.getColor(R.color.udfps_enroll_icon));
        mSensorOutlinePaint.setStyle(Paint.Style.STROKE);
        mSensorOutlinePaint.setStrokeWidth(2.f);

        mBlueFill = new Paint(0 /* flags */);
        mBlueFill.setAntiAlias(true);
        mBlueFill.setColor(context.getColor(R.color.udfps_moving_target_fill));
        mBlueFill.setStyle(Paint.Style.FILL);

        mBlueStroke = new Paint(0 /* flags */);
        mBlueStroke.setAntiAlias(true);
        mBlueStroke.setColor(context.getColor(R.color.udfps_moving_target_stroke));
        mBlueStroke.setStyle(Paint.Style.STROKE);
        mBlueStroke.setStrokeWidth(12);

        mMovingTargetFpIcon = context.getResources().getDrawable(R.drawable.ic_fingerprint, null);
        mMovingTargetFpIcon.setTint(Color.WHITE);
        mMovingTargetFpIcon.mutate();

        mFingerprintDrawable.setTint(mContext.getColor(R.color.udfps_enroll_icon));
    }

    void setEnrollHelper(@NonNull UdfpsEnrollHelper helper) {
        mEnrollHelper = helper;
    }

    @Override
    public void onSensorRectUpdated(@NonNull RectF sensorRect) {
        super.onSensorRectUpdated(sensorRect);
        mSensorRect = sensorRect;
    }

    @Override
    protected void updateFingerprintIconBounds(@NonNull Rect bounds) {
        super.updateFingerprintIconBounds(bounds);
        mMovingTargetFpIcon.setBounds(bounds);
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (isIlluminationShowing()) {
            return;
        }

        if (mSensorRect != null) {
            canvas.drawOval(mSensorRect, mSensorOutlinePaint);
        }
        mFingerprintDrawable.draw(canvas);

        // Draw moving target
        if (mEnrollHelper.isCenterEnrollmentComplete()) {
            mFingerprintDrawable.setAlpha(mAlpha == 255 ? 64 : mAlpha);
            mSensorOutlinePaint.setAlpha(mAlpha == 255 ? 64 : mAlpha);

            canvas.save();
            final PointF point = mEnrollHelper.getNextGuidedEnrollmentPoint();
            canvas.translate(point.x, point.y);
            if (mSensorRect != null) {
                canvas.drawOval(mSensorRect, mBlueFill);
                canvas.drawOval(mSensorRect, mBlueStroke);
            }

            mMovingTargetFpIcon.draw(canvas);
            canvas.restore();
        } else {
            mFingerprintDrawable.setAlpha(mAlpha);
            mSensorOutlinePaint.setAlpha(mAlpha);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        super.setAlpha(alpha);
        mSensorOutlinePaint.setAlpha(alpha);
        mBlueFill.setAlpha(alpha);
        mBlueStroke.setAlpha(alpha);
        mMovingTargetFpIcon.setAlpha(alpha);
        invalidateSelf();
    }
}
