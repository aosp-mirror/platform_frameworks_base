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
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

/**
 * Manages what parts of the status bar are touchable. Clients are primarily UI that displays in the
 * status bar even though the UI doesn't look like part of the status bar.
 */
public final class StatusBarTouchableRegionManager implements
        OnComputeInternalInsetsListener, ConfigurationListener {

    private final BubbleController mBubbleController = Dependency.get(BubbleController.class);
    private final HeadsUpManagerPhone mHeadsUpManager;
    private boolean mIsStatusBarExpanded = false;
    private boolean mShouldAdjustInsets = false;
    private final StatusBar mStatusBar;
    private final View mNotificationShadeWindowView;
    private View mNotificationPanelView;
    private boolean mForceCollapsedUntilLayout = false;
    private final NotificationShadeWindowController mNotificationShadeWindowController;

    public StatusBarTouchableRegionManager(HeadsUpManagerPhone headsUpManager,
                                           @NonNull StatusBar statusBar,
                                           @NonNull View notificationShadeWindowView) {
        mHeadsUpManager = headsUpManager;
        mStatusBar = statusBar;
        mNotificationShadeWindowView = notificationShadeWindowView;
        mNotificationShadeWindowController =
                Dependency.get(NotificationShadeWindowController.class);

        mBubbleController.setBubbleStateChangeListener((hasBubbles) -> {
            updateTouchableRegion();
        });

        mNotificationShadeWindowController.setForcePluginOpenListener((forceOpen) -> {
            updateTouchableRegion();
        });
        Dependency.get(ConfigurationController.class).addCallback(this);
        if (mNotificationShadeWindowView != null) {
            mNotificationPanelView = mNotificationShadeWindowView.findViewById(
                    R.id.notification_panel);
        }
    }

    /**
     * Set the touchable portion of the status bar based on what elements are visible.
     */
    public void updateTouchableRegion() {
        boolean hasCutoutInset = (mNotificationShadeWindowView != null)
                && (mNotificationShadeWindowView.getRootWindowInsets() != null)
                && (mNotificationShadeWindowView.getRootWindowInsets().getDisplayCutout() != null);
        boolean shouldObserve =
                mHeadsUpManager.hasPinnedHeadsUp() || mHeadsUpManager.isHeadsUpGoingAway()
                        || mBubbleController.hasBubbles()
                        || mForceCollapsedUntilLayout
                        || hasCutoutInset
                        || mNotificationShadeWindowController.getForcePluginOpen();
        if (shouldObserve == mShouldAdjustInsets) {
            return;
        }

        if (shouldObserve) {
            mNotificationShadeWindowView.getViewTreeObserver()
                    .addOnComputeInternalInsetsListener(this);
            mNotificationShadeWindowView.requestLayout();
        } else {
            mNotificationShadeWindowView.getViewTreeObserver()
                    .removeOnComputeInternalInsetsListener(this);
        }
        mShouldAdjustInsets = shouldObserve;
    }

    /**
     * Calls {@code updateTouchableRegion()} after a layout pass completes.
     */
    public void updateTouchableRegionAfterLayout() {
        if (mNotificationPanelView != null) {
            mForceCollapsedUntilLayout = true;
            mNotificationPanelView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (!mNotificationPanelView.isVisibleToUser()) {
                        mNotificationPanelView.removeOnLayoutChangeListener(this);
                        mForceCollapsedUntilLayout = false;
                        updateTouchableRegion();
                    }
                }
            });
        }
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
}
