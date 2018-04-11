/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.animation.Interpolator;

import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;

public class TrustDrawable extends Drawable {

    private static final long ENTERING_FROM_UNSET_START_DELAY = 200;
    private static final long VISIBLE_DURATION = 1000;
    private static final long EXIT_DURATION = 500;
    private static final long ENTER_DURATION = 500;

    private static final int ALPHA_VISIBLE_MIN = 0x26;
    private static final int ALPHA_VISIBLE_MAX = 0x4c;

    private static final int STATE_UNSET = -1;
    private static final int STATE_GONE = 0;
    private static final int STATE_ENTERING = 1;
    private static final int STATE_VISIBLE = 2;
    private static final int STATE_EXITING = 3;

    private int mAlpha;
    private boolean mAnimating;

    private int mCurAlpha;
    private float mCurInnerRadius;
    private Animator mCurAnimator;
    private int mState = STATE_UNSET;
    private Paint mPaint;
    private boolean mTrustManaged;

    private final float mInnerRadiusVisibleMin;
    private final float mInnerRadiusVisibleMax;
    private final float mInnerRadiusExit;
    private final float mInnerRadiusEnter;
    private final float mThickness;

    private final Animator mVisibleAnimator;

    public TrustDrawable(Context context) {
        Resources r = context.getResources();
        mInnerRadiusVisibleMin = r.getDimension(R.dimen.trust_circle_inner_radius_visible_min);
        mInnerRadiusVisibleMax = r.getDimension(R.dimen.trust_circle_inner_radius_visible_max);
        mInnerRadiusExit = r.getDimension(R.dimen.trust_circle_inner_radius_exit);
        mInnerRadiusEnter = r.getDimension(R.dimen.trust_circle_inner_radius_enter);
        mThickness = r.getDimension(R.dimen.trust_circle_thickness);

        mCurInnerRadius = mInnerRadiusEnter;

        mVisibleAnimator = makeVisibleAnimator();

        mPaint = new Paint();
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setColor(Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColor));
        mPaint.setAntiAlias(true);
        mPaint.setStrokeWidth(mThickness);
    }

    @Override
    public void draw(Canvas canvas) {
        int newAlpha = (mCurAlpha * mAlpha) / 256;
        if (newAlpha == 0) {
            return;
        }
        final Rect r = getBounds();
        mPaint.setAlpha(newAlpha);
        canvas.drawCircle(r.exactCenterX(), r.exactCenterY(), mCurInnerRadius, mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    public void start() {
        if (!mAnimating) {
            mAnimating = true;
            updateState(true);
            invalidateSelf();
        }
    }

    public void stop() {
        if (mAnimating) {
            mAnimating = false;
            if (mCurAnimator != null) {
                mCurAnimator.cancel();
                mCurAnimator = null;
            }
            mState = STATE_UNSET;
            mCurAlpha = 0;
            mCurInnerRadius = mInnerRadiusEnter;
            invalidateSelf();
        }
    }

    public void setTrustManaged(boolean trustManaged) {
        if (trustManaged == mTrustManaged && mState != STATE_UNSET) return;
        mTrustManaged = trustManaged;
        updateState(true);
    }

    private void updateState(boolean allowTransientState) {
        if (!mAnimating) {
            return;
        }

        int nextState = mState;
        if (mState == STATE_UNSET) {
            nextState = mTrustManaged ? STATE_ENTERING : STATE_GONE;
        } else if (mState == STATE_GONE) {
            if (mTrustManaged) nextState = STATE_ENTERING;
        } else if (mState == STATE_ENTERING) {
            if (!mTrustManaged) nextState = STATE_EXITING;
        } else if (mState == STATE_VISIBLE) {
            if (!mTrustManaged) nextState = STATE_EXITING;
        } else if (mState == STATE_EXITING) {
            if (mTrustManaged) nextState = STATE_ENTERING;
        }
        if (!allowTransientState) {
            if (nextState == STATE_ENTERING) nextState = STATE_VISIBLE;
            if (nextState == STATE_EXITING) nextState = STATE_GONE;
        }

        if (nextState != mState) {
            if (mCurAnimator != null) {
                mCurAnimator.cancel();
                mCurAnimator = null;
            }

            if (nextState == STATE_GONE) {
                mCurAlpha = 0;
                mCurInnerRadius = mInnerRadiusEnter;
            } else if (nextState == STATE_ENTERING) {
                mCurAnimator = makeEnterAnimator(mCurInnerRadius, mCurAlpha);
                if (mState == STATE_UNSET) {
                    mCurAnimator.setStartDelay(ENTERING_FROM_UNSET_START_DELAY);
                }
            } else if (nextState == STATE_VISIBLE) {
                mCurAlpha = ALPHA_VISIBLE_MAX;
                mCurInnerRadius = mInnerRadiusVisibleMax;
                mCurAnimator = mVisibleAnimator;
            } else if (nextState == STATE_EXITING) {
                mCurAnimator = makeExitAnimator(mCurInnerRadius, mCurAlpha);
            }

            mState = nextState;
            if (mCurAnimator != null) {
                mCurAnimator.start();
            }
            invalidateSelf();
        }
    }

    private Animator makeVisibleAnimator() {
        return makeAnimators(mInnerRadiusVisibleMax, mInnerRadiusVisibleMin,
                ALPHA_VISIBLE_MAX, ALPHA_VISIBLE_MIN, VISIBLE_DURATION,
                Interpolators.ACCELERATE_DECELERATE,
                true /* repeating */, false /* stateUpdateListener */);
    }

    private Animator makeEnterAnimator(float radius, int alpha) {
        return makeAnimators(radius, mInnerRadiusVisibleMax,
                alpha, ALPHA_VISIBLE_MAX, ENTER_DURATION, Interpolators.LINEAR_OUT_SLOW_IN,
                false /* repeating */, true /* stateUpdateListener */);
    }

    private Animator makeExitAnimator(float radius, int alpha) {
        return makeAnimators(radius, mInnerRadiusExit,
                alpha, 0, EXIT_DURATION, Interpolators.FAST_OUT_SLOW_IN,
                false /* repeating */, true /* stateUpdateListener */);
    }

    private Animator makeAnimators(float startRadius, float endRadius,
            int startAlpha, int endAlpha, long duration, Interpolator interpolator,
            boolean repeating, boolean stateUpdateListener) {
        ValueAnimator alphaAnimator = configureAnimator(
                ValueAnimator.ofInt(startAlpha, endAlpha),
                duration, mAlphaUpdateListener, interpolator, repeating);
        ValueAnimator sizeAnimator = configureAnimator(
                ValueAnimator.ofFloat(startRadius, endRadius),
                duration, mRadiusUpdateListener, interpolator, repeating);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(alphaAnimator, sizeAnimator);
        if (stateUpdateListener) {
            set.addListener(new StateUpdateAnimatorListener());
        }
        return set;
    }

    private ValueAnimator configureAnimator(ValueAnimator animator, long duration,
            ValueAnimator.AnimatorUpdateListener updateListener, Interpolator interpolator,
            boolean repeating) {
        animator.setDuration(duration);
        animator.addUpdateListener(updateListener);
        animator.setInterpolator(interpolator);
        if (repeating) {
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.REVERSE);
        }
        return animator;
    }

    private final ValueAnimator.AnimatorUpdateListener mAlphaUpdateListener =
            new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mCurAlpha = (int) animation.getAnimatedValue();
            invalidateSelf();
        }
    };

    private final ValueAnimator.AnimatorUpdateListener mRadiusUpdateListener =
            new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mCurInnerRadius = (float) animation.getAnimatedValue();
            invalidateSelf();
        }
    };

    private class StateUpdateAnimatorListener extends AnimatorListenerAdapter {
        boolean mCancelled;

        @Override
        public void onAnimationStart(Animator animation) {
            mCancelled = false;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mCancelled = true;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (!mCancelled) {
                updateState(false);
            }
        }
    }
}
