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

package com.android.systemui.statusbar.policy;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;

import com.android.systemui.R;

public class NotificationRowLayout extends ViewGroup {
    private static final String TAG = "NotificationRowLayout";
    private static final boolean DEBUG = false;
    private static final boolean SLOW_ANIMATIONS = false; // DEBUG;

    private static final boolean ANIMATE_LAYOUT = true;

    private static final boolean CLEAR_IF_SWIPED_FAR_ENOUGH = true;
    
    private static final boolean CONSTRAIN_SWIPE_ON_PERMANENT = true;

    private static final int APPEAR_ANIM_LEN = SLOW_ANIMATIONS ? 5000 : 250;
    private static final int DISAPPEAR_ANIM_LEN = APPEAR_ANIM_LEN;
    private static final int SNAP_ANIM_LEN = SLOW_ANIMATIONS ? 1000 : 250;

    private static final float SWIPE_ESCAPE_VELOCITY = 1500f;
    private static final float SWIPE_ANIM_VELOCITY_MIN = 1000f;

    Rect mTmpRect = new Rect();
    int mNumRows = 0;
    int mRowHeight = 0;
    int mHeight = 0;

    HashSet<View> mAppearingViews = new HashSet<View>();
    HashSet<View> mDisappearingViews = new HashSet<View>();

    VelocityTracker mVT;
    float mInitialTouchX, mInitialTouchY;
    View mSlidingChild = null;
    float mLiftoffVelocity;

    public NotificationRowLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationRowLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mVT = VelocityTracker.obtain();

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.NotificationRowLayout,
                defStyle, 0);
        mRowHeight = a.getDimensionPixelSize(R.styleable.NotificationRowLayout_rowHeight, 0);
        a.recycle();

        setLayoutTransition(null);

        if (DEBUG) {
            setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    Slog.d(TAG, "view added: " + child + "; new count: " + getChildCount());
                }
                @Override
                public void onChildViewRemoved(View parent, View child) {
                    Slog.d(TAG, "view removed: " + child + "; new count: " + (getChildCount() - 1));
                }
            });

            setBackgroundColor(0x80FF8000);
        }

    }

    // Swipey code
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
//        if (DEBUG) Slog.d(TAG, "intercepting touch event: " + ev);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mVT.clear();
                mVT.addMovement(ev);
                mInitialTouchX = ev.getX();
                mInitialTouchY = ev.getY();
                mSlidingChild = null;
                break;
            case MotionEvent.ACTION_MOVE:
                mVT.addMovement(ev);
                if (mSlidingChild == null) {
                    if (Math.abs(ev.getX() - mInitialTouchX) > 4) { // slide slop

                        // find the view under the pointer, accounting for GONE views
                        final int count = getChildCount();
                        int y = 0;
                        int childIdx = 0;
                        for (; childIdx < count; childIdx++) {
                            mSlidingChild = getChildAt(childIdx);
                            if (mSlidingChild.getVisibility() == GONE) {
                                continue;
                            }
                            y += mRowHeight;
                            if (mInitialTouchY < y) break;
                        }

                        mInitialTouchX -= mSlidingChild.getTranslationX();
                        mSlidingChild.animate().cancel();

                        if (DEBUG) {
                            Slog.d(TAG, String.format(
                                "now sliding child %d: %s (touchY=%.1f, rowHeight=%d, count=%d)",
                                childIdx, mSlidingChild, mInitialTouchY, mRowHeight, count));
                        }


                        // We need to prevent the surrounding ScrollView from intercepting us now;
                        // the scroll position will be locked while we swipe
                        requestDisallowInterceptTouchEvent(true);
                    }
                }
                break;
        }
        return mSlidingChild != null;
    }

    protected boolean canBeCleared(View v) {
        final View veto = v.findViewById(R.id.veto);
        return (veto != null && veto.getVisibility() != View.GONE);
    }

    protected boolean clear(View v) {
        final View veto = v.findViewById(R.id.veto);
        if (veto != null && veto.getVisibility() != View.GONE) {
            veto.performClick();
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
//        if (DEBUG) Slog.d(TAG, "touch event: " + ev + " sliding: " + mSlidingChild);
        if (mSlidingChild != null) {
            switch (action) {
                case MotionEvent.ACTION_OUTSIDE:
                case MotionEvent.ACTION_MOVE:
                    mVT.addMovement(ev);

                    float delta = (ev.getX() - mInitialTouchX);
                    if (CONSTRAIN_SWIPE_ON_PERMANENT && !canBeCleared(mSlidingChild)) {
                        delta = Math.copySign(
                                    Math.min(Math.abs(delta),
                                    mSlidingChild.getMeasuredWidth() * 0.2f), delta);
                    }
                    mSlidingChild.setTranslationX(delta);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mVT.addMovement(ev);
                    mVT.computeCurrentVelocity(1000 /* px/sec */);
                    if (DEBUG) Slog.d(TAG, "exit velocity: " + mVT.getXVelocity());
                    boolean restore = true;
                    mLiftoffVelocity = mVT.getXVelocity();
                    if (Math.abs(mLiftoffVelocity) > SWIPE_ESCAPE_VELOCITY
                        || (CLEAR_IF_SWIPED_FAR_ENOUGH && 
                            (mSlidingChild.getTranslationX() * 2) > mSlidingChild.getMeasuredWidth()))
                    {

                        // flingadingy
                        restore = ! clear(mSlidingChild);
                    }
                    if (restore) {
                        // snappity
                        mSlidingChild.animate().translationX(0)
                            .setDuration(SNAP_ANIM_LEN)
                            .start();
                    }
                    break;
            }
            return true;
        }
        return false;
    }

    //**
    @Override
    public void addView(View child, int index, LayoutParams params) {
        super.addView(child, index, params);

        final View childF = child;

        if (ANIMATE_LAYOUT) {
            mAppearingViews.add(child);

            child.setPivotY(0);
            AnimatorSet a = new AnimatorSet();
            a.playTogether(
                    ObjectAnimator.ofFloat(child, "alpha", 0f, 1f)
//                    ,ObjectAnimator.ofFloat(child, "scaleY", 0f, 1f)
            );
            a.setDuration(APPEAR_ANIM_LEN);
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mAppearingViews.remove(childF);
                    requestLayout(); // pick up any final changes in position
                }
            });
            a.start();
            requestLayout(); // start the container animation
        }
    }

    @Override
    public void removeView(View child) {
        final View childF = child;
        if (ANIMATE_LAYOUT) {
            if (mAppearingViews.contains(child)) {
                mAppearingViews.remove(child);
            }
            mDisappearingViews.add(child);

            child.setPivotY(0);

            final float velocity = (mSlidingChild == child) 
                    ? Math.min(mLiftoffVelocity, SWIPE_ANIM_VELOCITY_MIN)
                    : SWIPE_ESCAPE_VELOCITY;
            final TimeAnimator zoom = new TimeAnimator();
            zoom.setTimeListener(new TimeAnimator.TimeListener() {
                @Override
                public void onTimeUpdate(TimeAnimator animation, long totalTime, long deltaTime) {
                    childF.setTranslationX(childF.getTranslationX() + deltaTime / 1000f * velocity);
                }
            });

            final ObjectAnimator alphaFade = ObjectAnimator.ofFloat(child, "alpha", 0f);
            alphaFade.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    zoom.cancel(); // it won't end on its own
                    if (DEBUG) Slog.d(TAG, "actually removing child: " + childF);
                    NotificationRowLayout.super.removeView(childF);
                    childF.setAlpha(1f);
                    mDisappearingViews.remove(childF);
                    requestLayout(); // pick up any final changes in position
                }
            });

            AnimatorSet a = new AnimatorSet();
            a.playTogether(alphaFade, zoom);
                    
