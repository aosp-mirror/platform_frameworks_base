/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.ObjectAnimator;
import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.util.FloatProperty;
import android.util.MathUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.R;

public class NavigationBarEdgePanel extends View {
    private static final String TAG = "NavigationBarEdgePanel";

    // TODO: read from resources once drawing is finalized.
    private static final boolean SHOW_PROTECTION_STROKE = true;
    private static final int PROTECTION_COLOR = 0xffc0c0c0;
    private static final int STROKE_COLOR = 0xffe5e5e5;
    private static final int PROTECTION_WIDTH_PX = 4;
    private static final int BASE_EXTENT = 32;
    private static final int ARROW_HEIGHT_DP = 32;
    private static final int POINT_EXTENT_DP = 8;
    private static final int ARROW_THICKNESS_DP = 4;
    private static final float TRACK_LENGTH_MULTIPLIER = 1.5f;
    private static final float START_POINTING_RATIO = 0.3f;
    private static final float POINTEDNESS_BEFORE_SNAP_RATIO = 0.4f;
    private static final int ANIM_DURATION_MS = 150;

    private final Paint mPaint = new Paint();
    private final Paint mProtectionPaint = new Paint();

    private final ObjectAnimator mEndAnimator;
    private final ObjectAnimator mLegAnimator;

    private final float mDensity;
    private final float mBaseExtent;
    private final float mPointExtent;
    private final float mHeight;
    private final float mStrokeThickness;
    private final boolean mIsLeftPanel;

    private float mStartY;
    private float mStartX;

    private boolean mGestureDetected;
    private boolean mArrowsPointLeft;
    private float mGestureLength;
    private float mLegProgress;
    private float mDragProgress;

    // How much the "legs" of the back arrow have proceeded from being a line to an arrow.
    private static final FloatProperty<NavigationBarEdgePanel> LEG_PROGRESS =
            new FloatProperty<NavigationBarEdgePanel>("legProgress") {
        @Override
        public void setValue(NavigationBarEdgePanel object, float value) {
            object.setLegProgress(value);
        }

        @Override
        public Float get(NavigationBarEdgePanel object) {
            return object.getLegProgress();
        }
    };

    // How far across the view the arrow should be drawn.
    private static final FloatProperty<NavigationBarEdgePanel> DRAG_PROGRESS =
            new FloatProperty<NavigationBarEdgePanel>("dragProgress") {

                @Override
                public void setValue(NavigationBarEdgePanel object, float value) {
                    object.setDragProgress(value);
                }

                @Override
                public Float get(NavigationBarEdgePanel object) {
                    return object.getDragProgress();
                }
            };

    public static NavigationBarEdgePanel create(@NonNull Context context, int width, int height,
            int gravity) {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.setTitle(TAG + context.getDisplayId());
        lp.accessibilityTitle = context.getString(R.string.nav_bar_edge_panel);
        lp.windowAnimations = 0;
        NavigationBarEdgePanel panel = new NavigationBarEdgePanel(
                context, (gravity & Gravity.LEFT) == Gravity.LEFT);
        panel.setLayoutParams(lp);
        return panel;
    }

    private NavigationBarEdgePanel(Context context, boolean isLeftPanel) {
        super(context);

        mEndAnimator = ObjectAnimator.ofFloat(this, DRAG_PROGRESS, 1f);
        mEndAnimator.setAutoCancel(true);
        mEndAnimator.setDuration(ANIM_DURATION_MS);

        mLegAnimator = ObjectAnimator.ofFloat(this, LEG_PROGRESS, 1f);
        mLegAnimator.setAutoCancel(true);
        mLegAnimator.setDuration(ANIM_DURATION_MS);

        mDensity = context.getResources().getDisplayMetrics().density;

        mBaseExtent = dp(BASE_EXTENT);
        mHeight = dp(ARROW_HEIGHT_DP);
        mPointExtent = dp(POINT_EXTENT_DP);
        mStrokeThickness = dp(ARROW_THICKNESS_DP);

        mPaint.setStrokeWidth(mStrokeThickness);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setColor(STROKE_COLOR);
        mPaint.setAntiAlias(true);

        mProtectionPaint.setStrokeWidth(mStrokeThickness + PROTECTION_WIDTH_PX);
        mProtectionPaint.setStrokeCap(Paint.Cap.ROUND);
        mProtectionPaint.setColor(PROTECTION_COLOR);
        mProtectionPaint.setAntiAlias(true);

        // Both panels arrow point the same way
        mArrowsPointLeft = getLayoutDirection() == LAYOUT_DIRECTION_LTR;
        mIsLeftPanel = isLeftPanel;
    }

