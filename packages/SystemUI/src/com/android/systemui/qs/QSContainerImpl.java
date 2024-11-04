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
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.android.systemui.Dumpable;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.shade.LargeScreenHeaderHelper;
import com.android.systemui.shade.TouchLogger;
import com.android.systemui.util.LargeScreenUtils;

import java.io.PrintWriter;

/**
 * Wrapper view with background which contains {@link QSPanel} and {@link QuickStatusBarHeader}
 */
public class QSContainerImpl extends FrameLayout implements Dumpable {

    private int mFancyClippingLeftInset;
    private int mFancyClippingTop;
    private int mFancyClippingRightInset;
    private int mFancyClippingBottom;
    private final float[] mFancyClippingRadii = new float[] {0, 0, 0, 0, 0, 0, 0, 0};
    private  final Path mFancyClippingPath = new Path();
    private int mHeightOverride = -1;
    private QuickStatusBarHeader mHeader;
    private float mQsExpansion;
    private QSCustomizer mQSCustomizer;
    private QSPanel mQSPanel;
    private NonInterceptingScrollView mQSPanelContainer;

    private int mHorizontalMargins;
    private int mTilesPageMargin;
    private boolean mQsDisabled;
    private int mContentHorizontalPadding = -1;
    private boolean mClippingEnabled;
    private boolean mIsFullWidth;

    private boolean mSceneContainerEnabled;

    public QSContainerImpl(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQSPanelContainer = findViewById(R.id.expanded_qs_scroll_view);
        mQSPanel = findViewById(R.id.quick_settings_panel);
        mHeader = findViewById(R.id.header);
        mQSCustomizer = findViewById(R.id.qs_customize);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setSceneContainerEnabled(boolean enabled) {
        mSceneContainerEnabled = enabled;
        if (enabled) {
            mQSPanelContainer.removeAllViews();
            removeView(mQSPanelContainer);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            addView(mQSPanel, 0, lp);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public boolean performClick() {
        // Want to receive clicks so missing QQS tiles doesn't cause collapse, but
        // don't want to do anything with them.
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // QSPanel will show as many rows as it can (up to TileLayout.MAX_ROWS) such that the
        // bottom and footer are inside the screen.
        int availableHeight = View.MeasureSpec.getSize(heightMeasureSpec);

        if (!mSceneContainerEnabled) {
            MarginLayoutParams layoutParams =
                    (MarginLayoutParams) mQSPanelContainer.getLayoutParams();
            int maxQs = availableHeight - layoutParams.topMargin - layoutParams.bottomMargin
                    - getPaddingBottom();
            int padding = mPaddingLeft + mPaddingRight + layoutParams.leftMargin
                    + layoutParams.rightMargin;
            final int qsPanelWidthSpec = getChildMeasureSpec(widthMeasureSpec, padding,
                    layoutParams.width);
            mQSPanelContainer.measure(qsPanelWidthSpec,
                    MeasureSpec.makeMeasureSpec(maxQs, MeasureSpec.AT_MOST));
            int width = mQSPanelContainer.getMeasuredWidth() + padding;
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.EXACTLY));
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        // QSCustomizer will always be the height of the screen, but do this after
        // other measuring to avoid changing the height of the QS.
        mQSCustomizer.measure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.EXACTLY));
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
        if (!mSceneContainerEnabled) {
            // Do not measure QSPanel again when doing super.onMeasure.
            // This prevents the pages in PagedTileLayout to be remeasured with a different
            // (incorrect) size to the one used for determining the number of rows and then the
            // number of pages.
            if (child != mQSPanelContainer) {
                super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
                        parentHeightMeasureSpec, heightUsed);
            }
        } else {
            // Don't measure the customizer with all the children, it will be measured separately
            if (child != mQSCustomizer) {
                super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
                        parentHeightMeasureSpec, heightUsed);
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return TouchLogger.logDispatchTouch("QS", ev, super.dispatchTouchEvent(ev));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateExpansion();
        updateClippingPath();
    }

    @Nullable
    public NonInterceptingScrollView getQSPanelContainer() {
        return mQSPanelContainer;
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
    }

    void updateResources(QSPanelController qsPanelController,
            QuickStatusBarHeaderController quickStatusBarHeaderController) {
        int topPadding = QSUtils.getQsHeaderSystemIconsAreaHeight(mContext);
        if (!LargeScreenUtils.shouldUseLargeScreenShadeHeader(mContext.getResources())) {
            topPadding = LargeScreenHeaderHelper.getLargeScreenHeaderHeight(mContext);
        }
        if (mQSPanelContainer != null) {
            mQSPanelContainer.setPaddingRelative(
                    mQSPanelContainer.getPaddingStart(),
                    mSceneContainerEnabled ? 0 : topPadding,
                    mQSPanelContainer.getPaddingEnd(),
                    mQSPanelContainer.getPaddingBottom());
        } else {
            mQSPanel.setPaddingRelative(
                    mQSPanel.getPaddingStart(),
                    mSceneContainerEnabled ? 0 : topPadding,
                    mQSPanel.getPaddingEnd(),
                    mQSPanel.getPaddingBottom());
        }

        int horizontalMargins = getResources().getDimensionPixelSize(R.dimen.qs_horizontal_margin);
        int horizontalPadding = getResources().getDimensionPixelSize(
                R.dimen.qs_content_horizontal_padding);
        int tilesPageMargin = getResources().getDimensionPixelSize(
                R.dimen.qs_tiles_page_horizontal_margin);
        boolean marginsChanged = horizontalPadding != mContentHorizontalPadding
                || horizontalMargins != mHorizontalMargins
                || tilesPageMargin != mTilesPageMargin;
        mContentHorizontalPadding = horizontalPadding;
        mHorizontalMargins = horizontalMargins;
        mTilesPageMargin = tilesPageMargin;
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
        setBottom(getTop() + height);
    }

