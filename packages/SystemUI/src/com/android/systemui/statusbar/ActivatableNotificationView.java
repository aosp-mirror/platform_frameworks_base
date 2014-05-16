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
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.android.internal.R;

/**
 * Base class for both {@link ExpandableNotificationRow} and {@link NotificationOverflowContainer}
 * to implement dimming/activating on Keyguard for the double-tap gesture
 */
public abstract class ActivatableNotificationView extends ExpandableOutlineView {

    private static final long DOUBLETAP_TIMEOUT_MS = 1000;
    private static final int BACKGROUND_ANIMATION_LENGTH_MS = 220;

    private boolean mDimmed;

    private int mBgResId = R.drawable.notification_quantum_bg;
    private int mDimmedBgResId = R.drawable.notification_quantum_bg_dim;

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

    protected Drawable mBackgroundNormal;
    protected Drawable mBackgroundDimmed;
    private ObjectAnimator mBackgroundAnimator;
    private Interpolator mFastOutSlowInInterpolator;

    public ActivatableNotificationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        updateBackgroundResource();
        setWillNotDraw(false);
        mFastOutSlowInInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in);
    }

    private final Runnable mTapTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            makeInactive();
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        draw(canvas, mBackgroundNormal);
        draw(canvas, mBackgroundDimmed);
    }

    private void draw(Canvas canvas, Drawable drawable) {
        if (drawable != null) {
            drawable.setBounds(0, mClipTopAmount, getWidth(), mActualHeight);
            drawable.draw(canvas);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mBackgroundNormal
                || who == mBackgroundDimmed;
    }

    @Override
    protected void drawableStateChanged() {
        drawableStateChanged(mBackgroundNormal);
        drawableStateChanged(mBackgroundDimmed);
    }

    private void drawableStateChanged(Drawable d) {
        if (d != null && d.isStateful()) {
            d.setState(getDrawableState());
        }
    }

    @Override
    public void setOnClickListener(OnClickListener l) {
        super.setOnClickListener(l);
    }

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
                        makeActive(event.getX(), event.getY());
                        postDelayed(mTapTimeoutRunnable, DOUBLETAP_TIMEOUT_MS);
                    } else {
                        makeInactive();
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

    private void makeActive(float x, float y) {
        mBackgroundDimmed.setHotspot(0, x, y);
        mActivated = true;
        if (mOnActivatedListener != null) {
            mOnActivatedListener.onActivated(this);
        }
    }

    /**
     * Cancels the hotspot and makes the notification inactive.
     */
    private void makeInactive() {
        if (mActivated) {
            // Make sure that we clear the hotspot from the center.
            if (mBackgroundDimmed != null) {
                mBackgroundDimmed.setHotspot(0, getWidth() / 2, getActualHeight() / 2);
                mBackgroundDimmed.removeHotspot(0);
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
            setBackgroundDimmed(mDimmedBgResId, mDimmedBgTint);
        } else {
            setBackgroundNormal(mBgResId, mBgTint);
        }
        int startAlpha = mDimmed ? 255 : 0;
        int endAlpha = mDimmed ? 0 : 255;
        int duration = BACKGROUND_ANIMATION_LENGTH_MS;
        // Check whether there is already a background animation running.
        if (mBackgroundAnimator != null) {
            startAlpha = (Integer) mBackgroundAnimator.getAnimatedValue();
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
                ObjectAnimator.ofInt(mBackgroundNormal, "alpha", startAlpha, endAlpha);
        mBackgroundAnimator.setInterpolator(mFastOutSlowInInterpolator);
        mBackgroundAnimator.setDuration(duration);
        mBackgroundAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mDimmed) {
                    setBackgroundNormal(null);
                } else {
                    setBackgroundDimmed(null);
                }
                mBackgroundAnimator = null;
            }
        });
        mBackgroundAnimator.start();
    }

    private void updateBackgroundResource() {
        if (mDimmed) {
            setBackgroundDimmed(mDimmedBgResId, mDimmedBgTint);
            mBackgroundDimmed.setAlpha(255);
            setBackgroundNormal(null);
        } else {
            setBackgroundDimmed(null);
            setBackgroundNormal(mBgResId, mBgTint);
            mBackgroundNormal.setAlpha(255);
        }
    }

    /**
     * Sets a background drawable for the normal state. As we need to change our bounds
     * independently of layout, we need the notion of a background independently of the regular View
     * background..
     */
    private void setBackgroundNormal(Drawable backgroundNormal) {
        if (mBackgroundNormal != null) {
            mBackgroundNormal.setCallback(null);
            unscheduleDrawable(mBackgroundNormal);
        }
        mBackgroundNormal = backgroundNormal;
        if (mBackgroundNormal != null) {
            mBackgroundNormal.setCallback(this);
        }
        invalidate();
    }

    private void setBackgroundDimmed(Drawable overlay) {
        if (mBackgroundDimmed != null) {
            mBackgroundDimmed.setCallback(null);
            unscheduleDrawable(mBackgroundDimmed);
        }
        mBackgroundDimmed = overlay;
        if (mBackgroundDimmed != null) {
            mBackgroundDimmed.setCallback(this);
        }
        invalidate();
    }

    private void setBackgroundNormal(int drawableResId, int tintColor) {
        final Drawable d = getResources().getDrawable(drawableResId);
        if (tintColor != 0) {
            d.setColorFilter(tintColor, PorterDuff.Mode.SRC_ATOP);
        }
        setBackgroundNormal(d);
    }

    private void setBackgroundDimmed(int drawableResId, int tintColor) {
        final Drawable d = getResources().getDrawable(drawableResId);
        if (tintColor != 0) {
            d.setColorFilter(tintColor, PorterDuff.Mode.SRC_ATOP);
        }
        setBackgroundDimmed(d);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        setPivotX(getWidth() / 2);
    }

    @Override
    public void setActualHeight(int actualHeight, boolean notifyListeners) {
        super.setActualHeight(actualHeight, notifyListeners);
        invalidate();
        setPivotY(actualHeight / 2);
    }

    @Override
    public void setClipTopAmount(int clipTopAmount) {
        super.setClipTopAmount(clipTopAmount);
        invalidate();
    }

    public void setOnActivatedListener(OnActivatedListener onActivatedListener) {
        mOnActivatedListener = onActivatedListener;
    }

    public interface OnActivatedListener {
        void onActivated(View view);
        void onActivationReset(View view);
    }
}
