/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.systemui.Interpolators.ALPHA_IN;
import static com.android.systemui.Interpolators.ALPHA_OUT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.NonNull;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.FloatProperty;
import android.view.MotionEvent;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.recents.utilities.Utilities;

/**
 * QuickScrub action to send to launcher to start quickscrub gesture
 */
public class QuickScrubAction extends QuickSwitchAction {
    private static final String TAG = "QuickScrubAction";

    private static final float TRACK_SCALE = 0.95f;
    private static final float GRADIENT_WIDTH = .75f;
    private static final int ANIM_IN_DURATION_MS = 150;
    private static final int ANIM_OUT_DURATION_MS = 134;

    private AnimatorSet mTrackAnimator;
    private View mCurrentNavigationBarView;

    private float mTrackScale = TRACK_SCALE;
    private float mTrackAlpha;
    private float mHighlightCenter;
    private float mDarkIntensity;

    private final int mTrackThickness;
    private final int mTrackEndPadding;
    private final Paint mTrackPaint = new Paint();

    private final FloatProperty<QuickScrubAction> mTrackAlphaProperty =
            new FloatProperty<QuickScrubAction>("TrackAlpha") {
        @Override
        public void setValue(QuickScrubAction action, float alpha) {
            mTrackAlpha = alpha;
            mNavigationBarView.invalidate();
        }

        @Override
        public Float get(QuickScrubAction action) {
            return mTrackAlpha;
        }
    };

    private final FloatProperty<QuickScrubAction> mTrackScaleProperty =
            new FloatProperty<QuickScrubAction>("TrackScale") {
        @Override
        public void setValue(QuickScrubAction action, float scale) {
            mTrackScale = scale;
            mNavigationBarView.invalidate();
        }

        @Override
        public Float get(QuickScrubAction action) {
            return mTrackScale;
        }
    };

    private final FloatProperty<QuickScrubAction> mNavBarAlphaProperty =
            new FloatProperty<QuickScrubAction>("NavBarAlpha") {
        @Override
        public void setValue(QuickScrubAction action, float alpha) {
            if (mCurrentNavigationBarView != null) {
                mCurrentNavigationBarView.setAlpha(alpha);
            }
        }

        @Override
        public Float get(QuickScrubAction action) {
            if (mCurrentNavigationBarView != null) {
                return mCurrentNavigationBarView.getAlpha();
            }
            return 1f;
        }
    };