//                    ,ObjectAnimator.ofFloat(child, "scaleY", 0f)
//                    ,ObjectAnimator.ofFloat(child, "translationX", child.getTranslationX() + 300f)

            a.setDuration(DISAPPEAR_ANIM_LEN);
            a.start();
            requestLayout(); // start the container animation
        } else {
            super.removeView(child);
        }
    }
    //**

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        setWillNotDraw(false);
    }

    @Override
    public void onDraw(android.graphics.Canvas c) {
        super.onDraw(c);
        if (DEBUG) {
            //Slog.d(TAG, "onDraw: canvas height: " + c.getHeight() + "px; measured height: "
            //        + getMeasuredHeight() + "px");
            c.save();
            c.clipRect(6, 6, c.getWidth() - 6, getMeasuredHeight() - 6,
                    android.graphics.Region.Op.DIFFERENCE);
            c.drawColor(0xFFFF8000);
            c.restore();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int count = getChildCount();

        // pass 1: count the number of non-GONE views
        int numRows = 0;
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            if (mDisappearingViews.contains(child)) {
                continue;
            }
            numRows++;
        }
        if (numRows != mNumRows) {
            // uh oh, now you made us go and do work
            
            final int computedHeight = numRows * mRowHeight;
            if (DEBUG) {
                Slog.d(TAG, String.format("rows went from %d to %d, resizing to %dpx",
                            mNumRows, numRows, computedHeight));
            }

            mNumRows = numRows;

            if (ANIMATE_LAYOUT && isShown()) {
                ObjectAnimator.ofInt(this, "forcedHeight", computedHeight)
                    .setDuration(APPEAR_ANIM_LEN)
                    .start();
            } else {
                setForcedHeight(computedHeight);
            }
        }

        // pass 2: you know, do the measuring
        final int childWidthMS = widthMeasureSpec;
        final int childHeightMS = MeasureSpec.makeMeasureSpec(
                mRowHeight, MeasureSpec.EXACTLY);

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }

            child.measure(childWidthMS, childHeightMS);
        }

        setMeasuredDimension(
                getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(getForcedHeight(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int width = right - left;
        final int height = bottom - top;

        if (DEBUG) Slog.d(TAG, "onLayout: height=" + height);

        final int count = getChildCount();
        int y = 0;
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            final int thisRowHeight = (int)(child.getAlpha() * mRowHeight);
            if (DEBUG) {
                Slog.d(TAG, String.format(
                            "laying out child #%d: (0, %d, %d, %d) h=%d",
                            i, y, width, y + thisRowHeight, thisRowHeight));
            }
            child.layout(0, y, width, y + thisRowHeight);
            y += thisRowHeight;
        }
        if (DEBUG) {
            Slog.d(TAG, "onLayout: final y=" + y);
        }
    }

    public void setForcedHeight(int h) {
        if (DEBUG) Slog.d(TAG, "forcedHeight: " + h);
        if (h != mHeight) {
            mHeight = h;
            requestLayout();
        }
    }

    public int getForcedHeight() {
        return mHeight;
    }
}
