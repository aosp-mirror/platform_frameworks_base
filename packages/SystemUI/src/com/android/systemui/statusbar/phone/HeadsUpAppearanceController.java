/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.graphics.Point;
import android.graphics.Rect;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.ViewClippingUtil;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.HeadsUpStatusBarView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Controls the appearance of heads up notifications in the icon area and the header itself.
 */
public class HeadsUpAppearanceController implements OnHeadsUpChangedListener,
        DarkIconDispatcher.DarkReceiver, NotificationWakeUpCoordinator.WakeUpListener {
    public static final int CONTENT_FADE_DURATION = 110;
    public static final int CONTENT_FADE_DELAY = 100;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final NotificationStackScrollLayoutController mStackScrollerController;
    private final HeadsUpStatusBarView mHeadsUpStatusBarView;
    private final View mCenteredIconView;
    private final View mClockView;
    private final View mOperatorNameView;
    private final DarkIconDispatcher mDarkIconDispatcher;
    private final NotificationPanelViewController mNotificationPanelViewController;
    private final Consumer<ExpandableNotificationRow>
            mSetTrackingHeadsUp = this::setTrackingHeadsUp;
    private final BiConsumer<Float, Float> mSetExpandedHeight = this::setAppearFraction;
    private final KeyguardBypassController mBypassController;
    private final StatusBarStateController mStatusBarStateController;
    private final CommandQueue mCommandQueue;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;
    @VisibleForTesting
    float mExpandedHeight;
    @VisibleForTesting
    boolean mIsExpanded;
    @VisibleForTesting
    float mAppearFraction;
    private ExpandableNotificationRow mTrackedChild;
    private boolean mShown;
    private final ViewClippingUtil.ClippingParameters mParentClippingParams =
            new ViewClippingUtil.ClippingParameters() {
                @Override
                public boolean shouldFinish(View view) {
                    return view.getId() == R.id.status_bar;
                }
            };
    private boolean mAnimationsEnabled = true;
    Point mPoint;
    private KeyguardStateController mKeyguardStateController;


    public HeadsUpAppearanceController(
            NotificationIconAreaController notificationIconAreaController,
            HeadsUpManagerPhone headsUpManager,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            SysuiStatusBarStateController statusBarStateController,
            KeyguardBypassController keyguardBypassController,
            KeyguardStateController keyguardStateController,
            NotificationWakeUpCoordinator wakeUpCoordinator, CommandQueue commandQueue,
            NotificationPanelViewController notificationPanelViewController, View statusBarView) {
        this(notificationIconAreaController, headsUpManager, statusBarStateController,
                keyguardBypassController, wakeUpCoordinator, keyguardStateController,
                commandQueue, notificationStackScrollLayoutController,
                notificationPanelViewController,
                statusBarView.findViewById(R.id.heads_up_status_bar_view),
                statusBarView.findViewById(R.id.clock),
                statusBarView.findViewById(R.id.operator_name_frame),
                statusBarView.findViewById(R.id.centered_icon_area));
    }

    @VisibleForTesting
    public HeadsUpAppearanceController(
            NotificationIconAreaController notificationIconAreaController,
            HeadsUpManagerPhone headsUpManager,
            StatusBarStateController stateController,
            KeyguardBypassController bypassController,
            NotificationWakeUpCoordinator wakeUpCoordinator,
            KeyguardStateController keyguardStateController,
            CommandQueue commandQueue,
            NotificationStackScrollLayoutController stackScrollerController,
            NotificationPanelViewController notificationPanelViewController,
            HeadsUpStatusBarView headsUpStatusBarView,
            View clockView,
            View operatorNameView,
            View centeredIconView) {
        mNotificationIconAreaController = notificationIconAreaController;
        mHeadsUpManager = headsUpManager;
        mHeadsUpManager.addListener(this);
        mHeadsUpStatusBarView = headsUpStatusBarView;
        mCenteredIconView = centeredIconView;
        headsUpStatusBarView.setOnDrawingRectChangedListener(
                () -> updateIsolatedIconLocation(true /* requireUpdate */));
        mStackScrollerController = stackScrollerController;
        mNotificationPanelViewController = notificationPanelViewController;
        notificationPanelViewController.addTrackingHeadsUpListener(mSetTrackingHeadsUp);
        notificationPanelViewController.setHeadsUpAppearanceController(this);
        mStackScrollerController.addOnExpandedHeightChangedListener(mSetExpandedHeight);
        mStackScrollerController.setHeadsUpAppearanceController(this);
        mClockView = clockView;
        mOperatorNameView = operatorNameView;
        mDarkIconDispatcher = Dependency.get(DarkIconDispatcher.class);
        mDarkIconDispatcher.addDarkReceiver(this);

        mHeadsUpStatusBarView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (shouldBeVisible()) {
                    updateTopEntry();

                    // trigger scroller to notify the latest panel translation
                    mStackScrollerController.requestLayout();
                }
                mHeadsUpStatusBarView.removeOnLayoutChangeListener(this);
            }
        });
        mBypassController = bypassController;
        mStatusBarStateController = stateController;
        mWakeUpCoordinator = wakeUpCoordinator;
        wakeUpCoordinator.addListener(this);
        mCommandQueue = commandQueue;
        mKeyguardStateController = keyguardStateController;
    }


    public void destroy() {
        mHeadsUpManager.removeListener(this);
        mHeadsUpStatusBarView.setOnDrawingRectChangedListener(null);
        mWakeUpCoordinator.removeListener(this);
        mNotificationPanelViewController.removeTrackingHeadsUpListener(mSetTrackingHeadsUp);
        mNotificationPanelViewController.setVerticalTranslationListener(null);
        mNotificationPanelViewController.setHeadsUpAppearanceController(null);
        mStackScrollerController.removeOnExpandedHeightChangedListener(mSetExpandedHeight);
        mDarkIconDispatcher.removeDarkReceiver(this);
    }

    private void updateIsolatedIconLocation(boolean requireStateUpdate) {
        mNotificationIconAreaController.setIsolatedIconLocation(
                mHeadsUpStatusBarView.getIconDrawingRect(), requireStateUpdate);
    }

    @Override
    public void onHeadsUpPinned(NotificationEntry entry) {
        updateTopEntry();
        updateHeader(entry);
    }

    private void updateTopEntry() {
        NotificationEntry newEntry = null;
        if (shouldBeVisible()) {
            newEntry = mHeadsUpManager.getTopEntry();
        }
        NotificationEntry previousEntry = mHeadsUpStatusBarView.getShowingEntry();
        mHeadsUpStatusBarView.setEntry(newEntry);
        if (newEntry != previousEntry) {
            boolean animateIsolation = false;
            if (newEntry == null) {
                // no heads up anymore, lets start the disappear animation

                setShown(false);
                animateIsolation = !mIsExpanded;
            } else if (previousEntry == null) {
                // We now have a headsUp and didn't have one before. Let's start the disappear
                // animation
                setShown(true);
                animateIsolation = !mIsExpanded;
            }
            updateIsolatedIconLocation(false /* requireUpdate */);
            mNotificationIconAreaController.showIconIsolated(newEntry == null ? null
                    : newEntry.getIcons().getStatusBarIcon(), animateIsolation);
        }
    }

    private void setShown(boolean isShown) {
        if (mShown != isShown) {
            mShown = isShown;
            if (isShown) {
                updateParentClipping(false /* shouldClip */);
                mHeadsUpStatusBarView.setVisibility(View.VISIBLE);
                show(mHeadsUpStatusBarView);
                hide(mClockView, View.INVISIBLE);
                if (mCenteredIconView.getVisibility() != View.GONE) {
                    hide(mCenteredIconView, View.INVISIBLE);
                }
                if (mOperatorNameView != null) {
                    hide(mOperatorNameView, View.INVISIBLE);
                }
            } else {
                show(mClockView);
                if (mCenteredIconView.getVisibility() != View.GONE) {
                    show(mCenteredIconView);
                }
                if (mOperatorNameView != null) {
                    show(mOperatorNameView);
                }
                hide(mHeadsUpStatusBarView, View.GONE, () -> {
                    updateParentClipping(true /* shouldClip */);
                });
            }
            // Show the status bar icons when the view gets shown / hidden
            if (mStatusBarStateController.getState() != StatusBarState.SHADE) {
                mCommandQueue.recomputeDisableFlags(
                        mHeadsUpStatusBarView.getContext().getDisplayId(), false);
            }
        }
    }

    private void updateParentClipping(boolean shouldClip) {
        ViewClippingUtil.setClippingDeactivated(
                mHeadsUpStatusBarView, !shouldClip, mParentClippingParams);
    }

    /**
     * Hides the view and sets the state to endState when finished.
     *
     * @param view The view to hide.
     * @param endState One of {@link View#INVISIBLE} or {@link View#GONE}.
     * @see HeadsUpAppearanceController#hide(View, int, Runnable)
     * @see View#setVisibility(int)
     *
     */
    private void hide(View view, int endState) {
        hide(view, endState, null);
    }

    /**
     * Hides the view and sets the state to endState when finished.
     *
     * @param view The view to hide.
     * @param endState One of {@link View#INVISIBLE} or {@link View#GONE}.
     * @param callback Runnable to be executed after the view has been hidden.
     * @see View#setVisibility(int)
     *
     */
    private void hide(View view, int endState, Runnable callback) {
        if (mAnimationsEnabled) {
            CrossFadeHelper.fadeOut(view, CONTENT_FADE_DURATION /* duration */,
                    0 /* delay */, () -> {
                        view.setVisibility(endState);
                        if (callback != null) {
                            callback.run();
                        }
                    });
        } else {
            view.setVisibility(endState);
            if (callback != null) {
                callback.run();
            }
        }
    }

    private void show(View view) {
        if (mAnimationsEnabled) {
            CrossFadeHelper.fadeIn(view, CONTENT_FADE_DURATION /* duration */,
                    CONTENT_FADE_DELAY /* delay */);
        } else {
            view.setVisibility(View.VISIBLE);
        }
    }

    @VisibleForTesting
    void setAnimationsEnabled(boolean enabled) {
        mAnimationsEnabled = enabled;
    }

    @VisibleForTesting
    public boolean isShown() {
        return mShown;
    }

    /**
     * Should the headsup status bar view be visible right now? This may be different from isShown,
     * since the headsUp manager might not have notified us yet of the state change.
     *
     * @return if the heads up status bar view should be shown
     */
    public boolean shouldBeVisible() {
        boolean notificationsShown = !mWakeUpCoordinator.getNotificationsFullyHidden();
        boolean canShow = !mIsExpanded && notificationsShown;
        if (mBypassController.getBypassEnabled() &&
                (mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                        || mKeyguardStateController.isKeyguardGoingAway())
                && notificationsShown) {
            canShow = true;
        }
        return canShow && mHeadsUpManager.hasPinnedHeadsUp();
    }

    @Override
    public void onHeadsUpUnPinned(NotificationEntry entry) {
        updateTopEntry();
        updateHeader(entry);
    }

    public void setAppearFraction(float expandedHeight, float appearFraction) {
        boolean changed = expandedHeight != mExpandedHeight;
        mExpandedHeight = expandedHeight;
        mAppearFraction = appearFraction;
        boolean isExpanded = expandedHeight > 0;
        // We only notify if the expandedHeight changed and not on the appearFraction, since
        // otherwise we may run into an infinite loop where the panel and this are constantly
        // updating themselves over just a small fraction
        if (changed) {
            updateHeadsUpHeaders();
        }
        if (isExpanded != mIsExpanded) {
            mIsExpanded = isExpanded;
            updateTopEntry();
        }
    }

    /**
     * Set a headsUp to be tracked, meaning that it is currently being pulled down after being
     * in a pinned state on the top. The expand animation is different in that case and we need
     * to update the header constantly afterwards.
     *
     * @param trackedChild the tracked headsUp or null if it's not tracking anymore.
     */
    public void setTrackingHeadsUp(ExpandableNotificationRow trackedChild) {
        ExpandableNotificationRow previousTracked = mTrackedChild;
        mTrackedChild = trackedChild;
        if (previousTracked != null) {
            updateHeader(previousTracked.getEntry());
        }
    }

    private void updateHeadsUpHeaders() {
        mHeadsUpManager.getAllEntries().forEach(entry -> {
            updateHeader(entry);
        });
    }

    public void updateHeader(NotificationEntry entry) {
        ExpandableNotificationRow row = entry.getRow();
        float headerVisibleAmount = 1.0f;
        if (row.isPinned() || row.isHeadsUpAnimatingAway() || row == mTrackedChild
                || row.showingPulsing()) {
            headerVisibleAmount = mAppearFraction;
        }
        row.setHeaderVisibleAmount(headerVisibleAmount);
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mHeadsUpStatusBarView.onDarkChanged(area, darkIntensity, tint);
    }

    public void onStateChanged() {
        updateTopEntry();
    }

    void readFrom(HeadsUpAppearanceController oldController) {
        if (oldController != null) {
            mTrackedChild = oldController.mTrackedChild;
            mExpandedHeight = oldController.mExpandedHeight;
            mIsExpanded = oldController.mIsExpanded;
            mAppearFraction = oldController.mAppearFraction;
        }
    }

    @Override
    public void onFullyHiddenChanged(boolean isFullyHidden) {
        updateTopEntry();
    }
}
