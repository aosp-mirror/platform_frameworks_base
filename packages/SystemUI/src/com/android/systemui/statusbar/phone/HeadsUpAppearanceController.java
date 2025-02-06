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

import static com.android.systemui.statusbar.phone.fragment.dagger.HomeStatusBarModule.OPERATOR_NAME_FRAME_VIEW;

import android.graphics.Rect;
import android.util.MathUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.ViewClippingUtil;
import com.android.systemui.dagger.qualifiers.DisplaySpecific;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.shade.ShadeHeadsUpTracker;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.HeadsUpStatusBarView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips;
import com.android.systemui.statusbar.core.StatusBarRootModernization;
import com.android.systemui.statusbar.headsup.shared.StatusBarNoHunBehavior;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationIconInteractor;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.notification.headsup.PinnedStatus;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.shared.AsyncGroupHeaderViewInflation;
import com.android.systemui.statusbar.notification.stack.NotificationRoundnessManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.fragment.dagger.HomeStatusBarScope;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Controls the appearance of heads up notifications in the icon area and the header itself.
 * It also controls the roundness of the heads up notifications and the pulsing notifications.
 */
@HomeStatusBarScope
public class HeadsUpAppearanceController extends ViewController<HeadsUpStatusBarView>
        implements OnHeadsUpChangedListener,
        DarkIconDispatcher.DarkReceiver,
        NotificationWakeUpCoordinator.WakeUpListener {
    public static final int CONTENT_FADE_DURATION = 110;
    public static final int CONTENT_FADE_DELAY = 100;

    private static final SourceType HEADS_UP = SourceType.from("HeadsUp");
    private static final SourceType PULSING = SourceType.from("Pulsing");
    private final HeadsUpManager mHeadsUpManager;
    private final NotificationStackScrollLayoutController mStackScrollerController;

    private final DarkIconDispatcher mDarkIconDispatcher;
    private final ShadeViewController mShadeViewController;
    private final NotificationRoundnessManager mNotificationRoundnessManager;
    private final Consumer<ExpandableNotificationRow>
            mSetTrackingHeadsUp = this::setTrackingHeadsUp;
    private final BiConsumer<Float, Float> mSetExpandedHeight = this::setAppearFraction;
    private final KeyguardBypassController mBypassController;
    private final StatusBarStateController mStatusBarStateController;
    private final PhoneStatusBarTransitions mPhoneStatusBarTransitions;
    private final CommandQueue mCommandQueue;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;

    private final View mClockView;
    private final Optional<View> mOperatorNameViewOptional;

    @VisibleForTesting
    float mExpandedHeight;
    @VisibleForTesting
    float mAppearFraction;
    private ExpandableNotificationRow mTrackedChild;
    private PinnedStatus mPinnedStatus = PinnedStatus.NotPinned;
    private final ViewClippingUtil.ClippingParameters mParentClippingParams =
            new ViewClippingUtil.ClippingParameters() {
                @Override
                public boolean shouldFinish(View view) {
                    return view.getId() == R.id.status_bar;
                }
            };
    private boolean mAnimationsEnabled = true;
    private final KeyguardStateController mKeyguardStateController;
    private final HeadsUpNotificationIconInteractor mHeadsUpNotificationIconInteractor;

    @VisibleForTesting
    @Inject
    public HeadsUpAppearanceController(
            HeadsUpManager headsUpManager,
            StatusBarStateController stateController,
            PhoneStatusBarTransitions phoneStatusBarTransitions,
            KeyguardBypassController bypassController,
            NotificationWakeUpCoordinator wakeUpCoordinator,
            @DisplaySpecific DarkIconDispatcher darkIconDispatcher,
            KeyguardStateController keyguardStateController,
            CommandQueue commandQueue,
            NotificationStackScrollLayoutController stackScrollerController,
            ShadeViewController shadeViewController,
            NotificationRoundnessManager notificationRoundnessManager,
            HeadsUpStatusBarView headsUpStatusBarView,
            Clock clockView,
            HeadsUpNotificationIconInteractor headsUpNotificationIconInteractor,
            @Named(OPERATOR_NAME_FRAME_VIEW) Optional<View> operatorNameViewOptional) {
        super(headsUpStatusBarView);
        mNotificationRoundnessManager = notificationRoundnessManager;
        mHeadsUpManager = headsUpManager;

        // We may be mid-HUN-expansion when this controller is re-created (for example, if the user
        // has started pulling down the notification shade from the HUN and then the font size
        // changes). We need to re-fetch these values since they're used to correctly display the
        // HUN during this shade expansion.
        mTrackedChild = shadeViewController.getShadeHeadsUpTracker()
                .getTrackedHeadsUpNotification();
        mAppearFraction = stackScrollerController.getAppearFraction();
        mExpandedHeight = stackScrollerController.getExpandedHeight();

        mStackScrollerController = stackScrollerController;
        mShadeViewController = shadeViewController;
        mHeadsUpNotificationIconInteractor = headsUpNotificationIconInteractor;
        mStackScrollerController.setHeadsUpAppearanceController(this);
        mClockView = clockView;
        mOperatorNameViewOptional = operatorNameViewOptional;
        mDarkIconDispatcher = darkIconDispatcher;

        if (!StatusBarNoHunBehavior.isEnabled()) {
            mView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (shouldHeadsUpStatusBarBeVisible()) {
                        updatePinnedStatus();

                        // trigger scroller to notify the latest panel translation
                        mStackScrollerController.requestLayout();
                    }
                    mView.removeOnLayoutChangeListener(this);
                }
            });
        }
        mBypassController = bypassController;
        mStatusBarStateController = stateController;
        mPhoneStatusBarTransitions = phoneStatusBarTransitions;
        mWakeUpCoordinator = wakeUpCoordinator;
        mCommandQueue = commandQueue;
        mKeyguardStateController = keyguardStateController;
    }

    @Override
    protected void onViewAttached() {
        mHeadsUpManager.addListener(this);
        if (!StatusBarNoHunBehavior.isEnabled()) {
            mView.setOnDrawingRectChangedListener(this::updateIsolatedIconLocation);
            updateIsolatedIconLocation();
            mDarkIconDispatcher.addDarkReceiver(this);
            mWakeUpCoordinator.addListener(this);
        }
        getShadeHeadsUpTracker().addTrackingHeadsUpListener(mSetTrackingHeadsUp);
        getShadeHeadsUpTracker().setHeadsUpAppearanceController(this);
        mStackScrollerController.addOnExpandedHeightChangedListener(mSetExpandedHeight);
    }

    private ShadeHeadsUpTracker getShadeHeadsUpTracker() {
        return mShadeViewController.getShadeHeadsUpTracker();
    }

    @Override
    protected void onViewDetached() {
        mHeadsUpManager.removeListener(this);
        if (!StatusBarNoHunBehavior.isEnabled()) {
            mView.setOnDrawingRectChangedListener(null);
            mHeadsUpNotificationIconInteractor.setIsolatedIconLocation(null);
            mDarkIconDispatcher.removeDarkReceiver(this);
            mWakeUpCoordinator.removeListener(this);
        }
        getShadeHeadsUpTracker().removeTrackingHeadsUpListener(mSetTrackingHeadsUp);
        getShadeHeadsUpTracker().setHeadsUpAppearanceController(null);
        mStackScrollerController.removeOnExpandedHeightChangedListener(mSetExpandedHeight);
    }

    private void updateIsolatedIconLocation() {
        StatusBarNoHunBehavior.assertInLegacyMode();
        mHeadsUpNotificationIconInteractor.setIsolatedIconLocation(mView.getIconDrawingRect());
    }

    @Override
    public void onHeadsUpPinned(NotificationEntry entry) {
        updatePinnedStatus();
        updateHeader(entry);
        updateHeadsUpAndPulsingRoundness(entry);
    }

    @Override
    public void onHeadsUpStateChanged(@NonNull NotificationEntry entry, boolean isHeadsUp) {
        updateHeadsUpAndPulsingRoundness(entry);
        mPhoneStatusBarTransitions.onHeadsUpStateChanged(isHeadsUp);
    }

    private void updatePinnedStatus() {
        if (StatusBarNoHunBehavior.isEnabled()) {
            return;
        }
        NotificationEntry newEntry = null;
        if (shouldHeadsUpStatusBarBeVisible()) {
            newEntry = mHeadsUpManager.getTopEntry();
        }
        NotificationEntry previousEntry = mView.getShowingEntry();
        mView.setEntry(newEntry);
        if (newEntry != previousEntry) {
            if (newEntry == null) {
                // No longer heads up
                setPinnedStatus(PinnedStatus.NotPinned);
            } else if (previousEntry == null) {
                // We now have a heads up when we didn't have one before
                setPinnedStatus(newEntry.getPinnedStatus());
            }

            mHeadsUpNotificationIconInteractor.setIsolatedIconNotificationKey(
                    getIsolatedIconKey(newEntry));
        }
    }

    private static @Nullable String getIsolatedIconKey(NotificationEntry newEntry) {
        StatusBarNoHunBehavior.assertInLegacyMode();
        if (newEntry == null) {
            return null;
        }
        if (StatusBarNotifChips.isEnabled()) {
            // If the flag is on, only show the isolated icon if the HUN is pinned by the
            // *system*. (If the HUN was pinned by the user, then the user tapped the
            // notification status bar chip and we want to keep the chip showing.)
            if (newEntry.getPinnedStatus() == PinnedStatus.PinnedBySystem) {
                return newEntry.getRepresentativeEntry().getKey();
            } else {
                return null;
            }
        } else {
            // If the flag is off, we know all HUNs are pinned by the system and should show
            // the isolated icon
            return newEntry.getRepresentativeEntry().getKey();
        }
    }

    private void setPinnedStatus(PinnedStatus pinnedStatus) {
        if (StatusBarNoHunBehavior.isEnabled()) {
            return;
        }
        if (mPinnedStatus != pinnedStatus) {
            mPinnedStatus = pinnedStatus;

            boolean shouldShowHunStatusBar = StatusBarNotifChips.isEnabled()
                    ? mPinnedStatus == PinnedStatus.PinnedBySystem
                    // If the flag isn't enabled, all HUNs get the normal treatment.
                    : mPinnedStatus.isPinned();
            if (shouldShowHunStatusBar) {
                updateParentClipping(false /* shouldClip */);
                mView.setVisibility(View.VISIBLE);
                show(mView);
                if (!StatusBarRootModernization.isEnabled()) {
                    hide(mClockView, View.INVISIBLE);
                }
                mOperatorNameViewOptional.ifPresent(view -> hide(view, View.INVISIBLE));
            } else {
                if (!StatusBarRootModernization.isEnabled()) {
                    show(mClockView);
                }
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
        StatusBarNoHunBehavior.assertInLegacyMode();
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
        StatusBarNoHunBehavior.assertInLegacyMode();

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
        StatusBarNoHunBehavior.assertInLegacyMode();

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
    public PinnedStatus getPinnedStatus() {
        if (StatusBarNoHunBehavior.isEnabled()) {
            return PinnedStatus.NotPinned;
        }
        return mPinnedStatus;
    }

    /** True if the device's current state allows us to show HUNs and false otherwise. */
    private boolean canShowHeadsUp() {
        boolean notificationsShown = !mWakeUpCoordinator.getNotificationsFullyHidden();
        if (mBypassController.getBypassEnabled() &&
                (mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                        || mKeyguardStateController.isKeyguardGoingAway())
                && notificationsShown) {
            return true;
        }
        return !isExpanded() && notificationsShown;
    }

    /**
     * True if the headsup status bar view (which has just the HUN icon and app name) should be
     * visible right now and false otherwise.
     *
     * @deprecated use {@link com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor#getStatusBarHeadsUpState()}
     *    instead.
     */
    @Deprecated
    public boolean shouldHeadsUpStatusBarBeVisible() {
        if (StatusBarNoHunBehavior.isEnabled()) {
            return false;
        }

        if (StatusBarNotifChips.isEnabled()) {
            return canShowHeadsUp()
                    && mHeadsUpManager.pinnedHeadsUpStatus() == PinnedStatus.PinnedBySystem;
            // Note: This means that if mHeadsUpManager.pinnedHeadsUpStatus() == PinnedByUser,
            // #updateTopEntry won't do anything, so mPinnedStatus will remain as NotPinned and will
            // *not* update to PinnedByUser.
        } else {
            return canShowHeadsUp() && mHeadsUpManager.hasPinnedHeadsUp();
        }
    }

    @Override
    public void onHeadsUpUnPinned(NotificationEntry entry) {
        updatePinnedStatus();
        updateHeader(entry);
        updateHeadsUpAndPulsingRoundness(entry);
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
            updatePinnedStatus();
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
            NotificationEntry entry = previousTracked.getEntry();
            updateHeader(entry);
            updateHeadsUpAndPulsingRoundness(entry);
        }
    }

    private boolean isExpanded() {
        return mExpandedHeight > 0;
    }

    private void updateHeadsUpHeaders() {
        mHeadsUpManager.getAllEntries().forEach(entry -> {
            updateHeader(entry);
            updateHeadsUpAndPulsingRoundness(entry);
        });
    }

    public void updateHeader(NotificationEntry entry) {
        ExpandableNotificationRow row = entry.getRow();
        float headerVisibleAmount = 1.0f;
        // To fix the invisible HUN group header issue
        if (!AsyncGroupHeaderViewInflation.isEnabled()) {
            if (row.isPinned() || row.isHeadsUpAnimatingAway() || row == mTrackedChild
                    || row.showingPulsing()) {
                headerVisibleAmount = mAppearFraction;
            }
        }
        row.setHeaderVisibleAmount(headerVisibleAmount);
    }

    /**
     * Update the HeadsUp and the Pulsing roundness based on current state
     * @param entry target notification
     */
    public void updateHeadsUpAndPulsingRoundness(NotificationEntry entry) {
        ExpandableNotificationRow row = entry.getRow();
        boolean isTrackedChild = row == mTrackedChild;
        if (row.isPinned() || row.isHeadsUpAnimatingAway() || isTrackedChild) {
            float roundness = MathUtils.saturate(1f - mAppearFraction);
            row.requestRoundness(roundness, roundness, HEADS_UP);
        } else {
            row.requestRoundnessReset(HEADS_UP);
        }
        if (mNotificationRoundnessManager.shouldRoundNotificationPulsing()) {
            if (row.showingPulsing()) {
                row.requestRoundness(/* top = */ 1f, /* bottom = */ 1f, PULSING);
            } else {
                row.requestRoundnessReset(PULSING);
            }
        }
    }


    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        StatusBarNoHunBehavior.assertInLegacyMode();
        mView.onDarkChanged(areas, darkIntensity, tint);
    }

    public void onStateChanged() {
        StatusBarNoHunBehavior.assertInLegacyMode();
        updatePinnedStatus();
    }

    @Override
    public void onFullyHiddenChanged(boolean isFullyHidden) {
        StatusBarNoHunBehavior.assertInLegacyMode();
        updatePinnedStatus();
    }
}
