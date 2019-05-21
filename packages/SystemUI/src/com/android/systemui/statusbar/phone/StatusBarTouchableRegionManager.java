/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;

import com.android.systemui.Dependency;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

/**
 * Manages what parts of the status bar are touchable. Clients are primarily UI that displays in the
 * status bar even though the UI doesn't look like part of the status bar.
 */
public final class StatusBarTouchableRegionManager implements
        OnComputeInternalInsetsListener, ConfigurationListener {

    private final AssistManager mAssistManager = Dependency.get(AssistManager.class);
    private final BubbleController mBubbleController = Dependency.get(BubbleController.class);
    private final Context mContext;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private boolean mIsStatusBarExpanded = false;
    private boolean mShouldAdjustInsets = false;
    private final StatusBar mStatusBar;
    private int mStatusBarHeight;
    private final View mStatusBarWindowView;
    private boolean mForceCollapsedUntilLayout = false;

    public StatusBarTouchableRegionManager(@NonNull Context context,
                                           HeadsUpManagerPhone headsUpManager,
                                           @NonNull StatusBar statusBar,
                                           @NonNull View statusBarWindowView) {
        mContext = context;
        mHeadsUpManager = headsUpManager;
        mStatusBar = statusBar;
        mStatusBarWindowView = statusBarWindowView;

        initResources();

        mBubbleController.setBubbleStateChangeListener((hasBubbles) -> {
            updateTouchableRegion();
        });
        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    /**
     * Set the touchable portion of the status bar based on what elements are visible.
     */
    public void updateTouchableRegion() {
        boolean hasCutoutInset = (mStatusBarWindowView != null)
                && (mStatusBarWindowView.getRootWindowInsets() != null)
                && (mStatusBarWindowView.getRootWindowInsets().getDisplayCutout() != null);
        boolean shouldObserve =
                mHeadsUpManager.hasPinnedHeadsUp() || mHeadsUpManager.isHeadsUpGoingAway()
                        || mBubbleController.hasBubbles()
                        || mForceCollapsedUntilLayout
                        || hasCutoutInset;
        if (shouldObserve == mShouldAdjustInsets) {
            return;
        }

        if (shouldObserve) {
            mStatusBarWindowView.getViewTreeObserver().addOnComputeInternalInsetsListener(this);
            mStatusBarWindowView.requestLayout();
        } else {
            mStatusBarWindowView.getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        }
        mShouldAdjustInsets = shouldObserve;
    }

    /**
     * Calls {@code updateTouchableRegion()} after a layout pass completes.
     */
    public void updateTouchableRegionAfterLayout() {
        mForceCollapsedUntilLayout = true;
        mStatusBarWindowView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft,
                                       int oldTop, int oldRight, int oldBottom) {
                if (mStatusBarWindowView.getHeight() <= mStatusBarHeight) {
                    mStatusBarWindowView.removeOnLayoutChangeListener(this);
                    mForceCollapsedUntilLayout = false;
                    updateTouchableRegion();
                }
            }
        });
    }

    /**
     * Notify that the status bar panel gets expanded or collapsed.
     *
     * @param isExpanded True to notify expanded, false to notify collapsed.
     */
    public void setIsStatusBarExpanded(boolean isExpanded) {
        if (isExpanded != mIsStatusBarExpanded) {
            mIsStatusBarExpanded = isExpanded;
            if (isExpanded) {
                // make sure our state is sane
                mForceCollapsedUntilLayout = false;
            }
            updateTouchableRegion();
        }
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
        if (mIsStatusBarExpanded || mStatusBar.isBouncerShowing()) {
            // The touchable region is always the full area when expanded
            return;
        }

        mHeadsUpManager.updateTouchableRegion(info);

        Rect bubbleRect = mBubbleController.getTouchableRegion();
        if (bubbleRect != null) {
            info.touchableRegion.union(bubbleRect);
        }
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        initResources();
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        initResources();
    }

    @Override
    public void onOverlayChanged() {
        initResources();
    }

    private void initResources() {
        Resources resources = mContext.getResources();
        mStatusBarHeight = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
    }
}