    private AnimatorListenerAdapter mQuickScrubEndListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (mCurrentNavigationBarView != null) {
                mCurrentNavigationBarView.setAlpha(1f);
            }
            mCurrentNavigationBarView = null;
            updateHighlight();
        }
    };

    public QuickScrubAction(@NonNull NavigationBarView navigationBarView,
            @NonNull OverviewProxyService service) {
        super(navigationBarView, service);
        mTrackPaint.setAntiAlias(true);
        mTrackPaint.setDither(true);

        final Resources res = navigationBarView.getResources();
        mTrackThickness = res.getDimensionPixelSize(R.dimen.nav_quick_scrub_track_thickness);
        mTrackEndPadding = res.getDimensionPixelSize(R.dimen.nav_quick_scrub_track_edge_padding);
    }

    @Override
    public void setBarState(boolean changed, int navBarPos, boolean dragHorPositive,
            boolean dragVerPositive) {
        super.setBarState(changed, navBarPos, dragHorPositive, dragVerPositive);
        if (changed && isActive()) {
            // End quickscrub if the state changes mid-transition
            endQuickScrub(false /* animate */);
        }
    }

    @Override
    public void reset() {
        super.reset();

        // End any existing quickscrub animations before starting the new transition
        if (mTrackAnimator != null) {
            mTrackAnimator.end();
            mTrackAnimator = null;
        }
        mCurrentNavigationBarView = mNavigationBarView.getCurrentView();
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int paddingLeft = mNavigationBarView.getPaddingLeft();
        final int paddingTop = mNavigationBarView.getPaddingTop();
        final int paddingRight = mNavigationBarView.getPaddingRight();
        final int paddingBottom = mNavigationBarView.getPaddingBottom();
        final int width = (right - left) - paddingRight - paddingLeft;
        final int height = (bottom - top) - paddingBottom - paddingTop;
        final int x1, x2, y1, y2;
        if (isNavBarVertical()) {
            x1 = (width - mTrackThickness) / 2 + paddingLeft;
            x2 = x1 + mTrackThickness;
            y1 = paddingTop + mTrackEndPadding;
            y2 = y1 + height - 2 * mTrackEndPadding;
        } else {
            y1 = (height - mTrackThickness) / 2 + paddingTop;
            y2 = y1 + mTrackThickness;
            x1 = mNavigationBarView.getPaddingStart() + mTrackEndPadding;
            x2 = x1 + width - 2 * mTrackEndPadding;
        }
        mDragOverRect.set(x1, y1, x2, y2);
    }

    @Override
    public void onDarkIntensityChange(float intensity) {
        mDarkIntensity = intensity;
        updateHighlight();
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (!isEnabled()) {
            return;
        }
        mTrackPaint.setAlpha(Math.round(255f * mTrackAlpha));

        // Scale the track, but apply the inverse scale from the nav bar
        final float radius = mDragOverRect.height() / 2;
        canvas.save();
        float translate = Utilities.clamp(mHighlightCenter, mDragOverRect.left,
                mDragOverRect.right);
        canvas.translate(translate, 0);
        canvas.scale(mTrackScale / mNavigationBarView.getScaleX(),
                1f / mNavigationBarView.getScaleY(),
                mDragOverRect.centerX(), mDragOverRect.centerY());
        canvas.drawRoundRect(mDragOverRect.left - translate, mDragOverRect.top,
                mDragOverRect.right - translate, mDragOverRect.bottom, radius, radius, mTrackPaint);
        canvas.restore();
    }

    @Override
    public boolean isEnabled() {
        return mNavigationBarView.isQuickScrubEnabled();
    }

    @Override
    protected void onGestureStart(MotionEvent event) {
        updateHighlight();
        ObjectAnimator trackAnimator = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(mTrackAlphaProperty, 1f),
                PropertyValuesHolder.ofFloat(mTrackScaleProperty, 1f));
        trackAnimator.setInterpolator(ALPHA_IN);
        trackAnimator.setDuration(ANIM_IN_DURATION_MS);
        ObjectAnimator navBarAnimator = ObjectAnimator.ofFloat(this, mNavBarAlphaProperty, 0f);
        navBarAnimator.setInterpolator(ALPHA_OUT);
        navBarAnimator.setDuration(ANIM_OUT_DURATION_MS);
        mTrackAnimator = new AnimatorSet();
        mTrackAnimator.playTogether(trackAnimator, navBarAnimator);
        mTrackAnimator.start();

        startQuickGesture(event);
    }

    @Override
    public void onGestureMove(int x, int y) {
        super.onGestureMove(x, y);
        mHighlightCenter = x;
        mNavigationBarView.invalidate();
    }

    @Override
    protected void onGestureEnd() {
        endQuickScrub(true /* animate */);
    }

    private void endQuickScrub(boolean animate) {
        animateEnd();
        endQuickGesture(animate);
        if (!animate) {
            if (mTrackAnimator != null) {
                mTrackAnimator.end();
                mTrackAnimator = null;
            }
        }
    }

    private void updateHighlight() {
        if (mDragOverRect.isEmpty()) {
            return;
        }
        int colorBase, colorGrad;
        if (mDarkIntensity > 0.5f) {
            colorBase = getContext().getColor(R.color.quick_step_track_background_background_dark);
            colorGrad = getContext().getColor(R.color.quick_step_track_background_foreground_dark);
        } else {
            colorBase = getContext().getColor(R.color.quick_step_track_background_background_light);
            colorGrad = getContext().getColor(R.color.quick_step_track_background_foreground_light);
        }
        final RadialGradient mHighlight = new RadialGradient(0, mDragOverRect.height() / 2,
                mDragOverRect.width() * GRADIENT_WIDTH, colorGrad, colorBase,
                Shader.TileMode.CLAMP);
        mTrackPaint.setShader(mHighlight);
    }

    private void animateEnd() {
        if (mTrackAnimator != null) {
            mTrackAnimator.cancel();
        }

        ObjectAnimator trackAnimator = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(mTrackAlphaProperty, 0f),
                PropertyValuesHolder.ofFloat(mTrackScaleProperty, TRACK_SCALE));
        trackAnimator.setInterpolator(ALPHA_OUT);
        trackAnimator.setDuration(ANIM_OUT_DURATION_MS);
        ObjectAnimator navBarAnimator = ObjectAnimator.ofFloat(this, mNavBarAlphaProperty, 1f);
        navBarAnimator.setInterpolator(ALPHA_IN);
        navBarAnimator.setDuration(ANIM_IN_DURATION_MS);
        mTrackAnimator = new AnimatorSet();
        mTrackAnimator.playTogether(trackAnimator, navBarAnimator);
        mTrackAnimator.addListener(mQuickScrubEndListener);
        mTrackAnimator.start();
    }
}
