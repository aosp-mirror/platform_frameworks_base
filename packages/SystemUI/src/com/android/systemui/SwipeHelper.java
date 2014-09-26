/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.graphics.RectF;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

public class SwipeHelper implements Gefingerpoken {
    static final String TAG = "com.android.systemui.SwipeHelper";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_INVALIDATE = false;
    private static final boolean SLOW_ANIMATIONS = false; // DEBUG;
    private static final boolean CONSTRAIN_SWIPE = true;
    private static final boolean FADE_OUT_DURING_SWIPE = true;
    private static final boolean DISMISS_IF_SWIPED_FAR_ENOUGH = true;

    public static final int X = 0;
    public static final int Y = 1;

    private static LinearInterpolator sLinearInterpolator = new LinearInterpolator();
    private final Interpolator mFastOutLinearInInterpolator;

    private float SWIPE_ESCAPE_VELOCITY = 100f; // dp/sec
    private int DEFAULT_ESCAPE_ANIMATION_DURATION = 200; // ms
    private int MAX_ESCAPE_ANIMATION_DURATION = 400; // ms
    private int MAX_DISMISS_VELOCITY = 2000; // dp/sec
    private static final int SNAP_ANIM_LEN = SLOW_ANIMATIONS ? 1000 : 150; // ms

    public static float SWIPE_PROGRESS_FADE_START = 0f; // fraction of thumbnail width
                                                 // where fade starts
    static final float SWIPE_PROGRESS_FADE_END = 0.5f; // fraction of thumbnail width
                                              // beyond which swipe progress->0
    private float mMinSwipeProgress = 0f;
    private float mMaxSwipeProgress = 1f;

    private float mPagingTouchSlop;
    private Callback mCallback;
    private Handler mHandler;
    private int mSwipeDirection;
    private VelocityTracker mVelocityTracker;

    private float mInitialTouchPos;
    private boolean mDragging;
    private View mCurrView;
    private View mCurrAnimView;
    private boolean mCanCurrViewBeDimissed;
    private float mDensityScale;

    private boolean mLongPressSent;
    private LongPressListener mLongPressListener;
    private Runnable mWatchLongPress;
    private long mLongPressTimeout;

    final private int[] mTmpPos = new int[2];
    private int mFalsingThreshold;
    private boolean mTouchAboveFalsingThreshold;

    public SwipeHelper(int swipeDirection, Callback callback, Context context) {
        mCallback = callback;
        mHandler = new Handler();
        mSwipeDirection = swipeDirection;
        mVelocityTracker = VelocityTracker.obtain();
        mDensityScale =  context.getResources().getDisplayMetrics().density;
        mPagingTouchSlop = ViewConfiguration.get(context).getScaledPagingTouchSlop();

        mLongPressTimeout = (long) (ViewConfiguration.getLongPressTimeout() * 1.5f); // extra long-press!
        mFastOutLinearInInterpolator = AnimationUtils.loadInterpolator(context,
                android.R.interpolator.fast_out_linear_in);
        mFalsingThreshold = context.getResources().getDimensionPixelSize(
                R.dimen.swipe_helper_falsing_threshold);
    }

    public void setLongPressListener(LongPressListener listener) {
        mLongPressListener = listener;
    }

    public void setDensityScale(float densityScale) {
        mDensityScale = densityScale;
    }

