/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.internal.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ListView;
import android.widget.FrameLayout;

import java.util.ArrayList;


/**
 * Layout for the decor for ListViews on watch-type devices with small screens.
 * <p>
 * Supports one panel with the gravity set to top, and one panel with gravity set to bottom.
 * <p>
 * Use with one ListView child. The top and bottom panels will track the ListView's scrolling.
 * If there is no ListView child, it will act like a normal FrameLayout.
 */
public class WatchListDecorLayout extends FrameLayout
        implements ViewTreeObserver.OnScrollChangedListener {

    private int mForegroundPaddingLeft = 0;
    private int mForegroundPaddingTop = 0;
    private int mForegroundPaddingRight = 0;
    private int mForegroundPaddingBottom = 0;

    private final ArrayList<View> mMatchParentChildren = new ArrayList<>(1);

    /** Track the amount the ListView has to scroll up to account for padding change difference. */
    private int mPendingScroll;
    private View mBottomPanel;
    private View mTopPanel;
    private ListView mListView;
    private ViewTreeObserver mObserver;


    public WatchListDecorLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public WatchListDecorLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public WatchListDecorLayout(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mPendingScroll = 0;

        for (int i = 0; i < getChildCount(); ++i) {
            View child = getChildAt(i);
            if (child instanceof ListView) {
                if (mListView != null) {
                    throw new IllegalArgumentException("only one ListView child allowed");
                }
                mListView = (ListView) child;

                mListView.setNestedScrollingEnabled(true);
                mObserver = mListView.getViewTreeObserver();
                mObserver.addOnScrollChangedListener(this);
            } else {
                int gravity = (((LayoutParams) child.getLayoutParams()).gravity
                        & Gravity.VERTICAL_GRAVITY_MASK);
                if (gravity == Gravity.TOP && mTopPanel == null) {
                    mTopPanel = child;
                } else if (gravity == Gravity.BOTTOM && mBottomPanel == null) {
                    mBottomPanel = child;
                }
            }
        }
    }

    @Override
    public void onDetachedFromWindow() {
        mListView = null;
        mBottomPanel = null;
        mTopPanel = null;
        if (mObserver != null) {
            if (mObserver.isAlive()) {
                mObserver.removeOnScrollChangedListener(this);
            }
            mObserver = null;
        }
    }

    private void applyMeasureToChild(View child, int widthMeasureSpec, int heightMeasureSpec) {
        final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();

        final int childWidthMeasureSpec;
        if (lp.width == LayoutParams.MATCH_PARENT) {
            final int width = Math.max(0, getMeasuredWidth()
                    - getPaddingLeftWithForeground() - getPaddingRightWithForeground()
                    - lp.leftMargin - lp.rightMargin);
            childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                    width, MeasureSpec.EXACTLY);
        } else {
            childWidthMeasureSpec = getChildMeasureSpec(widthMeasureSpec,
                    getPaddingLeftWithForeground() + getPaddingRightWithForeground() +
                    lp.leftMargin + lp.rightMargin,
                    lp.width);
        }

        final int childHeightMeasureSpec;
        if (lp.height == LayoutParams.MATCH_PARENT) {
            final int height = Math.max(0, getMeasuredHeight()
                    - getPaddingTopWithForeground() - getPaddingBottomWithForeground()
                    - lp.topMargin - lp.bottomMargin);
            childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
                    height, MeasureSpec.EXACTLY);
        } else {
            childHeightMeasureSpec = getChildMeasureSpec(heightMeasureSpec,
                    getPaddingTopWithForeground() + getPaddingBottomWithForeground() +
                    lp.topMargin + lp.bottomMargin,
                    lp.height);
        }

        child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
    }

    private int measureAndGetHeight(View child, int widthMeasureSpec, int heightMeasureSpec) {
        if (child != null) {
            if (child.getVisibility() != GONE) {
                applyMeasureToChild(mBottomPanel, widthMeasureSpec, heightMeasureSpec);
                return child.getMeasuredHeight();
            } else if (getMeasureAllChildren()) {
                applyMeasureToChild(mBottomPanel, widthMeasureSpec, heightMeasureSpec);
            }
        }
        return 0;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int count = getChildCount();

        final boolean measureMatchParentChildren =
                MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY ||
                MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY;
        mMatchParentChildren.clear();

        int maxHeight = 0;
        int maxWidth = 0;
        int childState = 0;

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (getMeasureAllChildren() || child.getVisibility() != GONE) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                maxWidth = Math.max(maxWidth,
                        child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
                maxHeight = Math.max(maxHeight,
                        child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
                childState = combineMeasuredStates(childState, child.getMeasuredState());
                if (measureMatchParentChildren) {
                    if (lp.width == LayoutParams.MATCH_PARENT ||
                            lp.height == LayoutParams.MATCH_PARENT) {
                        mMatchParentChildren.add(child);
                    }
                }
            }
        }

        // Account for padding too
        maxWidth += getPaddingLeftWithForeground() + getPaddingRightWithForeground();
        maxHeight += getPaddingTopWithForeground() + getPaddingBottomWithForeground();

        // Check against our minimum height and width
        maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());
        maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());

        // Check against our foreground's minimum height and width
        final Drawable drawable = getForeground();
        if (drawable != null) {
            maxHeight = Math.max(maxHeight, drawable.getMinimumHeight());
            maxWidth = Math.max(maxWidth, drawable.getMinimumWidth());
        }

        setMeasuredDimension(resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
                resolveSizeAndState(maxHeight, heightMeasureSpec,
                        childState << MEASURED_HEIGHT_STATE_SHIFT));

        if (mListView != null) {
            if (mPendingScroll != 0) {
                mListView.scrollListBy(mPendingScroll);
                mPendingScroll = 0;
            }

            int paddingTop = Math.max(mListView.getPaddingTop(),
                    measureAndGetHeight(mTopPanel, widthMeasureSpec, heightMeasureSpec));
            int paddingBottom = Math.max(mListView.getPaddingBottom(),
                    measureAndGetHeight(mBottomPanel, widthMeasureSpec, heightMeasureSpec));

            if (paddingTop != mListView.getPaddingTop()
                    || paddingBottom != mListView.getPaddingBottom()) {
                mPendingScroll += mListView.getPaddingTop() - paddingTop;
                mListView.setPadding(
                        mListView.getPaddingLeft(), paddingTop,
                        mListView.getPaddingRight(), paddingBottom);
            }
        }

        count = mMatchParentChildren.size();
        if (count > 1) {
            for (int i = 0; i < count; i++) {
                final View child = mMatchParentChildren.get(i);
                if (mListView == null || (child != mTopPanel && child != mBottomPanel)) {
                    applyMeasureToChild(child, widthMeasureSpec, heightMeasureSpec);
                }
            }
        }
    }

    @Override
    public void setForegroundGravity(int foregroundGravity) {
        if (getForegroundGravity() != foregroundGravity) {
            super.setForegroundGravity(foregroundGravity);

            // calling get* again here because the set above may apply default constraints
            final Drawable foreground = getForeground();
            if (getForegroundGravity() == Gravity.FILL && foreground != null) {
                Rect padding = new Rect();
                if (foreground.getPadding(padding)) {
                    mForegroundPaddingLeft = padding.left;
                    mForegroundPaddingTop = padding.top;
                    mForegroundPaddingRight = padding.right;
                    mForegroundPaddingBottom = padding.bottom;
                }
            } else {
                mForegroundPaddingLeft = 0;
                mForegroundPaddingTop = 0;
                mForegroundPaddingRight = 0;
                mForegroundPaddingBottom = 0;
            }
        }
    }

    private int getPaddingLeftWithForeground() {
        return isForegroundInsidePadding() ? Math.max(mPaddingLeft, mForegroundPaddingLeft) :
            mPaddingLeft + mForegroundPaddingLeft;
    }

    private int getPaddingRightWithForeground() {
        return isForegroundInsidePadding() ? Math.max(mPaddingRight, mForegroundPaddingRight) :
            mPaddingRight + mForegroundPaddingRight;
    }

    private int getPaddingTopWithForeground() {
        return isForegroundInsidePadding() ? Math.max(mPaddingTop, mForegroundPaddingTop) :
            mPaddingTop + mForegroundPaddingTop;
    }

    private int getPaddingBottomWithForeground() {
        return isForegroundInsidePadding() ? Math.max(mPaddingBottom, mForegroundPaddingBottom) :
            mPaddingBottom + mForegroundPaddingBottom;
    }

    @Override
    public void onScrollChanged() {
        if (mListView == null) {
            return;
        }

        if (mTopPanel != null) {
            if (mListView.getChildCount() > 0) {
                if (mListView.getFirstVisiblePosition() == 0) {
                    View firstChild = mListView.getChildAt(0);
                    setScrolling(mTopPanel,
                            firstChild.getY() - mTopPanel.getHeight() - mTopPanel.getTop());
                } else {
                    // shift to hide the frame, last child is not the last position
                    setScrolling(mTopPanel, -mTopPanel.getHeight());
                }
            } else {
                setScrolling(mTopPanel, 0); // no visible child, fallback to default behaviour
            }
        }

        if (mBottomPanel != null) {
            if (mListView.getChildCount() > 0) {
                if (mListView.getLastVisiblePosition() >= mListView.getCount() - 1) {
                    View lastChild = mListView.getChildAt(mListView.getChildCount() - 1);
                    setScrolling(mBottomPanel, Math.max(
                            0,
                            lastChild.getY() + lastChild.getHeight() - mBottomPanel.getTop()));
                } else {
                    // shift to hide the frame, last child is not the last position
                    setScrolling(mBottomPanel, mBottomPanel.getHeight());
                }
            } else {
                setScrolling(mBottomPanel, 0); // no visible child, fallback to default behaviour
            }
        }
    }

    /** Only set scrolling for the panel if there is a change in its translationY. */
    private void setScrolling(View panel, float translationY) {
        if (panel.getTranslationY() != translationY) {
            panel.setTranslationY(translationY);
        }
    }
}
