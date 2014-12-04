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
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.TargetApi;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.LinearInterpolator;
import com.android.systemui.recents.RecentsConfiguration;

/**
 * This class facilitates swipe to dismiss. It defines an interface to be implemented by the
 * by the class hosting the views that need to swiped, and, using this interface, handles touch
 * events and translates / fades / animates the view as it is dismissed.
 */
public class SwipeHelper {
    static final String TAG = "SwipeHelper";
    private static final boolean SLOW_ANIMATIONS = false; // DEBUG;
    private static final boolean CONSTRAIN_SWIPE = true;
    private static final boolean FADE_OUT_DURING_SWIPE = true;
    private static final boolean DISMISS_IF_SWIPED_FAR_ENOUGH = true;

    public static final int X = 0;
    public static final int Y = 1;

    private static LinearInterpolator sLinearInterpolator = new LinearInterpolator();

    private float SWIPE_ESCAPE_VELOCITY = 100f; // dp/sec
    private int DEFAULT_ESCAPE_ANIMATION_DURATION = 75; // ms
    private int MAX_ESCAPE_ANIMATION_DURATION = 150; // ms
    private int MAX_DISMISS_VELOCITY = 2000; // dp/sec
    private static final int SNAP_ANIM_LEN = SLOW_ANIMATIONS ? 1000 : 250; // ms

    public static float ALPHA_FADE_START = 0.15f; // fraction of thumbnail width
                                                 // where fade starts
    static final float ALPHA_FADE_END = 0.65f; // fraction of thumbnail width
                                              // beyond which alpha->0
    private float mMinAlpha = 0f;

    private float mPagingTouchSlop;
    Callback mCallback;
    private int mSwipeDirection;
    private VelocityTracker mVelocityTracker;

    private float mInitialTouchPos;
    private boolean mDragging;

    private View mCurrView;
    private boolean mCanCurrViewBeDimissed;
    private float mDensityScale;

    public boolean mAllowSwipeTowardsStart = true;
    public boolean mAllowSwipeTowardsEnd = true;
    private boolean mRtl;

    public SwipeHelper(int swipeDirection, Callback callback, float densityScale,
            float pagingTouchSlop) {
        mCallback = callback;
        mSwipeDirection = swipeDirection;
        mVelocityTracker = VelocityTracker.obtain();
        mDensityScale = densityScale;
        mPagingTouchSlop = pagingTouchSlop;
    }

    public void setDensityScale(float densityScale) {
        mDensityScale = densityScale;
    }

    public void setPagingTouchSlop(float pagingTouchSlop) {
        mPagingTouchSlop = pagingTouchSlop;
    }

    public void cancelOngoingDrag() {
        if (mDragging) {
            if (mCurrView != null) {
                mCallback.onDragCancelled(mCurrView);
                setTranslation(mCurrView, 0);
                mCallback.onSnapBackCompleted(mCurrView);
                mCurrView = null;
            }
            mDragging = false;
        }
    }

    public void resetTranslation(View v) {
        setTranslation(v, 0);
    }

    private float getPos(MotionEvent ev) {
        return mSwipeDirection == X ? ev.getX() : ev.getY();
    }

    private float getTranslation(View v) {
        return mSwipeDirection == X ? v.getTranslationX() : v.getTranslationY();
    }

    private float getVelocity(VelocityTracker vt) {
        return mSwipeDirection == X ? vt.getXVelocity() :
                vt.getYVelocity();
    }

