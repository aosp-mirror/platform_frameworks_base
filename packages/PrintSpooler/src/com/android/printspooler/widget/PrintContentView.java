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
import android.view.inputmethod.InputMethodManager;
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
public final class PrintContentView extends ViewGroup implements View.OnClickListener {
    private static final int FIRST_POINTER_ID = 0;

    private static final int ALPHA_MASK = 0xff000000;
    private static final int ALPHA_SHIFT = 24;

    private static final int COLOR_MASK = 0xffffff;

    private final ViewDragHelper mDragger;

    private final int mScrimColor;

    private View mStaticContent;
    private ViewGroup mSummaryContent;
    private View mDynamicContent;

    private View mDraggableContent;
    private View mPrintButton;
    private View mMoreOptionsButton;
    private ViewGroup mOptionsContainer;

    private View mEmbeddedContentContainer;
    private View mEmbeddedContentScrim;

    private View mExpandCollapseHandle;
    private View mExpandCollapseIcon;

    private int mClosedOptionsOffsetY;
    private int mCurrentOptionsOffsetY = Integer.MIN_VALUE;

    private OptionsStateChangeListener mOptionsStateChangeListener;

    private OptionsStateController mOptionsStateController;

    private int mOldDraggableHeight;

    private float mDragProgress;

    public interface OptionsStateChangeListener {
        public void onOptionsOpened();
        public void onOptionsClosed();
    }

    public interface OptionsStateController {
        public boolean canOpenOptions();
        public boolean canCloseOptions();
    }

    public PrintContentView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDragger = ViewDragHelper.create(this, new DragCallbacks());

        mScrimColor = context.getColor(R.color.print_preview_scrim_color);

