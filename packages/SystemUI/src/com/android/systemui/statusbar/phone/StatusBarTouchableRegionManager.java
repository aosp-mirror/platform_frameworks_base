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
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;
import android.view.WindowInsets;

import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.ScreenDecorations;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages what parts of the status bar are touchable. Clients are primarily UI that display in the
 * status bar even though the UI doesn't look like part of the status bar. Currently this consists
 * of HeadsUpNotifications.
 */
@Singleton
public final class StatusBarTouchableRegionManager implements Dumpable {
    private static final String TAG = "TouchableRegionManager";

    private final Context mContext;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final NotificationShadeWindowController mNotificationShadeWindowController;

    private boolean mIsStatusBarExpanded = false;
    private boolean mShouldAdjustInsets = false;
    private StatusBar mStatusBar;
    private View mNotificationShadeWindowView;
    private View mNotificationPanelView;
    private boolean mForceCollapsedUntilLayout = false;

    private Region mTouchableRegion = new Region();
    private int mDisplayCutoutTouchableRegionSize;
    private int mStatusBarHeight;

    @Inject
    public StatusBarTouchableRegionManager(
            Context context,
            NotificationShadeWindowController notificationShadeWindowController,
            ConfigurationController configurationController,
            HeadsUpManagerPhone headsUpManager
    ) {
        mContext = context;
        initResources();
        configurationController.addCallback(new ConfigurationListener() {
            @Override
            public void onDensityOrFontScaleChanged() {
                initResources();
            }

            @Override
            public void onOverlayChanged() {
                initResources();
            }
        });

        mHeadsUpManager = headsUpManager;
        mHeadsUpManager.addListener(
                new OnHeadsUpChangedListener() {
                    @Override
                    public void onHeadsUpPinnedModeChanged(boolean hasPinnedNotification) {
                        if (Log.isLoggable(TAG, Log.WARN)) {
                            Log.w(TAG, "onHeadsUpPinnedModeChanged");
                        }
                        updateTouchableRegion();
                    }
                });
        mHeadsUpManager.addHeadsUpPhoneListener(
                new HeadsUpManagerPhone.OnHeadsUpPhoneListenerChange() {
                    @Override
                    public void onHeadsUpGoingAwayStateChanged(boolean headsUpGoingAway) {
                        if (!headsUpGoingAway) {
                            updateTouchableRegionAfterLayout();
                        } else {
                            updateTouchableRegion();
                        }
                    }
                });

        mNotificationShadeWindowController = notificationShadeWindowController;
        mNotificationShadeWindowController.setForcePluginOpenListener((forceOpen) -> {
            updateTouchableRegion();
        });
    }

    protected void setup(
            @NonNull StatusBar statusBar,
            @NonNull View notificationShadeWindowView) {
        mStatusBar = statusBar;
        mNotificationShadeWindowView = notificationShadeWindowView;
        mNotificationPanelView = mNotificationShadeWindowView.findViewById(R.id.notification_panel);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("StatusBarTouchableRegionManager state:");
        pw.print("  mTouchableRegion=");
        pw.println(mTouchableRegion);
    }

    /**
     * Notify that the status bar panel gets expanded or collapsed.
     *
     * @param isExpanded True to notify expanded, false to notify collapsed.
     */
    void setPanelExpanded(boolean isExpanded) {
        if (isExpanded != mIsStatusBarExpanded) {
            mIsStatusBarExpanded = isExpanded;
            if (isExpanded) {
                // make sure our state is sane
                mForceCollapsedUntilLayout = false;
            }
            updateTouchableRegion();
        }
    }

    /**
     * Calculates the touch region needed for heads up notifications, taking into consideration
     * any existing display cutouts (notch)
     * @return the heads up notification touch area
     */
    Region calculateTouchableRegion() {
        // Update touchable region for HeadsUp notifications
        final Region headsUpTouchableRegion = mHeadsUpManager.getTouchableRegion();
        if (headsUpTouchableRegion != null) {
            mTouchableRegion.set(headsUpTouchableRegion);
        } else {
            // If there aren't any HUNs, update the touch region to the status bar
            // width/height, potentially adjusting for a display cutout (notch)
            mTouchableRegion.set(0, 0, mNotificationShadeWindowView.getWidth(),
                    mStatusBarHeight);
            updateRegionForNotch(mTouchableRegion);
        }
        return mTouchableRegion;
    }

    private void initResources() {
        Resources resources = mContext.getResources();
        mDisplayCutoutTouchableRegionSize = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.display_cutout_touchable_region_size);
        mStatusBarHeight =
                resources.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
    }

    /**
     * Set the touchable portion of the status bar based on what elements are visible.
     */
    private void updateTouchableRegion() {
        boolean hasCutoutInset = (mNotificationShadeWindowView != null)
                && (mNotificationShadeWindowView.getRootWindowInsets() != null)
                && (mNotificationShadeWindowView.getRootWindowInsets().getDisplayCutout() != null);
        boolean shouldObserve = mHeadsUpManager.hasPinnedHeadsUp()
                        || mHeadsUpManager.isHeadsUpGoingAway()
                        || mForceCollapsedUntilLayout
                        || hasCutoutInset
                        || mNotificationShadeWindowController.getForcePluginOpen();
        if (shouldObserve == mShouldAdjustInsets) {
            return;
        }

        if (shouldObserve) {
            mNotificationShadeWindowView.getViewTreeObserver()
                    .addOnComputeInternalInsetsListener(mOnComputeInternalInsetsListener);
            mNotificationShadeWindowView.requestLayout();
        } else {
            mNotificationShadeWindowView.getViewTreeObserver()
                    .removeOnComputeInternalInsetsListener(mOnComputeInternalInsetsListener);
        }
        mShouldAdjustInsets = shouldObserve;
    }

    /**
     * Calls {@code updateTouchableRegion()} after a layout pass completes.
     */
    private void updateTouchableRegionAfterLayout() {
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

    private void updateRegionForNotch(Region touchableRegion) {
        WindowInsets windowInsets = mNotificationShadeWindowView.getRootWindowInsets();
        if (windowInsets == null) {
            Log.w(TAG, "StatusBarWindowView is not attached.");
            return;
        }
        DisplayCutout cutout = windowInsets.getDisplayCutout();
        if (cutout == null) {
            return;
        }

        // Expand touchable region such that we also catch touches that just start below the notch
        // area.
        Rect bounds = new Rect();
        ScreenDecorations.DisplayCutoutView.boundsFromDirection(cutout, Gravity.TOP, bounds);
        bounds.offset(0, mDisplayCutoutTouchableRegionSize);
        touchableRegion.union(bounds);
    }

    private final OnComputeInternalInsetsListener mOnComputeInternalInsetsListener =
            new OnComputeInternalInsetsListener() {
        @Override
        public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
            if (mIsStatusBarExpanded || mStatusBar.isBouncerShowing()) {
                // The touchable region is always the full area when expanded
                return;
            }

            // Update touch insets to include any area needed for touching features that live in
            // the status bar (ie: heads up notifications)
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            info.touchableRegion.set(calculateTouchableRegion());
        }
    };
}
