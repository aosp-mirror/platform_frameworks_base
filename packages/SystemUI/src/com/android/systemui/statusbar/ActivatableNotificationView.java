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

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.systemui.R;

/**
 * Base class for both {@link ExpandableNotificationRow} and {@link NotificationOverflowContainer}
 * to implement dimming/activating on Keyguard for the double-tap gesture
 */
public abstract class ActivatableNotificationView extends ExpandableOutlineView {

    private static final long DOUBLETAP_TIMEOUT_MS = 1200;
    private static final int BACKGROUND_ANIMATION_LENGTH_MS = 220;
    private static final int ACTIVATE_ANIMATION_LENGTH = 220;

    private static final Interpolator ACTIVATE_INVERSE_INTERPOLATOR
            = new PathInterpolator(0.6f, 0, 0.5f, 1);
    private static final Interpolator ACTIVATE_INVERSE_ALPHA_INTERPOLATOR
            = new PathInterpolator(0, 0, 0.5f, 1);
    private final int mMaxNotificationHeight;

    private boolean mDimmed;

    private int mBgResId = com.android.internal.R.drawable.notification_quantum_bg;
    private int mDimmedBgResId = com.android.internal.R.drawable.notification_quantum_bg_dim;

    private int mBgTint = 0;
    private int mDimmedBgTint = 0;

    /**
     * Flag to indicate that the notification has been touched once and the second touch will
     * click it.
     */
    private boolean mActivated;

    private float mDownX;
    private float mDownY;
    private final float mTouchSlop;

    private OnActivatedListener mOnActivatedListener;

    private Interpolator mLinearOutSlowInInterpolator;
    private Interpolator mFastOutSlowInInterpolator;

    private NotificationBackgroundView mBackgroundNormal;
    private NotificationBackgroundView mBackgroundDimmed;
    private ObjectAnimator mBackgroundAnimator;

