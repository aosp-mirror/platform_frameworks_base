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

import static com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentModule.OPERATOR_NAME_FRAME_VIEW;

import android.graphics.Rect;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.ViewClippingUtil;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.HeadsUpStatusBarView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentScope;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.util.ViewController;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Controls the appearance of heads up notifications in the icon area and the header itself.
 */
@StatusBarFragmentScope
public class HeadsUpAppearanceController extends ViewController<HeadsUpStatusBarView>
        implements OnHeadsUpChangedListener,
        DarkIconDispatcher.DarkReceiver,
        NotificationWakeUpCoordinator.WakeUpListener {
    public static final int CONTENT_FADE_DURATION = 110;
    public static final int CONTENT_FADE_DELAY = 100;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final NotificationStackScrollLayoutController mStackScrollerController;

    private final DarkIconDispatcher mDarkIconDispatcher;
    private final NotificationPanelViewController mNotificationPanelViewController;
    private final Consumer<ExpandableNotificationRow>
            mSetTrackingHeadsUp = this::setTrackingHeadsUp;
    private final BiConsumer<Float, Float> mSetExpandedHeight = this::setAppearFraction;
    private final KeyguardBypassController mBypassController;
    private final StatusBarStateController mStatusBarStateController;
    private final CommandQueue mCommandQueue;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;

    private final View mClockView;
    private final Optional<View> mOperatorNameViewOptional;

    @VisibleForTesting
    float mExpandedHeight;
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
    private final KeyguardStateController mKeyguardStateController;

    @VisibleForTesting
    @Inject
    public HeadsUpAppearanceController(
            NotificationIconAreaController notificationIconAreaController,
            HeadsUpManagerPhone headsUpManager,
            StatusBarStateController stateController,
            KeyguardBypassController bypassController,
            NotificationWakeUpCoordinator wakeUpCoordinator,
            DarkIconDispatcher darkIconDispatcher,
            KeyguardStateController keyguardStateController,
            CommandQueue commandQueue,
            NotificationStackScrollLayoutController stackScrollerController,
            NotificationPanelViewController notificationPanelViewController,
            HeadsUpStatusBarView headsUpStatusBarView,
            Clock clockView,
            @Named(OPERATOR_NAME_FRAME_VIEW) Optional<View> operatorNameViewOptional) {
        super(headsUpStatusBarView);
        mNotificationIconAreaController = notificationIconAreaController;
        mHeadsUpManager = headsUpManager;

        // We may be mid-HUN-expansion when this controller is re-created (for example, if the user
        // has started pulling down the notification shade from the HUN and then the font size
        // changes). We need to re-fetch these values since they're used to correctly display the
        // HUN during this shade expansion.
        mTrackedChild = notificationPanelViewController.getTrackedHeadsUpNotification();
        mAppearFraction = stackScrollerController.getAppearFraction();
        mExpandedHeight = stackScrollerController.getExpandedHeight();

        mStackScrollerController = stackScrollerController;
        mNotificationPanelViewController = notificationPanelViewController;
        mStackScrollerController.setHeadsUpAppearanceController(this);
        mClockView = clockView;
        mOperatorNameViewOptional = operatorNameViewOptional;
        mDarkIconDispatcher = darkIconDispatcher;

        mView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (shouldBeVisible()) {
                    updateTopEntry();

                    // trigger scroller to notify the latest panel translation
                    mStackScrollerController.requestLayout();
                }
                mView.removeOnLayoutChangeListener(this);
            }
        });
        mBypassController = bypassController;
        mStatusBarStateController = stateController;
        mWakeUpCoordinator = wakeUpCoordinator;
        mCommandQueue = commandQueue;
        mKeyguardStateController = keyguardStateController;
    }

    @Override
    protected void onViewAttached() {
        mHeadsUpManager.addListener(this);
        mView.setOnDrawingRectChangedListener(
                () -> updateIsolatedIconLocation(true /* requireUpdate */));
        mWakeUpCoordinator.addListener(this);
        mNotificationPanelViewController.addTrackingHeadsUpListener(mSetTrackingHeadsUp);
        mNotificationPanelViewController.setHeadsUpAppearanceController(this);
        mStackScrollerController.addOnExpandedHeightChangedListener(mSetExpandedHeight);
        mDarkIconDispatcher.addDarkReceiver(this);
    }

    @Override
    protected void onViewDetached() {
        mHeadsUpManager.removeListener(this);
        mView.setOnDrawingRectChangedListener(null);
        mWakeUpCoordinator.removeListener(this);
        mNotificationPanelViewController.removeTrackingHeadsUpListener(mSetTrackingHeadsUp);
        mNotificationPanelViewController.setHeadsUpAppearanceController(null);
        mStackScrollerController.removeOnExpandedHeightChangedListener(mSetExpandedHeight);
        mDarkIconDispatcher.removeDarkReceiver(this);
    }

    private void updateIsolatedIconLocation(boolean requireStateUpdate) {
        mNotificationIconAreaController.setIsolatedIconLocation(
                mView.getIconDrawingRect(), requireStateUpdate);
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
        NotificationEntry previousEntry = mView.getShowingEntry();
        mView.setEntry(newEntry);
        if (newEntry != previousEntry) {
            boolean animateIsolation = false;
            if (newEntry == null) {
                // no heads up anymore, lets start the disappear animation

                setShown(false);
                animateIsolation = !isExpanded();
            } else if (previousEntry == null) {
                // We now have a headsUp and didn't have one before. Let's start the disappear
                // animation
                setShown(true);
                animateIsolation = !isExpanded();
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
                mView.setVisibility(View.VISIBLE);
                show(mView);
                hide(mClockView, View.INVISIBLE);
                mOperatorNameViewOptional.ifPresent(view -> hide(view, View.INVISIBLE));
            } else {
                show(mClockView);
                mOperatorNameViewOptional.ifPresent(this::show);
                hide(mView, View.GONE, () -> {
                    updateParentClipping(true /* shouldClip */);
                });
            }
            // Show the status bar icons when the view gets shown / hidden
            if (mStatusBarStateController.getState() != StatusBarState.SHADE) {
                mCommandQueue.recomputeDisableFlags(
                        mView.getContext().getDisplayId(), false);
            }
        }
    }

    private void updateParentClipping(boolean shouldClip) {
        ViewClippingUtil.setClippingDeactivated(
                mView, !shouldClip, mParentClippingParams);
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
        boolean canShow = !isExpanded() && notificationsShown;
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
        boolean oldIsExpanded = isExpanded();

        mExpandedHeight = expandedHeight;
        mAppearFraction = appearFraction;
        // We only notify if the expandedHeight changed and not on the appearFraction, since
        // otherwise we may run into an infinite loop where the panel and this are constantly
        // updating themselves over just a small fraction
        if (changed) {
            updateHeadsUpHeaders();
        }
        if (isExpanded() != oldIsExpanded) {
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

    private boolean isExpanded() {
        return mExpandedHeight > 0;
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
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mView.onDarkChanged(areas, darkIntensity, tint);
    }

    public void onStateChanged() {
        updateTopEntry();
    }

    @Override
    public void onFullyHiddenChanged(boolean isFullyHidden) {
        updateTopEntry();
    }
}
