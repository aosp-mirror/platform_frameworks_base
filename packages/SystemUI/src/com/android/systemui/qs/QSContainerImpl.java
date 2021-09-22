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

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.qs.customize.QSCustomizer;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Wrapper view with background which contains {@link QSPanel} and {@link QuickStatusBarHeader}
 */
public class QSContainerImpl extends FrameLayout implements Dumpable {

    private final Point mSizePoint = new Point();
    private int mFancyClippingTop;
    private int mFancyClippingBottom;
    private final float[] mFancyClippingRadii = new float[] {0, 0, 0, 0, 0, 0, 0, 0};
    private  final Path mFancyClippingPath = new Path();
    private int mHeightOverride = -1;
    private View mQSDetail;
    private QuickStatusBarHeader mHeader;
    private float mQsExpansion;
    private QSCustomizer mQSCustomizer;
    private NonInterceptingScrollView mQSPanelContainer;
    private ImageView mDragHandle;

    private int mSideMargins;
    private boolean mQsDisabled;
    private int mContentPadding = -1;
    private int mNavBarInset = 0;
    private boolean mClippingEnabled;

    public QSContainerImpl(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQSPanelContainer = findViewById(R.id.expanded_qs_scroll_view);
        mQSDetail = findViewById(R.id.qs_detail);
        mHeader = findViewById(R.id.header);
        mQSCustomizer = findViewById(R.id.qs_customize);
        mDragHandle = findViewById(R.id.qs_drag_handle);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mSizePoint.set(0, 0); // Will be retrieved on next measure pass.
    }