    public void setWindowFlag(int flags, boolean enable) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp == null || enable == ((lp.flags & flags) != 0)) {
            return;
        }
        if (enable) {
            lp.flags |= flags;
        } else {
            lp.flags &= ~flags;
        }
        updateLayout(lp);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN : {
                show(event.getX(), event.getY());
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                handleNewSwipePoint(event.getX());
                break;
            }
            // Fall through
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                hide();
                break;
            }
        }

        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float edgeOffset = mBaseExtent * mDragProgress - mStrokeThickness;
        float animatedOffset = mPointExtent * mLegProgress;
        canvas.save();
        canvas.translate(
                mIsLeftPanel ? edgeOffset : getWidth() - edgeOffset,
                mStartY - mHeight * 0.5f);

        float outsideX = mArrowsPointLeft ? animatedOffset : 0;
        float middleX = mArrowsPointLeft ? 0 : animatedOffset;

        if (SHOW_PROTECTION_STROKE) {
            canvas.drawLine(outsideX, 0, middleX, mHeight * 0.5f, mProtectionPaint);
            canvas.drawLine(middleX, mHeight * 0.5f, outsideX, mHeight, mProtectionPaint);
        }

        canvas.drawLine(outsideX, 0, middleX, mHeight * 0.5f, mPaint);
        canvas.drawLine(middleX, mHeight * 0.5f, outsideX, mHeight, mPaint);
        canvas.restore();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // TODO: read the gesture length from the nav controller.
        mGestureLength = getWidth();
    }

    public void setDimensions(int width, int height) {
        final WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp.width != width || lp.height != height) {
            lp.width = width;
            lp.height = height;
            updateLayout(lp);
        }
    }

    private void setLegProgress(float progress) {
        mLegProgress = progress;
        invalidate();
    }

    private float getLegProgress() {
        return mLegProgress;
    }

    private void setDragProgress(float dragProgress) {
        mDragProgress = dragProgress;
        invalidate();
    }

    private float getDragProgress() {
        return mDragProgress;
    }

    private void hide() {
        animate().alpha(0f).setDuration(ANIM_DURATION_MS);
    }

    private void show(float x, float y) {
        mEndAnimator.cancel();
        mLegAnimator.cancel();
        setLegProgress(0f);
        setDragProgress(0f);
        setAlpha(1f);

        float halfHeight = mHeight * 0.5f;
        mStartY = MathUtils.constrain(y, halfHeight, getHeight() - halfHeight);
        mStartX = x;
    }

    private void handleNewSwipePoint(float x) {
        float dist = MathUtils.abs(x - mStartX);

        setDragProgress(MathUtils.constrainedMap(
                0, 1.0f,
                0, mGestureLength * TRACK_LENGTH_MULTIPLIER,
                dist));

        if (dist < mGestureLength) {
            float calculatedLegProgress = MathUtils.constrainedMap(
                    0f, POINTEDNESS_BEFORE_SNAP_RATIO,
                    mGestureLength * START_POINTING_RATIO, mGestureLength,
                    dist);

            // Blend animated value with drag calculated value, allow the gesture to continue
            // while the animation is playing with jump cuts in the animation.
            setLegProgress(MathUtils.lerp(calculatedLegProgress, mLegProgress, mDragProgress));

            if (mGestureDetected) {
                mGestureDetected = false;

                mLegAnimator.setFloatValues(POINTEDNESS_BEFORE_SNAP_RATIO);
                mLegAnimator.start();
            }
        } else {
            if (!mGestureDetected) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                mGestureDetected = true;

                mLegAnimator.setFloatValues(1f);
                mLegAnimator.start();
            }
        }
    }

    private void updateLayout(WindowManager.LayoutParams lp) {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        wm.updateViewLayout(this, lp);
    }

    private float dp(float dp) {
        return mDensity * dp;
    }
}
