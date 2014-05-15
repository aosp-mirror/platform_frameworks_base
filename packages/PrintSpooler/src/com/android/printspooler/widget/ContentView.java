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

package com.android.printspooler.widget;

import android.content.Context;
import android.support.v4.widget.ViewDragHelper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import com.android.printspooler.R;

/**
 * This class is a layout manager for the print screen. It has a sliding
 * area that contains the print options. If the sliding area is open the
 * print options are visible and if it is closed a summary of the print
 * job is shown. Under the sliding area there is a place for putting
 * arbitrary content such as preview, error message, progress indicator,
 * etc. The sliding area is covering the content holder under it when
 * the former is opened.
 */
@SuppressWarnings("unused")
public final class ContentView extends ViewGroup implements View.OnClickListener {
    private static final int FIRST_POINTER_ID = 0;

    private final ViewDragHelper mDragger;

    private View mStaticContent;
    private ViewGroup mSummaryContent;
    private View mDynamicContent;

    private View mDraggableContent;
    private ViewGroup mMoreOptionsContainer;
    private ViewGroup mOptionsContainer;

    private View mEmbeddedContentContainer;

    private View mExpandCollapseHandle;
    private View mExpandCollapseIcon;

    private int mClosedOptionsOffsetY;
    private int mCurrentOptionsOffsetY;

    private OptionsStateChangeListener mOptionsStateChangeListener;

    private int mOldDraggableHeight;

    public interface OptionsStateChangeListener {
        public void onOptionsOpened();
        public void onOptionsClosed();
    }

    public ContentView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDragger = ViewDragHelper.create(this, new DragCallbacks());