    @Override
    public boolean performClick() {
        // Want to receive clicks so missing QQS tiles doesn't cause collapse, but
        // don't want to do anything with them.
        return true;
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mNavBarInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        mQSPanelContainer.setPaddingRelative(
                mQSPanelContainer.getPaddingStart(),
                mQSPanelContainer.getPaddingTop(),
                mQSPanelContainer.getPaddingEnd(),
                mNavBarInset
        );
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // QSPanel will show as many rows as it can (up to TileLayout.MAX_ROWS) such that the
        // bottom and footer are inside the screen.
        MarginLayoutParams layoutParams = (MarginLayoutParams) mQSPanelContainer.getLayoutParams();

        int maxQs = getDisplayHeight() - layoutParams.topMargin - layoutParams.bottomMargin
                - getPaddingBottom();
        int padding = mPaddingLeft + mPaddingRight + layoutParams.leftMargin
                + layoutParams.rightMargin;
        final int qsPanelWidthSpec = getChildMeasureSpec(widthMeasureSpec, padding,
                layoutParams.width);
        mQSPanelContainer.measure(qsPanelWidthSpec,
                MeasureSpec.makeMeasureSpec(maxQs, MeasureSpec.AT_MOST));
        int width = mQSPanelContainer.getMeasuredWidth() + padding;
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getDisplayHeight(), MeasureSpec.EXACTLY));
        // QSCustomizer will always be the height of the screen, but do this after
        // other measuring to avoid changing the height of the QS.
        mQSCustomizer.measure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(getDisplayHeight(), MeasureSpec.EXACTLY));
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        if (!mFancyClippingPath.isEmpty()) {
            canvas.translate(0, -getTranslationY());
            canvas.clipOutPath(mFancyClippingPath);
            canvas.translate(0, getTranslationY());
        }
        super.dispatchDraw(canvas);
    }

    @Override
    protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed,
            int parentHeightMeasureSpec, int heightUsed) {
        // Do not measure QSPanel again when doing super.onMeasure.
        // This prevents the pages in PagedTileLayout to be remeasured with a different (incorrect)
        // size to the one used for determining the number of rows and then the number of pages.
        if (child != mQSPanelContainer) {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
                    parentHeightMeasureSpec, heightUsed);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateExpansion();
        updateClippingPath();
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
    }

    void updateResources(QSPanelController qsPanelController,
            QuickStatusBarHeaderController quickStatusBarHeaderController) {
        mQSPanelContainer.setPaddingRelative(
                getPaddingStart(),
                mContext.getResources()
                        .getDimensionPixelSize(R.dimen.qs_header_system_icons_area_height),
                getPaddingEnd(),
                getPaddingBottom()
        );

        int sideMargins = getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        int padding = getResources().getDimensionPixelSize(
                R.dimen.notification_shade_content_margin_horizontal);
        boolean marginsChanged = padding != mContentPadding || sideMargins != mSideMargins;
        mContentPadding = padding;
        mSideMargins = sideMargins;
        if (marginsChanged) {
            updatePaddingsAndMargins(qsPanelController, quickStatusBarHeaderController);
        }
    }

    /**
     * Overrides the height of this view (post-layout), so that the content is clipped to that
     * height and the background is set to that height.
     *
     * @param heightOverride the overridden height
     */
    public void setHeightOverride(int heightOverride) {
        mHeightOverride = heightOverride;
        updateExpansion();
    }

    public void updateExpansion() {
        int height = calculateContainerHeight();
        int scrollBottom = calculateContainerBottom();
        setBottom(getTop() + height);
        mQSDetail.setBottom(getTop() + scrollBottom);
        int qsDetailBottomMargin = ((MarginLayoutParams) mQSDetail.getLayoutParams()).bottomMargin;
        mQSDetail.setBottom(getTop() + scrollBottom - qsDetailBottomMargin);
        // Pin the drag handle to the bottom of the panel.
        mDragHandle.setTranslationY(scrollBottom - mDragHandle.getHeight());
    }

    protected int calculateContainerHeight() {
        int heightOverride = mHeightOverride != -1 ? mHeightOverride : getMeasuredHeight();
        // Need to add the dragHandle height so touches will be intercepted by it.
        int dragHandleHeight;
        if (mDragHandle.getVisibility() == VISIBLE) {
            dragHandleHeight = Math.round((1 - mQsExpansion) * mDragHandle.getHeight());
        } else {
            dragHandleHeight = 0;
        }
        return mQSCustomizer.isCustomizing() ? mQSCustomizer.getHeight()
                : Math.round(mQsExpansion * (heightOverride - mHeader.getHeight()))
                + mHeader.getHeight()
                + dragHandleHeight;
    }

    int calculateContainerBottom() {
        int heightOverride = mHeightOverride != -1 ? mHeightOverride : getMeasuredHeight();
        return mQSCustomizer.isCustomizing() ? mQSCustomizer.getHeight()
                : Math.round(mQsExpansion
                        * (heightOverride + mQSPanelContainer.getScrollRange()
                                - mQSPanelContainer.getScrollY() - mHeader.getHeight()))
                        + mHeader.getHeight();
    }

    public void setExpansion(float expansion) {
        mQsExpansion = expansion;
        mQSPanelContainer.setScrollingEnabled(expansion > 0f);
        mDragHandle.setAlpha(1.0f - expansion);
        mDragHandle.setClickable(expansion == 0f); // Only clickable when fully collapsed
        updateExpansion();
    }

    private void updatePaddingsAndMargins(QSPanelController qsPanelController,
            QuickStatusBarHeaderController quickStatusBarHeaderController) {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view == mQSCustomizer) {
                // Some views are always full width or have dependent padding
                continue;
            }
            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            lp.rightMargin = mSideMargins;
            lp.leftMargin = mSideMargins;
            if (view == mQSPanelContainer) {
                // QS panel lays out some of its content full width
                qsPanelController.setContentMargins(mContentPadding, mContentPadding);
                // Set it as double the side margin (to simulate end margin of current page +
                // start margin of next page).
                qsPanelController.setPageMargin(mSideMargins);
            } else if (view == mHeader) {
                quickStatusBarHeaderController.setContentMargins(mContentPadding, mContentPadding);
            } else {
                view.setPaddingRelative(
                        mContentPadding,
                        view.getPaddingTop(),
                        mContentPadding,
                        view.getPaddingBottom());
            }
        }
    }

    private int getDisplayHeight() {
        if (mSizePoint.y == 0) {
            getDisplay().getRealSize(mSizePoint);
        }
        return mSizePoint.y;
    }

    /**
     * Clip QS bottom using a concave shape.
     */
    public void setFancyClipping(int top, int bottom, int radius, boolean enabled) {
        boolean updatePath = false;
        if (mFancyClippingRadii[0] != radius) {
            mFancyClippingRadii[0] = radius;
            mFancyClippingRadii[1] = radius;
            mFancyClippingRadii[2] = radius;
            mFancyClippingRadii[3] = radius;
            updatePath = true;
        }
        if (mFancyClippingTop != top) {
            mFancyClippingTop = top;
            updatePath = true;
        }
        if (mFancyClippingBottom != bottom) {
            mFancyClippingBottom = bottom;
            updatePath = true;
        }
        if (mClippingEnabled != enabled) {
            mClippingEnabled = enabled;
            updatePath = true;
        }

        if (updatePath) {
            updateClippingPath();
        }
    }

    @Override
    protected boolean isTransformedTouchPointInView(float x, float y,
            View child, PointF outLocalPoint) {
        // Prevent touches outside the clipped area from propagating to a child in that area.
        if (mClippingEnabled && y + getTranslationY() > mFancyClippingTop) {
            return false;
        }
        return super.isTransformedTouchPointInView(x, y, child, outLocalPoint);
    }

    private void updateClippingPath() {
        mFancyClippingPath.reset();
        if (!mClippingEnabled) {
            invalidate();
            return;
        }

        mFancyClippingPath.addRoundRect(0, mFancyClippingTop, getWidth(),
                mFancyClippingBottom, mFancyClippingRadii, Path.Direction.CW);
        invalidate();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(getClass().getSimpleName() + " updateClippingPath: top("
                + mFancyClippingTop + ") bottom(" + mFancyClippingBottom  + ") mClippingEnabled("
                + mClippingEnabled + ")");
    }
}
