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
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.SwipeHelper;

import java.util.HashSet;

public class NotificationRowLayout extends ViewGroup implements SwipeHelper.Callback {
    private static final String TAG = "NotificationRowLayout";
    private static final boolean DEBUG = false;
    private static final boolean SLOW_ANIMATIONS = false; // DEBUG;

    private static final boolean ANIMATE_LAYOUT = true;

    private static final int APPEAR_ANIM_LEN = SLOW_ANIMATIONS ? 5000 : 250;
    private static final int DISAPPEAR_ANIM_LEN = APPEAR_ANIM_LEN;

    Rect mTmpRect = new Rect();
    int mNumRows = 0;
    int mRowHeight = 0;
    int mHeight = 0;

    HashSet<View> mAppearingViews = new HashSet<View>();
    HashSet<View> mDisappearingViews = new HashSet<View>();

    private SwipeHelper mSwipeHelper;

    public NotificationRowLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationRowLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

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

        float densityScale = getResources().getDisplayMetrics().density;
        float pagingTouchSlop = ViewConfiguration.get(mContext).getScaledPagingTouchSlop();
        mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, densityScale, pagingTouchSlop);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.v(TAG, "onInterceptTouchEvent()");
        return mSwipeHelper.onInterceptTouchEvent(ev) ||
            super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return mSwipeHelper.onTouchEvent(ev) ||
            super.onTouchEvent(ev);
    }

    public boolean canChildBeDismissed(View v) {
        final View veto = v.findViewById(R.id.veto);
        return (veto != null && veto.getVisibility() != View.GONE);
    }

    public void onChildDismissed(View v) {
        final View veto = v.findViewById(R.id.veto);
        if (veto != null && veto.getVisibility() != View.GONE) {
            veto.performClick();
        }
    }

    public void onBeginDrag(View v) {
        // We need to prevent the surrounding ScrollView from intercepting us now;
        // the scroll position will be locked while we swipe
        requestDisallowInterceptTouchEvent(true);
    }

    public void onDragCancelled(View v) {
    }

    public View getChildAtPosition(MotionEvent ev) {
        // find the view under the pointer, accounting for GONE views
        final int count = getChildCount();
        int y = 0;
        int touchY = (int) ev.getY();
        int childIdx = 0;
        View slidingChild;
        for (; childIdx < count; childIdx++) {
            slidingChild = getChildAt(childIdx);
            if (slidingChild.getVisibility() == GONE) {
                continue;
            }
            y += mRowHeight;
            if (touchY < y) return slidingChild;
        }
        return null;
    }

    public View getChildContentView(View v) {
        return v;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float densityScale = getResources().getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(mContext).getScaledPagingTouchSlop();
        mSwipeHelper.setPagingTouchSlop(pagingTouchSlop);
    }

    //**
    @Override
    public void addView(View child, int index, LayoutParams params) {
        super.addView(child, index, params);

        final View childF = child;

        if (ANIMATE_LAYOUT) {
            mAppearingViews.add(child);

            child.setPivotY(0);
            final ObjectAnimator alphaFade = ObjectAnimator.ofFloat(child, "alpha", 0f, 1f);
            alphaFade.setDuration(APPEAR_ANIM_LEN);
            alphaFade.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mAppearingViews.remove(childF);
                    requestLayout(); // pick up any final changes in position
                }
            });
            alphaFade.start();
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

            final ObjectAnimator alphaFade = ObjectAnimator.ofFloat(child, "alpha", 0f);
            alphaFade.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (DEBUG) Slog.d(TAG, "actually removing child: " + childF);
                    NotificationRowLayout.super.removeView(childF);
                    childF.setAlpha(1f);
                    mDisappearingViews.remove(childF);
                    requestLayout(); // pick up any final changes in position
                }
            });

            alphaFade.setDuration(DISAPPEAR_ANIM_LEN);
            alphaFade.start();
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
            float alpha = child.getAlpha();
            if (alpha > 1.0f) {
                if (DEBUG) {
                    Slog.w(TAG, "alpha=" + alpha + " > 1!!! " + child);
                }
                alpha = 1f;
            }
            final int thisRowHeight = (int)(alpha * mRowHeight);
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