        // The options view is sliding under the static header but appears
        // after it in the layout, so we will draw in opposite order.
        setChildrenDrawingOrderEnabled(true);
    }

    public void setOptionsStateChangeListener(OptionsStateChangeListener listener) {
        mOptionsStateChangeListener = listener;
    }

    private boolean isOptionsOpened() {
        return mCurrentOptionsOffsetY == 0;
    }

    private boolean isOptionsClosed() {
        return mCurrentOptionsOffsetY == mClosedOptionsOffsetY;
    }

    private void openOptions() {
        if (isOptionsOpened()) {
            return;
        }
        mDragger.smoothSlideViewTo(mDynamicContent, mDynamicContent.getLeft(),
                getOpenedOptionsY());
        invalidate();
    }

    private void closeOptions() {
        if (isOptionsClosed()) {
            return;
        }
        mDragger.smoothSlideViewTo(mDynamicContent, mDynamicContent.getLeft(),
                getClosedOptionsY());
        invalidate();
    }

    @Override
    protected int getChildDrawingOrder(int childCount, int i) {
        return childCount - i - 1;
    }

    @Override
    protected void onFinishInflate() {
        mStaticContent = findViewById(R.id.static_content);
        mSummaryContent = (ViewGroup) findViewById(R.id.summary_content);
        mDynamicContent = findViewById(R.id.dynamic_content);
        mDraggableContent = findViewById(R.id.draggable_content);
        mMoreOptionsContainer = (ViewGroup) findViewById(R.id.more_options_container);
        mOptionsContainer = (ViewGroup) findViewById(R.id.options_container);
        mEmbeddedContentContainer = findViewById(R.id.embedded_content_container);
        mExpandCollapseIcon = findViewById(R.id.expand_collapse_icon);
        mExpandCollapseHandle = findViewById(R.id.expand_collapse_handle);

        mExpandCollapseIcon.setOnClickListener(this);
        mExpandCollapseHandle.setOnClickListener(this);

        // Make sure we start in a closed options state.
        onDragProgress(1.0f);
    }

    @Override
    public void onClick(View view) {
        if (view == mExpandCollapseHandle || view == mExpandCollapseIcon) {
            if (isOptionsClosed()) {
                openOptions();
            } else if (isOptionsOpened()) {
                closeOptions();
            } // else in open/close progress do nothing.
        }
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        /* do nothing */
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mDragger.processTouchEvent(event);
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mDragger.shouldInterceptTouchEvent(event)
                || super.onInterceptTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        if (mDragger.continueSettling(true)) {
            postInvalidateOnAnimation();
        }
    }

    private int getOpenedOptionsY() {
        return mStaticContent.getBottom();
    }

    private int getClosedOptionsY() {
        return getOpenedOptionsY() + mClosedOptionsOffsetY;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChild(mStaticContent, widthMeasureSpec, heightMeasureSpec);

        if (mSummaryContent.getVisibility() != View.GONE) {
            measureChild(mSummaryContent, widthMeasureSpec, heightMeasureSpec);
        }

        measureChild(mDynamicContent, widthMeasureSpec, heightMeasureSpec);

        final int heightSize = MeasureSpec.getSize(heightMeasureSpec);

//        // The height of the draggable content may change and if that happens
//        // we have to adjust the current offset to ensure the sliding area is
//        // at the same position.
//        mCurrentOptionsOffsetY -= mDraggableContent.getMeasuredHeight()
//                - oldDraggableHeight;

        if (mOldDraggableHeight != mDraggableContent.getMeasuredHeight()) {
            mCurrentOptionsOffsetY -= mDraggableContent.getMeasuredHeight()
                    - mOldDraggableHeight;
            mOldDraggableHeight = mDraggableContent.getMeasuredHeight();
        }

        // The height of the draggable content may change and if that happens
        // we have to adjust the sliding area closed state offset.
        mClosedOptionsOffsetY = mSummaryContent.getMeasuredHeight()
                - mDraggableContent.getMeasuredHeight();

        // The content host must be maximally large size that fits entirely
        // on the screen when the options are collapsed.
        ViewGroup.LayoutParams params = mEmbeddedContentContainer.getLayoutParams();
        if (params.height == 0) {
            params.height = heightSize - mStaticContent.getMeasuredHeight()
                    - mSummaryContent.getMeasuredHeight() - mDynamicContent.getMeasuredHeight()
                    + mDraggableContent.getMeasuredHeight();

            mCurrentOptionsOffsetY = mClosedOptionsOffsetY;
        }

        // The content host can grow vertically as much as needed - we will be covering it.
        final int hostHeightMeasureSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, 0);
        measureChild(mEmbeddedContentContainer, widthMeasureSpec, hostHeightMeasureSpec);

        setMeasuredDimension(resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec),
                resolveSize(heightSize, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        mStaticContent.layout(left, top, right, mStaticContent.getMeasuredHeight());

        if (mSummaryContent.getVisibility() != View.GONE) {
            mSummaryContent.layout(left, mStaticContent.getMeasuredHeight(), right,
                    mStaticContent.getMeasuredHeight() + mSummaryContent.getMeasuredHeight());
        }

        final int dynContentTop = mStaticContent.getMeasuredHeight() + mCurrentOptionsOffsetY;
        final int dynContentBottom = dynContentTop + mDynamicContent.getMeasuredHeight();

        mDynamicContent.layout(left, dynContentTop, right, dynContentBottom);

        final int embContentTop = mStaticContent.getMeasuredHeight() + mClosedOptionsOffsetY
                + mDynamicContent.getMeasuredHeight();
        final int embContentBottom = embContentTop + mEmbeddedContentContainer.getMeasuredHeight();

        mEmbeddedContentContainer.layout(left, embContentTop, right, embContentBottom);
    }

    private void onDragProgress(float progress) {
        final int summaryCount = mSummaryContent.getChildCount();
        for (int i = 0; i < summaryCount; i++) {
            View child = mSummaryContent.getChildAt(i);
            child.setAlpha(progress);
        }

        if (progress == 0) {
            if (mOptionsStateChangeListener != null) {
                mOptionsStateChangeListener.onOptionsOpened();
            }
            mSummaryContent.setVisibility(View.GONE);
            mExpandCollapseIcon.setBackgroundResource(R.drawable.ic_expand_less);
        } else {
            mSummaryContent.setVisibility(View.VISIBLE);
        }

        final float inverseAlpha = 1.0f - progress;

        final int optionCount = mOptionsContainer.getChildCount();
        for (int i = 0; i < optionCount; i++) {
            View child = mOptionsContainer.getChildAt(i);
            child.setAlpha(inverseAlpha);
        }

        if (mMoreOptionsContainer.getVisibility() != View.GONE) {
            final int moreOptionCount = mMoreOptionsContainer.getChildCount();
            for (int i = 0; i < moreOptionCount; i++) {
                View child = mMoreOptionsContainer.getChildAt(i);
                child.setAlpha(inverseAlpha);
            }
        }

        if (inverseAlpha == 0) {
            if (mOptionsStateChangeListener != null) {
                mOptionsStateChangeListener.onOptionsClosed();
            }
            if (mMoreOptionsContainer.getVisibility() != View.GONE) {
                mMoreOptionsContainer.setVisibility(View.INVISIBLE);
            }
            mDraggableContent.setVisibility(View.INVISIBLE);
            mExpandCollapseIcon.setBackgroundResource(R.drawable.ic_expand_more);
        } else {
            if (mMoreOptionsContainer.getVisibility() != View.GONE) {
                mMoreOptionsContainer.setVisibility(View.VISIBLE);
            }
            mDraggableContent.setVisibility(View.VISIBLE);
        }
    }

    private final class DragCallbacks extends ViewDragHelper.Callback {
        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            return child == mDynamicContent && pointerId == FIRST_POINTER_ID;
        }

        @Override
        public void onViewPositionChanged(View changedView, int left, int top, int dx, int dy) {
            mCurrentOptionsOffsetY += dy;
            final float progress = ((float) top - getOpenedOptionsY())
                    / (getClosedOptionsY() - getOpenedOptionsY());

            mDraggableContent.notifySubtreeAccessibilityStateChangedIfNeeded();

            onDragProgress(progress);
        }

        public void onViewReleased(View child, float velocityX, float velocityY) {
            final int childTop = child.getTop();

            final int openedOptionsY = getOpenedOptionsY();
            final int closedOptionsY = getClosedOptionsY();

            if (childTop == openedOptionsY || childTop == closedOptionsY) {
                return;
            }

            final int halfRange = closedOptionsY + (openedOptionsY - closedOptionsY) / 2;
            if (childTop < halfRange) {
                mDragger.smoothSlideViewTo(child, child.getLeft(), closedOptionsY);
            } else {
                mDragger.smoothSlideViewTo(child, child.getLeft(), openedOptionsY);
            }

            invalidate();
        }

        public int getViewVerticalDragRange(View child) {
            return mDraggableContent.getHeight();
        }

        public int clampViewPositionVertical(View child, int top, int dy) {
            final int staticOptionBottom = mStaticContent.getBottom();
            return Math.max(Math.min(top, getOpenedOptionsY()), getClosedOptionsY());
        }
    }
}
