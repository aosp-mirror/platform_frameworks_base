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
import android.animation.ObjectAnimator;
import android.animation.Animator.AnimatorListener;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.graphics.RectF;
import android.util.Log;
import android.view.animation.LinearInterpolator;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

public class SwipeHelper {
    static final String TAG = "com.android.systemui.SwipeHelper";
    private static final boolean DEBUG = true;
    private static final boolean DEBUG_INVALIDATE = false;
    private static final boolean SLOW_ANIMATIONS = false; // DEBUG;

    public static final int X = 0;
    public static final int Y = 1;

    private boolean CONSTRAIN_SWIPE = true;
    private boolean FADE_OUT_DURING_SWIPE = true;
    private boolean DISMISS_IF_SWIPED_FAR_ENOUGH = true;

    private float SWIPE_ESCAPE_VELOCITY = 100f; // dp/sec
    private int MAX_ESCAPE_ANIMATION_DURATION = 500; // ms
    private static final int SNAP_ANIM_LEN = SLOW_ANIMATIONS ? 1000 : 250; // ms

    public static float ALPHA_FADE_START = 0.8f; // fraction of thumbnail width
                                                 // where fade starts
    static final float ALPHA_FADE_END = 0.5f; // fraction of thumbnail width
                                              // beyond which alpha->0

    private float mPagingTouchSlop;
    private Callback mCallback;
    private int mSwipeDirection;
    private VelocityTracker mVelocityTracker;

    private float mInitialTouchPos;
    private boolean mDragging;
    private View mCurrView;
    private float mDensityScale;

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

    private float getPos(MotionEvent ev) {
        return mSwipeDirection == X ? ev.getX() : ev.getY();
    }