    protected int calculateContainerHeight() {
        int heightOverride = mHeightOverride != -1 ? mHeightOverride : getMeasuredHeight();
        // Need to add the dragHandle height so touches will be intercepted by it.
        return mQSCustomizer.isCustomizing() ? mQSCustomizer.getHeight()
                : Math.round(mQsExpansion * (heightOverride - mHeader.getHeight()))
                + mHeader.getHeight();
    }

    // These next two methods are used with Scene container to determine the size of QQS and QS .

    /**
     * Returns the size of the QQS container, regardless of the measured size of this view.
     * @return size in pixels of QQS
     */
    public int getQqsHeight() {
        SceneContainerFlag.assertInNewMode();
        return mHeader.getMeasuredHeight();
    }

    /**
     * @return height with the squishiness fraction applied.
     */
    int getSquishedQqsHeight() {
        return mHeader.getSquishedHeight();
    }

    /**
     * Returns the size of QS (or the QSCustomizer), regardless of the measured size of this view
     * @return size in pixels of QS (or QSCustomizer)
     */
    public int getQsHeight() {
        return mQSCustomizer.isCustomizing() ? mQSCustomizer.getMeasuredHeight()
                : mQSPanel.getMeasuredHeight();
    }

    /**
     * @return height with the squishiness fraction applied.
     */
    int getSquishedQsHeight() {
        return mQSPanel.getSquishedHeight();
    }

    public void setExpansion(float expansion) {
        mQsExpansion = expansion;
        if (mQSPanelContainer != null) {
            mQSPanelContainer.setScrollingEnabled(expansion > 0f);
        }
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
            if (view.getId() != R.id.qs_footer_actions) {
                // Only padding for FooterActionsView, no margin. That way, the background goes
                // all the way to the edge.
                LayoutParams lp = (LayoutParams) view.getLayoutParams();
                lp.rightMargin = mHorizontalMargins;
                lp.leftMargin = mHorizontalMargins;
            }
            if (view == mQSPanelContainer || view == mQSPanel) {
                // QS panel lays out some of its content full width
                qsPanelController.setContentMargins(mContentHorizontalPadding,
                        mContentHorizontalPadding);
                setPageMargins(qsPanelController);
            } else if (view == mHeader) {
                quickStatusBarHeaderController.setContentMargins(mContentHorizontalPadding,
                        mContentHorizontalPadding);
            } else {
                // Set the horizontal paddings unless the view is the Compose implementation of the
                // footer actions.
                if (view.getId() != R.id.qs_footer_actions) {
                    view.setPaddingRelative(
                            mContentHorizontalPadding,
                            view.getPaddingTop(),
                            mContentHorizontalPadding,
                            view.getPaddingBottom());
                }
            }
        }
    }

    private void setPageMargins(QSPanelController qsPanelController) {
        if (SceneContainerFlag.isEnabled()) {
            if (mHorizontalMargins == mTilesPageMargin * 2 + 1) {
                qsPanelController.setPageMargin(mTilesPageMargin, mTilesPageMargin + 1);
            } else {
                qsPanelController.setPageMargin(mTilesPageMargin, mTilesPageMargin);
            }
        } else {
            qsPanelController.setPageMargin(mTilesPageMargin, mTilesPageMargin);
        }
    }

    /**
     * Clip QS bottom using a concave shape.
     */
    public void setFancyClipping(int leftInset, int top, int rightInset, int bottom, int radius,
            boolean enabled, boolean fullWidth) {
        boolean updatePath = false;
        if (mFancyClippingRadii[0] != radius) {
            mFancyClippingRadii[0] = radius;
            mFancyClippingRadii[1] = radius;
            mFancyClippingRadii[2] = radius;
            mFancyClippingRadii[3] = radius;
            updatePath = true;
        }
        if (mFancyClippingLeftInset != leftInset) {
            mFancyClippingLeftInset = leftInset;
            updatePath = true;
        }
        if (mFancyClippingTop != top) {
            mFancyClippingTop = top;
            updatePath = true;
        }
        if (mFancyClippingRightInset != rightInset) {
            mFancyClippingRightInset = rightInset;
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
        if (mIsFullWidth != fullWidth) {
            mIsFullWidth = fullWidth;
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

        int clippingLeft = mIsFullWidth ? -mFancyClippingLeftInset : 0;
        int clippingRight = mIsFullWidth ? getWidth() + mFancyClippingRightInset : getWidth();
        mFancyClippingPath.addRoundRect(clippingLeft, mFancyClippingTop, clippingRight,
                mFancyClippingBottom, mFancyClippingRadii, Path.Direction.CW);
        invalidate();
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(getClass().getSimpleName() + " updateClippingPath: "
                + "leftInset(" + mFancyClippingLeftInset + ") "
                + "top(" + mFancyClippingTop + ") "
                + "rightInset(" + mFancyClippingRightInset + ") "
                + "bottom(" + mFancyClippingBottom  + ") "
                + "mClippingEnabled(" + mClippingEnabled + ") "
                + "mIsFullWidth(" + mIsFullWidth + ")");
    }
}
