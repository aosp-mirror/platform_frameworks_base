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

import com.android.compose.animation.scene.ObservableTransitionState;
import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.ScreenDecorations;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.res.R;
import com.android.systemui.scene.domain.interactor.SceneInteractor;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.scene.shared.model.Scenes;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.util.kotlin.JavaAdapter;

import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Manages what parts of the status bar are touchable. Clients are primarily UI that display in the
 * status bar even though the UI doesn't look like part of the status bar. Currently this consists
 * of HeadsUpNotifications.
 */
@SysUISingleton
public final class StatusBarTouchableRegionManager implements Dumpable {
    private static final String TAG = "TouchableRegionManager";

    private final Context mContext;
    private final HeadsUpManager mHeadsUpManager;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;

    private boolean mIsStatusBarExpanded = false;
    private boolean mIsIdleOnGone = true;
    private boolean mShouldAdjustInsets = false;
    private View mNotificationShadeWindowView;
    private View mNotificationPanelView;
    private boolean mForceCollapsedUntilLayout = false;

    private Region mTouchableRegion = new Region();
    private int mDisplayCutoutTouchableRegionSize;
    private int mStatusBarHeight;
    private final PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    private final AlternateBouncerInteractor mAlternateBouncerInteractor;

    private final OnComputeInternalInsetsListener mOnComputeInternalInsetsListener;

    @Inject
    public StatusBarTouchableRegionManager(
            Context context,
            NotificationShadeWindowController notificationShadeWindowController,
            ConfigurationController configurationController,
            HeadsUpManager headsUpManager,
            ShadeInteractor shadeInteractor,
            Provider<SceneInteractor> sceneInteractor,
            JavaAdapter javaAdapter,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            PrimaryBouncerInteractor primaryBouncerInteractor,
            AlternateBouncerInteractor alternateBouncerInteractor
    ) {
        mContext = context;
        initResources();
        configurationController.addCallback(new ConfigurationListener() {
            @Override
            public void onDensityOrFontScaleChanged() {
                initResources();
            }

            @Override
            public void onThemeChanged() {
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
        mHeadsUpManager.addHeadsUpPhoneListener(this::onHeadsUpAnimatingAwayStateChanged);

        mNotificationShadeWindowController = notificationShadeWindowController;
        mNotificationShadeWindowController.setForcePluginOpenListener((forceOpen) -> {
            updateTouchableRegion();
        });

        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;

        if (SceneContainerFlag.isEnabled()) {
            javaAdapter.alwaysCollectFlow(
                    sceneInteractor.get().getTransitionState(),
                    this::onSceneChanged);
        } else {
            javaAdapter.alwaysCollectFlow(
                    shadeInteractor.isAnyExpanded(),
                    this::onShadeOrQsExpanded);
        }

        mPrimaryBouncerInteractor = primaryBouncerInteractor;
        mAlternateBouncerInteractor = alternateBouncerInteractor;
        mOnComputeInternalInsetsListener = this::onComputeInternalInsets;
    }

    protected void setup(@NonNull View notificationShadeWindowView) {
        mNotificationShadeWindowView = notificationShadeWindowView;
        mNotificationPanelView = mNotificationShadeWindowView.findViewById(R.id.notification_panel);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("StatusBarTouchableRegionManager state:");
        pw.print("  mTouchableRegion=");
        pw.println(mTouchableRegion);
    }

    private void onShadeOrQsExpanded(Boolean isExpanded) {
        if (isExpanded != mIsStatusBarExpanded) {
            mIsStatusBarExpanded = isExpanded;
            if (isExpanded) {
                // make sure our state is sensible
                mForceCollapsedUntilLayout = false;
            }
            updateTouchableRegion();
        }
    }

    private void onSceneChanged(ObservableTransitionState transitionState) {
        boolean isIdleOnGone = transitionState.isIdle(Scenes.Gone);
        if (isIdleOnGone != mIsIdleOnGone) {
            mIsIdleOnGone = isIdleOnGone;
            if (!isIdleOnGone) {
                // make sure our state is sensible
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
    public Region calculateTouchableRegion() {
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
        mStatusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
    }

    /**
     * Set the touchable portion of the status bar based on what elements are visible.
     */
    public void updateTouchableRegion() {
        boolean hasCutoutInset = (mNotificationShadeWindowView != null)
                && (mNotificationShadeWindowView.getRootWindowInsets() != null)
                && (mNotificationShadeWindowView.getRootWindowInsets().getDisplayCutout() != null);
        boolean shouldObserve = mHeadsUpManager.hasPinnedHeadsUp()
                        || mHeadsUpManager.isHeadsUpAnimatingAwayValue()
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

    public void updateRegionForNotch(Region touchableRegion) {
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

    /**
     * Helper to let us know when calculating the region is not needed because we know the entire
     * screen needs to be touchable.
     */
    private boolean shouldMakeEntireScreenTouchable() {
        // The touchable region is always the full area when expanded, whether we're showing the
        // shade or the bouncer. It's also fully touchable when the screen off animation is playing
        // since we don't want stray touches to go through the light reveal scrim to whatever is
        // underneath.
        return mIsStatusBarExpanded
                || (SceneContainerFlag.isEnabled() && !mIsIdleOnGone)
                || mPrimaryBouncerInteractor.isShowing().getValue()
                || mAlternateBouncerInteractor.isVisibleState()
                || mUnlockedScreenOffAnimationController.isAnimationPlaying();
    }

    private void onHeadsUpAnimatingAwayStateChanged(boolean headsUpAnimatingAway) {
        if (!headsUpAnimatingAway) {
            updateTouchableRegionAfterLayout();
        } else {
            updateTouchableRegion();
        }
    }

    private void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
        if (shouldMakeEntireScreenTouchable()) {
            return;
        }

        // Update touch insets to include any area needed for touching features that live in
        // the status bar (ie: heads up notifications)
        info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        info.touchableRegion.set(calculateTouchableRegion());
    }
}