    private float getPos(View v) {
        return mSwipeDirection == X ? v.getX() : v.getY();
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

    private float getContentSize(View v) {
        View content = mCallback.getChildContentView(v);
        return getSize(content);
    }

    private float getAlphaForOffset(View view, float thumbSize) {
        final float fadeSize = ALPHA_FADE_END * thumbSize;
        float result = 1.0f;
        float pos = getPos(view);
        if (pos >= thumbSize * ALPHA_FADE_START) {
            result = 1.0f - (pos - thumbSize * ALPHA_FADE_START) / fadeSize;
        } else if (pos < thumbSize * (1.0f - ALPHA_FADE_START)) {
            result = 1.0f + (thumbSize * ALPHA_FADE_START + pos) / fadeSize;
        }
        return result;
    }

    void invalidateGlobalRegion(View view) {
        RectF childBounds = new RectF(view.getLeft(), view.getTop(), view.getRight(), view
                .getBottom());
        childBounds.offset(view.getX(), view.getY());
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

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDragging = false;
                mCurrView = mCallback.getChildAtPosition(ev);
                mVelocityTracker.clear();
                mVelocityTracker.addMovement(ev);
                mInitialTouchPos = getPos(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mCurrView != null) {
                    mVelocityTracker.addMovement(ev);
                    float pos = getPos(ev);
                    float delta = pos - mInitialTouchPos;
                    if (Math.abs(delta) > mPagingTouchSlop) {
                        mCallback.onBeginDrag(mCurrView);
                        mDragging = true;
                        mInitialTouchPos = getPos(ev) - getPos(mCurrView);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                mDragging = false;
                mCurrView = null;
                break;
        }
        return mDragging;
    }

    public void dismissChild(final View animView, float velocity) {
        float newPos;
        if (velocity < 0 || (velocity == 0 && getPos(animView) < 0)) {
            newPos = -getSize(animView);
        } else {
            newPos = getSize(animView);
        }
        int duration = MAX_ESCAPE_ANIMATION_DURATION;
        if (velocity != 0) {
            duration = Math.min(duration,
                                (int) (Math.abs(newPos - getPos(animView)) * 1000f / Math
                                        .abs(velocity)));
        }
        ObjectAnimator anim = createTranslationAnimation(animView, newPos);
        anim.setInterpolator(new LinearInterpolator());
        anim.setDuration(duration);
        anim.addListener(new AnimatorListener() {
            public void onAnimationStart(Animator animation) {
            }

            public void onAnimationRepeat(Animator animation) {
            }

            public void onAnimationEnd(Animator animation) {
                mCallback.onChildDismissed(animView);
            }

            public void onAnimationCancel(Animator animation) {
                mCallback.onChildDismissed(animView);
            }
        });
        anim.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                if (FADE_OUT_DURING_SWIPE) {
                    animView.setAlpha(getAlphaForOffset(animView, getContentSize(animView)));
                }
                invalidateGlobalRegion(animView);
            }
        });
        anim.start();
    }

    public void snapChild(final View animView, float velocity) {
        ObjectAnimator anim = createTranslationAnimation(animView, 0);
        int duration = SNAP_ANIM_LEN;
        anim.setDuration(duration);
        anim.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                if (FADE_OUT_DURING_SWIPE) {
                    animView.setAlpha(getAlphaForOffset(animView, getContentSize(animView)));
                }
                invalidateGlobalRegion(animView);
            }
        });
        anim.start();
    }

    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDragging) {
            return false;
        }

        mVelocityTracker.addMovement(ev);
        final int action = ev.getAction();
        switch (action) {
            case MotionEvent.ACTION_OUTSIDE:
            case MotionEvent.ACTION_MOVE:
                if (mCurrView != null) {
                    float delta = getPos(ev) - mInitialTouchPos;
                    // don't let items that can't be dismissed be dragged more than
                    // maxScrollDistance
                    if (CONSTRAIN_SWIPE && !mCallback.canChildBeDismissed(mCurrView)) {
                        float size = getSize(mCurrView);
                        float maxScrollDistance = 0.15f * size;
                        if (Math.abs(delta) >= size) {
                            delta = delta > 0 ? maxScrollDistance : -maxScrollDistance;
                        } else {
                            delta = maxScrollDistance * (float) Math.sin((delta/size)*(Math.PI/2));
                        }
                    }
                    setTranslation(mCurrView, delta);
                    if (FADE_OUT_DURING_SWIPE) {
                        mCurrView.setAlpha(getAlphaForOffset(mCurrView, getContentSize(mCurrView)));
                    }
                    invalidateGlobalRegion(mCurrView);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mCurrView != null) {
                    float maxVelocity = 1000; // px/sec
                    mVelocityTracker.computeCurrentVelocity(1000 /* px/sec */, maxVelocity);
                    float escapeVelocity = SWIPE_ESCAPE_VELOCITY * mDensityScale;
                    float velocity = getVelocity(mVelocityTracker);
                    float perpendicularVelocity = getPerpendicularVelocity(mVelocityTracker);

                    // Decide whether to dismiss the current view
                    boolean childSwipedFarEnough = DISMISS_IF_SWIPED_FAR_ENOUGH &&
                            Math.abs(getPos(mCurrView)) > 0.4 * getSize(mCurrView);
                    boolean childSwipedFastEnough = (Math.abs(velocity) > escapeVelocity) &&
                            (Math.abs(velocity) > Math.abs(perpendicularVelocity)) &&
                            (velocity > 0) == (getPos(mCurrView) > 0);

                    boolean dismissChild = mCallback.canChildBeDismissed(mCurrView) &&
                            (childSwipedFastEnough || childSwipedFarEnough);

                    if (dismissChild) {
                        // flingadingy
                        dismissChild(mCurrView, childSwipedFastEnough ? velocity : 0f);
                    } else {
                        // snappity
                        snapChild(mCurrView, velocity);
                    }
                }
                break;
        }
        return true;
    }

    public interface Callback {
        View getChildAtPosition(MotionEvent ev);

        View getChildContentView(View v);

        boolean canChildBeDismissed(View v);

        void onBeginDrag(View v);

        void onChildDismissed(View v);
    }
}
