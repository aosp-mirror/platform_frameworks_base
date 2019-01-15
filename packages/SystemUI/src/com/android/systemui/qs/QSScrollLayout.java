/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import androidx.core.widget.NestedScrollView;
import android.util.Property;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.qs.touch.OverScroll;
import com.android.systemui.qs.touch.SwipeDetector;

/**
 * Quick setting scroll view containing the brightness slider and the QS tiles.
 *
 * <p>Call {@link #shouldIntercept(MotionEvent)} from parent views'
 * {@link #onInterceptTouchEvent(MotionEvent)} method to determine whether this view should
 * consume the touch event.
 */
public class QSScrollLayout extends NestedScrollView {
    private final int mTouchSlop;
    private final int mFooterHeight;
    private int mLastMotionY;
    private final SwipeDetector mSwipeDetector;
    private final OverScrollHelper mOverScrollHelper;
    private float mContentTranslationY;

    public QSScrollLayout(Context context, View... children) {
        super(context);
        mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        mFooterHeight = getResources().getDimensionPixelSize(R.dimen.qs_footer_height);
        LinearLayout linearLayout = new LinearLayout(mContext);
        linearLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        for (View view : children) {
            linearLayout.addView(view);
        }
        addView(linearLayout);
        setOverScrollMode(OVER_SCROLL_NEVER);
        mOverScrollHelper = new OverScrollHelper();
        mSwipeDetector = new SwipeDetector(context, mOverScrollHelper, SwipeDetector.VERTICAL);
        mSwipeDetector.setDetectableScrollConditions(SwipeDetector.DIRECTION_BOTH, true);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!canScrollVertically(1) && !canScrollVertically(-1)) {
            return false;
        }
        mSwipeDetector.onTouchEvent(ev);
        return super.onInterceptTouchEvent(ev) || mOverScrollHelper.isInOverScroll();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!canScrollVertically(1) && !canScrollVertically(-1)) {
            return false;
        }
        mSwipeDetector.onTouchEvent(ev);
        return super.onTouchEvent(ev);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.translate(0, mContentTranslationY);
        super.dispatchDraw(canvas);
        canvas.translate(0, -mContentTranslationY);
    }

    public boolean shouldIntercept(MotionEvent ev) {
        if (ev.getY() > (getBottom() - mFooterHeight)) {
            // Do not intercept touches that are below the divider between QS and the footer.
            return false;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mLastMotionY = (int) ev.getY();
        } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Do not allow NotificationPanelView to intercept touch events when this
            // view can be scrolled down.
            if (mLastMotionY >= 0 && Math.abs(ev.getY() - mLastMotionY) > mTouchSlop
                    && canScrollVertically(1)) {
                requestParentDisallowInterceptTouchEvent(true);
                mLastMotionY = (int) ev.getY();
                return true;
            }
        } else if (ev.getActionMasked() == MotionEvent.ACTION_CANCEL
            || ev.getActionMasked() == MotionEvent.ACTION_UP) {
            mLastMotionY = -1;
            requestParentDisallowInterceptTouchEvent(false);
        }
        return false;
    }

    private void requestParentDisallowInterceptTouchEvent(boolean disallowIntercept) {
        final ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    private void setContentTranslationY(float contentTranslationY) {
        mContentTranslationY = contentTranslationY;
        invalidate();
    }

    private static final Property<QSScrollLayout, Float> CONTENT_TRANS_Y =
            new Property<QSScrollLayout, Float>(Float.class, "qsScrollLayoutContentTransY") {
                @Override
                public Float get(QSScrollLayout qsScrollLayout) {
                    return qsScrollLayout.mContentTranslationY;
                }

                @Override
                public void set(QSScrollLayout qsScrollLayout, Float y) {
                    qsScrollLayout.setContentTranslationY(y);
                }
            };

    private class OverScrollHelper implements SwipeDetector.Listener {
        private boolean mIsInOverScroll;

        // We use this value to calculate the actual amount the user has overscrolled.
        private float mFirstDisplacement = 0;

        @Override
        public void onDragStart(boolean start) {}

        @Override
        public boolean onDrag(float displacement, float velocity) {
            // Only overscroll if the user is scrolling down when they're already at the bottom
            // or scrolling up when they're already at the top.
            boolean wasInOverScroll = mIsInOverScroll;
            mIsInOverScroll = (!canScrollVertically(1) && displacement < 0) ||
                    (!canScrollVertically(-1) && displacement > 0);

            if (wasInOverScroll && !mIsInOverScroll) {
                // Exit overscroll. This can happen when the user is in overscroll and then
                // scrolls the opposite way. Note that this causes the reset translation animation
                // to run while the user is dragging, which feels a bit unnatural.
                reset();
            } else if (mIsInOverScroll) {
                if (Float.compare(mFirstDisplacement, 0) == 0) {
                    // Because users can scroll before entering overscroll, we need to
                    // subtract the amount where the user was not in overscroll.
                    mFirstDisplacement = displacement;
                }
                float overscrollY = displacement - mFirstDisplacement;
                setContentTranslationY(getDampedOverScroll(overscrollY));
            }

            return mIsInOverScroll;
        }

        @Override
        public void onDragEnd(float velocity, boolean fling) {
            reset();
        }

        private void reset() {
            if (Float.compare(mContentTranslationY, 0) != 0) {
                ObjectAnimator.ofFloat(QSScrollLayout.this, CONTENT_TRANS_Y, 0)
                        .setDuration(100)
                        .start();
            }
            mIsInOverScroll = false;
            mFirstDisplacement = 0;
        }

        public boolean isInOverScroll() {
            return mIsInOverScroll;
        }

        private float getDampedOverScroll(float y) {
            return OverScroll.dampedScroll(y, getHeight());
        }
    }
}