    public ActivatableNotificationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mFastOutSlowInInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in);
        mLinearOutSlowInInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.linear_out_slow_in);
        mMaxNotificationHeight = getResources().getDimensionPixelSize(R.dimen.notification_max_height);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBackgroundNormal = (NotificationBackgroundView) findViewById(R.id.backgroundNormal);
        mBackgroundDimmed = (NotificationBackgroundView) findViewById(R.id.backgroundDimmed);
        updateBackgroundResource();
    }

    private final Runnable mTapTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            makeInactive();
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mDimmed) {
            return handleTouchEventDimmed(event);
        } else {
            return super.onTouchEvent(event);
        }
    }

    private boolean handleTouchEventDimmed(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                if (mDownY > getActualHeight()) {
                    return false;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!isWithinTouchSlop(event)) {
                    makeInactive();
                    return false;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (isWithinTouchSlop(event)) {
                    if (!mActivated) {
                        makeActive();
                        postDelayed(mTapTimeoutRunnable, DOUBLETAP_TIMEOUT_MS);
                    } else {
                        performClick();
                    }
                } else {
                    makeInactive();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                makeInactive();
                break;
            default:
                break;
        }
        return true;
    }

    private void makeActive() {
        startActivateAnimation(false /* reverse */);
        mActivated = true;
        if (mOnActivatedListener != null) {
            mOnActivatedListener.onActivated(this);
        }
    }

    private void startActivateAnimation(boolean reverse) {
        int widthHalf = mBackgroundNormal.getWidth()/2;
        int heightHalf = mBackgroundNormal.getActualHeight()/2;
        float radius = (float) Math.sqrt(widthHalf*widthHalf + heightHalf*heightHalf);
        ValueAnimator animator =
                mBackgroundNormal.createRevealAnimator(widthHalf, heightHalf, 0, radius);
        mBackgroundNormal.setVisibility(View.VISIBLE);
        Interpolator interpolator;
        Interpolator alphaInterpolator;
        if (!reverse) {
            interpolator = mLinearOutSlowInInterpolator;
            alphaInterpolator = mLinearOutSlowInInterpolator;
        } else {
            interpolator = ACTIVATE_INVERSE_INTERPOLATOR;
            alphaInterpolator = ACTIVATE_INVERSE_ALPHA_INTERPOLATOR;
        }
        animator.setInterpolator(interpolator);
        animator.setDuration(ACTIVATE_ANIMATION_LENGTH);
        if (reverse) {
            mBackgroundNormal.setAlpha(1f);
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mBackgroundNormal.setVisibility(View.INVISIBLE);
                }
            });
            animator.reverse();
        } else {
            mBackgroundNormal.setAlpha(0.4f);
            animator.start();
        }
        mBackgroundNormal.animate()
                .alpha(reverse ? 0f : 1f)
                .setInterpolator(alphaInterpolator)
                .setDuration(ACTIVATE_ANIMATION_LENGTH);
    }

    /**
     * Cancels the hotspot and makes the notification inactive.
     */
    private void makeInactive() {
        if (mActivated) {
            if (mDimmed) {
                startActivateAnimation(true /* reverse */);
            }
            mActivated = false;
        }
        if (mOnActivatedListener != null) {
            mOnActivatedListener.onActivationReset(this);
        }
        removeCallbacks(mTapTimeoutRunnable);
    }

    private boolean isWithinTouchSlop(MotionEvent event) {
        return Math.abs(event.getX() - mDownX) < mTouchSlop
                && Math.abs(event.getY() - mDownY) < mTouchSlop;
    }

    public void setDimmed(boolean dimmed, boolean fade) {
        if (mDimmed != dimmed) {
            mDimmed = dimmed;
            if (fade) {
                fadeBackgroundResource();
            } else {
                updateBackgroundResource();
            }
        }
    }

    /**
     * Sets the resource id for the background of this notification.
     *
     * @param bgResId The background resource to use in normal state.
     * @param dimmedBgResId The background resource to use in dimmed state.
     */
    public void setBackgroundResourceIds(int bgResId, int bgTint, int dimmedBgResId, int dimmedTint) {
        mBgResId = bgResId;
        mBgTint = bgTint;
        mDimmedBgResId = dimmedBgResId;
        mDimmedBgTint = dimmedTint;
        updateBackgroundResource();
    }

    public void setBackgroundResourceIds(int bgResId, int dimmedBgResId) {
        setBackgroundResourceIds(bgResId, 0, dimmedBgResId, 0);
    }

    private void fadeBackgroundResource() {
        if (mDimmed) {
            mBackgroundDimmed.setVisibility(View.VISIBLE);
        } else {
            mBackgroundNormal.setVisibility(View.VISIBLE);
        }
        float startAlpha = mDimmed ? 1f : 0;
        float endAlpha = mDimmed ? 0 : 1f;
        int duration = BACKGROUND_ANIMATION_LENGTH_MS;
        // Check whether there is already a background animation running.
        if (mBackgroundAnimator != null) {
            startAlpha = (Float) mBackgroundAnimator.getAnimatedValue();
            duration = (int) mBackgroundAnimator.getCurrentPlayTime();
            mBackgroundAnimator.removeAllListeners();
            mBackgroundAnimator.cancel();
            if (duration <= 0) {
                updateBackgroundResource();
                return;
            }
        }
        mBackgroundNormal.setAlpha(startAlpha);
        mBackgroundAnimator =
                ObjectAnimator.ofFloat(mBackgroundNormal, View.ALPHA, startAlpha, endAlpha);
        mBackgroundAnimator.setInterpolator(mFastOutSlowInInterpolator);
        mBackgroundAnimator.setDuration(duration);
        mBackgroundAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mDimmed) {
                    mBackgroundNormal.setVisibility(View.INVISIBLE);
                } else {
                    mBackgroundDimmed.setVisibility(View.INVISIBLE);
                }
                mBackgroundAnimator = null;
            }
        });
        mBackgroundAnimator.start();
    }

    private void updateBackgroundResource() {
        if (mDimmed) {
            mBackgroundDimmed.setVisibility(View.VISIBLE);
            mBackgroundDimmed.setCustomBackground(mDimmedBgResId, mDimmedBgTint);
            mBackgroundNormal.setVisibility(View.INVISIBLE);
        } else {
            mBackgroundDimmed.setVisibility(View.INVISIBLE);
            mBackgroundNormal.setVisibility(View.VISIBLE);
            mBackgroundNormal.setCustomBackground(mBgResId, mBgTint);
            mBackgroundNormal.setAlpha(1f);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int newHeightSpec = MeasureSpec.makeMeasureSpec(mMaxNotificationHeight,
                MeasureSpec.AT_MOST);
        int maxChildHeight = 0;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child != mBackgroundDimmed && child != mBackgroundNormal) {
                child.measure(widthMeasureSpec, newHeightSpec);
                int childHeight = child.getMeasuredHeight();
                maxChildHeight = Math.max(maxChildHeight, childHeight);
            }
        }
        newHeightSpec = MeasureSpec.makeMeasureSpec(maxChildHeight, MeasureSpec.EXACTLY);
        mBackgroundDimmed.measure(widthMeasureSpec, newHeightSpec);
        mBackgroundNormal.measure(widthMeasureSpec, newHeightSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, maxChildHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        setPivotX(getWidth() / 2);
    }

    @Override
    public void setActualHeight(int actualHeight, boolean notifyListeners) {
        super.setActualHeight(actualHeight, notifyListeners);
        setPivotY(actualHeight / 2);
        mBackgroundNormal.setActualHeight(actualHeight);
        mBackgroundDimmed.setActualHeight(actualHeight);
    }

    @Override
    public void setClipTopAmount(int clipTopAmount) {
        super.setClipTopAmount(clipTopAmount);
        mBackgroundNormal.setClipTopAmount(clipTopAmount);
        mBackgroundDimmed.setClipTopAmount(clipTopAmount);
    }

    public void setOnActivatedListener(OnActivatedListener onActivatedListener) {
        mOnActivatedListener = onActivatedListener;
    }

    public interface OnActivatedListener {
        void onActivated(View view);
        void onActivationReset(View view);
    }
}
