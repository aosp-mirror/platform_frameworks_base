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

import static com.android.app.animation.Interpolators.STANDARD;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_SCROLL_FLING;
import static com.android.server.notification.Flags.screenshareNotificationHiding;
import static com.android.systemui.Dependency.ALLOW_NOTIFICATION_LONG_PRESS_NAME;
import static com.android.systemui.Flags.confineNotificationTouchToViewWidth;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.OnEmptySpaceClickListener;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.OnOverscrollTopChangedListener;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_ALL;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_GENTLE;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_HIGH_PRIORITY;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.SelectedRows;
import static com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_STANDARD;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.animation.ObjectAnimator;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.Pair;
import android.util.Property;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.view.OneShotPreDrawListener;
import com.android.systemui.Dumpable;
import com.android.systemui.ExpandHelper;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository;
import com.android.systemui.keyguard.shared.model.KeyguardState;
import com.android.systemui.keyguard.shared.model.TransitionStep;
import com.android.systemui.media.controls.ui.controller.KeyguardMediaController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.OnMenuEventListener;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.qs.flags.QSComposeFragment;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.scene.ui.view.WindowRootView;
import com.android.systemui.shade.QSHeaderBoundsProvider;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.UserChangedListener;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.ColorUpdateLogger;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.HeadsUpNotificationViewControllerEmptyImpl;
import com.android.systemui.statusbar.notification.HeadsUpTouchHelper;
import com.android.systemui.statusbar.notification.HeadsUpTouchHelper.HeadsUpNotificationViewController;
import com.android.systemui.statusbar.notification.LaunchAnimationParameters;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.PipelineDumpable;
import com.android.systemui.statusbar.notification.collection.PipelineDumper;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.provider.NotificationDismissibilityProvider;
import com.android.systemui.statusbar.notification.collection.provider.VisibilityLocationProviderDelegator;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.NotifStackController;
import com.android.systemui.statusbar.notification.collection.render.NotifStats;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController;
import com.android.systemui.statusbar.notification.dagger.SilentHeader;
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor;
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.NotificationGuts;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationSnooze;
import com.android.systemui.statusbar.notification.shared.GroupHunAnimationFix;
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.NotificationListViewBinder;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController;
import com.android.systemui.statusbar.policy.SplitShadeStateController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.Compile;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.wallpapers.domain.interactor.WallpaperInteractor;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

/**
 * Controller for {@link NotificationStackScrollLayout}.
 */