    public void setPagingTouchSlop(float pagingTouchSlop) {
        mPagingTouchSlop = pagingTouchSlop;
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
                mSwipeDirection == X ? "translationX" : "translationY", newPos);
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
        return mSwipeDirection == X ? v.getMeasuredWidth() :
                v.getMeasuredHeight();
    }

    public void setMinSwipeProgress(float minSwipeProgress) {
        mMinSwipeProgress = minSwipeProgress;
    }

    public void setMaxSwipeProgress(float maxSwipeProgress) {
        mMaxSwipeProgress = maxSwipeProgress;
    }

    private float getSwipeProgressForOffset(View view) {
        float viewSize = getSize(view);
        final float fadeSize = SWIPE_PROGRESS_FADE_END * viewSize;
        float result = 1.0f;
        float pos = getTranslation(view);
        if (pos >= viewSize * SWIPE_PROGRESS_FADE_START) {
            result = 1.0f - (pos - viewSize * SWIPE_PROGRESS_FADE_START) / fadeSize;
        } else if (pos < viewSize * (1.0f - SWIPE_PROGRESS_FADE_START)) {
            result = 1.0f + (viewSize * SWIPE_PROGRESS_FADE_START + pos) / fadeSize;
        }
        return Math.min(Math.max(mMinSwipeProgress, result), mMaxSwipeProgress);
    }

    private void updateSwipeProgressFromOffset(View animView, boolean dismissable) {
        float swipeProgress = getSwipeProgressForOffset(animView);
        if (!mCallback.updateSwipeProgress(animView, dismissable, swipeProgress)) {
            if (FADE_OUT_DURING_SWIPE && dismissable) {
                float alpha = swipeProgress;
                if (alpha != 0f && alpha != 1f) {
                    animView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                } else {
                    animView.setLayerType(View.LAYER_TYPE_NONE, null);
                }
                animView.setAlpha(getSwipeProgressForOffset(animView));
            }
        }
        invalidateGlobalRegion(animView);
    }

    // invalidate the view's own bounds all the way up the view hierarchy
    public static void invalidateGlobalRegion(View view) {
        invalidateGlobalRegion(
            view,
            new RectF(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()));
    }

    // invalidate a rectangle relative to the view's coordinate system all the way up the view
    // hierarchy
    public static void invalidateGlobalRegion(View view, RectF childBounds) {
        //childBounds.offset(view.getTranslationX(), view.getTranslationY());
        if (DEBUG_INVALIDATE)
            Log.v(TAG, "-------------");
        while (view.getParent() != null && view.getParent() instanceof View) {
            view = (View) view.getParent();
            view.getMatrix().mapRect(childBounds);
            view.invalidate((int) Math.floor(childBounds.left),
                            (int) Math.floor(childBounds.top),
                            (int) Math.ceil(childBounds.right),
                            (int) Math.ceil(childBounds.bottom));
            if (DEBUG_INVALIDATE) {
                Log.v(TAG, "INVALIDATE(" + (int) Math.floor(childBounds.left)
                        + "," + (int) Math.floor(childBounds.top)
                        + "," + (int) Math.ceil(childBounds.right)
                        + "," + (int) Math.ceil(childBounds.bottom));
            }
        }
    }

    public void removeLongPressCallback() {
        if (mWatchLongPress != null) {
            mHandler.removeCallbacks(mWatchLongPress);
            mWatchLongPress = null;
        }
    }

    public boolean onInterceptTouchEvent(final MotionEvent ev) {
        final int action = ev.getAction();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mTouchAboveFalsingThreshold = false;
                mDragging = false;
                mLongPressSent = false;
                mCurrView = mCallback.getChildAtPosition(ev);
                mVelocityTracker.clear();
                if (mCurrView != null) {
                    mCurrAnimView = mCallback.getChildContentView(mCurrView);
                    mCanCurrViewBeDimissed = mCallback.canChildBeDismissed(mCurrView);
                    mVelocityTracker.addMovement(ev);
                    mInitialTouchPos = getPos(ev);

                    if (mLongPressListener != null) {
                        if (mWatchLongPress == null) {
                            mWatchLongPress = new Runnable() {
                                @Override
                                public void run() {
                                    if (mCurrView != null && !mLongPressSent) {
                                        mLongPressSent = true;
                                        mCurrView.sendAccessibilityEvent(
                                                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                                        mCurrView.getLocationOnScreen(mTmpPos);
                                        final int x = (int) ev.getRawX() - mTmpPos[0];
                                        final int y = (int) ev.getRawY() - mTmpPos[1];
                                        mLongPressListener.onLongPress(mCurrView, x, y);
                                    }
                                }
                            };
                        }
                        mHandler.postDelayed(mWatchLongPress, mLongPressTimeout);
                    }

                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (mCurrView != null && !mLongPressSent) {
                    mVelocityTracker.addMovement(ev);
                    float pos = getPos(ev);
                    float delta = pos - mInitialTouchPos;
                    if (Math.abs(delta) > mPagingTouchSlop) {
                        mCallback.onBeginDrag(mCurrView);
                        mDragging = true;
                        mInitialTouchPos = getPos(ev) - getTranslation(mCurrAnimView);

                        removeLongPressCallback();
                    }
                }

                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                final boolean captured = (mDragging || mLongPressSent);
                mDragging = false;
                mCurrView = null;
                mCurrAnimView = null;
                mLongPressSent = false;
                removeLongPressCallback();
                if (captured) return true;
                break;
        }
        return mDragging || mLongPressSent;
    }

    /**
     * @param view The view to be dismissed
     * @param velocity The desired pixels/second speed at which the view should move
     */
    public void dismissChild(final View view, float velocity) {
        dismissChild(view, velocity, null, 0, false, 0);
    }

    /**
     * @param view The view to be dismissed
     * @param velocity The desired pixels/second speed at which the view should move
     * @param endAction The action to perform at the end
     * @param delay The delay after which we should start
     * @param useAccelerateInterpolator Should an accelerating Interpolator be used
     * @param fixedDuration If not 0, this exact duration will be taken
     */
    public void dismissChild(final View view, float velocity, final Runnable endAction,
            long delay, boolean useAccelerateInterpolator, long fixedDuration) {
        final View animView = mCallback.getChildContentView(view);
        final boolean canAnimViewBeDismissed = mCallback.canChildBeDismissed(view);
        float newPos;

        if (velocity < 0
                || (velocity == 0 && getTranslation(animView) < 0)
                // if we use the Menu to dismiss an item in landscape, animate up
                || (velocity == 0 && getTranslation(animView) == 0 && mSwipeDirection == Y)) {
            newPos = -getSize(animView);
        } else {
            newPos = getSize(animView);
        }
        long duration;
        if (fixedDuration == 0) {
            duration = MAX_ESCAPE_ANIMATION_DURATION;
            if (velocity != 0) {
                duration = Math.min(duration,
                        (int) (Math.abs(newPos - getTranslation(animView)) * 1000f / Math
                                .abs(velocity))
                );
            } else {
                duration = DEFAULT_ESCAPE_ANIMATION_DURATION;
            }
        } else {
            duration = fixedDuration;
        }

        animView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ObjectAnimator anim = createTranslationAnimation(animView, newPos);
        if (useAccelerateInterpolator) {
            anim.setInterpolator(mFastOutLinearInInterpolator);
        } else {
            anim.setInterpolator(sLinearInterpolator);
        }
        anim.setDuration(duration);
        if (delay > 0) {
            anim.setStartDelay(delay);
        }
        anim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                mCallback.onChildDismissed(view);
                if (endAction != null) {
                    endAction.run();
                }
                animView.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });
        anim.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                updateSwipeProgressFromOffset(animView, canAnimViewBeDismissed);
            }
        });
        anim.start();
    }

    public void snapChild(final View view, float velocity) {
        final View animView = mCallback.getChildContentView(view);
        final boolean canAnimViewBeDismissed = mCallback.canChildBeDismissed(animView);
        ObjectAnimator anim = createTranslationAnimation(animView, 0);
        int duration = SNAP_ANIM_LEN;
        anim.setDuration(duration);
        anim.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                updateSwipeProgressFromOffset(animView, canAnimViewBeDismissed);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animator) {
                updateSwipeProgressFromOffset(animView, canAnimViewBeDismissed);
                mCallback.onChildSnappedBack(animView);
            }
        });
        anim.start();
    }

    public boolean onTouchEvent(MotionEvent ev) {
        if (mLongPressSent) {
            return true;
        }

        if (!mDragging) {
            if (mCallback.getChildAtPosition(ev) != null) {

                // We are dragging directly over a card, make sure that we also catch the gesture
                // even if nobody else wants the touch event.
                onInterceptTouchEvent(ev);
                return true;
            } else {

                // We are not doing anything, make sure the long press callback
                // is not still ticking like a bomb waiting to go off.
                removeLongPressCallback();
                return false;
            }
        }

        mVelocityTracker.addMovement(ev);
        final int action = ev.getAction();
        switch (action) {
            case MotionEvent.ACTION_OUTSIDE:
            case MotionEvent.ACTION_MOVE:
                if (mCurrView != null) {
                    float delta = getPos(ev) - mInitialTouchPos;
                    float absDelta = Math.abs(delta);
                    if (absDelta >= getFalsingThreshold()) {
                        mTouchAboveFalsingThreshold = true;
                    }
                    // don't let items that can't be dismissed be dragged more than
                    // maxScrollDistance
                    if (CONSTRAIN_SWIPE && !mCallback.canChildBeDismissed(mCurrView)) {
                        float size = getSize(mCurrAnimView);
                        float maxScrollDistance = 0.15f * size;
                        if (absDelta >= size) {
                            delta = delta > 0 ? maxScrollDistance : -maxScrollDistance;
                        } else {
                            delta = maxScrollDistance * (float) Math.sin((delta/size)*(Math.PI/2));
                        }
                    }
                    setTranslation(mCurrAnimView, delta);

                    updateSwipeProgressFromOffset(mCurrAnimView, mCanCurrViewBeDimissed);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mCurrView != null) {
                    float maxVelocity = MAX_DISMISS_VELOCITY * mDensityScale;
                    mVelocityTracker.computeCurrentVelocity(1000 /* px/sec */, maxVelocity);
                    float escapeVelocity = SWIPE_ESCAPE_VELOCITY * mDensityScale;
                    float velocity = getVelocity(mVelocityTracker);
                    float perpendicularVelocity = getPerpendicularVelocity(mVelocityTracker);

                    // Decide whether to dismiss the current view
                    boolean childSwipedFarEnough = DISMISS_IF_SWIPED_FAR_ENOUGH &&
                            Math.abs(getTranslation(mCurrAnimView)) > 0.4 * getSize(mCurrAnimView);
                    boolean childSwipedFastEnough = (Math.abs(velocity) > escapeVelocity) &&
                            (Math.abs(velocity) > Math.abs(perpendicularVelocity)) &&
                            (velocity > 0) == (getTranslation(mCurrAnimView) > 0);
                    boolean falsingDetected = mCallback.isAntiFalsingNeeded()
                            && !mTouchAboveFalsingThreshold;

                    boolean dismissChild = mCallback.canChildBeDismissed(mCurrView)
                            && !falsingDetected && (childSwipedFastEnough || childSwipedFarEnough);

                    if (dismissChild) {
                        // flingadingy
                        dismissChild(mCurrView, childSwipedFastEnough ? velocity : 0f);
                    } else {
                        // snappity
                        mCallback.onDragCancelled(mCurrView);
                        snapChild(mCurrView, velocity);
                    }
                }
                break;
        }
        return true;
    }

    private int getFalsingThreshold() {
        float factor = mCallback.getFalsingThresholdFactor();
        return (int) (mFalsingThreshold * factor);
    }

    public interface Callback {
        View getChildAtPosition(MotionEvent ev);

        View getChildContentView(View v);

        boolean canChildBeDismissed(View v);

        boolean isAntiFalsingNeeded();

        void onBeginDrag(View v);

        void onChildDismissed(View v);

        void onDragCancelled(View v);

        void onChildSnappedBack(View animView);

        /**
         * Updates the swipe progress on a child.
         *
         * @return if true, prevents the default alpha fading.
         */
        boolean updateSwipeProgress(View animView, boolean dismissable, float swipeProgress);

        /**
         * @return The factor the falsing threshold should be multiplied with
         */
        float getFalsingThresholdFactor();
    }

    /**
     * Equivalent to View.OnLongClickListener with coordinates
     */
    public interface LongPressListener {
        /**
         * Equivalent to {@link View.OnLongClickListener#onLongClick(View)} with coordinates
         * @return whether the longpress was handled
         */
        boolean onLongPress(View v, int x, int y);
    }
}
