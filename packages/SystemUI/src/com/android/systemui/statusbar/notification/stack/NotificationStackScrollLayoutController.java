/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import static android.service.notification.NotificationStats.DISMISSAL_SHADE;
import static android.service.notification.NotificationStats.DISMISS_SENTIMENT_NEUTRAL;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_SCROLL_FLING;
import static com.android.systemui.Dependency.ALLOW_NOTIFICATION_LONG_PRESS_NAME;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.OnEmptySpaceClickListener;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.OnOverscrollTopChangedListener;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_ALL;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_GENTLE;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_HIGH_PRIORITY;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.SelectedRows;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.canChildBeDismissed;
import static com.android.systemui.statusbar.phone.NotificationIconAreaController.HIGH_PRIORITY;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.MathUtils;
import android.util.Pair;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.ExpandHelper;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.media.KeyguardMediaController;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.OnMenuEventListener;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.UserChangedListener;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.ExpandAnimationParameters;
import com.android.systemui.statusbar.notification.ForegroundServiceDismissalFeatureController;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy.NotificationGroup;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy.OnGroupChangeListener;
import com.android.systemui.statusbar.notification.collection.legacy.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController;
import com.android.systemui.statusbar.notification.dagger.SilentHeader;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.ForegroundServiceDungeonView;
import com.android.systemui.statusbar.notification.row.NotificationGuts;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationSnooze;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.HeadsUpTouchHelper;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

import kotlin.Unit;

/**
 * Controller for {@link NotificationStackScrollLayout}.
 */
@StatusBarComponent.StatusBarScope
public class NotificationStackScrollLayoutController {
    private static final String TAG = "StackScrollerController";
    private static final boolean DEBUG = false;

    private final boolean mAllowLongPress;
    private final NotificationGutsManager mNotificationGutsManager;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final NotificationRoundnessManager mNotificationRoundnessManager;
    private final TunerService mTunerService;
    private final DynamicPrivacyController mDynamicPrivacyController;
    private final ConfigurationController mConfigurationController;
    private final ZenModeController mZenModeController;
    private final MetricsLogger mMetricsLogger;
    private final FalsingCollector mFalsingCollector;
    private final FalsingManager mFalsingManager;
    private final Resources mResources;
    private final NotificationSwipeHelper.Builder mNotificationSwipeHelperBuilder;
    private final ScrimController mScrimController;
    private final FeatureFlags mFeatureFlags;
    private final NotifPipeline mNotifPipeline;
    private final NotifCollection mNotifCollection;
    private final NotificationEntryManager mNotificationEntryManager;
    private final IStatusBarService mIStatusBarService;
    private final UiEventLogger mUiEventLogger;
    private final ForegroundServiceDismissalFeatureController mFgFeatureController;
    private final ForegroundServiceSectionController mFgServicesSectionController;
    private final LayoutInflater mLayoutInflater;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final VisualStabilityManager mVisualStabilityManager;
    private final ShadeController mShadeController;
    private final KeyguardMediaController mKeyguardMediaController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final KeyguardBypassController mKeyguardBypassController;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    // TODO: StatusBar should be encapsulated behind a Controller
    private final StatusBar mStatusBar;
    private final SectionHeaderController mSilentHeaderController;
    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;

    private NotificationStackScrollLayout mView;
    private boolean mFadeNotificationsOnDismiss;
    private NotificationSwipeHelper mSwipeHelper;
    private boolean mShowEmptyShadeView;
    private int mBarState;
    private HeadsUpAppearanceController mHeadsUpAppearanceController;

    private View mLongPressedView;

    private final NotificationListContainerImpl mNotificationListContainer =
            new NotificationListContainerImpl();

    @Nullable
    private NotificationActivityStarter mNotificationActivityStarter;

    private ColorExtractor.OnColorsChangedListener mOnColorsChangedListener;

    /**
     * The total distance in pixels that the full shade transition takes to transition entirely to
     * the full shade.
     */
    private int mTotalDistanceForFullShadeTransition;

    /**
     * The amount of movement the notifications do when transitioning to the full shade before
     * reaching the overstrech
     */
    private int mNotificationDragDownMovement;

