/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.bubbles.bar;

import static com.android.wm.shell.animation.Interpolators.ALPHA_IN;
import static com.android.wm.shell.animation.Interpolators.ALPHA_OUT;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.BubbleOverflow;
import com.android.wm.shell.bubbles.BubblePositioner;
import com.android.wm.shell.bubbles.BubbleViewProvider;

import java.util.function.Consumer;

/**
 * Similar to {@link com.android.wm.shell.bubbles.BubbleStackView}, this view is added to window
 * manager to display bubbles. However, it is only used when bubbles are being displayed in
 * launcher in the bubble bar. This view does not show a stack of bubbles that can be moved around
 * on screen and instead shows & animates the expanded bubble for the bubble bar.
 */
public class BubbleBarLayerView extends FrameLayout
        implements ViewTreeObserver.OnComputeInternalInsetsListener {

    private static final String TAG = BubbleBarLayerView.class.getSimpleName();

    private static final float SCRIM_ALPHA = 0.2f;

    private final BubbleController mBubbleController;
    private final BubblePositioner mPositioner;
    private final BubbleBarAnimationHelper mAnimationHelper;
    private final View mScrimView;

    @Nullable
    private BubbleViewProvider mExpandedBubble;
    private BubbleBarExpandedView mExpandedView;
    private @Nullable Consumer<String> mUnBubbleConversationCallback;

    // TODO(b/273310265) - currently the view is always on the right, need to update for RTL.
    /** Whether the expanded view is displaying on the left of the screen or not. */
    private boolean mOnLeft = false;

    /** Whether a bubble is expanded. */
    private boolean mIsExpanded = false;

    private final Region mTouchableRegion = new Region();
    private final Rect mTempRect = new Rect();

    public BubbleBarLayerView(Context context, BubbleController controller) {
        super(context);
        mBubbleController = controller;
        mPositioner = mBubbleController.getPositioner();

        mAnimationHelper = new BubbleBarAnimationHelper(context,
                this, mPositioner);

        mScrimView = new View(getContext());
        mScrimView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mScrimView.setBackgroundDrawable(new ColorDrawable(
                getResources().getColor(android.R.color.system_neutral1_1000)));
        addView(mScrimView);
        mScrimView.setAlpha(0f);
        mScrimView.setBackgroundDrawable(new ColorDrawable(
                getResources().getColor(android.R.color.system_neutral1_1000)));

        setOnClickListener(view -> {
            mBubbleController.collapseStack();
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mPositioner.update();
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);

        if (mExpandedView != null) {
            removeView(mExpandedView);
            mExpandedView = null;
        }
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        mTouchableRegion.setEmpty();
        getTouchableRegion(mTouchableRegion);
        inoutInfo.touchableRegion.set(mTouchableRegion);
    }

    /** Updates the sizes of any displaying expanded view. */
    public void onDisplaySizeChanged() {
        if (mIsExpanded && mExpandedView != null) {
            updateExpandedView();
        }
    }

    /** Whether the stack of bubbles is expanded or not. */
    public boolean isExpanded() {
        return mIsExpanded;
    }

    // (TODO: b/273310265): BubblePositioner should be source of truth when this work is done.
    /** Whether the expanded view is positioned on the left or right side of the screen. */
    public boolean isOnLeft() {
        return mOnLeft;
    }

    /** Shows the expanded view of the provided bubble. */
    public void showExpandedView(BubbleViewProvider b) {
        BubbleBarExpandedView expandedView = b.getBubbleBarExpandedView();
        if (expandedView == null) {
            return;
        }
        if (mExpandedBubble != null && !b.getKey().equals(mExpandedBubble.getKey())) {
            removeView(mExpandedView);
            mExpandedView = null;
        }
        if (mExpandedView == null) {
            mExpandedBubble = b;
            mExpandedView = expandedView;
            boolean isOverflowExpanded = b.getKey().equals(BubbleOverflow.KEY);
            final int width = mPositioner.getExpandedViewWidthForBubbleBar(isOverflowExpanded);
            final int height = mPositioner.getExpandedViewHeightForBubbleBar(isOverflowExpanded);
            mExpandedView.setVisibility(GONE);
            mExpandedView.setUnBubbleConversationCallback(mUnBubbleConversationCallback);
            mExpandedView.setLayerBoundsSupplier(() -> new Rect(0, 0, getWidth(), getHeight()));
            mExpandedView.setUnBubbleConversationCallback(bubbleKey -> {
                if (mUnBubbleConversationCallback != null) {
                    mUnBubbleConversationCallback.accept(bubbleKey);
                }
            });
            mExpandedView.setY(mPositioner.getExpandedViewBottomForBubbleBar() - height);
            addView(mExpandedView, new FrameLayout.LayoutParams(width, height));
        }

        mIsExpanded = true;
        mBubbleController.getSysuiProxy().onStackExpandChanged(true);
        mAnimationHelper.animateExpansion(mExpandedBubble);
        showScrim(true);
    }

    /** Collapses any showing expanded view */
    public void collapse() {
        mIsExpanded = false;
        final BubbleBarExpandedView viewToRemove = mExpandedView;
        mAnimationHelper.animateCollapse(() -> removeView(viewToRemove));
        mBubbleController.getSysuiProxy().onStackExpandChanged(false);
        mExpandedView = null;
        showScrim(false);
    }

    /** Sets the function to call to un-bubble the given conversation. */
    public void setUnBubbleConversationCallback(
            @Nullable Consumer<String> unBubbleConversationCallback) {
        mUnBubbleConversationCallback = unBubbleConversationCallback;
    }

    /** Updates the expanded view size and position. */
    private void updateExpandedView() {
        if (mExpandedView == null) return;
        boolean isOverflowExpanded = mExpandedBubble.getKey().equals(BubbleOverflow.KEY);
        final int padding = mPositioner.getBubbleBarExpandedViewPadding();
        final int width = mPositioner.getExpandedViewWidthForBubbleBar(isOverflowExpanded);
        final int height = mPositioner.getExpandedViewHeightForBubbleBar(isOverflowExpanded);
        FrameLayout.LayoutParams lp = (LayoutParams) mExpandedView.getLayoutParams();
        lp.width = width;
        lp.height = height;
        mExpandedView.setLayoutParams(lp);
        if (mOnLeft) {
            mExpandedView.setX(mPositioner.getInsets().left + padding);
        } else {
            mExpandedView.setX(mPositioner.getAvailableRect().width() - width - padding);
        }
        mExpandedView.setY(mPositioner.getExpandedViewBottomForBubbleBar() - height);
        mExpandedView.updateLocation();
    }

    private void showScrim(boolean show) {
        if (show) {
            mScrimView.animate()
                    .setInterpolator(ALPHA_IN)
                    .alpha(SCRIM_ALPHA)
                    .start();
        } else {
            mScrimView.animate()
                    .alpha(0f)
                    .setInterpolator(ALPHA_OUT)
                    .start();
        }
    }

    /**
     * Fills in the touchable region for expanded view. This is used by window manager to
     * decide which touch events go to the expanded view.
     */
    private void getTouchableRegion(Region outRegion) {
        mTempRect.setEmpty();
        if (mIsExpanded) {
            getBoundsOnScreen(mTempRect);
            outRegion.op(mTempRect, Region.Op.UNION);
        }
    }
}