@SysUISingleton
public class NotificationStackScrollLayoutController implements Dumpable {
    private static final String TAG = "StackScrollerController";
    private static final boolean DEBUG = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.DEBUG);
    private static final String HIGH_PRIORITY = "high_priority";
    /** Delay in milli-seconds before shade closes for clear all. */
    private static final int DELAY_BEFORE_SHADE_CLOSE = 200;

    private final boolean mAllowLongPress;
    private final NotificationGutsManager mNotificationGutsManager;
    private final NotificationsController mNotificationsController;
    private final NotificationVisibilityProvider mVisibilityProvider;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;
    private final HeadsUpManager mHeadsUpManager;
    private HeadsUpTouchHelper mHeadsUpTouchHelper;
    private final NotificationRoundnessManager mNotificationRoundnessManager;
    private final TunerService mTunerService;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final DynamicPrivacyController mDynamicPrivacyController;
    private final ConfigurationController mConfigurationController;
    private final ZenModeController mZenModeController;
    private final MetricsLogger mMetricsLogger;
    private final ColorUpdateLogger mColorUpdateLogger;

    private final DumpManager mDumpManager;
    private final FalsingCollector mFalsingCollector;
    private final FalsingManager mFalsingManager;
    private final NotificationSwipeHelper.Builder mNotificationSwipeHelperBuilder;
    private final NotifPipeline mNotifPipeline;
    private final NotifCollection mNotifCollection;
    private final UiEventLogger mUiEventLogger;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final VisibilityLocationProviderDelegator mVisibilityLocationProviderDelegator;
    private final ShadeController mShadeController;
    private final Provider<WindowRootView> mWindowRootView;
    private final KeyguardMediaController mKeyguardMediaController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final KeyguardBypassController mKeyguardBypassController;
    private final PowerInteractor mPowerInteractor;
    private final PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final SectionHeaderController mSilentHeaderController;
    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final InteractionJankMonitor mJankMonitor;
    private final NotificationStackSizeCalculator mNotificationStackSizeCalculator;
    private final StackStateLogger mStackStateLogger;
    private final NotificationStackScrollLogger mLogger;

    private final GroupExpansionManager mGroupExpansionManager;
    private final SeenNotificationsInteractor mSeenNotificationsInteractor;
    private final KeyguardTransitionRepository mKeyguardTransitionRepo;
    private NotificationStackScrollLayout mView;
    private TouchHandler mTouchHandler;
    private NotificationSwipeHelper mSwipeHelper;
    @Nullable
    private Boolean mHistoryEnabled;
    private int mBarState;
    private HeadsUpAppearanceController mHeadsUpAppearanceController;
    private boolean mIsInTransitionToAod = false;

    private final NotificationTargetsHelper mNotificationTargetsHelper;
    private final SecureSettings mSecureSettings;
    private final NotificationDismissibilityProvider mDismissibilityProvider;
    private final ActivityStarter mActivityStarter;
    private final SensitiveNotificationProtectionController
            mSensitiveNotificationProtectionController;

    private final WallpaperInteractor mWallpaperInteractor;

    private View mLongPressedView;

    private final NotificationListContainerImpl mNotificationListContainer =
            new NotificationListContainerImpl();
    private final NotifStackController mNotifStackController =
            new NotifStackControllerImpl();

    @Nullable
    private NotificationActivityStarter mNotificationActivityStarter;

    @VisibleForTesting
    final View.OnAttachStateChangeListener mOnAttachStateChangeListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    mColorUpdateLogger.logTriggerEvent("NSSLC.onViewAttachedToWindow()");
                    mConfigurationController.addCallback(mConfigurationListener);
                    if (!FooterViewRefactor.isEnabled()) {
                        mZenModeController.addCallback(mZenModeControllerCallback);
                    }
                    final int newBarState = mStatusBarStateController.getState();
                    if (newBarState != mBarState) {
                        mStateListener.onStateChanged(newBarState);
                        mStateListener.onStatePostChange();
                    }
                    mStatusBarStateController.addCallback(
                            mStateListener, SysuiStatusBarStateController.RANK_STACK_SCROLLER);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mColorUpdateLogger.logTriggerEvent("NSSLC.onViewDetachedFromWindow()");
                    mConfigurationController.removeCallback(mConfigurationListener);
                    if (!FooterViewRefactor.isEnabled()) {
                        mZenModeController.removeCallback(mZenModeControllerCallback);
                    }
                    mStatusBarStateController.removeCallback(mStateListener);
                }
            };

    private static final Property<NotificationStackScrollLayoutController, Float>
            HIDE_ALPHA_PROPERTY = new Property<>(Float.class, "HideNotificationsAlpha") {
                @Override
                public Float get(NotificationStackScrollLayoutController object) {
                    return object.mMaxAlphaForUnhide;
                }

                @Override
                public void set(NotificationStackScrollLayoutController object, Float value) {
                    object.setMaxAlphaForUnhide(value);
                }
            };

    @Nullable
    private ObjectAnimator mHideAlphaAnimator = null;

    private final DeviceProvisionedListener mDeviceProvisionedListener =
            new DeviceProvisionedListener() {
                @Override
                public void onDeviceProvisionedChanged() {
                    updateCurrentUserIsSetup();
                }

                @Override
                public void onUserSwitched() {
                    updateCurrentUserIsSetup();
                }

                @Override
                public void onUserSetupChanged() {
                    updateCurrentUserIsSetup();
                }

                private void updateCurrentUserIsSetup() {
                    mView.setCurrentUserSetup(mDeviceProvisionedController.isCurrentUserSetup());
                }
            };

    private final Runnable mSensitiveStateChangedListener = new Runnable() {
        @Override
        public void run() {
            // Animate false to protect against screen recording capturing content
            // during the animation
            updateSensitivenessWithAnimation(false);
        }
    };

    private final DynamicPrivacyController.Listener mDynamicPrivacyControllerListener = () -> {
        if (!FooterViewRefactor.isEnabled()) {
            // Let's update the footer once the notifications have been updated (in the next frame)
            mView.post(this::updateFooter);
        }
    };

    @VisibleForTesting
    final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onDensityOrFontScaleChanged() {
            if (!FooterViewRefactor.isEnabled()) {
                updateShowEmptyShadeView();
            }
            mView.reinflateViews();
        }

        @Override
        public void onUiModeChanged() {
            mColorUpdateLogger.logTriggerEvent("NSSLC.onUiModeChanged()",
                    "mode=" + mConfigurationController.getNightModeName());
            mView.updateBgColor();
            mView.updateDecorViews();
        }

        @Override
        public void onThemeChanged() {
            mColorUpdateLogger.logTriggerEvent("NSSLC.onThemeChanged()",
                    "mode=" + mConfigurationController.getNightModeName());
            mView.updateCornerRadius();
            mView.updateBgColor();
            mView.updateDecorViews();
            mView.reinflateViews();
            if (!FooterViewRefactor.isEnabled()) {
                updateShowEmptyShadeView();
                updateFooter();
            }
        }

        @Override
        public void onConfigChanged(Configuration newConfig) {
            updateResources();
        }
    };

    private NotifStats mNotifStats = NotifStats.getEmpty();
    private float mMaxAlphaForKeyguard = 1.0f;
    private String mMaxAlphaForKeyguardSource = "constructor";
    private float mMaxAlphaForUnhide = 1.0f;
    private float mMaxAlphaFromView = 1.0f;

    /**
     * Maximum alpha when to and from or sitting idle on the glanceable hub. Will be 1.0f when the
     * hub is not visible or transitioning.
     */
    private float mMaxAlphaForGlanceableHub = 1.0f;

    private final NotificationListViewBinder mViewBinder;

    private void updateResources() {
        mNotificationStackSizeCalculator.updateResources();
    }

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStatePreChange(int oldState, int newState) {
                    if (!SceneContainerFlag.isEnabled() && oldState == StatusBarState.SHADE_LOCKED
                            && newState == KEYGUARD) {
                        mView.requestAnimateEverything();
                    }
                }

                @Override
                public void onStateChanged(int newState) {
                    mBarState = newState;
                    mView.setStatusBarState(mBarState);
                    if (newState == KEYGUARD) {
                        mGroupExpansionManager.collapseGroups();
                    }
                }

                @Override
                public void onUpcomingStateChanged(int newState) {
                    if (!FooterViewRefactor.isEnabled()) {
                        mView.setUpcomingStatusBarState(newState);
                    }
                }

                @Override
                public void onStatePostChange() {
                    updateSensitivenessWithAnimation(mStatusBarStateController.goingToFullShade());
                    mView.onStatePostChange(mStatusBarStateController.fromShadeLocked());
                    if (!FooterViewRefactor.isEnabled()) {
                        updateImportantForAccessibility();
                    }
                }
            };

    private final UserChangedListener mLockscreenUserChangeListener = new UserChangedListener() {
        @Override
        public void onUserChanged(int userId) {
            updateSensitivenessWithAnimation(false);
            mHistoryEnabled = null;
            if (!FooterViewRefactor.isEnabled()) {
                updateFooter();
            }
        }
    };

    /**
     * Recalculate sensitiveness without animation; called when waking up while keyguard occluded.
     */
    public void updateSensitivenessForOccludedWakeup() {
        updateSensitivenessWithAnimation(false);
    }

    private void updateSensitivenessWithAnimation(boolean animate) {
        Trace.beginSection("NSSLC.updateSensitivenessWithAnimation");
        if (screenshareNotificationHiding()) {
            boolean isAnyProfilePublic = mLockscreenUserManager.isAnyProfilePublicMode();
            boolean isSensitiveContentProtectionActive =
                    mSensitiveNotificationProtectionController.isSensitiveStateActive();
            boolean isSensitive = isAnyProfilePublic || isSensitiveContentProtectionActive;

            // Only animate if in a non-sensitive state (not screen sharing)
            boolean shouldAnimate = animate && !isSensitiveContentProtectionActive;
            mView.updateSensitiveness(shouldAnimate, isSensitive);
        } else {
            mView.updateSensitiveness(animate, mLockscreenUserManager.isAnyProfilePublicMode());
        }
        Trace.endSection();
    }

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
            if (view instanceof ExpandableNotificationRow row) {
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
            }
        }

        @Override
        public void onMenuShown(View row) {
            if (row instanceof ExpandableNotificationRow notificationRow) {
                mMetricsLogger.write(notificationRow.getEntry().getSbn().getLogMaker()
                        .setCategory(MetricsEvent.ACTION_REVEAL_GEAR)
                        .setType(MetricsEvent.TYPE_ACTION));
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
                    } else {
                        Log.e(TAG, "Provider has shouldShowGutsOnSnapOpen, but provided no "
                                + "menu item in menuItemtoExposeOnSnap. Skipping.");
                    }

                    // Close the menu row since we went directly to the guts
                    mSwipeHelper.resetExposedMenuView(false, true);
                }
            }
        }
    };

    @VisibleForTesting
    final NotificationSwipeHelper.NotificationCallback mNotificationCallback =
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
                    mNotificationsController.setNotificationSnoozed(sbn, snoozeOption);
                }

                @Override
                public boolean shouldDismissQuickly() {
                    return mView.isExpanded() && mView.isFullyAwake();
                }

                @Override
                public void onDragCancelled(View v) {
                }

                /**
                 * Handles cleanup after the given {@code view} has been fully swiped out (including
                 * re-invoking dismiss logic in case the notification has not made its way out yet).
                 */
                @Override
                public void onChildDismissed(View view) {
                    if (!(view instanceof ActivatableNotificationView row)) {
                        return;
                    }
                    if (!row.isDismissed()) {
                        handleChildViewDismissed(view);
                    }

                    row.removeFromTransientContainer();
                    if (row instanceof ExpandableNotificationRow) {
                        ((ExpandableNotificationRow) row).removeChildrenWithKeepInParent();
                    }
                }

                /**
                 * Starts up notification dismiss and tells the notification, if any, to remove
                 * itself from the layout.
                 *
                 * @param view view (e.g. notification) to dismiss from the layout
                 */

                public void handleChildViewDismissed(View view) {
                    // The View needs to clean up the Swipe states, e.g. roundness.
                    mView.onSwipeEnd();
                    if (mView.getClearAllInProgress()) {
                        return;
                    }
                    if (view instanceof ExpandableNotificationRow row) {
                        if (row.isHeadsUp()) {
                            mHeadsUpManager.addSwipedOutNotification(
                                    row.getEntry().getSbn().getKey());
                        }
                        row.performDismiss(false /* fromAccessibility */);
                    }

                    mView.addSwipedOutView(view);
                    if (mFalsingCollector.shouldEnforceBouncer()) {
                        mActivityStarter.executeRunnableDismissingKeyguard(
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
                            false /* ignoreDecors */,
                            !confineNotificationTouchToViewWidth() /* ignoreWidth */);
                    if (child instanceof ExpandableNotificationRow row) {
                        ExpandableNotificationRow parent = row.getNotificationParent();
                        if (parent != null && parent.areChildrenExpanded()
                                && (parent.areGutsExposed()
                                || mSwipeHelper.getExposedMenuView() == parent
                                || (parent.getAttachedChildren().size() == 1
                                && mDismissibilityProvider.isDismissable(parent.getEntry())))) {
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
                    mView.onSwipeBegin(v);
                }

                @Override
                public void onChildSnappedBack(View animView, float targetLeft) {
                    mView.onSwipeEnd();
                    if (animView instanceof ExpandableNotificationRow row) {
                        if (row.isPinned() && !canChildBeDismissed(row)
                                && row.getEntry().getSbn().getNotification().fullScreenIntent
                                == null) {
                            mHeadsUpManager.removeNotification(
                                    row.getEntry().getSbn().getKey(),
                                    /* removeImmediately= */ true ,
                                    /* reason= */ "onChildSnappedBack"
                            );
                        }
                    }
                }

                @Override
                public boolean updateSwipeProgress(View animView, boolean dismissable,
                        float swipeProgress) {
                    // Returning true prevents alpha fading.
                    return false;
                }

                @Override
                public float getFalsingThresholdFactor() {
                    return ShadeViewController.getFalsingThresholdFactor(
                            mPowerInteractor.getDetailedWakefulness().getValue());
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
                    SceneContainerFlag.assertInLegacyMode();
                    mView.setInHeadsUpPinnedMode(inPinnedMode);
                }

                @Override
                public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
                    SceneContainerFlag.assertInLegacyMode();
                    NotificationEntry topEntry = mHeadsUpManager.getTopEntry();
                    mView.setTopHeadsUpRow(topEntry != null ? topEntry.getRow() : null);
                    generateHeadsUpAnimation(entry, isHeadsUp);
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
            NotificationStackScrollLayout view,
            @Named(ALLOW_NOTIFICATION_LONG_PRESS_NAME) boolean allowLongPress,
            NotificationGutsManager notificationGutsManager,
            NotificationsController notificationsController,
            NotificationVisibilityProvider visibilityProvider,
            NotificationWakeUpCoordinator wakeUpCoordinator,
            HeadsUpManager headsUpManager,
            Provider<IStatusBarService> statusBarService,
            NotificationRoundnessManager notificationRoundnessManager,
            TunerService tunerService,
            DeviceProvisionedController deviceProvisionedController,
            DynamicPrivacyController dynamicPrivacyController,
            ConfigurationController configurationController,
            SysuiStatusBarStateController statusBarStateController,
            KeyguardMediaController keyguardMediaController,
            KeyguardBypassController keyguardBypassController,
            PowerInteractor powerInteractor,
            PrimaryBouncerInteractor primaryBouncerInteractor,
            KeyguardTransitionRepository keyguardTransitionRepo,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager lockscreenUserManager,
            MetricsLogger metricsLogger,
            ColorUpdateLogger colorUpdateLogger,
            DumpManager dumpManager,
            FalsingCollector falsingCollector,
            FalsingManager falsingManager,
            NotificationSwipeHelper.Builder notificationSwipeHelperBuilder,
            GroupExpansionManager groupManager,
            @SilentHeader SectionHeaderController silentHeaderController,
            NotifPipeline notifPipeline,
            NotifCollection notifCollection,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            UiEventLogger uiEventLogger,
            NotificationRemoteInputManager remoteInputManager,
            VisibilityLocationProviderDelegator visibilityLocationProviderDelegator,
            SeenNotificationsInteractor seenNotificationsInteractor,
            NotificationListViewBinder viewBinder,
            ShadeController shadeController,
            Provider<WindowRootView> windowRootView,
            InteractionJankMonitor jankMonitor,
            StackStateLogger stackLogger,
            NotificationStackScrollLogger logger,
            NotificationStackSizeCalculator notificationStackSizeCalculator,
            NotificationTargetsHelper notificationTargetsHelper,
            SecureSettings secureSettings,
            NotificationDismissibilityProvider dismissibilityProvider,
            ActivityStarter activityStarter,
            SplitShadeStateController splitShadeStateController,
            SensitiveNotificationProtectionController sensitiveNotificationProtectionController,
            WallpaperInteractor wallpaperInteractor) {
        mView = view;
        mKeyguardTransitionRepo = keyguardTransitionRepo;
        mViewBinder = viewBinder;
        mStackStateLogger = stackLogger;
        mLogger = logger;
        mAllowLongPress = allowLongPress;
        mNotificationGutsManager = notificationGutsManager;
        mNotificationsController = notificationsController;
        mVisibilityProvider = visibilityProvider;
        mWakeUpCoordinator = wakeUpCoordinator;
        mHeadsUpManager = headsUpManager;
        if (SceneContainerFlag.isEnabled()) {
            mHeadsUpTouchHelper = new HeadsUpTouchHelper(
                    mHeadsUpManager,
                    statusBarService.get(),
                    getHeadsUpCallback(),
                    getHeadsUpNotificationViewController()
            );
        }
        mNotificationRoundnessManager = notificationRoundnessManager;
        mTunerService = tunerService;
        mDeviceProvisionedController = deviceProvisionedController;
        mDynamicPrivacyController = dynamicPrivacyController;
        mConfigurationController = configurationController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardMediaController = keyguardMediaController;
        mKeyguardBypassController = keyguardBypassController;
        mPowerInteractor = powerInteractor;
        mPrimaryBouncerInteractor = primaryBouncerInteractor;
        mZenModeController = zenModeController;
        mLockscreenUserManager = lockscreenUserManager;
        mMetricsLogger = metricsLogger;
        mColorUpdateLogger = colorUpdateLogger;
        mDumpManager = dumpManager;
        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        mFalsingCollector = falsingCollector;
        mFalsingManager = falsingManager;
        mNotificationSwipeHelperBuilder = notificationSwipeHelperBuilder;
        mJankMonitor = jankMonitor;
        mNotificationStackSizeCalculator = notificationStackSizeCalculator;
        mGroupExpansionManager = groupManager;
        mSilentHeaderController = silentHeaderController;
        mNotifPipeline = notifPipeline;
        mNotifCollection = notifCollection;
        mUiEventLogger = uiEventLogger;
        mRemoteInputManager = remoteInputManager;
        mVisibilityLocationProviderDelegator = visibilityLocationProviderDelegator;
        mSeenNotificationsInteractor = seenNotificationsInteractor;
        mShadeController = shadeController;
        mWindowRootView = windowRootView;
        mNotificationTargetsHelper = notificationTargetsHelper;
        mSecureSettings = secureSettings;
        mDismissibilityProvider = dismissibilityProvider;
        mActivityStarter = activityStarter;
        mSensitiveNotificationProtectionController = sensitiveNotificationProtectionController;
        mWallpaperInteractor = wallpaperInteractor;
        mView.passSplitShadeStateController(splitShadeStateController);
        if (SceneContainerFlag.isEnabled()) {
            mWakeUpCoordinator.setStackScroller(this);
        }
        mDumpManager.registerDumpable(this);
        updateResources();
        setUpView();
    }

    private void setUpView() {
        mView.setStackStateLogger(mStackStateLogger);
        mView.setController(this);
        mView.setLogger(mLogger);
        mTouchHandler = new TouchHandler();
        mView.setTouchHandler(mTouchHandler);
        mView.setResetUserExpandedStatesRunnable(mNotificationsController::resetUserExpandedStates);
        mView.setActivityStarter(mActivityStarter);
        mView.setClearAllAnimationListener(this::onAnimationEnd);
        mView.setClearAllListener((selection) -> mUiEventLogger.log(
                NotificationPanelEvent.fromSelection(selection)));
        if (!FooterViewRefactor.isEnabled()) {
            mView.setFooterClearAllListener(() ->
                    mMetricsLogger.action(MetricsEvent.ACTION_DISMISS_ALL_NOTES));
            mView.setIsRemoteInputActive(mRemoteInputManager.isRemoteInputActive());
            mRemoteInputManager.addControllerCallback(new RemoteInputController.Callback() {
                @Override
                public void onRemoteInputActive(boolean active) {
                    mView.setIsRemoteInputActive(active);
                }
            });
        }
        mView.setClearAllFinishedWhilePanelExpandedRunnable(()-> {
            final Runnable doCollapseRunnable = () ->
                    mShadeController.animateCollapseShade(CommandQueue.FLAG_EXCLUDE_NONE);
            mView.postDelayed(doCollapseRunnable, /* delayMillis = */ DELAY_BEFORE_SHADE_CLOSE);
        });
        mDumpManager.registerDumpable(mView);

        mKeyguardBypassController.registerOnBypassStateChangedListener(
                isEnabled -> mNotificationRoundnessManager.setShouldRoundPulsingViews(!isEnabled));
        mNotificationRoundnessManager.setShouldRoundPulsingViews(
                !mKeyguardBypassController.getBypassEnabled());

        mSwipeHelper = mNotificationSwipeHelperBuilder
                .setNotificationCallback(mNotificationCallback)
                .setOnMenuEventListener(mMenuEventListener)
                .build();

        mNotifPipeline.addCollectionListener(new NotifCollectionListener() {
            @Override
            public void onEntryUpdated(NotificationEntry entry) {
                mView.onEntryUpdated(entry);
            }
        });

        mView.initView(mView.getContext(), mSwipeHelper, mNotificationStackSizeCalculator);
        mView.setKeyguardBypassEnabled(mKeyguardBypassController.getBypassEnabled());
        mKeyguardBypassController
                .registerOnBypassStateChangedListener(mView::setKeyguardBypassEnabled);
        if (!FooterViewRefactor.isEnabled()) {
            mView.setManageButtonClickListener(v -> {
                if (mNotificationActivityStarter != null) {
                    mNotificationActivityStarter.startHistoryIntent(v, mView.isHistoryShown());
                }
            });
        }

        if (!SceneContainerFlag.isEnabled()) {
            mHeadsUpManager.addListener(mOnHeadsUpChangedListener);
        }
        mHeadsUpManager.setAnimationStateHandler(mView::setHeadsUpGoingAwayAnimationsAllowed);
        mDynamicPrivacyController.addListener(mDynamicPrivacyControllerListener);

        mLockscreenShadeTransitionController.setStackScroller(this);

        mLockscreenUserManager.addUserChangedListener(mLockscreenUserChangeListener);

        mVisibilityLocationProviderDelegator.setDelegate(this::isInVisibleLocation);

        mTunerService.addTunable(
                (key, newValue) -> {
                    switch (key) {
                        case Settings.Secure.NOTIFICATION_HISTORY_ENABLED:
                            mHistoryEnabled = null;  // invalidate
                            if (!FooterViewRefactor.isEnabled()) {
                                updateFooter();
                            }
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
            return kotlin.Unit.INSTANCE;
        });

        if (!FooterViewRefactor.isEnabled()) {
            // attach callback, and then call it to update mView immediately
            mDeviceProvisionedController.addCallback(mDeviceProvisionedListener);
            mDeviceProvisionedListener.onDeviceProvisionedChanged();
        }

        if (screenshareNotificationHiding()) {
            mSensitiveNotificationProtectionController
                    .registerSensitiveStateListener(mSensitiveStateChangedListener);
        }

        if (mView.isAttachedToWindow()) {
            mOnAttachStateChangeListener.onViewAttachedToWindow(mView);
        }
        mView.addOnAttachStateChangeListener(mOnAttachStateChangeListener);
        if (!FooterViewRefactor.isEnabled()) {
            mSilentHeaderController.setOnClearSectionClickListener(v -> clearSilentNotifications());
        }

        mGroupExpansionManager.registerGroupExpansionChangeListener(
                (changedRow, expanded) -> mView.onGroupExpandChanged(changedRow, expanded));

        mViewBinder.bindWhileAttached(mView, this);

        if (!FooterViewRefactor.isEnabled()) {
            collectFlow(mView, mKeyguardTransitionRepo.getTransitions(),
                    this::onKeyguardTransitionChanged);
        }

        mView.setWallpaperInteractor(mWallpaperInteractor);
    }

    private boolean isInVisibleLocation(NotificationEntry entry) {
        ExpandableNotificationRow row = entry.getRow();
        if (row == null) {
            return false;
        }

        ExpandableViewState childViewState = row.getViewState();
        if ((childViewState.location & ExpandableViewState.VISIBLE_LOCATIONS) == 0) {
            return false;
        }

        return row.getVisibility() == View.VISIBLE;
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

    public float getAppearFraction() {
        return mView.getAppearFraction();
    }

    public float getExpandedHeight() {
        return mView.getExpandedHeight();
    }

    public void requestLayout() {
        mView.requestLayout();
    }

    public void addOneShotPreDrawListener(Runnable runnable) {
        OneShotPreDrawListener.add(mView, runnable);
    }

    public Display getDisplay() {
        return mView.getDisplay();
    }

    public WindowInsets getRootWindowInsets() {
        return mView.getRootWindowInsets();
    }

    public int getRight() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getRight();
    }

    public boolean isLayoutRtl() {
        return mView.isLayoutRtl();
    }

    /**
     * @return the left of the view.
     */
    public int getLeft() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getLeft();
    }

    /**
     * @return the top of the view.
     */
    public int getTop() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getTop();
    }

    /**
     * @return the bottom of the view.
     */
    public int getBottom() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getBottom();
    }

    public float getTranslationX() {
        return mView.getTranslationX();
    }

    /** Set view y-translation */
    public void setTranslationY(float translationY) {
        mView.setTranslationY(translationY);
    }

    /** Set view x-translation */
    public void setTranslationX(float translationX) {
        mView.setTranslationX(translationX);
    }

    public int indexOfChild(View view) {
        return mView.indexOfChild(view);
    }

    public void setOnHeightChangedListener(
            ExpandableView.OnHeightChangedListener listener) {
        mView.setOnHeightChangedListener(listener);
    }

    /**
     * Invoked in addition to {@see #setOnHeightChangedListener}
     */
    public void setOnHeightChangedRunnable(Runnable r) {
        mView.setOnHeightChangedRunnable(r);
    }

    public void setOverscrollTopChangedListener(
            OnOverscrollTopChangedListener listener) {
        SceneContainerFlag.assertInLegacyMode();
        mView.setOverscrollTopChangedListener(listener);
    }

    public void setOnEmptySpaceClickListener(
            OnEmptySpaceClickListener listener) {
        SceneContainerFlag.assertInLegacyMode();
        mView.setOnEmptySpaceClickListener(listener);
    }

    public void setTrackingHeadsUp(ExpandableNotificationRow expandableNotificationRow) {
        mView.setTrackingHeadsUp(expandableNotificationRow);
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
        SceneContainerFlag.assertInLegacyMode();
        return mView != null && mView.isAddOrRemoveAnimationPending();
    }

    public int getVisibleNotificationCount() {
        FooterViewRefactor.assertInLegacyMode();
        return mNotifStats.getNumActiveNotifs();
    }

    public boolean isHistoryEnabled() {
        Boolean historyEnabled = mHistoryEnabled;
        if (historyEnabled == null) {
            if (mView == null || mView.getContext() == null) {
                Log.wtf(TAG, "isHistoryEnabled failed to initialize its value");
                return false;
            }
            mHistoryEnabled = historyEnabled = mSecureSettings.getIntForUser(
                    Settings.Secure.NOTIFICATION_HISTORY_ENABLED,
                    0,
                    UserHandle.USER_CURRENT) == 1;
        }
        return historyEnabled;
    }

    public int getIntrinsicContentHeight() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getIntrinsicContentHeight();
    }

    /**
     * Dispatch a touch to the scene container framework.
     * TODO(b/316965302): Replace findViewById to avoid DFS
     */
    public void sendTouchToSceneFramework(MotionEvent ev) {
        View sceneContainer = mWindowRootView.get()
                .findViewById(R.id.scene_container_root_composable);
        if (sceneContainer != null) {
            sceneContainer.dispatchTouchEvent(ev);
        }
    }

    public void setIntrinsicPadding(int intrinsicPadding) {
        SceneContainerFlag.assertInLegacyMode();
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
        SceneContainerFlag.assertInLegacyMode();
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
        SceneContainerFlag.assertInLegacyMode();
        return mView.getX();
    }

    public boolean isBelowLastNotification(float x, float y) {
        SceneContainerFlag.assertInLegacyMode();
        return mView.isBelowLastNotification(x, y);
    }

    public float getWidth() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getWidth();
    }

    public float getOpeningHeight() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getOpeningHeight();
    }

    public float getBottomMostNotificationBottom() {
        SceneContainerFlag.assertInLegacyMode();
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

    public void setQsFullScreen(boolean fullScreen) {
        mView.setQsFullScreen(fullScreen);
        if (!FooterViewRefactor.isEnabled()) {
            updateShowEmptyShadeView();
        }
    }

    public void setScrollingEnabled(boolean enabled) {
        SceneContainerFlag.assertInLegacyMode();
        mView.setScrollingEnabled(enabled);
    }

    public void setQsExpansionFraction(float expansionFraction) {
        SceneContainerFlag.assertInLegacyMode();
        mView.setQsExpansionFraction(expansionFraction);
    }

    public void setOnStackYChanged(Consumer<Boolean> onStackYChanged) {
        SceneContainerFlag.assertInLegacyMode();
        mView.setOnStackYChanged(onStackYChanged);
    }

    public float getNotificationSquishinessFraction() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getNotificationSquishinessFraction();
    }

    public float calculateAppearFractionBypass() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.calculateAppearFractionBypass();
    }

    public void updateTopPadding(float qsHeight, boolean animate) {
        SceneContainerFlag.assertInLegacyMode();
        mView.updateTopPadding(qsHeight, animate);
    }

    public boolean isScrolledToBottom() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.isScrolledToBottom();
    }

    public int getNotGoneChildCount() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getNotGoneChildCount();
    }

    public float getIntrinsicPadding() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getIntrinsicPadding();
    }

    public float getLayoutMinHeight() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getLayoutMinHeight();
    }

    public int getEmptyBottomMargin() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getEmptyBottomMargin();
    }

    public float getTopPaddingOverflow() {
        return mView.getTopPaddingOverflow();
    }

    public int getTopPadding() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getTopPadding();
    }

    public float getEmptyShadeViewHeight() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getEmptyShadeViewHeight();
    }

    /** Set the max alpha for keyguard */
    public void setMaxAlphaForKeyguard(float alpha, String source) {
        mMaxAlphaForKeyguard = alpha;
        mMaxAlphaForKeyguardSource = source;
        updateAlpha();
        if (DEBUG) {
            Log.d(TAG, "setMaxAlphaForKeyguard=" + alpha + " --- from: " + source);
        }
    }

    private void setMaxAlphaForUnhide(float alpha) {
        mMaxAlphaForUnhide = alpha;
        updateAlpha();
    }

    /**
     * Sets the max alpha value for notifications when idle on the glanceable hub or when
     * transitioning to/from the glanceable hub.
     */
    public void setMaxAlphaForGlanceableHub(float alpha) {
        mMaxAlphaForGlanceableHub = alpha;
        updateAlpha();
    }

    /**
     * Max alpha from the containing view. Used by brightness slider as an example.
     */
    public void setMaxAlphaFromView(float alpha) {
        mMaxAlphaFromView = alpha;
        updateAlpha();
    }

    private void updateAlpha() {
        if (mView != null) {
            mView.setAlpha(Math.min(Math.min(mMaxAlphaFromView, mMaxAlphaForKeyguard),
                    Math.min(mMaxAlphaForUnhide, mMaxAlphaForGlanceableHub)));
        }
    }

    public float getAlpha() {
        return mView.getAlpha();
    }

    public void setSuppressChildrenMeasureAndLayout(boolean suppressLayout) {
        mView.suppressChildrenMeasureAndLayout(suppressLayout);
    }

    public void updateNotificationsContainerVisibility(boolean visible, boolean animate) {
        if (mHideAlphaAnimator != null) {
            mHideAlphaAnimator.cancel();
        }

        final float targetAlpha = visible ? 1f : 0f;

        if (animate) {
            mHideAlphaAnimator = createAlphaAnimator(targetAlpha);
            mHideAlphaAnimator.start();
        } else {
            HIDE_ALPHA_PROPERTY.set(this, targetAlpha);
        }
    }

    private ObjectAnimator createAlphaAnimator(float targetAlpha) {
        final ObjectAnimator objectAnimator = ObjectAnimator
                .ofFloat(this, HIDE_ALPHA_PROPERTY, targetAlpha);
        objectAnimator.setInterpolator(STANDARD);
        objectAnimator.setDuration(ANIMATION_DURATION_STANDARD);
        return objectAnimator;
    }

    public float calculateAppearFraction(float height) {
        SceneContainerFlag.assertInLegacyMode();
        return mView.calculateAppearFraction(height);
    }

    public void onExpansionStarted() {
        SceneContainerFlag.assertInLegacyMode();
        mView.onExpansionStarted();
        checkSnoozeLeavebehind();
    }

    public void onExpansionStopped() {
        SceneContainerFlag.assertInLegacyMode();
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
        SceneContainerFlag.assertInLegacyMode();
        mView.setHeadsUpBoundaries(height, bottomBarHeight);
    }

    public void setPanelFlinging(boolean flinging) {
        SceneContainerFlag.assertInLegacyMode();
        mView.setPanelFlinging(flinging);
    }

    /**
     * Set the visibility of the view, and propagate it to specific children.
     *
     * @param visible either the view is visible or not.
     */
    public void updateVisibility(boolean visible) {
        mView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);

        // Refactor note: the empty shade's visibility doesn't seem to actually depend on the
        // parent visibility (so this update seemingly doesn't do anything). Therefore, this is not
        // modeled in the refactored code.
        if (!FooterViewRefactor.isEnabled() && mView.getVisibility() == View.VISIBLE) {
            // Synchronize EmptyShadeView visibility with the parent container.
            updateShowEmptyShadeView();
            updateImportantForAccessibility();
        }
    }

    /**
     * Update whether we should show the empty shade view ("no notifications" in the shade).
     * <p>
     * When in split mode, notifications are always visible regardless of the state of the
     * QuickSettings panel. That being the case, empty view is always shown if the other conditions
     * are true.
     */
    public void updateShowEmptyShadeView() {
        FooterViewRefactor.assertInLegacyMode();

        Trace.beginSection("NSSLC.updateShowEmptyShadeView");

        final boolean shouldShow = getVisibleNotificationCount() == 0
                && !mView.isQsFullScreen()
                // Hide empty shade view when in transition to AOD.
                // That avoids "No Notifications" to blink when transitioning to AOD.
                // For more details, see: b/228790482
                && !mIsInTransitionToAod
                // Don't show any notification content if the bouncer is showing. See b/267060171.
                && !mPrimaryBouncerInteractor.isBouncerShowing();

        mView.updateEmptyShadeView(shouldShow, mZenModeController.areNotificationsHiddenInShade());

        Trace.endSection();
    }

    /**
     * Update the importantForAccessibility of NotificationStackScrollLayout.
     * <p>
     * We want the NSSL to be unimportant for accessibility when there's no
     * notifications in it while the device is on lock screen, to avoid unlablel NSSL view.
     * Otherwise, we want it to be important for accessibility to enable accessibility
     * auto-scrolling in NSSL.
     */
    public void updateImportantForAccessibility() {
        FooterViewRefactor.assertInLegacyMode();
        if (getVisibleNotificationCount() == 0 && mView.onKeyguard()) {
            mView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        } else {
            mView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
    }

    public boolean isShowingEmptyShadeView() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.isEmptyShadeViewVisible();
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        SceneContainerFlag.assertInLegacyMode();
        mView.setHeadsUpAnimatingAway(headsUpAnimatingAway);
    }

    public HeadsUpTouchHelper.Callback getHeadsUpCallback() {
        return mView.getHeadsUpCallback();
    }

    public void forceNoOverlappingRendering(boolean force) {
        mView.forceNoOverlappingRendering(force);
    }

    public void setExpandingVelocity(float velocity) {
        mView.setExpandingVelocity(velocity);
    }

    public void setExpandedHeight(float expandedHeight) {
        SceneContainerFlag.assertInLegacyMode();
        mView.setExpandedHeight(expandedHeight);
    }

    /**
     * Sets the QS header. Used to check if a touch is within its bounds.
     */
    public void setQsHeader(ViewGroup view) {
        QSComposeFragment.assertInLegacyMode();
        mView.setQsHeader(view);
    }

    public void setQsHeaderBoundsProvider(QSHeaderBoundsProvider qsHeaderBoundsProvider) {
        QSComposeFragment.isUnexpectedlyInLegacyMode();
        mView.setQsHeaderBoundsProvider(qsHeaderBoundsProvider);
    }

    public void setAnimationsEnabled(boolean enabled) {
        mView.setAnimationsEnabled(enabled);
    }

    public void setDozing(boolean dozing, boolean animate) {
        SceneContainerFlag.assertInLegacyMode();
        mView.setDozing(dozing);
    }

    public void setPulsing(boolean pulsing, boolean animatePulse) {
        mView.setPulsing(pulsing, animatePulse);
    }

    /**
     * Return whether there are any clearable notifications
     */
    public boolean hasActiveClearableNotifications(@SelectedRows int selection) {
        FooterViewRefactor.assertInLegacyMode();
        return hasNotifications(selection, true /* clearable */);
    }

    public boolean hasNotifications(@SelectedRows int selection, boolean isClearable) {
        FooterViewRefactor.assertInLegacyMode();
        boolean hasAlertingMatchingClearable = isClearable
                ? mNotifStats.getHasClearableAlertingNotifs()
                : mNotifStats.getHasNonClearableAlertingNotifs();
        boolean hasSilentMatchingClearable = isClearable
                ? mNotifStats.getHasClearableSilentNotifs()
                : mNotifStats.getHasNonClearableSilentNotifs();
        switch (selection) {
            case ROWS_GENTLE:
                return hasSilentMatchingClearable;
            case ROWS_HIGH_PRIORITY:
                return hasAlertingMatchingClearable;
            case ROWS_ALL:
                return hasSilentMatchingClearable || hasAlertingMatchingClearable;
            default:
                throw new IllegalStateException("Bad selection: " + selection);
        }
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
    public void setKeyguardBottomPaddingForDebug(float keyguardBottomPadding) {
        mView.setKeyguardBottomPadding(keyguardBottomPadding);
    }

    public RemoteInputController.Delegate createDelegate() {
        return new RemoteInputController.Delegate() {
            public void setRemoteInputActive(NotificationEntry entry,
                    boolean remoteInputActive) {
                if (SceneContainerFlag.isEnabled()) {
                    sendRemoteInputRowBottomBound(entry, remoteInputActive);
                }
                mHeadsUpManager.setRemoteInputActive(entry, remoteInputActive);
                entry.notifyHeightChanged(true /* needsAnimation */);
                if (!FooterViewRefactor.isEnabled()) {
                    updateFooter();
                }
            }

            public void lockScrollTo(NotificationEntry entry) {
                mView.lockScrollTo(entry.getRow());
            }

            public void requestDisallowLongPressAndDismiss() {
                mView.requestDisallowLongPress();
                mView.requestDisallowDismiss();
            }

            private void sendRemoteInputRowBottomBound(NotificationEntry entry,
                    boolean remoteInputActive) {
                ExpandableNotificationRow row = entry.getRow();
                float top = row.getTranslationY();
                int height = row.getActualHeight();
                float bottom = top + height + row.getRemoteInputActionsContainerExpandedOffset();
                mView.sendRemoteInputRowBottomBound(remoteInputActive ? bottom : null);
            }
        };
    }

    public void updateFooter() {
        FooterViewRefactor.assertInLegacyMode();
        Trace.beginSection("NSSLC.updateFooter");
        mView.updateFooter();
        Trace.endSection();
    }

    public void onUpdateRowStates() {
        mView.onUpdateRowStates();
    }

    public void runAfterAnimationFinished(Runnable r) {
        mView.runAfterAnimationFinished(r);
    }

    public ExpandableView getFirstChildNotGone() {
        SceneContainerFlag.assertInLegacyMode();
        return mView.getFirstChildNotGone();
    }

    public void generateHeadsUpAnimation(NotificationEntry entry, boolean isHeadsUp) {
        mView.generateHeadsUpAnimation(entry, isHeadsUp);
    }

    public void setMaxTopPadding(int padding) {
        SceneContainerFlag.assertInLegacyMode();
        mView.setMaxTopPadding(padding);
    }

    public int getTransientViewCount() {
        return mView.getTransientViewCount();
    }

    public View getTransientView(int i) {
        return mView.getTransientView(i);
    }

    public NotificationStackScrollLayout getView() {
        return mView;
    }

    public float calculateGapHeight(ExpandableView previousView, ExpandableView child, int count) {
        return mView.calculateGapHeight(previousView, child, count);
    }

    NotificationRoundnessManager getNotificationRoundnessManager() {
        return mNotificationRoundnessManager;
    }

    public NotificationListContainer getNotificationListContainer() {
        return mNotificationListContainer;
    }

    public NotifStackController getNotifStackController() {
        return mNotifStackController;
    }

    public void resetCheckSnoozeLeavebehind() {
        mView.resetCheckSnoozeLeavebehind();
    }

    private DismissedByUserStats getDismissedByUserStats(NotificationEntry entry) {
        return new DismissedByUserStats(
                DISMISSAL_SHADE,
                DISMISS_SENTIMENT_NEUTRAL,
                mVisibilityProvider.obtain(entry, true));
    }

    private View getGutsView() {
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
        return view;
    }

    public void closeControlsIfOutsideTouch(MotionEvent ev) {
        SceneContainerFlag.assertInLegacyMode();
        View view = getGutsView();
        if (view != null && !NotificationSwipeHelper.isTouchInView(ev, view)) {
            // Touch was outside visible guts / menu notification, close what's visible
            closeAndSaveGuts();
        }
    }

    void closeControlsDueToOutsideTouch() {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return;
        closeAndSaveGuts();
    }

    private void closeAndSaveGuts() {
        mNotificationGutsManager.closeAndSaveGuts(false /* removeLeavebehind */,
                false /* force */, true /* removeControls */, -1 /* x */, -1 /* y */,
                false /* resetMenu */);
        mSwipeHelper.resetExposedMenuView(true /* animate */, true /* force */);
    }

    boolean isTouchInGutsView(MotionEvent event) {
        View view = getGutsView();
        return NotificationSwipeHelper.isTouchInView(event, view);
    }

    public void clearSilentNotifications() {
        FooterViewRefactor.assertInLegacyMode();
        // Leave the shade open if there will be other notifs left over to clear
        final boolean closeShade = !hasActiveClearableNotifications(ROWS_HIGH_PRIORITY);
        mView.clearNotifications(ROWS_GENTLE, closeShade);
    }

    private void onAnimationEnd(List<ExpandableNotificationRow> viewsToRemove,
            @SelectedRows int selectedRows) {
        if (selectedRows == ROWS_ALL) {
            mNotifCollection.dismissAllNotifications(
                    mLockscreenUserManager.getCurrentUserId());
        } else {
            final List<Pair<NotificationEntry, DismissedByUserStats>>
                    entriesWithRowsDismissedFromShade = new ArrayList<>();
            for (ExpandableNotificationRow row : viewsToRemove) {
                final NotificationEntry entry = row.getEntry();
                entriesWithRowsDismissedFromShade.add(
                        new Pair<>(entry, getDismissedByUserStats(entry)));
            }
            mNotifCollection.dismissNotifications(entriesWithRowsDismissedFromShade);
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
        SceneContainerFlag.assertInLegacyMode();
        return mLongPressedView != null;
    }

    /**
     * @return the inset during the full shade transition, that needs to be added to the position
     * of the quick settings edge. This is relevant for media, that is transitioning
     * from the keyguard host to the quick settings one.
     */
    public int getFullShadeTransitionInset() {
        SceneContainerFlag.assertInLegacyMode();
        MediaContainerView view = mKeyguardMediaController.getSinglePaneContainer();
        if (view == null || view.getHeight() == 0
                || mStatusBarStateController.getState() != KEYGUARD) {
            return 0;
        }
        return view.getHeight() + mView.getPaddingAfterMedia();
    }

    /**
     * @param fraction The fraction of lockscreen to shade transition.
     *                 0f for all other states.
     *                 <p>
     *                 Once the lockscreen to shade transition completes and the shade is 100% open,
     *                 LockscreenShadeTransitionController resets amount and fraction to 0, where
     *                 they remain until the next lockscreen-to-shade transition.
     */
    public void setTransitionToFullShadeAmount(float fraction) {
        mView.setFractionToShade(fraction);
    }

    /**
     * Sets the amount of vertical over scroll that should be performed on NSSL.
     */
    public void setOverScrollAmount(int overScrollAmount) {
        mView.setExtraTopInsetForFullShadeTransition(overScrollAmount);
    }

    /**
     *
     */
    public void setWillExpand(boolean willExpand) {
        mView.setWillExpand(willExpand);
    }

    /**
     * Set a listener to when scrolling changes.
     */
    public void setOnScrollListener(Consumer<Integer> listener) {
        SceneContainerFlag.assertInLegacyMode();
        mView.setOnScrollListener(listener);
    }

    /**
     * Set rounded rect clipping bounds on this view.
     */
    public void setRoundedClippingBounds(int left, int top, int right, int bottom, int topRadius,
            int bottomRadius) {
        SceneContainerFlag.assertInLegacyMode();
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

    public NotificationTargetsHelper getNotificationTargetsHelper() {
        return mNotificationTargetsHelper;
    }

    public void setShelf(NotificationShelf shelf) {
        mView.setShelf(shelf);
    }

    public int getShelfHeight() {
        ExpandableView shelf = mView.getShelf();
        return shelf == null ? 0 : shelf.getIntrinsicHeight();
    }

    @VisibleForTesting
    void onKeyguardTransitionChanged(TransitionStep transitionStep) {
        FooterViewRefactor.assertInLegacyMode();
        boolean isTransitionToAod = transitionStep.getTo().equals(KeyguardState.AOD)
                && (transitionStep.getFrom().equals(KeyguardState.GONE)
                || transitionStep.getFrom().equals(KeyguardState.OCCLUDED));
        if (mIsInTransitionToAod != isTransitionToAod) {
            mIsInTransitionToAod = isTransitionToAod;
            updateShowEmptyShadeView();
        }
    }

    @VisibleForTesting
    TouchHandler getTouchHandler() {
        return mTouchHandler;
    }

    private HeadsUpNotificationViewController getHeadsUpNotificationViewController() {
        HeadsUpNotificationViewController headsUpViewController;
        if (SceneContainerFlag.isEnabled()) {
            headsUpViewController = new HeadsUpNotificationViewController() {
                @Override
                public void setHeadsUpDraggingStartingHeight(int startHeight) {
                    // do nothing
                }

                @Override
                public void setTrackedHeadsUp(ExpandableNotificationRow expandableNotificationRow) {
                    setTrackingHeadsUp(expandableNotificationRow);
                }

                @Override
                public void startExpand(float newX, float newY, boolean startTracking,
                        float expandedHeight) {
                    // do nothing
                }
            };
        } else {
            headsUpViewController = new HeadsUpNotificationViewControllerEmptyImpl();
        }
        return headsUpViewController;
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mMaxAlphaFromView=" + mMaxAlphaFromView);
        pw.println("mMaxAlphaForUnhide=" + mMaxAlphaForUnhide);
        pw.println("mMaxAlphaForGlanceableHub=" + mMaxAlphaForGlanceableHub);
        pw.println("mMaxAlphaForKeyguard=" + mMaxAlphaForKeyguard);
        pw.println("mMaxAlphaForKeyguardSource=" + mMaxAlphaForKeyguardSource);
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

        @Override
        public int getId() {
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

    private class NotificationListContainerImpl implements NotificationListContainer,
            PipelineDumpable {

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
        public int getContainerChildCount() {
            return mView.getContainerChildCount();
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
                NotificationEntry entry = row.getEntry();
                mHeadsUpAppearanceController.updateHeader(entry);
                mHeadsUpAppearanceController.updateHeadsUpAndPulsingRoundness(entry);
                if (GroupHunAnimationFix.isEnabled() && !animatingAway) {
                    // invalidate list to make sure the row is sorted to the correct section
                    mHeadsUpManager.onEntryAnimatingAwayEnded(entry);
                }
            });
        }

        @Override
        public void applyLaunchAnimationParams(LaunchAnimationParameters params) {
            mView.applyLaunchAnimationParams(params);
        }

        @Override
        public void setExpandingNotification(ExpandableNotificationRow row) {
            mView.setExpandingNotification(row);
        }

        @Override
        public void dumpPipeline(@NonNull PipelineDumper d) {
            d.dump("NotificationStackScrollLayoutController.this",
                    NotificationStackScrollLayoutController.this);
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
            boolean hunWantsIt = false;
            if (shouldHeadsUpHandleTouch()) {
                hunWantsIt = mHeadsUpTouchHelper.onInterceptTouchEvent(ev);
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
            if (mJankMonitor != null && scrollWantsIt
                    && ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
                mJankMonitor.begin(mView, CUJ_NOTIFICATION_SHADE_SCROLL_FLING);
            }
            return swipeWantsIt || scrollWantsIt || expandWantsIt || longPressWantsIt || hunWantsIt;
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
                    // We need to dispatch the overscroll differently when Scene Container is on,
                    // since NSSL no longer controls its own scroll.
                    if (SceneContainerFlag.isEnabled() && !isCancelOrUp) {
                        mView.startOverscrollAfterExpanding();
                        return true;
                    } else {
                        mView.dispatchDownEventToScroller(ev);
                    }
                }
            }
            boolean horizontalSwipeWantsIt = false;
            boolean scrollerWantsIt = false;
            // NOTE: the order of these is important. If reversed, onScrollTouch will reset on an
            // UP event, causing horizontalSwipeWantsIt to be set to true on vertical swipes.
            if (mLongPressedView == null && !mView.isBeingDragged()
                    && !expandingNotification
                    && !mView.getExpandedInThisMotion()
                    && !onlyScrollingInThisMotion
                    && !mView.getDisallowDismissInThisMotion()) {
                horizontalSwipeWantsIt = mSwipeHelper.onTouchEvent(ev);
            }
            if (mLongPressedView == null && mView.isExpanded() && !mSwipeHelper.isSwiping()
                    && !expandingNotification && !mView.getDisallowScrollingInThisMotion()) {
                scrollerWantsIt = mView.onScrollTouch(ev);
            }
            boolean hunWantsIt = false;
            if (shouldHeadsUpHandleTouch()) {
                hunWantsIt = mHeadsUpTouchHelper.onTouchEvent(ev);
                if (hunWantsIt) {
                    mView.startDraggingOnHun();
                }
            }

            // Check if we need to clear any snooze leavebehinds
            if (guts != null && !NotificationSwipeHelper.isTouchInView(ev, guts)
                    && guts.getGutsContent() instanceof NotificationSnooze ns) {
                if ((ns.isExpanded() && isCancelOrUp)
                        || (!horizontalSwipeWantsIt && scrollerWantsIt)) {
                    // If the leavebehind is expanded we clear it on the next up event, otherwise we
                    // clear it on the next non-horizontal swipe or expand event.
                    checkSnoozeLeavebehind();
                }
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                // Ensure the falsing manager records the touch. we don't do anything with it
                // at the moment, but it may trigger a global falsing event.
                if (!horizontalSwipeWantsIt) {
                    mFalsingManager.isFalseTouch(Classifier.SHADE_DRAG);
                }
                mView.setCheckForLeaveBehind(true);
            }
            traceJankOnTouchEvent(ev.getActionMasked(), scrollerWantsIt);
            return horizontalSwipeWantsIt || scrollerWantsIt || expandWantsIt || longPressWantsIt
                    || hunWantsIt;
        }

        private void traceJankOnTouchEvent(int action, boolean scrollerWantsIt) {
            if (mJankMonitor == null) {
                Log.w(TAG, "traceJankOnTouchEvent, mJankMonitor is null");
                return;
            }
            // Handle interaction jank monitor cases.
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    if (scrollerWantsIt) {
                        mJankMonitor.begin(mView, CUJ_NOTIFICATION_SHADE_SCROLL_FLING);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (scrollerWantsIt && !mView.isFlingAfterUpEvent()) {
                        mJankMonitor.end(CUJ_NOTIFICATION_SHADE_SCROLL_FLING);
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (scrollerWantsIt) {
                        mJankMonitor.cancel(CUJ_NOTIFICATION_SHADE_SCROLL_FLING);
                    }
                    break;
            }
        }

        private boolean shouldHeadsUpHandleTouch() {
            return SceneContainerFlag.isEnabled() && mLongPressedView == null
                    && !mSwipeHelper.isSwiping();
        }
    }

    private class NotifStackControllerImpl implements NotifStackController {
        @Override
        public void setNotifStats(@NonNull NotifStats notifStats) {
            FooterViewRefactor.assertInLegacyMode();
            mNotifStats = notifStats;

            if (!FooterViewRefactor.isEnabled()) {
                mView.setHasFilteredOutSeenNotifications(
                        mSeenNotificationsInteractor
                                .getHasFilteredOutSeenNotifications().getValue());

                updateFooter();
                updateShowEmptyShadeView();
                updateImportantForAccessibility();
            }
        }
    }
}