    @VisibleForTesting
    final View.OnAttachStateChangeListener mOnAttachStateChangeListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    mConfigurationController.addCallback(mConfigurationListener);
                    mZenModeController.addCallback(mZenModeControllerCallback);
                    mBarState = mStatusBarStateController.getState();
                    mStatusBarStateController.addCallback(
                            mStateListener, SysuiStatusBarStateController.RANK_STACK_SCROLLER);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mConfigurationController.removeCallback(mConfigurationListener);
                    mZenModeController.removeCallback(mZenModeControllerCallback);
                    mStatusBarStateController.removeCallback(mStateListener);
                }
            };

    private final DynamicPrivacyController.Listener mDynamicPrivacyControllerListener = () -> {
        if (mView.isExpanded()) {
            // The bottom might change because we're using the final actual height of the view
            mView.setAnimateBottomOnLayout(true);
        }
        // Let's update the footer once the notifications have been updated (in the next frame)
        mView.post(() -> {
            updateFooter();
            updateSectionBoundaries("dynamic privacy changed");
        });
    };

    @VisibleForTesting
    final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onDensityOrFontScaleChanged() {
            updateShowEmptyShadeView();
            mView.reinflateViews();
        }

        @Override
        public void onOverlayChanged() {
            updateShowEmptyShadeView();
            mView.updateCornerRadius();
            mView.updateBgColor();
            mView.updateDecorViews();
            mView.reinflateViews();
        }

        @Override
        public void onUiModeChanged() {
            mView.updateBgColor();
            mView.updateDecorViews();
        }

        @Override
        public void onThemeChanged() {
            updateFooter();
        }

        @Override
        public void onConfigChanged(Configuration newConfig) {
            updateResources();
        }
    };

    private void updateResources() {
        mNotificationDragDownMovement = mResources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_notification_movement);
        mTotalDistanceForFullShadeTransition = mResources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_qs_transition_distance);
    }

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStatePreChange(int oldState, int newState) {
                    if (oldState == StatusBarState.SHADE_LOCKED
                            && newState == KEYGUARD) {
                        mView.requestAnimateEverything();
                    }
                }

                @Override
                public void onStateChanged(int newState) {
                    mBarState = newState;
                    mView.setStatusBarState(mBarState);
                }

                @Override
                public void onStatePostChange() {
                    mView.updateSensitiveness(mStatusBarStateController.goingToFullShade(),
                            mLockscreenUserManager.isAnyProfilePublicMode());
                    mView.onStatePostChange(mStatusBarStateController.fromShadeLocked());
                    mNotificationEntryManager.updateNotifications("StatusBar state changed");
                }
            };

    private final UserChangedListener mLockscreenUserChangeListener = new UserChangedListener() {
        @Override
        public void onUserChanged(int userId) {
            mView.updateSensitiveness(false, mLockscreenUserManager.isAnyProfilePublicMode());
        }
    };

    /**
     * Set the overexpansion of the panel to be applied to the view.
     */
    public void setOverExpansion(float overExpansion) {
        mView.setOverExpansion(overExpansion);
    }

    private final OnMenuEventListener mMenuEventListener = new OnMenuEventListener() {
        @Override
        public void onMenuClicked(
                View view, int x, int y, NotificationMenuRowPlugin.MenuItem item) {
            if (!mAllowLongPress) {
                return;
            }
            if (view instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) view;
                mMetricsLogger.write(row.getEntry().getSbn().getLogMaker()
                        .setCategory(MetricsEvent.ACTION_TOUCH_GEAR)
                        .setType(MetricsEvent.TYPE_ACTION)
                );
            }
            mNotificationGutsManager.openGuts(view, x, y, item);
        }

        @Override
        public void onMenuReset(View row) {
            View translatingParentView = mSwipeHelper.getTranslatingParentView();
            if (translatingParentView != null && row == translatingParentView) {
                mSwipeHelper.clearExposedMenuView();
                mSwipeHelper.clearTranslatingParentView();
                if (row instanceof ExpandableNotificationRow) {
                    mHeadsUpManager.setMenuShown(
                            ((ExpandableNotificationRow) row).getEntry(), false);

                }
            }
        }

        @Override
        public void onMenuShown(View row) {
            if (row instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow notificationRow = (ExpandableNotificationRow) row;
                mMetricsLogger.write(notificationRow.getEntry().getSbn().getLogMaker()
                        .setCategory(MetricsEvent.ACTION_REVEAL_GEAR)
                        .setType(MetricsEvent.TYPE_ACTION));
                mHeadsUpManager.setMenuShown(notificationRow.getEntry(), true);
                mSwipeHelper.onMenuShown(row);
                mNotificationGutsManager.closeAndSaveGuts(true /* removeLeavebehind */,
                        false /* force */, false /* removeControls */, -1 /* x */, -1 /* y */,
                        false /* resetMenu */);

                // Check to see if we want to go directly to the notification guts
                NotificationMenuRowPlugin provider = notificationRow.getProvider();
                if (provider.shouldShowGutsOnSnapOpen()) {
                    NotificationMenuRowPlugin.MenuItem item = provider.menuItemToExposeOnSnap();
                    if (item != null) {
                        Point origin = provider.getRevealAnimationOrigin();
                        mNotificationGutsManager.openGuts(row, origin.x, origin.y, item);
                    } else  {
                        Log.e(TAG, "Provider has shouldShowGutsOnSnapOpen, but provided no "
                                + "menu item in menuItemtoExposeOnSnap. Skipping.");
                    }

                    // Close the menu row since we went directly to the guts
                    mSwipeHelper.resetExposedMenuView(false, true);
                }
            }
        }
    };

    private final NotificationSwipeHelper.NotificationCallback mNotificationCallback =
            new NotificationSwipeHelper.NotificationCallback() {

                @Override
                public void onDismiss() {
                    mNotificationGutsManager.closeAndSaveGuts(true /* removeLeavebehind */,
                            false /* force */, false /* removeControls */, -1 /* x */, -1 /* y */,
                            false /* resetMenu */);
                }

                @Override
                public float getTotalTranslationLength(View animView) {
                    return mView.getTotalTranslationLength(animView);
                }

                @Override
                public void onSnooze(StatusBarNotification sbn,
                        NotificationSwipeActionHelper.SnoozeOption snoozeOption) {
                    mStatusBar.setNotificationSnoozed(sbn, snoozeOption);
                }

                @Override
                public boolean shouldDismissQuickly() {
                    return mView.isExpanded() && mView.isFullyAwake();
                }

                @Override
                public void onDragCancelled(View v) {
                    mFalsingCollector.onNotificationStopDismissing();
                }

                /**
                 * Handles cleanup after the given {@code view} has been fully swiped out (including
                 * re-invoking dismiss logic in case the notification has not made its way out yet).
                 */
                @Override
                public void onChildDismissed(View view) {
                    if (!(view instanceof ActivatableNotificationView)) {
                        return;
                    }
                    ActivatableNotificationView row = (ActivatableNotificationView) view;
                    if (!row.isDismissed()) {
                        handleChildViewDismissed(view);
                    }
                    ViewGroup transientContainer = row.getTransientContainer();
                    if (transientContainer != null) {
                        transientContainer.removeTransientView(view);
                    }
                }

                /**
                 * Starts up notification dismiss and tells the notification, if any, to remove
                 * itself from the layout.
                 *
                 * @param view view (e.g. notification) to dismiss from the layout
                 */

                public void handleChildViewDismissed(View view) {
                    if (mView.getDismissAllInProgress()) {
                        return;
                    }
                    mView.onSwipeEnd();
                    if (view instanceof ExpandableNotificationRow) {
                        ExpandableNotificationRow row = (ExpandableNotificationRow) view;
                        if (row.isHeadsUp()) {
                            mHeadsUpManager.addSwipedOutNotification(
                                    row.getEntry().getSbn().getKey());
                        }
                        row.performDismiss(false /* fromAccessibility */);
                    }

                    mView.addSwipedOutView(view);
                    mFalsingCollector.onNotificationDismissed();
                    if (mFalsingCollector.shouldEnforceBouncer()) {
                        mStatusBar.executeRunnableDismissingKeyguard(
                                null,
                                null /* cancelAction */,
                                false /* dismissShade */,
                                true /* afterKeyguardGone */,
                                false /* deferred */);
                    }
                }

                @Override
                public boolean isAntiFalsingNeeded() {
                    return mView.onKeyguard();
                }

                @Override
                public View getChildAtPosition(MotionEvent ev) {
                    View child = mView.getChildAtPosition(
                            ev.getX(),
                            ev.getY(),
                            true /* requireMinHeight */,
                            false /* ignoreDecors */);
                    if (child instanceof ExpandableNotificationRow) {
                        ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                        ExpandableNotificationRow parent = row.getNotificationParent();
                        if (parent != null && parent.areChildrenExpanded()
                                && (parent.areGutsExposed()
                                || mSwipeHelper.getExposedMenuView() == parent
                                || (parent.getAttachedChildren().size() == 1
                                && parent.getEntry().isClearable()))) {
                            // In this case the group is expanded and showing the menu for the
                            // group, further interaction should apply to the group, not any
                            // child notifications so we use the parent of the child. We also do the
                            // same if we only have a single child.
                            child = parent;
                        }
                    }
                    return child;
                }

                @Override
                public void onLongPressSent(View v) {
                    mLongPressedView = v;
                }

                @Override
                public void onBeginDrag(View v) {
                    mFalsingCollector.onNotificationStartDismissing();
                    mView.onSwipeBegin(v);
                }

                @Override
                public void onChildSnappedBack(View animView, float targetLeft) {
                    mView.onSwipeEnd();
                    if (animView instanceof ExpandableNotificationRow) {
                        ExpandableNotificationRow row = (ExpandableNotificationRow) animView;
                        if (row.isPinned() && !canChildBeDismissed(row)
                                && row.getEntry().getSbn().getNotification().fullScreenIntent
                                == null) {
                            mHeadsUpManager.removeNotification(row.getEntry().getSbn().getKey(),
                                    true /* removeImmediately */);
                        }
                    }
                }

                @Override
                public boolean updateSwipeProgress(View animView, boolean dismissable,
                        float swipeProgress) {
                    // Returning true prevents alpha fading.
                    return !mFadeNotificationsOnDismiss;
                }

                @Override
                public float getFalsingThresholdFactor() {
                    return mStatusBar.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
                }

                @Override
                public int getConstrainSwipeStartPosition() {
                    NotificationMenuRowPlugin menuRow = mSwipeHelper.getCurrentMenuRow();
                    if (menuRow != null) {
                        return Math.abs(menuRow.getMenuSnapTarget());
                    }
                    return 0;
                }

                @Override
                public boolean canChildBeDismissed(View v) {
                    return NotificationStackScrollLayout.canChildBeDismissed(v);
                }

                @Override
                public boolean canChildBeDismissedInDirection(View v, boolean isRightOrDown) {
                    //TODO: b/131242807 for why this doesn't do anything with direction
                    return canChildBeDismissed(v);
                }
            };

    private final OnHeadsUpChangedListener mOnHeadsUpChangedListener =
            new OnHeadsUpChangedListener() {
                @Override
                public void onHeadsUpPinnedModeChanged(boolean inPinnedMode) {
                    mView.setInHeadsUpPinnedMode(inPinnedMode);
                }

                @Override
                public void onHeadsUpPinned(NotificationEntry entry) {
                    mNotificationRoundnessManager.updateView(entry.getRow(), false /* animate */);
                }

                @Override
                public void onHeadsUpUnPinned(NotificationEntry entry) {
                    ExpandableNotificationRow row = entry.getRow();
                    // update the roundedness posted, because we might be animating away the
                    // headsup soon, so no need to set the roundedness to 0 and then back to 1.
                    row.post(() -> mNotificationRoundnessManager.updateView(row,
                            true /* animate */));
                }

                @Override
                public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
                    long numEntries = mHeadsUpManager.getAllEntries().count();
                    NotificationEntry topEntry = mHeadsUpManager.getTopEntry();
                    mView.setNumHeadsUp(numEntries);
                    mView.setTopHeadsUpEntry(topEntry);
                    generateHeadsUpAnimation(entry, isHeadsUp);
                    ExpandableNotificationRow row = entry.getRow();
                    mNotificationRoundnessManager.updateView(row, true /* animate */);
                }
            };

    private final ZenModeController.Callback mZenModeControllerCallback =
            new ZenModeController.Callback() {
                @Override
                public void onZenChanged(int zen) {
                    updateShowEmptyShadeView();
                }
            };

    @Inject
    public NotificationStackScrollLayoutController(
            @Named(ALLOW_NOTIFICATION_LONG_PRESS_NAME) boolean allowLongPress,
            NotificationGutsManager notificationGutsManager,
            HeadsUpManagerPhone headsUpManager,
            NotificationRoundnessManager notificationRoundnessManager,
            TunerService tunerService,
            DynamicPrivacyController dynamicPrivacyController,
            ConfigurationController configurationController,
            SysuiStatusBarStateController statusBarStateController,
            KeyguardMediaController keyguardMediaController,
            KeyguardBypassController keyguardBypassController,
            ZenModeController zenModeController,
            SysuiColorExtractor colorExtractor,
            NotificationLockscreenUserManager lockscreenUserManager,
            MetricsLogger metricsLogger,
            FalsingCollector falsingCollector,
            FalsingManager falsingManager,
            @Main Resources resources,
            NotificationSwipeHelper.Builder notificationSwipeHelperBuilder,
            StatusBar statusBar,
            ScrimController scrimController,
            NotificationGroupManagerLegacy legacyGroupManager,
            GroupExpansionManager groupManager,
            @SilentHeader SectionHeaderController silentHeaderController,
            FeatureFlags featureFlags,
            NotifPipeline notifPipeline,
            NotifCollection notifCollection,
            NotificationEntryManager notificationEntryManager,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            IStatusBarService iStatusBarService,
            UiEventLogger uiEventLogger,
            ForegroundServiceDismissalFeatureController fgFeatureController,
            ForegroundServiceSectionController fgServicesSectionController,
            LayoutInflater layoutInflater,
            NotificationRemoteInputManager remoteInputManager,
            VisualStabilityManager visualStabilityManager,
            ShadeController shadeController) {
        mAllowLongPress = allowLongPress;
        mNotificationGutsManager = notificationGutsManager;
        mHeadsUpManager = headsUpManager;
        mNotificationRoundnessManager = notificationRoundnessManager;
        mTunerService = tunerService;
        mDynamicPrivacyController = dynamicPrivacyController;
        mConfigurationController = configurationController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardMediaController = keyguardMediaController;
        mKeyguardBypassController = keyguardBypassController;
        mZenModeController = zenModeController;
        mLockscreenUserManager = lockscreenUserManager;
        mMetricsLogger = metricsLogger;
        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        mFalsingCollector = falsingCollector;
        mFalsingManager = falsingManager;
        mResources = resources;
        mNotificationSwipeHelperBuilder = notificationSwipeHelperBuilder;
        mStatusBar = statusBar;
        mScrimController = scrimController;
        groupManager.registerGroupExpansionChangeListener(
                (changedRow, expanded) -> mView.onGroupExpandChanged(changedRow, expanded));
        legacyGroupManager.registerGroupChangeListener(new OnGroupChangeListener() {
            @Override
            public void onGroupCreatedFromChildren(NotificationGroup group) {
                mStatusBar.requestNotificationUpdate("onGroupCreatedFromChildren");
            }

            @Override
            public void onGroupsChanged() {
                mStatusBar.requestNotificationUpdate("onGroupsChanged");
            }
        });
        mSilentHeaderController = silentHeaderController;
        mFeatureFlags = featureFlags;
        mNotifPipeline = notifPipeline;
        mNotifCollection = notifCollection;
        mNotificationEntryManager = notificationEntryManager;
        mIStatusBarService = iStatusBarService;
        mUiEventLogger = uiEventLogger;
        mFgFeatureController = fgFeatureController;
        mFgServicesSectionController = fgServicesSectionController;
        mLayoutInflater = layoutInflater;
        mRemoteInputManager = remoteInputManager;
        mVisualStabilityManager = visualStabilityManager;
        mShadeController = shadeController;
        updateResources();
    }

    public void attach(NotificationStackScrollLayout view) {
        mView = view;
        mView.setController(this);
        mView.setTouchHandler(new TouchHandler());
        mView.setStatusBar(mStatusBar);
        mView.setDismissAllAnimationListener(this::onAnimationEnd);
        mView.setDismissListener((selection) -> mUiEventLogger.log(
                NotificationPanelEvent.fromSelection(selection)));
        mView.setFooterDismissListener(() ->
                mMetricsLogger.action(MetricsEvent.ACTION_DISMISS_ALL_NOTES));
        mView.setIsRemoteInputActive(mRemoteInputManager.isRemoteInputActive());
        mRemoteInputManager.addControllerCallback(new RemoteInputController.Callback() {
            @Override
            public void onRemoteInputActive(boolean active) {
                mView.setIsRemoteInputActive(active);
            }
        });
        mView.setShadeController(mShadeController);

        mKeyguardBypassController.registerOnBypassStateChangedListener(
                isEnabled -> mNotificationRoundnessManager.setShouldRoundPulsingViews(!isEnabled));
        mNotificationRoundnessManager.setShouldRoundPulsingViews(
                !mKeyguardBypassController.getBypassEnabled());

        if (mFgFeatureController.isForegroundServiceDismissalEnabled()) {
            mView.initializeForegroundServiceSection(
                    (ForegroundServiceDungeonView) mFgServicesSectionController.createView(
                            mLayoutInflater));
        }

        mSwipeHelper = mNotificationSwipeHelperBuilder
                .setSwipeDirection(SwipeHelper.X)
                .setNotificationCallback(mNotificationCallback)
                .setOnMenuEventListener(mMenuEventListener)
                .build();

        if (mFeatureFlags.isNewNotifPipelineRenderingEnabled()) {
            mNotifPipeline.addCollectionListener(new NotifCollectionListener() {
                @Override
                public void onEntryUpdated(NotificationEntry entry) {
                    mView.onEntryUpdated(entry);
                }
            });
        } else {
            mNotificationEntryManager.addNotificationEntryListener(new NotificationEntryListener() {
                @Override
                public void onPreEntryUpdated(NotificationEntry entry) {
                    mView.onEntryUpdated(entry);
                }
            });
        }

        mView.initView(mView.getContext(), mSwipeHelper);
        mView.setKeyguardBypassEnabled(mKeyguardBypassController.getBypassEnabled());
        mKeyguardBypassController
                .registerOnBypassStateChangedListener(mView::setKeyguardBypassEnabled);
        mView.setManageButtonClickListener(v -> {
            if (mNotificationActivityStarter != null) {
                mNotificationActivityStarter.startHistoryIntent(v, mView.isHistoryShown());
            }
        });

        mHeadsUpManager.addListener(mOnHeadsUpChangedListener);
        mHeadsUpManager.setAnimationStateHandler(mView::setHeadsUpGoingAwayAnimationsAllowed);
        mDynamicPrivacyController.addListener(mDynamicPrivacyControllerListener);

        mScrimController.setScrimBehindChangeRunnable(mView::updateBackgroundDimming);

        mLockscreenShadeTransitionController.setStackScroller(this);

        mLockscreenUserManager.addUserChangedListener(mLockscreenUserChangeListener);

        mFadeNotificationsOnDismiss =  // TODO: this should probably be injected directly
                mResources.getBoolean(R.bool.config_fadeNotificationsOnDismiss);

        mNotificationRoundnessManager.setOnRoundingChangedCallback(mView::invalidate);
        mView.addOnExpandedHeightChangedListener(mNotificationRoundnessManager::setExpanded);

        mVisualStabilityManager.setVisibilityLocationProvider(this::isInVisibleLocation);

        mTunerService.addTunable(
                (key, newValue) -> {
                    switch (key) {
                        case Settings.Secure.NOTIFICATION_HISTORY_ENABLED:
                            updateFooter();
                            break;
                        case HIGH_PRIORITY:
                            mView.setHighPriorityBeforeSpeedBump("1".equals(newValue));
                            break;
                    }
                },
                HIGH_PRIORITY,
                Settings.Secure.NOTIFICATION_HISTORY_ENABLED);

        mKeyguardMediaController.setVisibilityChangedListener(visible -> {
            if (visible) {
                mView.generateAddAnimation(
                        mKeyguardMediaController.getSinglePaneContainer(),
                        false /*fromMoreCard */);
            } else {
                mView.generateRemoveAnimation(mKeyguardMediaController.getSinglePaneContainer());
            }
            mView.requestChildrenUpdate();
            return Unit.INSTANCE;
        });

        if (mView.isAttachedToWindow()) {
            mOnAttachStateChangeListener.onViewAttachedToWindow(mView);
        }
        mView.addOnAttachStateChangeListener(mOnAttachStateChangeListener);
        mSilentHeaderController.setOnClearAllClickListener(v -> clearSilentNotifications());
    }

    private boolean isInVisibleLocation(NotificationEntry entry) {
        ExpandableNotificationRow row = entry.getRow();
        ExpandableViewState childViewState = row.getViewState();

        if (childViewState == null) {
            return false;
        }
        if ((childViewState.location & ExpandableViewState.VISIBLE_LOCATIONS) == 0) {
            return false;
        }
        if (row.getVisibility() != View.VISIBLE) {
            return false;
        }
        return true;
    }

    public boolean isViewAffectedBySwipe(ExpandableView expandableView) {
        return mNotificationRoundnessManager.isViewAffectedBySwipe(expandableView);
    }

    public void addOnExpandedHeightChangedListener(BiConsumer<Float, Float> listener) {
        mView.addOnExpandedHeightChangedListener(listener);
    }

    public void removeOnExpandedHeightChangedListener(BiConsumer<Float, Float> listener) {
        mView.removeOnExpandedHeightChangedListener(listener);
    }

    public void addOnLayoutChangeListener(View.OnLayoutChangeListener listener) {
        mView.addOnLayoutChangeListener(listener);
    }

    public void removeOnLayoutChangeListener(View.OnLayoutChangeListener listener) {
        mView.removeOnLayoutChangeListener(listener);
    }

    public void setHeadsUpAppearanceController(HeadsUpAppearanceController controller) {
        mHeadsUpAppearanceController = controller;
        mView.setHeadsUpAppearanceController(controller);
    }

    public void requestLayout() {
        mView.requestLayout();
    }

    public Display getDisplay() {
        return mView.getDisplay();
    }

    public WindowInsets getRootWindowInsets() {
        return mView.getRootWindowInsets();
    }

    public int getRight() {
        return mView.getRight();
    }

    public boolean isLayoutRtl() {
        return mView.isLayoutRtl();
    }

    /**
     * @return the left of the view.
     */
    public int getLeft() {
        return mView.getLeft();
    }

    /**
     * @return the top of the view.
     */
    public int getTop() {
        return mView.getTop();
    }

    public float getTranslationX() {
        return mView.getTranslationX();
    }

    public int indexOfChild(View view) {
        return mView.indexOfChild(view);
    }

    public void setOnHeightChangedListener(
            ExpandableView.OnHeightChangedListener listener) {
        mView.setOnHeightChangedListener(listener);
    }

    public void setOverscrollTopChangedListener(
            OnOverscrollTopChangedListener listener) {
        mView.setOverscrollTopChangedListener(listener);
    }

    public void setOnEmptySpaceClickListener(
            OnEmptySpaceClickListener listener) {
        mView.setOnEmptySpaceClickListener(listener);
    }

    public void setTrackingHeadsUp(ExpandableNotificationRow expandableNotificationRow) {
        mView.setTrackingHeadsUp(expandableNotificationRow);
        mNotificationRoundnessManager.setTrackingHeadsUp(expandableNotificationRow);
    }

    public void wakeUpFromPulse() {
        mView.wakeUpFromPulse();
    }

    public boolean isPulseExpanding() {
        return mView.isPulseExpanding();
    }

    public void setOnPulseHeightChangedListener(Runnable listener) {
        mView.setOnPulseHeightChangedListener(listener);
    }

    public void setDozeAmount(float amount) {
        mView.setDozeAmount(amount);
    }

    public int getSpeedBumpIndex() {
        return mView.getSpeedBumpIndex();
    }

    public void setHideAmount(float linearAmount, float amount) {
        mView.setHideAmount(linearAmount, amount);
    }

    public void notifyHideAnimationStart(boolean hide) {
        mView.notifyHideAnimationStart(hide);
    }

    public float setPulseHeight(float height) {
        return mView.setPulseHeight(height);
    }

    public void getLocationOnScreen(int[] outLocation) {
        mView.getLocationOnScreen(outLocation);
    }

    public ExpandableView getChildAtRawPosition(float x, float y) {
        return mView.getChildAtRawPosition(x, y);
    }

    public ViewGroup.LayoutParams getLayoutParams() {
        return mView.getLayoutParams();
    }

    /**
     * Updates layout parameters on the root view
     */
    public void setLayoutParams(ViewGroup.LayoutParams lp) {
        mView.setLayoutParams(lp);
    }

    public void setIsFullWidth(boolean isFullWidth) {
        mView.setIsFullWidth(isFullWidth);
    }

    public boolean isAddOrRemoveAnimationPending() {
        return mView.isAddOrRemoveAnimationPending();
    }

    public int getVisibleNotificationCount() {
        return mView.getVisibleNotificationCount();
    }

    public int getIntrinsicContentHeight() {
        return mView.getIntrinsicContentHeight();
    }

    public void setIntrinsicPadding(int intrinsicPadding) {
        mView.setIntrinsicPadding(intrinsicPadding);
    }

    public int getHeight() {
        return mView.getHeight();
    }

    public int getChildCount() {
        return mView.getChildCount();
    }

    public ExpandableView getChildAt(int i) {
        return (ExpandableView) mView.getChildAt(i);
    }

    public void goToFullShade(long delay) {
        mView.goToFullShade(delay);
    }

    public void setOverScrollAmount(float amount, boolean onTop, boolean animate,
            boolean cancelAnimators) {
        mView.setOverScrollAmount(amount, onTop, animate, cancelAnimators);
    }

    public void setOverScrollAmount(float amount, boolean onTop, boolean animate) {
        mView.setOverScrollAmount(amount, onTop, animate);
    }

    public void resetScrollPosition() {
        mView.resetScrollPosition();
    }

    public void setShouldShowShelfOnly(boolean shouldShowShelfOnly) {
        mView.setShouldShowShelfOnly(shouldShowShelfOnly);
    }

    public void cancelLongPress() {
        mView.cancelLongPress();
    }

    public float getX() {
        return mView.getX();
    }

    public boolean isBelowLastNotification(float x, float y) {
        return mView.isBelowLastNotification(x, y);
    }

    public float getWidth() {
        return mView.getWidth();
    }

    public float getOpeningHeight() {
        return mView.getOpeningHeight();
    }

    public float getBottomMostNotificationBottom() {
        return mView.getBottomMostNotificationBottom();
    }

    public void checkSnoozeLeavebehind() {
        if (mView.getCheckSnoozeLeaveBehind()) {
            mNotificationGutsManager.closeAndSaveGuts(true /* removeLeavebehind */,
                    false /* force */, false /* removeControls */, -1 /* x */, -1 /* y */,
                    false /* resetMenu */);
            mView.setCheckForLeaveBehind(false);
        }
    }

    public void setQsExpanded(boolean expanded) {
        mView.setQsExpanded(expanded);
        updateShowEmptyShadeView();
    }

    public void setScrollingEnabled(boolean enabled) {
        mView.setScrollingEnabled(enabled);
    }

    public void setQsExpansionFraction(float expansionFraction) {
        mView.setQsExpansionFraction(expansionFraction);
    }

    public void setOnStackYChanged(Consumer<Boolean> onStackYChanged) {
        mView.setOnStackYChanged(onStackYChanged);
    }

    public float calculateAppearFractionBypass() {
        return mView.calculateAppearFractionBypass();
    }

    public void updateTopPadding(float qsHeight, boolean animate) {
        mView.updateTopPadding(qsHeight, animate);
    }

    public boolean isScrolledToBottom() {
        return mView.isScrolledToBottom();
    }

    public int getNotGoneChildCount() {
        return mView.getNotGoneChildCount();
    }

    public float getIntrinsicPadding() {
        return mView.getIntrinsicPadding();
    }

    public float getLayoutMinHeight() {
        return mView.getLayoutMinHeight();
    }

    public int getEmptyBottomMargin() {
        return mView.getEmptyBottomMargin();
    }

    public float getTopPaddingOverflow() {
        return mView.getTopPaddingOverflow();
    }

    public int getTopPadding() {
        return mView.getTopPadding();
    }

    public float getEmptyShadeViewHeight() {
        return mView.getEmptyShadeViewHeight();
    }

    public void setAlpha(float alpha) {
        mView.setAlpha(alpha);
    }

    public float calculateAppearFraction(float height) {
        return mView.calculateAppearFraction(height);
    }

    public void onExpansionStarted() {
        mView.onExpansionStarted();
        checkSnoozeLeavebehind();
    }

    public void onExpansionStopped() {
        mView.setCheckForLeaveBehind(false);
        mView.onExpansionStopped();
    }

    public void onPanelTrackingStarted() {
        mView.onPanelTrackingStarted();
    }

    public void onPanelTrackingStopped() {
        mView.onPanelTrackingStopped();
    }

    public void setHeadsUpBoundaries(int height, int bottomBarHeight) {
        mView.setHeadsUpBoundaries(height, bottomBarHeight);
    }

    public void setUnlockHintRunning(boolean running) {
        mView.setUnlockHintRunning(running);
    }

    public boolean isFooterViewNotGone() {
        return mView.isFooterViewNotGone();
    }

    public boolean isFooterViewContentVisible() {
        return mView.isFooterViewContentVisible();
    }

    public int getFooterViewHeightWithPadding() {
        return mView.getFooterViewHeightWithPadding();
    }

    /**
     * Update whether we should show the empty shade view (no notifications in the shade).
     * If so, send the update to our view.
     *
     * When in split mode, notifications are always visible regardless of the state of the
     * QuickSettings panel. That being the case, empty view is always shown if the other conditions
     * are true.
     */
    public void updateShowEmptyShadeView() {
        mShowEmptyShadeView = mBarState != KEYGUARD
                && (!mView.isQsExpanded() || mView.isUsingSplitNotificationShade())
                && mView.getVisibleNotificationCount() == 0;

        mView.updateEmptyShadeView(
                mShowEmptyShadeView,
                mZenModeController.areNotificationsHiddenInShade());
    }

    public boolean areNotificationsHiddenInShade() {
        return mZenModeController.areNotificationsHiddenInShade();
    }

    public boolean isShowingEmptyShadeView() {
        return mShowEmptyShadeView;
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mView.setHeadsUpAnimatingAway(headsUpAnimatingAway);
    }

    public HeadsUpTouchHelper.Callback getHeadsUpCallback() {
        return mView.getHeadsUpCallback();
    }

    public void forceNoOverlappingRendering(boolean force) {
        mView.forceNoOverlappingRendering(force);
    }

    public void setTranslationX(float translation) {
        mView.setTranslationX(translation);
    }

    public void setExpandingVelocity(float velocity) {
        mView.setExpandingVelocity(velocity);
    }

    public void setExpandedHeight(float expandedHeight) {
        mView.setExpandedHeight(expandedHeight);
    }

    public void setQsContainer(ViewGroup view) {
        mView.setQsContainer(view);
    }

    public void setAnimationsEnabled(boolean enabled) {
        mView.setAnimationsEnabled(enabled);
    }

    public void setDozing(boolean dozing, boolean animate, PointF wakeUpTouchLocation) {
        mView.setDozing(dozing, animate, wakeUpTouchLocation);
    }

    public void setPulsing(boolean pulsing, boolean animatePulse) {
        mView.setPulsing(pulsing, animatePulse);
    }

    /**
     * Return whether there are any clearable notifications
     */
    public boolean hasActiveClearableNotifications(@SelectedRows int selection) {
        return hasNotifications(selection, true /* clearable */);
    }

    public boolean hasNotifications(@SelectedRows int selection, boolean isClearable) {
        if (mDynamicPrivacyController.isInLockedDownShade()) {
            return false;
        }
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (!(child instanceof ExpandableNotificationRow)) {
                continue;
            }
            final ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            final boolean matchClearable =
                    isClearable ? row.canViewBeDismissed() : !row.canViewBeDismissed();
            final boolean inSection =
                    NotificationStackScrollLayout.matchesSelection(row, selection);
            if (matchClearable && inSection) {
                return true;
            }
        }
        return false;
    }

    /**
     * Set the maximum number of notifications that can currently be displayed
     */
    public void setMaxDisplayedNotifications(int maxNotifications) {
        mNotificationListContainer.setMaxDisplayedNotifications(maxNotifications);
    }

    /**
     * This is used for debugging only; it will be used to draw the otherwise invisible line which
     * NotificationPanelViewController treats as the bottom when calculating how many notifications
     * appear on the keyguard.
     * Setting a negative number will disable rendering this line.
     */
    public void setKeyguardBottomPadding(float keyguardBottomPadding) {
        mView.setKeyguardBottomPadding(keyguardBottomPadding);
    }

    public RemoteInputController.Delegate createDelegate() {
        return new RemoteInputController.Delegate() {
            public void setRemoteInputActive(NotificationEntry entry,
                    boolean remoteInputActive) {
                mHeadsUpManager.setRemoteInputActive(entry, remoteInputActive);
                entry.notifyHeightChanged(true /* needsAnimation */);
                updateFooter();
            }

            public void lockScrollTo(NotificationEntry entry) {
                mView.lockScrollTo(entry.getRow());
            }

            public void requestDisallowLongPressAndDismiss() {
                mView.requestDisallowLongPress();
                mView.requestDisallowDismiss();
            }
        };
    }

    public void updateSectionBoundaries(String reason) {
        mView.updateSectionBoundaries(reason);
    }

    public void updateFooter() {
        mView.updateFooter();
    }

    public void onUpdateRowStates() {
        mView.onUpdateRowStates();
    }

    public ActivatableNotificationView getActivatedChild() {
        return mView.getActivatedChild();
    }

    public void setActivatedChild(ActivatableNotificationView view) {
        mView.setActivatedChild(view);
    }

    public void runAfterAnimationFinished(Runnable r) {
        mView.runAfterAnimationFinished(r);
    }

    public void setShelfController(NotificationShelfController notificationShelfController) {
        mView.setShelfController(notificationShelfController);
    }

    public ExpandableView getFirstChildNotGone() {
        return mView.getFirstChildNotGone();
    }

    private void generateHeadsUpAnimation(NotificationEntry entry, boolean isHeadsUp) {
        mView.generateHeadsUpAnimation(entry, isHeadsUp);
    }

    public void generateHeadsUpAnimation(ExpandableNotificationRow row, boolean isHeadsUp) {
        mView.generateHeadsUpAnimation(row, isHeadsUp);
    }

    public void setMaxTopPadding(int padding) {
        mView.setMaxTopPadding(padding);
    }

    public int getTransientViewCount() {
        return mView.getTransientViewCount();
    }

    public View getTransientView(int i) {
        return mView.getTransientView(i);
    }

    public int getPositionInLinearLayout(ExpandableView row) {
        return mView.getPositionInLinearLayout(row);
    }

    public NotificationStackScrollLayout getView() {
        return mView;
    }

    public float calculateGapHeight(ExpandableView previousView, ExpandableView child, int count) {
        return mView.calculateGapHeight(previousView, child, count);
    }

    NotificationRoundnessManager getNoticationRoundessManager() {
        return mNotificationRoundnessManager;
    }

    public NotificationListContainer getNotificationListContainer() {
        return mNotificationListContainer;
    }

    public void resetCheckSnoozeLeavebehind() {
        mView.resetCheckSnoozeLeavebehind();
    }

    private DismissedByUserStats getDismissedByUserStats(
            NotificationEntry entry,
            int numVisibleEntries
    ) {
        return new DismissedByUserStats(
                DISMISSAL_SHADE,
                DISMISS_SENTIMENT_NEUTRAL,
                NotificationVisibility.obtain(
                        entry.getKey(),
                        entry.getRanking().getRank(),
                        numVisibleEntries,
                        true,
                        NotificationLogger.getNotificationLocation(entry)));
    }

    /**
     * @return if the shade has currently any active notifications.
     */
    public boolean hasActiveNotifications() {
        if (mFeatureFlags.isNewNotifPipelineRenderingEnabled()) {
            return !mNotifPipeline.getShadeList().isEmpty();
        } else {
            return mNotificationEntryManager.hasActiveNotifications();
        }
    }

    public void closeControlsIfOutsideTouch(MotionEvent ev) {
        NotificationGuts guts = mNotificationGutsManager.getExposedGuts();
        NotificationMenuRowPlugin menuRow = mSwipeHelper.getCurrentMenuRow();
        View translatingParentView = mSwipeHelper.getTranslatingParentView();
        View view = null;
        if (guts != null && !guts.getGutsContent().isLeavebehind()) {
            // Only close visible guts if they're not a leavebehind.
            view = guts;
        } else if (menuRow != null && menuRow.isMenuVisible()
                && translatingParentView != null) {
            // Checking menu
            view = translatingParentView;
        }
        if (view != null && !NotificationSwipeHelper.isTouchInView(ev, view)) {
            // Touch was outside visible guts / menu notification, close what's visible
            mNotificationGutsManager.closeAndSaveGuts(false /* removeLeavebehind */,
                    false /* force */, true /* removeControls */, -1 /* x */, -1 /* y */,
                    false /* resetMenu */);
            mSwipeHelper.resetExposedMenuView(true /* animate */, true /* force */);
        }
    }

    public void clearSilentNotifications() {
        // Leave the shade open if there will be other notifs left over to clear
        final boolean closeShade = !hasActiveClearableNotifications(ROWS_HIGH_PRIORITY);
        mView.clearNotifications(ROWS_GENTLE, closeShade);
    }

    private void onAnimationEnd(List<ExpandableNotificationRow> viewsToRemove,
            @SelectedRows int selectedRows) {
        if (mFeatureFlags.isNewNotifPipelineRenderingEnabled()) {
            if (selectedRows == ROWS_ALL) {
                mNotifCollection.dismissAllNotifications(
                        mLockscreenUserManager.getCurrentUserId());
            } else {
                final List<Pair<NotificationEntry, DismissedByUserStats>>
                        entriesWithRowsDismissedFromShade = new ArrayList<>();
                final int numVisibleEntries = mNotifPipeline.getShadeListCount();
                for (ExpandableNotificationRow row : viewsToRemove) {
                    final NotificationEntry entry = row.getEntry();
                    entriesWithRowsDismissedFromShade.add(
                            new Pair<>(
                                    entry,
                                    getDismissedByUserStats(entry, numVisibleEntries)));
                }
                mNotifCollection.dismissNotifications(entriesWithRowsDismissedFromShade);
            }
        } else {
            for (ExpandableNotificationRow rowToRemove : viewsToRemove) {
                if (canChildBeDismissed(rowToRemove)) {
                    mNotificationEntryManager.performRemoveNotification(
                            rowToRemove.getEntry().getSbn(),
                            getDismissedByUserStats(
                                    rowToRemove.getEntry(),
                                    mNotificationEntryManager.getActiveNotificationsCount()),
                            NotificationListenerService.REASON_CANCEL_ALL);
                } else {
                    rowToRemove.resetTranslation();
                }
            }
            if (selectedRows == ROWS_ALL) {
                try {
                    // TODO(b/169585328): Do not clear media player notifications
                    mIStatusBarService.onClearAllNotifications(
                            mLockscreenUserManager.getCurrentUserId());
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * @return the expand helper callback.
     */
    public ExpandHelper.Callback getExpandHelperCallback() {
        return mView.getExpandHelperCallback();
    }

    /**
     * @return If the shade is in the locked down shade.
     */
    public boolean isInLockedDownShade() {
        return mDynamicPrivacyController.isInLockedDownShade();
    }

    public boolean isLongPressInProgress() {
        return mLongPressedView != null;
    }

    /**
     * Set the dimmed state for all of the notification views.
     */
    public void setDimmed(boolean dimmed, boolean animate) {
        mView.setDimmed(dimmed, animate);
    }

    /**
     * @return the inset during the full shade transition, that needs to be added to the position
     *         of the quick settings edge. This is relevant for media, that is transitioning
     *         from the keyguard host to the quick settings one.
     */
    public int getFullShadeTransitionInset() {
        MediaHeaderView view = mKeyguardMediaController.getSinglePaneContainer();
        if (view == null || view.getHeight() == 0
                || mStatusBarStateController.getState() != KEYGUARD) {
            return 0;
        }
        return view.getHeight() + mView.getPaddingAfterMedia();
    }

    /**
     * Set the amount of pixels we have currently dragged down if we're transitioning to the full
     * shade. 0.0f means we're not transitioning yet.
     */
    public void setTransitionToFullShadeAmount(float amount) {
        float extraTopInset = 0.0f;
        if (mStatusBarStateController.getState() == KEYGUARD) {
            float overallProgress = MathUtils.saturate(amount / mView.getHeight());
            float transitionProgress = Interpolators.getOvershootInterpolation(overallProgress,
                    0.6f,
                    (float) mTotalDistanceForFullShadeTransition / (float) mView.getHeight());
            extraTopInset = transitionProgress * mNotificationDragDownMovement;
        }
        mView.setExtraTopInsetForFullShadeTransition(extraTopInset);
    }

    /** */
    public void setWillExpand(boolean willExpand) {
        mView.setWillExpand(willExpand);
    }

    /**
     * Set a listener to when scrolling changes.
     */
    public void setOnScrollListener(Consumer<Integer> listener) {
        mView.setOnScrollListener(listener);
    }

    /**
     * Set rounded rect clipping bounds on this view.
     */
    public void setRoundedClippingBounds(int left, int top, int right, int bottom, int topRadius,
            int bottomRadius) {
        mView.setRoundedClippingBounds(left, top, right, bottom, topRadius, bottomRadius);
    }

    /**
     * Request an animation whenever the toppadding changes next
     */
    public void animateNextTopPaddingChange() {
        mView.animateNextTopPaddingChange();
    }

    public void setNotificationActivityStarter(NotificationActivityStarter activityStarter) {
        mNotificationActivityStarter = activityStarter;
    }

    /**
     * Enum for UiEvent logged from this class
     */
    enum NotificationPanelEvent implements UiEventLogger.UiEventEnum {
        INVALID(0),
        @UiEvent(doc = "User dismissed all notifications from notification panel.")
        DISMISS_ALL_NOTIFICATIONS_PANEL(312),
        @UiEvent(doc = "User dismissed all silent notifications from notification panel.")
        DISMISS_SILENT_NOTIFICATIONS_PANEL(314);
        private final int mId;
        NotificationPanelEvent(int id) {
            mId = id;
        }
        @Override public int getId() {
            return mId;
        }

        public static UiEventLogger.UiEventEnum fromSelection(@SelectedRows int selection) {
            if (selection == ROWS_ALL) {
                return DISMISS_ALL_NOTIFICATIONS_PANEL;
            }
            if (selection == NotificationStackScrollLayout.ROWS_GENTLE) {
                return DISMISS_SILENT_NOTIFICATIONS_PANEL;
            }
            if (NotificationStackScrollLayoutController.DEBUG) {
                throw new IllegalArgumentException("Unexpected selection" + selection);
            }
            return INVALID;
        }
    }

    private class NotificationListContainerImpl implements NotificationListContainer {

        @Override
        public void setChildTransferInProgress(boolean childTransferInProgress) {
            mView.setChildTransferInProgress(childTransferInProgress);
        }

        @Override
        public void changeViewPosition(ExpandableView child, int newIndex) {
            mView.changeViewPosition(child, newIndex);
        }

        @Override
        public void notifyGroupChildAdded(ExpandableView row) {
            mView.notifyGroupChildAdded(row);
        }

        @Override
        public void notifyGroupChildRemoved(ExpandableView row, ViewGroup childrenContainer) {
            mView.notifyGroupChildRemoved(row, childrenContainer);
        }

        @Override
        public void generateAddAnimation(ExpandableView child, boolean fromMoreCard) {
            mView.generateAddAnimation(child, fromMoreCard);
        }

        @Override
        public void generateChildOrderChangedEvent() {
            mView.generateChildOrderChangedEvent();
        }

        @Override
        public int getContainerChildCount() {
            return mView.getContainerChildCount();
        }

        @Override
        public void setNotificationActivityStarter(
                NotificationActivityStarter notificationActivityStarter) {
            NotificationStackScrollLayoutController.this
                    .setNotificationActivityStarter(notificationActivityStarter);
        }

        @Override
        public int getTopClippingStartLocation() {
            return mView.getTopClippingStartLocation();
        }

        @Override
        public View getContainerChildAt(int i) {
            return mView.getContainerChildAt(i);
        }

        @Override
        public void removeContainerView(View v) {
            mView.removeContainerView(v);
        }

        @Override
        public void addContainerView(View v) {
            mView.addContainerView(v);
        }

        @Override
        public void addContainerViewAt(View v, int index) {
            mView.addContainerViewAt(v, index);
        }

        @Override
        public void setMaxDisplayedNotifications(int maxNotifications) {
            mView.setMaxDisplayedNotifications(maxNotifications);
        }

        @Override
        public ViewGroup getViewParentForNotification(NotificationEntry entry) {
            return mView.getViewParentForNotification(entry);
        }

        @Override
        public void resetExposedMenuView(boolean animate, boolean force) {
            mSwipeHelper.resetExposedMenuView(animate, force);
        }

        @Override
        public NotificationSwipeActionHelper getSwipeActionHelper() {
            return mSwipeHelper;
        }

        @Override
        public void cleanUpViewStateForEntry(NotificationEntry entry) {
            mView.cleanUpViewStateForEntry(entry);
        }

        @Override
        public void setChildLocationsChangedListener(
                NotificationLogger.OnChildLocationsChangedListener listener) {
            mView.setChildLocationsChangedListener(listener);
        }

        public boolean hasPulsingNotifications() {
            return mView.hasPulsingNotifications();
        }

        @Override
        public boolean isInVisibleLocation(NotificationEntry entry) {
            return NotificationStackScrollLayoutController.this.isInVisibleLocation(entry);
        }

        @Override
        public void onHeightChanged(ExpandableView view, boolean needsAnimation) {
            mView.onChildHeightChanged(view, needsAnimation);
        }

        @Override
        public void onReset(ExpandableView view) {
            mView.onChildHeightReset(view);
        }

        @Override
        public void bindRow(ExpandableNotificationRow row) {
            row.setHeadsUpAnimatingAwayListener(animatingAway -> {
                mNotificationRoundnessManager.updateView(row, false);
                mHeadsUpAppearanceController.updateHeader(row.getEntry());
            });
        }

        @Override
        public void applyExpandAnimationParams(ExpandAnimationParameters params) {
            mView.applyExpandAnimationParams(params);
        }

        @Override
        public void setExpandingNotification(ExpandableNotificationRow row) {
            mView.setExpandingNotification(row);
        }

        @Override
        public boolean containsView(View v) {
            return mView.containsView(v);
        }

        @Override
        public void setWillExpand(boolean willExpand) {
            mView.setWillExpand(willExpand);
        }
    }

    class TouchHandler implements Gefingerpoken {
        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            mView.initDownStates(ev);
            mView.handleEmptySpaceClick(ev);

            NotificationGuts guts = mNotificationGutsManager.getExposedGuts();

            boolean longPressWantsIt = false;
            if (mLongPressedView != null) {
                longPressWantsIt = mSwipeHelper.onInterceptTouchEvent(ev);
            }
            boolean expandWantsIt = false;
            if (mLongPressedView == null && !mSwipeHelper.isSwiping()
                    && !mView.getOnlyScrollingInThisMotion() && guts == null) {
                expandWantsIt = mView.getExpandHelper().onInterceptTouchEvent(ev);
            }
            boolean scrollWantsIt = false;
            if (mLongPressedView == null && !mSwipeHelper.isSwiping()
                    && !mView.isExpandingNotification()) {
                scrollWantsIt = mView.onInterceptTouchEventScroll(ev);
            }
            boolean swipeWantsIt = false;
            if (mLongPressedView == null && !mView.isBeingDragged()
                    && !mView.isExpandingNotification()
                    && !mView.getExpandedInThisMotion()
                    && !mView.getOnlyScrollingInThisMotion()
                    && !mView.getDisallowDismissInThisMotion()) {
                swipeWantsIt = mSwipeHelper.onInterceptTouchEvent(ev);
            }
            // Check if we need to clear any snooze leavebehinds
            boolean isUp = ev.getActionMasked() == MotionEvent.ACTION_UP;
            if (!NotificationSwipeHelper.isTouchInView(ev, guts) && isUp && !swipeWantsIt &&
                    !expandWantsIt && !scrollWantsIt) {
                mView.setCheckForLeaveBehind(false);
                mNotificationGutsManager.closeAndSaveGuts(true /* removeLeavebehind */,
                        false /* force */, false /* removeControls */, -1 /* x */, -1 /* y */,
                        false /* resetMenu */);
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                mView.setCheckForLeaveBehind(true);
            }

            // When swiping directly on the NSSL, this would only get an onTouchEvent.
            // We log any touches other than down, which will be captured by onTouchEvent.
            // In the intercept we only start tracing when it's not a down (otherwise that down
            // would be duplicated when intercepted).
            if (scrollWantsIt && ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
                InteractionJankMonitor.getInstance().begin(mView,
                        CUJ_NOTIFICATION_SHADE_SCROLL_FLING);
            }
            return swipeWantsIt || scrollWantsIt || expandWantsIt || longPressWantsIt;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            NotificationGuts guts = mNotificationGutsManager.getExposedGuts();
            boolean isCancelOrUp = ev.getActionMasked() == MotionEvent.ACTION_CANCEL
                    || ev.getActionMasked() == MotionEvent.ACTION_UP;
            mView.handleEmptySpaceClick(ev);
            boolean longPressWantsIt = false;
            if (guts != null && mLongPressedView != null) {
                longPressWantsIt = mSwipeHelper.onTouchEvent(ev);
            }
            boolean expandWantsIt = false;
            boolean onlyScrollingInThisMotion = mView.getOnlyScrollingInThisMotion();
            boolean expandingNotification = mView.isExpandingNotification();
            if (mLongPressedView == null && mView.getIsExpanded()
                    && !mSwipeHelper.isSwiping() && !onlyScrollingInThisMotion && guts == null) {
                ExpandHelper expandHelper = mView.getExpandHelper();
                if (isCancelOrUp) {
                    expandHelper.onlyObserveMovements(false);
                }
                boolean wasExpandingBefore = expandingNotification;
                expandWantsIt = expandHelper.onTouchEvent(ev);
                expandingNotification = mView.isExpandingNotification();
                if (mView.getExpandedInThisMotion() && !expandingNotification && wasExpandingBefore
                        && !mView.getDisallowScrollingInThisMotion()) {
                    mView.dispatchDownEventToScroller(ev);
                }
            }
            boolean scrollerWantsIt = false;
            if (mLongPressedView == null && mView.isExpanded() && !mSwipeHelper.isSwiping()
                    && !expandingNotification && !mView.getDisallowScrollingInThisMotion()) {
                scrollerWantsIt = mView.onScrollTouch(ev);
            }
            boolean horizontalSwipeWantsIt = false;
            if (mLongPressedView == null && !mView.isBeingDragged()
                    && !expandingNotification
                    && !mView.getExpandedInThisMotion()
                    && !onlyScrollingInThisMotion
                    && !mView.getDisallowDismissInThisMotion()) {
                horizontalSwipeWantsIt = mSwipeHelper.onTouchEvent(ev);
            }

            // Check if we need to clear any snooze leavebehinds
            if (guts != null && !NotificationSwipeHelper.isTouchInView(ev, guts)
                    && guts.getGutsContent() instanceof NotificationSnooze) {
                NotificationSnooze ns = (NotificationSnooze) guts.getGutsContent();
                if ((ns.isExpanded() && isCancelOrUp)
                        || (!horizontalSwipeWantsIt && scrollerWantsIt)) {
                    // If the leavebehind is expanded we clear it on the next up event, otherwise we
                    // clear it on the next non-horizontal swipe or expand event.
                    checkSnoozeLeavebehind();
                }
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                // Ensure the falsing manager records the touch. we don't do anything with it
                // at the moment.
                mFalsingManager.isFalseTouch(Classifier.SHADE_DRAG);
                mView.setCheckForLeaveBehind(true);
            }
            traceJankOnTouchEvent(ev.getActionMasked(), scrollerWantsIt);
            return horizontalSwipeWantsIt || scrollerWantsIt || expandWantsIt || longPressWantsIt;
        }

        private void traceJankOnTouchEvent(int action, boolean scrollerWantsIt) {
            // Handle interaction jank monitor cases.
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    if (scrollerWantsIt) {
                        InteractionJankMonitor.getInstance()
                                .begin(mView, CUJ_NOTIFICATION_SHADE_SCROLL_FLING);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (scrollerWantsIt && !mView.isFlingAfterUpEvent()) {
                        InteractionJankMonitor.getInstance()
                                .end(CUJ_NOTIFICATION_SHADE_SCROLL_FLING);
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (scrollerWantsIt) {
                        InteractionJankMonitor.getInstance()
                                .cancel(CUJ_NOTIFICATION_SHADE_SCROLL_FLING);
                    }
                    break;
            }
        }
    }
}