        // The options view is sliding under the static header but appears
        // after it in the layout, so we will draw in opposite order.
        setChildrenDrawingOrderEnabled(true);
    }

    public void setOptionsStateChangeListener(OptionsStateChangeListener listener) {
        mOptionsStateChangeListener = listener;
    }

    public void setOpenOptionsController(OptionsStateController controller) {
        mOptionsStateController = controller;
    }

    public boolean isOptionsOpened() {
        return mCurrentOptionsOffsetY == 0;
    }

    private boolean isOptionsClosed() {
        return mCurrentOptionsOffsetY == mClosedOptionsOffsetY;
    }

    public void openOptions() {
        if (isOptionsOpened()) {
            return;
        }
        mDragger.smoothSlideViewTo(mDynamicContent, mDynamicContent.getLeft(),
                getOpenedOptionsY());
        invalidate();
    }

    public void closeOptions() {
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
        mPrintButton = findViewById(R.id.print_button);
        mMoreOptionsButton = findViewById(R.id.more_options_button);
        mOptionsContainer = (ViewGroup) findViewById(R.id.options_container);
        mEmbeddedContentContainer = findViewById(R.id.embedded_content_container);
        mEmbeddedContentScrim = findViewById(R.id.embedded_content_scrim);
        mExpandCollapseHandle = findViewById(R.id.expand_collapse_handle);
        mExpandCollapseIcon = findViewById(R.id.expand_collapse_icon);

        mExpandCollapseHandle.setOnClickListener(this);
        mSummaryContent.setOnClickListener(this);

        // Make sure we start in a closed options state.
        onDragProgress(1.0f);

        // The framework gives focus to the frist focusable and we
        // do not want that, hence we will take focus instead.
        setFocusableInTouchMode(true);
    }

    @Override
    public void focusableViewAvailable(View v) {
        // The framework gives focus to the frist focusable and we
        // do not want that, hence do not announce new focusables.
        return;
    }

    @Override
    public void onClick(View view) {
        if (view == mExpandCollapseHandle || view == mSummaryContent) {
            if (isOptionsClosed() && mOptionsStateController.canOpenOptions()) {
                openOptions();
            } else if (isOptionsOpened() && mOptionsStateController.canCloseOptions()) {
                closeOptions();
            } // else in open/close progress do nothing.
        } else if (view == mEmbeddedContentScrim) {
            if (isOptionsOpened() && mOptionsStateController.canCloseOptions()) {
                closeOptions();
            }
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

    private int computeScrimColor() {
        final int baseAlpha = (mScrimColor & ALPHA_MASK) >>> ALPHA_SHIFT;
        final int adjustedAlpha = (int) (baseAlpha * (1 - mDragProgress));
        return adjustedAlpha << ALPHA_SHIFT | (mScrimColor & COLOR_MASK);
    }

    private int getOpenedOptionsY() {
        return mStaticContent.getBottom();
    }

    private int getClosedOptionsY() {
        return getOpenedOptionsY() + mClosedOptionsOffsetY;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final boolean wasOpened = isOptionsOpened();

        measureChild(mStaticContent, widthMeasureSpec, heightMeasureSpec);

        if (mSummaryContent.getVisibility() != View.GONE) {
            measureChild(mSummaryContent, widthMeasureSpec, heightMeasureSpec);
        }

        measureChild(mDynamicContent, widthMeasureSpec, heightMeasureSpec);

        measureChild(mPrintButton, widthMeasureSpec, heightMeasureSpec);

        // The height of the draggable content may change and if that happens
        // we have to adjust the sliding area closed state offset.
        mClosedOptionsOffsetY = mSummaryContent.getMeasuredHeight()
                - mDraggableContent.getMeasuredHeight();

        if (mCurrentOptionsOffsetY == Integer.MIN_VALUE) {
            mCurrentOptionsOffsetY = mClosedOptionsOffsetY;
        }

        final int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        // The content host must be maximally large size that fits entirely
        // on the screen when the options are collapsed.
        ViewGroup.LayoutParams params = mEmbeddedContentContainer.getLayoutParams();
        params.height = heightSize - mStaticContent.getMeasuredHeight()
                - mSummaryContent.getMeasuredHeight() - mDynamicContent.getMeasuredHeight()
                + mDraggableContent.getMeasuredHeight();

        // The height of the draggable content may change and if that happens
        // we have to adjust the current offset to ensure the sliding area is
        // at the correct position.
        if (mOldDraggableHeight != mDraggableContent.getMeasuredHeight()) {
            if (mOldDraggableHeight != 0) {
                mCurrentOptionsOffsetY = wasOpened ? 0 : mClosedOptionsOffsetY;
            }
            mOldDraggableHeight = mDraggableContent.getMeasuredHeight();
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

        MarginLayoutParams params = (MarginLayoutParams) mPrintButton.getLayoutParams();

        final int printButtonLeft;
        if (getLayoutDirection() == View.LAYOUT_DIRECTION_LTR) {
            printButtonLeft = right - mPrintButton.getMeasuredWidth() - params.getMarginStart();
        } else {
            printButtonLeft = left + params.getMarginStart();
        }
        final int printButtonTop = dynContentBottom - mPrintButton.getMeasuredHeight() / 2;
        final int printButtonRight = printButtonLeft + mPrintButton.getMeasuredWidth();
        final int printButtonBottom = printButtonTop + mPrintButton.getMeasuredHeight();

        mPrintButton.layout(printButtonLeft, printButtonTop, printButtonRight, printButtonBottom);

        final int embContentTop = mStaticContent.getMeasuredHeight() + mClosedOptionsOffsetY
                + mDynamicContent.getMeasuredHeight();
        final int embContentBottom = embContentTop + mEmbeddedContentContainer.getMeasuredHeight();

        mEmbeddedContentContainer.layout(left, embContentTop, right, embContentBottom);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new ViewGroup.MarginLayoutParams(getContext(), attrs);
    }

    private void onDragProgress(float progress) {
        if (Float.compare(mDragProgress, progress) == 0) {
            return;
        }

        if ((mDragProgress == 0 && progress > 0)
                || (mDragProgress == 1.0f && progress < 1.0f)) {
            mSummaryContent.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            mDraggableContent.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            mMoreOptionsButton.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            ensureImeClosedAndInputFocusCleared();
        }
        if ((mDragProgress > 0 && progress == 0)
                || (mDragProgress < 1.0f && progress == 1.0f)) {
            mSummaryContent.setLayerType(View.LAYER_TYPE_NONE, null);
            mDraggableContent.setLayerType(View.LAYER_TYPE_NONE, null);
            mMoreOptionsButton.setLayerType(View.LAYER_TYPE_NONE, null);
            mMoreOptionsButton.setLayerType(View.LAYER_TYPE_NONE, null);
        }

        mDragProgress = progress;

        mSummaryContent.setAlpha(progress);

        final float inverseAlpha = 1.0f - progress;
        mOptionsContainer.setAlpha(inverseAlpha);
        mMoreOptionsButton.setAlpha(inverseAlpha);

        mEmbeddedContentScrim.setBackgroundColor(computeScrimColor());
        if (progress == 0) {
            if (mOptionsStateChangeListener != null) {
                mOptionsStateChangeListener.onOptionsOpened();
            }
            mExpandCollapseHandle.setContentDescription(
                    mContext.getString(R.string.collapse_handle));
            announceForAccessibility(mContext.getString(R.string.print_options_expanded));
            mSummaryContent.setVisibility(View.GONE);
            mEmbeddedContentScrim.setOnClickListener(this);
            mExpandCollapseIcon.setBackgroundResource(R.drawable.ic_expand_less);
        } else {
            mSummaryContent.setVisibility(View.VISIBLE);
        }

        if (progress == 1.0f) {
            if (mOptionsStateChangeListener != null) {
                mOptionsStateChangeListener.onOptionsClosed();
            }
            mExpandCollapseHandle.setContentDescription(
                    mContext.getString(R.string.expand_handle));
            announceForAccessibility(mContext.getString(R.string.print_options_collapsed));
            if (mMoreOptionsButton.getVisibility() != View.GONE) {
                mMoreOptionsButton.setVisibility(View.INVISIBLE);
            }
            mDraggableContent.setVisibility(View.INVISIBLE);
            // If we change the scrim visibility the dimming is lagging
            // and is janky. Now it is there but transparent, doing nothing.
            mEmbeddedContentScrim.setOnClickListener(null);
            mEmbeddedContentScrim.setClickable(false);
            mExpandCollapseIcon.setBackgroundResource(R.drawable.ic_expand_more);
        } else {
            if (mMoreOptionsButton.getVisibility() != View.GONE) {
                mMoreOptionsButton.setVisibility(View.VISIBLE);
            }
            mDraggableContent.setVisibility(View.VISIBLE);
        }
    }

    private void ensureImeClosedAndInputFocusCleared() {
        View focused = findFocus();

        if (focused != null && focused.isFocused()) {
            InputMethodManager imm = (InputMethodManager) mContext.getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            if (imm.isActive(focused)) {
                imm.hideSoftInputFromWindow(getWindowToken(), 0);
            }
            focused.clearFocus();
        }
    }

    private final class DragCallbacks extends ViewDragHelper.Callback {
        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            if (isOptionsOpened() && !mOptionsStateController.canCloseOptions()
                    || isOptionsClosed() && !mOptionsStateController.canOpenOptions()) {
                return false;
            }
            return child == mDynamicContent && pointerId == FIRST_POINTER_ID;
        }

        @Override
        public void onViewPositionChanged(View changedView, int left, int top, int dx, int dy) {
            if ((isOptionsClosed() || isOptionsClosed()) && dy <= 0) {
                return;
            }

            mCurrentOptionsOffsetY += dy;
            final float progress = ((float) top - getOpenedOptionsY())
                    / (getClosedOptionsY() - getOpenedOptionsY());

            mPrintButton.offsetTopAndBottom(dy);

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

        public int getOrderedChildIndex(int index) {
            return getChildCount() - index - 1;
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
