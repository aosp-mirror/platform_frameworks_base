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
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.shared.system.WindowManagerWrapper;

/**
 * Displays the dismiss UI and target for floating objects.
 */
public class PipDismissViewController {

    // This delay controls how long to wait before we show the target when the user first moves
    // the PIP, to prevent the target from animating if the user just wants to fling the PIP
    public static final int SHOW_TARGET_DELAY = 100;
    private static final int SHOW_TARGET_DURATION = 350;
    private static final int HIDE_TARGET_DURATION = 225;

    private Context mContext;
    private WindowManager mWindowManager;
    private View mDismissView;

    // Used for dismissing a bubble -- bubble should be in the target to be considered a dismiss
    private View mTargetView;
    private int mTargetSlop;
    private Point mWindowSize;
    private int[] mLoc = new int[2];
    private boolean mIntersecting;
    private Vibrator mVibe;

    public PipDismissViewController(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mVibe = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /**
     * Creates the dismiss target for showing via {@link #showDismissTarget()}.
     */
    public void createDismissTarget() {
        if (mDismissView == null) {
            // Determine sizes for the view
            final Rect stableInsets = new Rect();
            WindowManagerWrapper.getInstance().getStableInsets(stableInsets);
            mWindowSize = new Point();
            mWindowManager.getDefaultDisplay().getRealSize(mWindowSize);
            final int gradientHeight = mContext.getResources().getDimensionPixelSize(
                    R.dimen.pip_dismiss_gradient_height);
            final int bottomMargin = mContext.getResources().getDimensionPixelSize(
                    R.dimen.pip_dismiss_text_bottom_margin);
            mTargetSlop = mContext.getResources().getDimensionPixelSize(
                    R.dimen.bubble_dismiss_slop);

            // Create a new view for the dismiss target
            LayoutInflater inflater = LayoutInflater.from(mContext);
            mDismissView = inflater.inflate(R.layout.pip_dismiss_view, null);
            mDismissView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            mDismissView.forceHasOverlappingRendering(false);

            // Set the gradient background
            Drawable gradient = mContext.getResources().getDrawable(R.drawable.pip_dismiss_scrim);
            gradient.setAlpha((int) (255 * 0.85f));
            mDismissView.setBackground(gradient);

            // Adjust bottom margins of the text
            mTargetView = mDismissView.findViewById(R.id.pip_dismiss_text);
            FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) mTargetView.getLayoutParams();
            tlp.bottomMargin = stableInsets.bottom + bottomMargin;
            mTargetView.setLayoutParams(tlp);

            // Add the target to the window
            LayoutParams lp =  new LayoutParams(
                    LayoutParams.MATCH_PARENT, gradientHeight,
                    0, mWindowSize.y - gradientHeight,
                    LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | LayoutParams.FLAG_NOT_TOUCHABLE
                            | LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("pip-dismiss-overlay");
            lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            mWindowManager.addView(mDismissView, lp);
        }
        mDismissView.animate().cancel();
    }


    /**
     * Updates the dismiss target based on location of the view, only used for bubbles not for PIP.
     *
     * @return whether the view is within the dismiss target.
     */
    public boolean updateTarget(View view) {
        if (mDismissView == null) {
            return false;
        }
        if (mDismissView.getAlpha() > 0) {
            view.getLocationOnScreen(mLoc);
            Rect viewRect = new Rect(mLoc[0], mLoc[1], mLoc[0] + view.getWidth(),
                    mLoc[1] + view.getHeight());
            mTargetView.getLocationOnScreen(mLoc);
            Rect targetRect = new Rect(mLoc[0], mLoc[1], mLoc[0] + mTargetView.getWidth(),
                    mLoc[1] + mTargetView.getHeight());
            expandRect(targetRect, mTargetSlop);
            boolean intersecting = targetRect.intersect(viewRect);
            if (intersecting != mIntersecting) {
                // TODO: is this the right effect?
                mVibe.vibrate(VibrationEffect.get(intersecting
                        ? VibrationEffect.EFFECT_CLICK
                        : VibrationEffect.EFFECT_TICK));
            }
            mIntersecting = intersecting;
            return intersecting;
        }
        return false;
    }

    /**
     * Shows the dismiss target.
     */
    public void showDismissTarget() {
        mDismissView.animate()
                .alpha(1f)
                .setInterpolator(Interpolators.LINEAR)
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
                    .setInterpolator(Interpolators.LINEAR)
                    .setStartDelay(0)
                    .setDuration(HIDE_TARGET_DURATION)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            mWindowManager.removeViewImmediate(mDismissView);
                            mDismissView = null;
                        }
                    })
                    .start();
        }
    }

    private void expandRect(Rect outRect, int expandAmount) {
        outRect.left = Math.max(0, outRect.left - expandAmount);
        outRect.top = Math.max(0, outRect.top - expandAmount);
        outRect.right = Math.min(mWindowSize.x, outRect.right + expandAmount);
        outRect.bottom = Math.min(mWindowSize.y, outRect.bottom + expandAmount);
    }
}
