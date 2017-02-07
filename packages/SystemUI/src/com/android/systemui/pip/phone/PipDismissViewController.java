/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.pip.phone;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

public class PipDismissViewController {

    // This delay controls how long to wait before we show the target when the user first moves
    // the PIP, to prevent the target from animating if the user just wants to fling the PIP
    private static final int SHOW_TARGET_DELAY = 100;
    private static final int SHOW_TARGET_DURATION = 200;

    private static final float DISMISS_TEXT_MAX_SCALE = 2f;
    private static final float DISMISS_GRADIENT_MIN_HEIGHT_PERCENT = 0.33f;
    private static final float DISMISS_GRADIENT_MAX_HEIGHT_PERCENT = 0.5f;
    private static final float DISMISS_THRESHOLD = 0.55f;

    private Context mContext;
    private WindowManager mWindowManager;

    private View mDismissView;
    private Rect mDismissTargetScreenBounds = new Rect();

    private View mDismissContainer;
    private View mGradientView;
    private float mMinHeight;
    private float mMaxHeight;

    public PipDismissViewController(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * Creates the dismiss target for showing via {@link #showDismissTarget()}.
     */
    public void createDismissTarget() {
        if (mDismissView == null) {
            // Determine sizes for the gradient
            Point windowSize = new Point();
            mWindowManager.getDefaultDisplay().getSize(windowSize);
            mMinHeight = windowSize.y * DISMISS_GRADIENT_MIN_HEIGHT_PERCENT;
            mMaxHeight = windowSize.y * DISMISS_GRADIENT_MAX_HEIGHT_PERCENT;

            // Create a new view for the dismiss target
            LayoutInflater inflater = LayoutInflater.from(mContext);
            mDismissView = inflater.inflate(R.layout.pip_dismiss_view, null);
            mGradientView = mDismissView.findViewById(R.id.gradient_view);
            FrameLayout.LayoutParams glp = (android.widget.FrameLayout.LayoutParams) mGradientView
                    .getLayoutParams();
            glp.height = (int) mMaxHeight;
            mGradientView.setLayoutParams(glp);
            mGradientView.setPivotY(windowSize.y);
            mGradientView.setScaleY(mMaxHeight / mMinHeight); // Set to min height via scaling
            mDismissContainer = mDismissView.findViewById(R.id.pip_dismiss_container);
            mDismissContainer.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (mDismissContainer != null) {
                        mDismissContainer.getBoundsOnScreen(mDismissTargetScreenBounds);
                    }
                }
            });

            // Add the target to the window
            WindowManager.LayoutParams lp =  new WindowManager.LayoutParams(
                    windowSize.x,
                    (int) mMaxHeight,
                    WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            mWindowManager.addView(mDismissView, lp);
        }
        mDismissView.animate().cancel();
    }

    /**
     * Shows the dismiss target.
     */
    public void showDismissTarget(Rect pinnedStack) {
        updateDismissTarget(pinnedStack);
        mDismissView.animate()
                .alpha(1f)
                .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                .setStartDelay(SHOW_TARGET_DELAY)
                .setDuration(SHOW_TARGET_DURATION)
                .start();
    }

    /**
     * Hides and destroys the dismiss target.
     */
    public void destroyDismissTarget() {
        if (mDismissView != null) {
            mDismissView.animate()
                    .alpha(0f)
                    .setInterpolator(Interpolators.FAST_OUT_LINEAR_IN)
                    .setStartDelay(0)
                    .setDuration(SHOW_TARGET_DURATION)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            mWindowManager.removeView(mDismissView);
                            mDismissView = null;
                        }
                    })
                    .start();
        }
    }

    /**
     * Updates the appearance of the dismiss target based on how close the PIP is.
     */
    public void updateDismissTarget(Rect pinnedStack) {
        // As PIP moves over / away from delete target it grows / shrinks
        final float scalePercent = calculateDistancePercent(pinnedStack);
        final float newScale = 1 + (DISMISS_TEXT_MAX_SCALE - 1) * scalePercent;
        final float minGradientScale = mMinHeight / mMaxHeight;
        final float newHeight = Math.max(minGradientScale, scalePercent);
        mGradientView.setScaleY(newHeight);
        mDismissContainer.setScaleX(newScale);
        mDismissContainer.setScaleY(newScale);
    }

    /**
     * @return the percentage of distance the PIP is away from the dismiss target point.
     */
    private float calculateDistancePercent(Rect pinnedStack) {
        final int distance = mDismissTargetScreenBounds.height();
        final int textX = mDismissTargetScreenBounds.centerX();
        final int textY = mDismissTargetScreenBounds.bottom;
        final float pipCurrX = pinnedStack.centerX();
        final float pipCurrY = pinnedStack.bottom;
        final float currentDistance = PointF.length(pipCurrX - textX, pipCurrY - textY);
        if (currentDistance <= distance) {
            return 1 - (currentDistance / distance);
        }
        return 0;
    }

    /**
     * @return the dismiss target screen bounds.
     */
    public Rect getDismissBounds() {
        return mDismissTargetScreenBounds;
    }

    /**
     * @return whether the PIP is positioned on the dismiss target enough to be dismissed.
     */
    public boolean shouldDismiss(Rect pinnedStack) {
        return calculateDistancePercent(pinnedStack) >= DISMISS_THRESHOLD;
    }
}