    private ObjectAnimator createTranslationAnimation(View v, float newPos) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(v,
                mSwipeDirection == X ? View.TRANSLATION_X : View.TRANSLATION_Y, newPos);
        return anim;
    }

    private float getPerpendicularVelocity(VelocityTracker vt) {
        return mSwipeDirection == X ? vt.getYVelocity() :
                vt.getXVelocity();
    }

    private void setTranslation(View v, float translate) {
        if (mSwipeDirection == X) {
            v.setTranslationX(translate);
        } else {
            v.setTranslationY(translate);
        }
    }

    private float getSize(View v) {
        final DisplayMetrics dm = v.getContext().getResources().getDisplayMetrics();
        return mSwipeDirection == X ? dm.widthPixels : dm.heightPixels;
    }

    public void setMinAlpha(float minAlpha) {
        mMinAlpha = minAlpha;
    }

    float getAlphaForOffset(View view) {
        float viewSize = getSize(view);
        final float fadeSize = ALPHA_FADE_END * viewSize;
        float result = 1.0f;
        float pos = getTranslation(view);
        if (pos >= viewSize * ALPHA_FADE_START) {
            result = 1.0f - (pos - viewSize * ALPHA_FADE_START) / fadeSize;
        } else if (pos < viewSize * (1.0f - ALPHA_FADE_START)) {
            result = 1.0f + (viewSize * ALPHA_FADE_START + pos) / fadeSize;
        }
        result = Math.min(result, 1.0f);
        result = Math.max(result, 0f);
        return Math.max(mMinAlpha, result);
    }

    /**
     * Determines whether the given view has RTL layout.
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    public static boolean isLayoutRtl(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return View.LAYOUT_DIRECTION_RTL == view.getLayoutDirection();
        } else {
            return false;
        }
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDragging = false;
                mCurrView = mCallback.getChildAtPosition(ev);
                mVelocityTracker.clear();
                if (mCurrView != null) {
                    mRtl = isLayoutRtl(mCurrView);
                    mCanCurrViewBeDimissed = mCallback.canChildBeDismissed(mCurrView);
                    mVelocityTracker.addMovement(ev);
                    mInitialTouchPos = getPos(ev);
                } else {
                    mCanCurrViewBeDimissed = false;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mCurrView != null) {
                    mVelocityTracker.addMovement(ev);
                    float pos = getPos(ev);
                    float delta = pos - mInitialTouchPos;
                    if (Math.abs(delta) > mPagingTouchSlop) {
                        mCallback.onBeginDrag(mCurrView);
                        mDragging = true;
                        mInitialTouchPos = pos - getTranslation(mCurrView);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                mCurrView = null;
                break;
        }
        return mDragging;
    }

    /**
     * @param view The view to be dismissed
     * @param velocity The desired pixels/second speed at which the view should move
     */
    private void dismissChild(final View view, float velocity) {
        final boolean canAnimViewBeDismissed = mCallback.canChildBeDismissed(view);
        float newPos;
        if (velocity < 0
                || (velocity == 0 && getTranslation(view) < 0)
                // if we use the Menu to dismiss an item in landscape, animate up
                || (velocity == 0 && getTranslation(view) == 0 && mSwipeDirection == Y)) {
            newPos = -getSize(view);
        } else {
            newPos = getSize(view);
        }
        int duration = MAX_ESCAPE_ANIMATION_DURATION;
        if (velocity != 0) {
            duration = Math.min(duration,
                                (int) (Math.abs(newPos - getTranslation(view)) *
                                        1000f / Math.abs(velocity)));
        } else {
            duration = DEFAULT_ESCAPE_ANIMATION_DURATION;
        }

        ValueAnimator anim = createTranslationAnimation(view, newPos);
        anim.setInterpolator(sLinearInterpolator);
        anim.setDuration(duration);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mCallback.onChildDismissed(view);
                if (FADE_OUT_DURING_SWIPE && canAnimViewBeDismissed) {
                    view.setAlpha(1.f);
                }
            }
        });
        anim.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (FADE_OUT_DURING_SWIPE && canAnimViewBeDismissed) {
                    view.setAlpha(getAlphaForOffset(view));
                }
            }
        });
        anim.start();
    }

    private void snapChild(final View view, float velocity) {
        final boolean canAnimViewBeDismissed = mCallback.canChildBeDismissed(view);
        ValueAnimator anim = createTranslationAnimation(view, 0);
        int duration = SNAP_ANIM_LEN;
        anim.setDuration(duration);
        anim.setInterpolator(RecentsConfiguration.getInstance().linearOutSlowInInterpolator);
        anim.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (FADE_OUT_DURING_SWIPE && canAnimViewBeDismissed) {
                    view.setAlpha(getAlphaForOffset(view));
                }
                mCallback.onSwipeChanged(mCurrView, view.getTranslationX());
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (FADE_OUT_DURING_SWIPE && canAnimViewBeDismissed) {
                    view.setAlpha(1.0f);
                }
                mCallback.onSnapBackCompleted(view);
            }
        });
        anim.start();
    }

    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDragging) {
            if (!onInterceptTouchEvent(ev)) {
                return mCanCurrViewBeDimissed;
            }
        }

        mVelocityTracker.addMovement(ev);
        final int action = ev.getAction();
        switch (action) {
            case MotionEvent.ACTION_OUTSIDE:
            case MotionEvent.ACTION_MOVE:
                if (mCurrView != null) {
                    float delta = getPos(ev) - mInitialTouchPos;
                    setSwipeAmount(delta);
                    mCallback.onSwipeChanged(mCurrView, delta);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mCurrView != null) {
                    endSwipe(mVelocityTracker);
                }
                break;
        }
        return true;
    }

    private void setSwipeAmount(float amount) {
        // don't let items that can't be dismissed be dragged more than
        // maxScrollDistance
        if (CONSTRAIN_SWIPE
                && (!isValidSwipeDirection(amount) || !mCallback.canChildBeDismissed(mCurrView))) {
            float size = getSize(mCurrView);
            float maxScrollDistance = 0.15f * size;
            if (Math.abs(amount) >= size) {
                amount = amount > 0 ? maxScrollDistance : -maxScrollDistance;
            } else {
                amount = maxScrollDistance * (float) Math.sin((amount/size)*(Math.PI/2));
            }
        }
        setTranslation(mCurrView, amount);
        if (FADE_OUT_DURING_SWIPE && mCanCurrViewBeDimissed) {
            float alpha = getAlphaForOffset(mCurrView);
            mCurrView.setAlpha(alpha);
        }
    }

    private boolean isValidSwipeDirection(float amount) {
        if (mSwipeDirection == X) {
            if (mRtl) {
                return (amount <= 0) ? mAllowSwipeTowardsEnd : mAllowSwipeTowardsStart;
            } else {
                return (amount <= 0) ? mAllowSwipeTowardsStart : mAllowSwipeTowardsEnd;
            }
        }

        // Vertical swipes are always valid.
        return true;
    }

    private void endSwipe(VelocityTracker velocityTracker) {
        float maxVelocity = MAX_DISMISS_VELOCITY * mDensityScale;
        velocityTracker.computeCurrentVelocity(1000 /* px/sec */, maxVelocity);
        float velocity = getVelocity(velocityTracker);
        float perpendicularVelocity = getPerpendicularVelocity(velocityTracker);
        float escapeVelocity = SWIPE_ESCAPE_VELOCITY * mDensityScale;
        float translation = getTranslation(mCurrView);
        // Decide whether to dismiss the current view
        boolean childSwipedFarEnough = DISMISS_IF_SWIPED_FAR_ENOUGH &&
                Math.abs(translation) > 0.6 * getSize(mCurrView);
        boolean childSwipedFastEnough = (Math.abs(velocity) > escapeVelocity) &&
                (Math.abs(velocity) > Math.abs(perpendicularVelocity)) &&
                (velocity > 0) == (translation > 0);

        boolean dismissChild = mCallback.canChildBeDismissed(mCurrView)
                && isValidSwipeDirection(translation)
                && (childSwipedFastEnough || childSwipedFarEnough);

        if (dismissChild) {
            // flingadingy
            dismissChild(mCurrView, childSwipedFastEnough ? velocity : 0f);
        } else {
            // snappity
            mCallback.onDragCancelled(mCurrView);
            snapChild(mCurrView, velocity);
        }
    }

    public interface Callback {
        View getChildAtPosition(MotionEvent ev);

        boolean canChildBeDismissed(View v);

        void onBeginDrag(View v);

        void onSwipeChanged(View v, float delta);

        void onChildDismissed(View v);

        void onSnapBackCompleted(View v);

        void onDragCancelled(View v);
    }
}
