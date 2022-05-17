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

import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;

import static androidx.constraintlayout.widget.ConstraintSet.END;
import static androidx.constraintlayout.widget.ConstraintSet.PARENT_ID;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE;
import static com.android.keyguard.KeyguardClockSwitch.LARGE;
import static com.android.keyguard.KeyguardClockSwitch.SMALL;
import static com.android.systemui.animation.Interpolators.EMPHASIZED_ACCELERATE;
import static com.android.systemui.animation.Interpolators.EMPHASIZED_DECELERATE;
import static com.android.systemui.classifier.Classifier.QS_COLLAPSE;
import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_FOLD_TO_AOD;
import static com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManagerKt.STATE_CLOSED;
import static com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManagerKt.STATE_OPEN;
import static com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManagerKt.STATE_OPENING;
import static com.android.systemui.util.DumpUtilsKt.asIndenting;

import static java.lang.Float.isNaN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.Fragment;
import android.app.StatusBarManager;
import android.content.ContentResolver;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Trace;
import android.os.UserManager;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.MathUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.policy.SystemBarUtils;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.ActiveUnlockConfig;
import com.android.keyguard.KeyguardStatusView;
import com.android.keyguard.KeyguardStatusViewController;
import com.android.keyguard.KeyguardUnfoldTransition;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.LockIconViewController;
import com.android.keyguard.dagger.KeyguardQsUserSwitchComponent;
import com.android.keyguard.dagger.KeyguardStatusBarViewComponent;
import com.android.keyguard.dagger.KeyguardStatusViewComponent;
import com.android.keyguard.dagger.KeyguardUserSwitcherComponent;
import com.android.systemui.DejankUtils;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.animation.LaunchAnimator;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.controls.dagger.ControlsComponent;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.dump.DumpsysTableLogger;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.media.KeyguardMediaController;
import com.android.systemui.media.MediaDataManager;
import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.FalsingManager.FalsingTapListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.qrcodescanner.controller.QRCodeScannerController;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.QsFrameTranslateController;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.events.PrivacyDotViewController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.ConversationNotificationManager;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.ViewGroupFadeHelper;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.render.ShadeViewManager;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger.LockscreenUiEvent;
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.phone.panelstate.PanelState;
import com.android.systemui.statusbar.phone.shade.transition.ShadeTransitionController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardQsUserSwitchController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcherController;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcherView;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.unfold.SysUIUnfoldComponent;
import com.android.systemui.util.LargeScreenUtils;
import com.android.systemui.util.ListenerSet;
import com.android.systemui.util.Utils;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.wallet.controller.QuickAccessWalletController;
import com.android.wm.shell.animation.FlingAnimationUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Provider;

@CentralSurfacesComponent.CentralSurfacesScope
public class NotificationPanelViewController extends PanelViewController {

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * The parallax amount of the quick settings translation when dragging down the panel
     */
    private static final float QS_PARALLAX_AMOUNT = 0.175f;

    /**
     * Fling expanding QS.
     */
    private static final int FLING_EXPAND = 0;

    /**
     * Fling collapsing QS, potentially stopping when QS becomes QQS.
     */
    private static final int FLING_COLLAPSE = 1;

    /**
     * Fling until QS is completely hidden.
     */
    private static final int FLING_HIDE = 2;
    private static final long ANIMATION_DELAY_ICON_FADE_IN =
            ActivityLaunchAnimator.TIMINGS.getTotalDuration()
                    - CollapsedStatusBarFragment.FADE_IN_DURATION
                    - CollapsedStatusBarFragment.FADE_IN_DELAY - 48;

    private final DozeParameters mDozeParameters;
    private final OnHeightChangedListener mOnHeightChangedListener = new OnHeightChangedListener();
    private final Runnable mCollapseExpandAction = new CollapseExpandAction();
    private final OnOverscrollTopChangedListener
            mOnOverscrollTopChangedListener =
            new OnOverscrollTopChangedListener();
    private final KeyguardAffordanceHelperCallback
            mKeyguardAffordanceHelperCallback =
            new KeyguardAffordanceHelperCallback();
    private final OnEmptySpaceClickListener
            mOnEmptySpaceClickListener =
            new OnEmptySpaceClickListener();
    private final MyOnHeadsUpChangedListener
            mOnHeadsUpChangedListener =
            new MyOnHeadsUpChangedListener();
    private final HeightListener mHeightListener = new HeightListener();
    private final ConfigurationListener mConfigurationListener = new ConfigurationListener();
    private final SettingsChangeObserver mSettingsChangeObserver;

    @VisibleForTesting final StatusBarStateListener mStatusBarStateListener =
            new StatusBarStateListener();
    private final NotificationPanelView mView;
    private final VibratorHelper mVibratorHelper;
    private final MetricsLogger mMetricsLogger;
    private final ActivityManager mActivityManager;
    private final ConfigurationController mConfigurationController;
    private final Provider<FlingAnimationUtils.Builder> mFlingAnimationUtilsBuilder;
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    private final NotificationIconAreaController mNotificationIconAreaController;

    // Cap and total height of Roboto font. Needs to be adjusted when font for the big clock is
    // changed.
    private static final int CAP_HEIGHT = 1456;
    private static final int FONT_HEIGHT = 2163;

    /**
     * Maximum time before which we will expand the panel even for slow motions when getting a
     * touch passed over from launcher.
     */
    private static final int MAX_TIME_TO_OPEN_WHEN_FLINGING_FROM_LAUNCHER = 300;

    private static final int MAX_DOWN_EVENT_BUFFER_SIZE = 50;

    private static final String COUNTER_PANEL_OPEN = "panel_open";
    private static final String COUNTER_PANEL_OPEN_QS = "panel_open_qs";
    private static final String COUNTER_PANEL_OPEN_PEEK = "panel_open_peek";

    private static final Rect M_DUMMY_DIRTY_RECT = new Rect(0, 0, 1, 1);
    private static final Rect EMPTY_RECT = new Rect();

    private final InteractionJankMonitor mInteractionJankMonitor;
    private final LayoutInflater mLayoutInflater;
    private final FeatureFlags mFeatureFlags;
    private final PowerManager mPowerManager;
    private final AccessibilityManager mAccessibilityManager;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;
    private final PulseExpansionHandler mPulseExpansionHandler;
    private final KeyguardBypassController mKeyguardBypassController;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final ConversationNotificationManager mConversationNotificationManager;
    private final AuthController mAuthController;
    private final MediaHierarchyManager mMediaHierarchyManager;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final KeyguardStatusViewComponent.Factory mKeyguardStatusViewComponentFactory;
    private final KeyguardQsUserSwitchComponent.Factory mKeyguardQsUserSwitchComponentFactory;
    private final KeyguardUserSwitcherComponent.Factory mKeyguardUserSwitcherComponentFactory;
    private final KeyguardStatusBarViewComponent.Factory mKeyguardStatusBarViewComponentFactory;
    private final FragmentService mFragmentService;
    private final ScrimController mScrimController;
    private final PrivacyDotViewController mPrivacyDotViewController;
    private final QuickAccessWalletController mQuickAccessWalletController;
    private final QRCodeScannerController mQRCodeScannerController;
    private final ControlsComponent mControlsComponent;
    private final NotificationRemoteInputManager mRemoteInputManager;

    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final ShadeTransitionController mShadeTransitionController;
    private final TapAgainViewController mTapAgainViewController;
    private final LargeScreenShadeHeaderController mLargeScreenShadeHeaderController;
    private final RecordingController mRecordingController;
    private final PanelEventsEmitter mPanelEventsEmitter;
    private boolean mSplitShadeEnabled;
    // The bottom padding reserved for elements of the keyguard measuring notifications
    private float mKeyguardNotificationBottomPadding;
    /**
     * The top padding from where notification should start in lockscreen.
     * Should be static also during animations and should match the Y of the first notification.
     */
    private float mKeyguardNotificationTopPadding;
    // Current max allowed keyguard notifications determined by measuring the panel
    private int mMaxAllowedKeyguardNotifications;

    private ViewGroup mPreviewContainer;
    private KeyguardAffordanceHelper mAffordanceHelper;
    private KeyguardQsUserSwitchController mKeyguardQsUserSwitchController;
    private KeyguardUserSwitcherController mKeyguardUserSwitcherController;
    private KeyguardStatusBarView mKeyguardStatusBar;
    private KeyguardStatusBarViewController mKeyguardStatusBarViewController;
    @VisibleForTesting QS mQs;
    private FrameLayout mQsFrame;
    private QsFrameTranslateController mQsFrameTranslateController;
    private KeyguardStatusViewController mKeyguardStatusViewController;
    private LockIconViewController mLockIconViewController;
    private NotificationsQuickSettingsContainer mNotificationContainerParent;
    private NotificationsQSContainerController mNotificationsQSContainerController;
    private boolean mAnimateNextPositionUpdate;
    private float mQuickQsHeaderHeight;
    private ScreenOffAnimationController mScreenOffAnimationController;
    private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;

    private int mTrackingPointer;
    private VelocityTracker mQsVelocityTracker;
    private boolean mQsTracking;

    /**
     * If set, the ongoing touch gesture might both trigger the expansion in {@link PanelView} and
     * the expansion for quick settings.
     */
    private boolean mConflictingQsExpansionGesture;

    private boolean mPanelExpanded;

    /**
     * Indicates that QS is in expanded state which can happen by:
     * - single pane shade: expanding shade and then expanding QS
     * - split shade: just expanding shade (QS are expanded automatically)
     */
    private boolean mQsExpanded;
    private boolean mQsExpandedWhenExpandingStarted;
    private boolean mQsFullyExpanded;
    private boolean mKeyguardShowing;
    private boolean mKeyguardQsUserSwitchEnabled;
    private boolean mKeyguardUserSwitcherEnabled;
    private boolean mDozing;
    private boolean mDozingOnDown;
    private boolean mBouncerShowing;
    private int mBarState;
    private float mInitialHeightOnTouch;
    private float mInitialTouchX;
    private float mInitialTouchY;
    private float mQsExpansionHeight;
    private int mQsMinExpansionHeight;
    private int mQsMaxExpansionHeight;
    private int mQsPeekHeight;
    private boolean mStackScrollerOverscrolling;
    private boolean mQsExpansionFromOverscroll;
    private float mLastOverscroll;
    private boolean mQsExpansionEnabledPolicy = true;
    private boolean mQsExpansionEnabledAmbient = true;
    private ValueAnimator mQsExpansionAnimator;
    private FlingAnimationUtils mFlingAnimationUtils;
    private int mStatusBarMinHeight;
    private int mStatusBarHeaderHeightKeyguard;
    private float mOverStretchAmount;
    private float mDownX;
    private float mDownY;
    private int mDisplayTopInset = 0; // in pixels
    private int mDisplayRightInset = 0; // in pixels
    private int mLargeScreenShadeHeaderHeight;
    private int mSplitShadeNotificationsScrimMarginBottom;

    /**
     * Vertical overlap allowed between the bottom of the notification shelf and
     * the top of the lock icon or the under-display fingerprint sensor background.
     */
    private int mShelfAndLockIconOverlap;

    private final KeyguardClockPositionAlgorithm
            mClockPositionAlgorithm =
            new KeyguardClockPositionAlgorithm();
    private final KeyguardClockPositionAlgorithm.Result
            mClockPositionResult =
            new KeyguardClockPositionAlgorithm.Result();
    private boolean mIsExpanding;

    private boolean mBlockTouches;

    /**
     * Determines if QS should be already expanded when expanding shade.
     * Used for split shade, two finger gesture as well as accessibility shortcut to QS.
     */
    @VisibleForTesting boolean mQsExpandImmediate;
    private boolean mTwoFingerQsExpandPossible;
    private String mHeaderDebugInfo;

    /**
     * If we are in a panel collapsing motion, we reset scrollY of our scroll view but still
     * need to take this into account in our panel height calculation.
     */
    private boolean mQsAnimatorExpand;
    private boolean mIsLaunchTransitionFinished;
    private boolean mIsLaunchTransitionRunning;
    private Runnable mLaunchAnimationEndRunnable;
    private boolean mOnlyAffordanceInThisMotion;
    private ValueAnimator mQsSizeChangeAnimator;

    private boolean mQsScrimEnabled = true;
    private boolean mQsTouchAboveFalsingThreshold;
    private int mQsFalsingThreshold;

    private HeadsUpTouchHelper mHeadsUpTouchHelper;
    private boolean mListenForHeadsUp;
    private int mNavigationBarBottomHeight;
    private boolean mExpandingFromHeadsUp;
    private boolean mCollapsedOnDown;
    private int mPositionMinSideMargin;
    private boolean mClosingWithAlphaFadeOut;
    private boolean mHeadsUpAnimatingAway;
    private boolean mLaunchingAffordance;
    private boolean mAffordanceHasPreview;
    private final FalsingManager mFalsingManager;
    private final FalsingCollector mFalsingCollector;
    private String mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_AFFORDANCE;

    private Runnable mHeadsUpExistenceChangedRunnable = () -> {
        setHeadsUpAnimatingAway(false);
        updatePanelExpansionAndVisibility();
    };
    private boolean mShowIconsWhenExpanded;
    private int mIndicationBottomPadding;
    private int mAmbientIndicationBottomPadding;
    private boolean mIsFullWidth;
    private boolean mBlockingExpansionForCurrentTouch;

    // TODO (b/204204226): no longer needed once refactor is complete
    private final boolean mUseCombinedQSHeaders;

    /**
     * Following variables maintain state of events when input focus transfer may occur.
     */
    private boolean mExpectingSynthesizedDown; // expecting to see synthesized DOWN event
    private boolean mLastEventSynthesizedDown; // last event was synthesized DOWN event

    /**
     * Current dark amount that follows regular interpolation curve of animation.
     */
    private float mInterpolatedDarkAmount;

    /**
     * Dark amount that animates from 0 to 1 or vice-versa in linear manner, even if the
     * interpolation curve is different.
     */
    private float mLinearDarkAmount;

    private boolean mPulsing;
    private boolean mUserSetupComplete;
    private boolean mHideIconsDuringLaunchAnimation = true;
    private int mStackScrollerMeasuringPass;
    /**
     * Non-null if there's a heads-up notification that we're currently tracking the position of.
     */
    @Nullable
    private ExpandableNotificationRow mTrackedHeadsUpNotification;
    private final ArrayList<Consumer<ExpandableNotificationRow>>
            mTrackingHeadsUpListeners = new ArrayList<>();
    private HeadsUpAppearanceController mHeadsUpAppearanceController;

    private int mPanelAlpha;
    private Runnable mPanelAlphaEndAction;
    private float mBottomAreaShadeAlpha;
    private final ValueAnimator mBottomAreaShadeAlphaAnimator;
    private final AnimatableProperty mPanelAlphaAnimator = AnimatableProperty.from("panelAlpha",
            NotificationPanelView::setPanelAlphaInternal,
            NotificationPanelView::getCurrentPanelAlpha,
            R.id.panel_alpha_animator_tag, R.id.panel_alpha_animator_start_tag,
            R.id.panel_alpha_animator_end_tag);
    private final AnimationProperties mPanelAlphaOutPropertiesAnimator =
            new AnimationProperties().setDuration(150).setCustomInterpolator(
                    mPanelAlphaAnimator.getProperty(), Interpolators.ALPHA_OUT);
    private final AnimationProperties mPanelAlphaInPropertiesAnimator =
            new AnimationProperties().setDuration(200).setAnimationEndAction((property) -> {
                            if (mPanelAlphaEndAction != null) {
                                mPanelAlphaEndAction.run();
                            }
                        }).setCustomInterpolator(
                    mPanelAlphaAnimator.getProperty(), Interpolators.ALPHA_IN);
    private final NotificationEntryManager mEntryManager;

    private final CommandQueue mCommandQueue;
    private final UserManager mUserManager;
    private final MediaDataManager mMediaDataManager;
    private final SysUiState mSysUiState;

    private NotificationShadeDepthController mDepthController;
    private int mDisplayId;

    /**
     * Cache the resource id of the theme to avoid unnecessary work in onThemeChanged.
     *
     * onThemeChanged is forced when the theme might not have changed. So, to avoid unncessary
     * work, check the current id with the cached id.
     */
    private int mThemeResId;
    private KeyguardIndicationController mKeyguardIndicationController;
    private int mShelfHeight;
    private int mDarkIconSize;
    private int mHeadsUpInset;
    private boolean mHeadsUpPinnedMode;
    private boolean mAllowExpandForSmallExpansion;
    private Runnable mExpandAfterLayoutRunnable;

    /**
     * The padding between the start of notifications and the qs boundary on the lockscreen.
     * On lockscreen, notifications aren't inset this extra amount, but we still want the
     * qs boundary to be padded.
     */
    private int mLockscreenNotificationQSPadding;

    /**
     * The amount of progress we are currently in if we're transitioning to the full shade.
     * 0.0f means we're not transitioning yet, while 1 means we're all the way in the full
     * shade. This value can also go beyond 1.1 when we're overshooting!
     */
    private float mTransitioningToFullShadeProgress;

    /**
     * Position of the qs bottom during the full shade transition. This is needed as the toppadding
     * can change during state changes, which makes it much harder to do animations
     */
    private int mTransitionToFullShadeQSPosition;

    /**
     * Distance that the full shade transition takes in order for qs to fully transition to the
     * shade.
     */
    private int mDistanceForQSFullShadeTransition;

    /**
     * The translation amount for QS for the full shade transition
     */
    private float mQsTranslationForFullShadeTransition;

    /**
     * The maximum overshoot allowed for the top padding for the full shade transition
     */
    private int mMaxOverscrollAmountForPulse;

    /**
     * Should we animate the next bounds update
     */
    private boolean mAnimateNextNotificationBounds;
    /**
     * The delay for the next bounds animation
     */
    private long mNotificationBoundsAnimationDelay;

    /**
     * The duration of the notification bounds animation
     */
    private long mNotificationBoundsAnimationDuration;

    /**
     * Is this a collapse that started on the panel where we should allow the panel to intercept
     */
    private boolean mIsPanelCollapseOnQQS;

    private boolean mAnimatingQS;

    /**
     * The end bounds of a clipping animation.
     */
    private final Rect mQsClippingAnimationEndBounds = new Rect();

    /**
     * The animator for the qs clipping bounds.
     */
    private ValueAnimator mQsClippingAnimation = null;

    /**
     * Is the current animator resetting the qs translation.
     */
    private boolean mIsQsTranslationResetAnimator;

    /**
     * Is the current animator resetting the pulse expansion after a drag down
     */
    private boolean mIsPulseExpansionResetAnimator;
    private final Rect mKeyguardStatusAreaClipBounds = new Rect();
    private final Region mQsInterceptRegion = new Region();

    /**
     * The alpha of the views which only show on the keyguard but not in shade / shade locked
     */
    private float mKeyguardOnlyContentAlpha = 1.0f;

    /**
     * The translationY of the views which only show on the keyguard but in shade / shade locked.
     */
    private int mKeyguardOnlyTransitionTranslationY = 0;

    private float mUdfpsMaxYBurnInOffset;

    /**
     * Are we currently in gesture navigation
     */
    private boolean mIsGestureNavigation;
    private int mOldLayoutDirection;
    private NotificationShelfController mNotificationShelfController;
    private int mScrimCornerRadius;
    private int mScreenCornerRadius;
    private boolean mQSAnimatingHiddenFromCollapsed;

    private int mQsClipTop;
    private int mQsClipBottom;
    private boolean mQsVisible;
    private final ContentResolver mContentResolver;
    private float mMinFraction;

    private final Executor mUiExecutor;
    private final SecureSettings mSecureSettings;

    private KeyguardMediaController mKeyguardMediaController;

    private boolean mStatusViewCentered = true;

    private Optional<KeyguardUnfoldTransition> mKeyguardUnfoldTransition;
    private Optional<NotificationPanelUnfoldAnimationController>
            mNotificationPanelUnfoldAnimationController;

    private final NotificationListContainer mNotificationListContainer;
    private final NotificationStackSizeCalculator mNotificationStackSizeCalculator;

    private final NPVCDownEventState.Buffer mLastDownEvents;

    private View.AccessibilityDelegate mAccessibilityDelegate = new View.AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId()
                    || action
                    == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()) {
                mStatusBarKeyguardViewManager.showBouncer(true);
                return true;
            }
            return super.performAccessibilityAction(host, action, args);
        }
    };

    private final FalsingTapListener mFalsingTapListener = new FalsingTapListener() {
        @Override
        public void onDoubleTapRequired() {
            if (mStatusBarStateController.getState() == StatusBarState.SHADE_LOCKED) {
                mTapAgainViewController.show();
            } else {
                mKeyguardIndicationController.showTransientIndication(
                        R.string.notification_tap_again);
            }
            mVibratorHelper.vibrate(VibrationEffect.EFFECT_STRENGTH_MEDIUM);
        }
    };

    @Inject
    public NotificationPanelViewController(NotificationPanelView view,
            @Main Resources resources,
            @Main Handler handler,
            LayoutInflater layoutInflater,
            FeatureFlags featureFlags,
            NotificationWakeUpCoordinator coordinator, PulseExpansionHandler pulseExpansionHandler,
            DynamicPrivacyController dynamicPrivacyController,
            KeyguardBypassController bypassController, FalsingManager falsingManager,
            FalsingCollector falsingCollector,
            NotificationEntryManager notificationEntryManager,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController,
            StatusBarWindowStateController statusBarWindowStateController,
            NotificationShadeWindowController notificationShadeWindowController,
            DozeLog dozeLog,
            DozeParameters dozeParameters, CommandQueue commandQueue, VibratorHelper vibratorHelper,
            LatencyTracker latencyTracker, PowerManager powerManager,
            AccessibilityManager accessibilityManager, @DisplayId int displayId,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            MetricsLogger metricsLogger,
            ActivityManager activityManager,
            ConfigurationController configurationController,
            Provider<FlingAnimationUtils.Builder> flingAnimationUtilsBuilder,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager,
            ConversationNotificationManager conversationNotificationManager,
            MediaHierarchyManager mediaHierarchyManager,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            NotificationsQSContainerController notificationsQSContainerController,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            KeyguardStatusViewComponent.Factory keyguardStatusViewComponentFactory,
            KeyguardQsUserSwitchComponent.Factory keyguardQsUserSwitchComponentFactory,
            KeyguardUserSwitcherComponent.Factory keyguardUserSwitcherComponentFactory,
            KeyguardStatusBarViewComponent.Factory keyguardStatusBarViewComponentFactory,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            NotificationIconAreaController notificationIconAreaController,
            AuthController authController,
            ScrimController scrimController,
            UserManager userManager,
            MediaDataManager mediaDataManager,
            NotificationShadeDepthController notificationShadeDepthController,
            AmbientState ambientState,
            LockIconViewController lockIconViewController,
            KeyguardMediaController keyguardMediaController,
            PrivacyDotViewController privacyDotViewController,
            TapAgainViewController tapAgainViewController,
            NavigationModeController navigationModeController,
            FragmentService fragmentService,
            ContentResolver contentResolver,
            QuickAccessWalletController quickAccessWalletController,
            QRCodeScannerController qrCodeScannerController,
            RecordingController recordingController,
            @Main Executor uiExecutor,
            SecureSettings secureSettings,
            LargeScreenShadeHeaderController largeScreenShadeHeaderController,
            ScreenOffAnimationController screenOffAnimationController,
            LockscreenGestureLogger lockscreenGestureLogger,
            PanelExpansionStateManager panelExpansionStateManager,
            NotificationRemoteInputManager remoteInputManager,
            Optional<SysUIUnfoldComponent> unfoldComponent,
            ControlsComponent controlsComponent,
            InteractionJankMonitor interactionJankMonitor,
            QsFrameTranslateController qsFrameTranslateController,
            SysUiState sysUiState,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            NotificationListContainer notificationListContainer,
            PanelEventsEmitter panelEventsEmitter,
            NotificationStackSizeCalculator notificationStackSizeCalculator,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            ShadeTransitionController shadeTransitionController,
            SystemClock systemClock) {
        super(view,
                falsingManager,
                dozeLog,
                keyguardStateController,
                (SysuiStatusBarStateController) statusBarStateController,
                notificationShadeWindowController,
                vibratorHelper,
                statusBarKeyguardViewManager,
                latencyTracker,
                flingAnimationUtilsBuilder.get(),
                statusBarTouchableRegionManager,
                lockscreenGestureLogger,
                panelExpansionStateManager,
                ambientState,
                interactionJankMonitor,
                keyguardUnlockAnimationController,
                systemClock);
        mView = view;
        mVibratorHelper = vibratorHelper;
        mKeyguardMediaController = keyguardMediaController;
        mPrivacyDotViewController = privacyDotViewController;
        mQuickAccessWalletController = quickAccessWalletController;
        mQRCodeScannerController = qrCodeScannerController;
        mControlsComponent = controlsComponent;
        mMetricsLogger = metricsLogger;
        mActivityManager = activityManager;
        mConfigurationController = configurationController;
        mFlingAnimationUtilsBuilder = flingAnimationUtilsBuilder;
        mMediaHierarchyManager = mediaHierarchyManager;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mNotificationsQSContainerController = notificationsQSContainerController;
        mNotificationListContainer = notificationListContainer;
        mNotificationStackSizeCalculator = notificationStackSizeCalculator;
        mNotificationsQSContainerController.init();
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
        mNotificationIconAreaController = notificationIconAreaController;
        mKeyguardStatusViewComponentFactory = keyguardStatusViewComponentFactory;
        mKeyguardStatusBarViewComponentFactory = keyguardStatusBarViewComponentFactory;
        mDepthController = notificationShadeDepthController;
        mContentResolver = contentResolver;
        mKeyguardQsUserSwitchComponentFactory = keyguardQsUserSwitchComponentFactory;
        mKeyguardUserSwitcherComponentFactory = keyguardUserSwitcherComponentFactory;
        mFragmentService = fragmentService;
        mSettingsChangeObserver = new SettingsChangeObserver(handler);
        mSplitShadeEnabled =
                LargeScreenUtils.shouldUseSplitNotificationShade(mResources);
        mView.setWillNotDraw(!DEBUG);
        mLargeScreenShadeHeaderController = largeScreenShadeHeaderController;
        mLayoutInflater = layoutInflater;
        mFeatureFlags = featureFlags;
        mFalsingManager = falsingManager;
        mFalsingCollector = falsingCollector;
        mPowerManager = powerManager;
        mWakeUpCoordinator = coordinator;
        mAccessibilityManager = accessibilityManager;
        mView.setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
        setPanelAlpha(255, false /* animate */);
        mCommandQueue = commandQueue;
        mRecordingController = recordingController;
        mDisplayId = displayId;
        mPulseExpansionHandler = pulseExpansionHandler;
        mDozeParameters = dozeParameters;
        mScrimController = scrimController;
        mUserManager = userManager;
        mMediaDataManager = mediaDataManager;
        mTapAgainViewController = tapAgainViewController;
        mUiExecutor = uiExecutor;
        mSecureSettings = secureSettings;
        mInteractionJankMonitor = interactionJankMonitor;
        mSysUiState = sysUiState;
        mPanelEventsEmitter = panelEventsEmitter;
        pulseExpansionHandler.setPulseExpandAbortListener(() -> {
            if (mQs != null) {
                mQs.animateHeaderSlidingOut();
            }
        });
        statusBarWindowStateController.addListener(this::onStatusBarWindowStateChanged);
        mThemeResId = mView.getContext().getThemeResId();
        mKeyguardBypassController = bypassController;
        mUpdateMonitor = keyguardUpdateMonitor;
        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        mShadeTransitionController = shadeTransitionController;
        lockscreenShadeTransitionController.setNotificationPanelController(this);
        shadeTransitionController.setNotificationPanelViewController(this);
        DynamicPrivacyControlListener
                dynamicPrivacyControlListener =
                new DynamicPrivacyControlListener();
        dynamicPrivacyController.addListener(dynamicPrivacyControlListener);

        panelExpansionStateManager.addStateListener(this::onPanelStateChanged);

        mBottomAreaShadeAlphaAnimator = ValueAnimator.ofFloat(1f, 0);
        mBottomAreaShadeAlphaAnimator.addUpdateListener(animation -> {
            mBottomAreaShadeAlpha = (float) animation.getAnimatedValue();
            updateKeyguardBottomAreaAlpha();
        });
        mBottomAreaShadeAlphaAnimator.setDuration(160);
        mBottomAreaShadeAlphaAnimator.setInterpolator(Interpolators.ALPHA_OUT);
        mEntryManager = notificationEntryManager;
        mConversationNotificationManager = conversationNotificationManager;
        mAuthController = authController;
        mLockIconViewController = lockIconViewController;
        mScreenOffAnimationController = screenOffAnimationController;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;
        mRemoteInputManager = remoteInputManager;
        mLastDownEvents = new NPVCDownEventState.Buffer(MAX_DOWN_EVENT_BUFFER_SIZE);

        int currentMode = navigationModeController.addListener(
                mode -> mIsGestureNavigation = QuickStepContract.isGesturalMode(mode));
        mIsGestureNavigation = QuickStepContract.isGesturalMode(currentMode);

        mView.setBackgroundColor(Color.TRANSPARENT);
        OnAttachStateChangeListener onAttachStateChangeListener = new OnAttachStateChangeListener();
        mView.addOnAttachStateChangeListener(onAttachStateChangeListener);
        if (mView.isAttachedToWindow()) {
            onAttachStateChangeListener.onViewAttachedToWindow(mView);
        }

        mView.setOnApplyWindowInsetsListener(new OnApplyWindowInsetsListener());

        if (DEBUG) {
            mView.getOverlay().add(new DebugDrawable());
        }

        mKeyguardUnfoldTransition = unfoldComponent.map(c -> c.getKeyguardUnfoldTransition());
        mNotificationPanelUnfoldAnimationController = unfoldComponent.map(
                SysUIUnfoldComponent::getNotificationPanelUnfoldAnimationController);

        mQsFrameTranslateController = qsFrameTranslateController;
        updateUserSwitcherFlags();
        onFinishInflate();
        mUseCombinedQSHeaders = featureFlags.isEnabled(Flags.COMBINED_QS_HEADERS);
        keyguardUnlockAnimationController.addKeyguardUnlockAnimationListener(
                new KeyguardUnlockAnimationController.KeyguardUnlockAnimationListener() {
                    @Override
                    public void onUnlockAnimationFinished() {
                        // Make sure the clock is in the correct position after the unlock animation
                        // so that it's not in the wrong place when we show the keyguard again.
                        positionClockAndNotifications(true /* forceClockUpdate */);
                    }

                    @Override
                    public void onUnlockAnimationStarted(
                            boolean playingCannedAnimation,
                            boolean isWakeAndUnlock,
                            long unlockAnimationStartDelay,
                            long unlockAnimationDuration) {
                        // Disable blurs while we're unlocking so that panel expansion does not
                        // cause blurring. This will eventually be re-enabled by the panel view on
                        // ACTION_UP, since the user's finger might still be down after a swipe to
                        // unlock gesture, and we don't want that to cause blurring either.
                        mDepthController.setBlursDisabledForUnlock(mTracking);

                        if (playingCannedAnimation && !isWakeAndUnlock) {
                            // Hide the panel so it's not in the way or the surface behind the
                            // keyguard, which will be appearing. If we're wake and unlocking, the
                            // lock screen is hidden instantly so should not be flung away.
                            if (isTracking() || isFlinging()) {
                                // Instant collpase the notification panel since the notification
                                // panel is already in the middle animating
                                onTrackingStopped(false);
                                instantCollapse();
                            } else {
                                mView.animate()
                                        .alpha(0f)
                                        .setStartDelay(0)
                                        // Translate up by 4%.
                                        .translationY(mView.getHeight() * -0.04f)
                                        // This start delay is to give us time to animate out before
                                        // the launcher icons animation starts, so use that as our
                                        // duration.
                                        .setDuration(unlockAnimationStartDelay)
                                        .setInterpolator(EMPHASIZED_ACCELERATE)
                                        .withEndAction(() -> {
                                            instantCollapse();
                                            mView.setAlpha(1f);
                                            mView.setTranslationY(0f);
                                        })
                                        .start();
                            }
                        }
                    }
                });
    }

    private void onFinishInflate() {
        loadDimens();
        mKeyguardStatusBar = mView.findViewById(R.id.keyguard_header);

        FrameLayout userAvatarContainer = null;
        KeyguardUserSwitcherView keyguardUserSwitcherView = null;

        if (mKeyguardUserSwitcherEnabled && mUserManager.isUserSwitcherEnabled()) {
            if (mKeyguardQsUserSwitchEnabled) {
                ViewStub stub = mView.findViewById(R.id.keyguard_qs_user_switch_stub);
                userAvatarContainer = (FrameLayout) stub.inflate();
            } else {
                ViewStub stub = mView.findViewById(R.id.keyguard_user_switcher_stub);
                keyguardUserSwitcherView = (KeyguardUserSwitcherView) stub.inflate();
            }
        }

        mKeyguardStatusBarViewController =
                mKeyguardStatusBarViewComponentFactory.build(
                        mKeyguardStatusBar,
                        mNotificationPanelViewStateProvider)
                        .getKeyguardStatusBarViewController();
        mKeyguardStatusBarViewController.init();

        mNotificationContainerParent = mView.findViewById(R.id.notification_container_parent);
        updateViewControllers(
                mView.findViewById(R.id.keyguard_status_view),
                userAvatarContainer,
                keyguardUserSwitcherView);

        NotificationStackScrollLayout stackScrollLayout = mView.findViewById(
                R.id.notification_stack_scroller);
        mNotificationStackScrollLayoutController.attach(stackScrollLayout);
        mNotificationStackScrollLayoutController.setOnHeightChangedListener(
                mOnHeightChangedListener);
        mNotificationStackScrollLayoutController.setOverscrollTopChangedListener(
                mOnOverscrollTopChangedListener);
        mNotificationStackScrollLayoutController.setOnScrollListener(this::onNotificationScrolled);
        mNotificationStackScrollLayoutController.setOnStackYChanged(this::onStackYChanged);
        mNotificationStackScrollLayoutController.setOnEmptySpaceClickListener(
                mOnEmptySpaceClickListener);
        addTrackingHeadsUpListener(mNotificationStackScrollLayoutController::setTrackingHeadsUp);
        mKeyguardBottomArea = mView.findViewById(R.id.keyguard_bottom_area);
        mPreviewContainer = mView.findViewById(R.id.preview_container);
        mKeyguardBottomArea.setPreviewContainer(mPreviewContainer);

        initBottomArea();

        mWakeUpCoordinator.setStackScroller(mNotificationStackScrollLayoutController);
        mQsFrame = mView.findViewById(R.id.qs_frame);
        mPulseExpansionHandler.setUp(mNotificationStackScrollLayoutController);
        mWakeUpCoordinator.addListener(new NotificationWakeUpCoordinator.WakeUpListener() {
            @Override
            public void onFullyHiddenChanged(boolean isFullyHidden) {
                mKeyguardStatusBarViewController.updateForHeadsUp();
            }

            @Override
            public void onPulseExpansionChanged(boolean expandingChanged) {
                if (mKeyguardBypassController.getBypassEnabled()) {
                    // Position the notifications while dragging down while pulsing
                    requestScrollerTopPaddingUpdate(false /* animate */);
                }
            }
        });

        mView.setRtlChangeListener(layoutDirection -> {
            if (layoutDirection != mOldLayoutDirection) {
                mAffordanceHelper.onRtlPropertiesChanged();
                mOldLayoutDirection = layoutDirection;
            }
        });

        mView.setAccessibilityDelegate(mAccessibilityDelegate);
        if (mSplitShadeEnabled) {
            updateResources();
        }

        mTapAgainViewController.init();
        mKeyguardUnfoldTransition.ifPresent(u -> u.setup(mView));
        mNotificationPanelUnfoldAnimationController.ifPresent(controller ->
                controller.setup(mNotificationContainerParent));
    }

    @Override
    protected void loadDimens() {
        super.loadDimens();
        mFlingAnimationUtils = mFlingAnimationUtilsBuilder.get()
                .setMaxLengthSeconds(0.4f).build();
        mStatusBarMinHeight = SystemBarUtils.getStatusBarHeight(mView.getContext());
        mStatusBarHeaderHeightKeyguard = Utils.getStatusBarHeaderHeightKeyguard(mView.getContext());
        mQsPeekHeight = mResources.getDimensionPixelSize(R.dimen.qs_peek_height);
        mClockPositionAlgorithm.loadDimens(mResources);
        mQsFalsingThreshold = mResources.getDimensionPixelSize(R.dimen.qs_falsing_threshold);
        mPositionMinSideMargin = mResources.getDimensionPixelSize(
                R.dimen.notification_panel_min_side_margin);
        mIndicationBottomPadding = mResources.getDimensionPixelSize(
                R.dimen.keyguard_indication_bottom_padding);
        mShelfHeight = mResources.getDimensionPixelSize(R.dimen.notification_shelf_height);
        mDarkIconSize = mResources.getDimensionPixelSize(R.dimen.status_bar_icon_drawing_size_dark);
        int statusbarHeight = SystemBarUtils.getStatusBarHeight(mView.getContext());
        mHeadsUpInset = statusbarHeight + mResources.getDimensionPixelSize(
                R.dimen.heads_up_status_bar_padding);
        mDistanceForQSFullShadeTransition = mResources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_qs_transition_distance);
        mMaxOverscrollAmountForPulse = mResources.getDimensionPixelSize(
                R.dimen.pulse_expansion_max_top_overshoot);
        mScrimCornerRadius = mResources.getDimensionPixelSize(
                R.dimen.notification_scrim_corner_radius);
        mScreenCornerRadius = (int) ScreenDecorationsUtils.getWindowCornerRadius(
                mView.getContext());
        mLockscreenNotificationQSPadding = mResources.getDimensionPixelSize(
                R.dimen.notification_side_paddings);
        mUdfpsMaxYBurnInOffset = mResources.getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y);
    }

    private void updateViewControllers(KeyguardStatusView keyguardStatusView,
            FrameLayout userAvatarView,
            KeyguardUserSwitcherView keyguardUserSwitcherView) {
        // Re-associate the KeyguardStatusViewController
        KeyguardStatusViewComponent statusViewComponent =
                mKeyguardStatusViewComponentFactory.build(keyguardStatusView);
        mKeyguardStatusViewController = statusViewComponent.getKeyguardStatusViewController();
        mKeyguardStatusViewController.init();

        if (mKeyguardUserSwitcherController != null) {
            // Try to close the switcher so that callbacks are triggered if necessary.
            // Otherwise, NPV can get into a state where some of the views are still hidden
            mKeyguardUserSwitcherController.closeSwitcherIfOpenAndNotSimple(false);
        }

        mKeyguardQsUserSwitchController = null;
        mKeyguardUserSwitcherController = null;

        // Re-associate the KeyguardUserSwitcherController
        if (userAvatarView != null) {
            KeyguardQsUserSwitchComponent userSwitcherComponent =
                    mKeyguardQsUserSwitchComponentFactory.build(userAvatarView);
            mKeyguardQsUserSwitchController =
                    userSwitcherComponent.getKeyguardQsUserSwitchController();
            mKeyguardQsUserSwitchController.init();
            mKeyguardStatusBarViewController.setKeyguardUserSwitcherEnabled(true);
        } else if (keyguardUserSwitcherView != null) {
            KeyguardUserSwitcherComponent userSwitcherComponent =
                    mKeyguardUserSwitcherComponentFactory.build(keyguardUserSwitcherView);
            mKeyguardUserSwitcherController =
                    userSwitcherComponent.getKeyguardUserSwitcherController();
            mKeyguardUserSwitcherController.init();
            mKeyguardStatusBarViewController.setKeyguardUserSwitcherEnabled(true);
        } else {
            mKeyguardStatusBarViewController.setKeyguardUserSwitcherEnabled(false);
        }
    }

    /**
     * Returns if there's a custom clock being presented.
     */
    public boolean hasCustomClock() {
        return mKeyguardStatusViewController.hasCustomClock();
    }

    private void setCentralSurfaces(CentralSurfaces centralSurfaces) {
        // TODO: this can be injected.
        mCentralSurfaces = centralSurfaces;
        mKeyguardBottomArea.setCentralSurfaces(mCentralSurfaces);
    }

    public void updateResources() {
        mSplitShadeNotificationsScrimMarginBottom =
                mResources.getDimensionPixelSize(
                        R.dimen.split_shade_notifications_scrim_margin_bottom);
        mShelfAndLockIconOverlap =
                mResources.getDimensionPixelSize(R.dimen.shelf_and_lock_icon_overlap);

        final boolean newSplitShadeEnabled =
                LargeScreenUtils.shouldUseSplitNotificationShade(mResources);
        final boolean splitShadeChanged = mSplitShadeEnabled != newSplitShadeEnabled;
        mSplitShadeEnabled = newSplitShadeEnabled;

        boolean useLargeScreenShadeHeader =
                LargeScreenUtils.shouldUseLargeScreenShadeHeader(mView.getResources());
        if (mQs != null) {
            mQs.setInSplitShade(mSplitShadeEnabled);
        }
        mLargeScreenShadeHeaderHeight =
                mResources.getDimensionPixelSize(R.dimen.large_screen_shade_header_height);
        mQuickQsHeaderHeight = useLargeScreenShadeHeader ? mLargeScreenShadeHeaderHeight :
                SystemBarUtils.getQuickQsOffsetHeight(mView.getContext());
        int topMargin = useLargeScreenShadeHeader ? mLargeScreenShadeHeaderHeight :
                mResources.getDimensionPixelSize(R.dimen.notification_panel_margin_top);
        mLargeScreenShadeHeaderController.setActive(useLargeScreenShadeHeader);
        mAmbientState.setStackTopMargin(topMargin);
        mNotificationsQSContainerController.updateResources();

        updateKeyguardStatusViewAlignment(/* animate= */false);

        mKeyguardMediaController.refreshMediaPosition();

        if (splitShadeChanged) {
            // when we switch from split shade to regular shade we want to enforce setting qs to
            // the default state: expanded for split shade and collapsed otherwise
            if (!isOnKeyguard() && mPanelExpanded) {
                setQsExpanded(mSplitShadeEnabled);
            }
            updateClockAppearance();
            updateQsState();
            mNotificationStackScrollLayoutController.updateFooter();
        }
    }

    private View reInflateStub(int viewId, int stubId, int layoutId, boolean enabled) {
        View view = mView.findViewById(viewId);
        if (view != null) {
            int index = mView.indexOfChild(view);
            mView.removeView(view);
            if (enabled) {
                view = mLayoutInflater.inflate(layoutId, mView, false);
                mView.addView(view, index);
            } else {
                // Add the stub back so we can re-inflate it again if necessary
                ViewStub stub = new ViewStub(mView.getContext(), layoutId);
                stub.setId(stubId);
                mView.addView(stub, index);
                view = null;
            }
        } else if (enabled) {
            // It's possible the stub was never inflated if the configuration changed
            ViewStub stub = mView.findViewById(stubId);
            view = stub.inflate();
        }
        return view;
    }

    private void reInflateViews() {
        if (DEBUG) Log.d(TAG, "reInflateViews");
        // Re-inflate the status view group.
        KeyguardStatusView keyguardStatusView =
                mNotificationContainerParent.findViewById(R.id.keyguard_status_view);
        int statusIndex = mNotificationContainerParent.indexOfChild(keyguardStatusView);
        mNotificationContainerParent.removeView(keyguardStatusView);
        keyguardStatusView = (KeyguardStatusView) mLayoutInflater.inflate(
                R.layout.keyguard_status_view, mNotificationContainerParent, false);
        mNotificationContainerParent.addView(keyguardStatusView, statusIndex);
        // When it's reinflated, this is centered by default. If it shouldn't be, this will update
        // below when resources are updated.
        mStatusViewCentered = true;
        attachSplitShadeMediaPlayerContainer(
                keyguardStatusView.findViewById(R.id.status_view_media_container));

        // we need to update KeyguardStatusView constraints after reinflating it
        updateResources();

        // Re-inflate the keyguard user switcher group.
        updateUserSwitcherFlags();
        boolean isUserSwitcherEnabled = mUserManager.isUserSwitcherEnabled();
        boolean showQsUserSwitch = mKeyguardQsUserSwitchEnabled && isUserSwitcherEnabled;
        boolean showKeyguardUserSwitcher =
                !mKeyguardQsUserSwitchEnabled
                        && mKeyguardUserSwitcherEnabled
                        && isUserSwitcherEnabled;
        FrameLayout userAvatarView = (FrameLayout) reInflateStub(
                R.id.keyguard_qs_user_switch_view /* viewId */,
                R.id.keyguard_qs_user_switch_stub /* stubId */,
                R.layout.keyguard_qs_user_switch /* layoutId */,
                showQsUserSwitch /* enabled */);
        KeyguardUserSwitcherView keyguardUserSwitcherView =
                (KeyguardUserSwitcherView) reInflateStub(
                        R.id.keyguard_user_switcher_view /* viewId */,
                        R.id.keyguard_user_switcher_stub /* stubId */,
                        R.layout.keyguard_user_switcher /* layoutId */,
                        showKeyguardUserSwitcher /* enabled */);

        updateViewControllers(mView.findViewById(R.id.keyguard_status_view), userAvatarView,
                keyguardUserSwitcherView);

        // Update keyguard bottom area
        int index = mView.indexOfChild(mKeyguardBottomArea);
        mView.removeView(mKeyguardBottomArea);
        KeyguardBottomAreaView oldBottomArea = mKeyguardBottomArea;
        mKeyguardBottomArea = (KeyguardBottomAreaView) mLayoutInflater.inflate(
                R.layout.keyguard_bottom_area, mView, false);
        mKeyguardBottomArea.initFrom(oldBottomArea);
        mKeyguardBottomArea.setPreviewContainer(mPreviewContainer);
        mView.addView(mKeyguardBottomArea, index);
        initBottomArea();
        mKeyguardIndicationController.setIndicationArea(mKeyguardBottomArea);
        mStatusBarStateListener.onDozeAmountChanged(mStatusBarStateController.getDozeAmount(),
                mStatusBarStateController.getInterpolatedDozeAmount());

        mKeyguardStatusViewController.setKeyguardStatusViewVisibility(
                mBarState,
                false,
                false,
                mBarState);
        if (mKeyguardQsUserSwitchController != null) {
            mKeyguardQsUserSwitchController.setKeyguardQsUserSwitchVisibility(
                    mBarState,
                    false,
                    false,
                    mBarState);
        }
        if (mKeyguardUserSwitcherController != null) {
            mKeyguardUserSwitcherController.setKeyguardUserSwitcherVisibility(
                    mBarState,
                    false,
                    false,
                    mBarState);
        }
        setKeyguardBottomAreaVisibility(mBarState, false);

        mKeyguardUnfoldTransition.ifPresent(u -> u.setup(mView));
        mNotificationPanelUnfoldAnimationController.ifPresent(u -> u.setup(mView));
    }

    private void attachSplitShadeMediaPlayerContainer(FrameLayout container) {
        mKeyguardMediaController.attachSplitShadeContainer(container);
    }

    private void initBottomArea() {
        mAffordanceHelper = new KeyguardAffordanceHelper(
                mKeyguardAffordanceHelperCallback, mView.getContext(), mFalsingManager);
        mKeyguardBottomArea.setAffordanceHelper(mAffordanceHelper);
        mKeyguardBottomArea.setCentralSurfaces(mCentralSurfaces);
        mKeyguardBottomArea.setUserSetupComplete(mUserSetupComplete);
        mKeyguardBottomArea.setFalsingManager(mFalsingManager);
        mKeyguardBottomArea.initWallet(mQuickAccessWalletController);
        mKeyguardBottomArea.initControls(mControlsComponent);
        mKeyguardBottomArea.initQRCodeScanner(mQRCodeScannerController);
    }

    @VisibleForTesting
    void setMaxDisplayedNotifications(int maxAllowed) {
        mMaxAllowedKeyguardNotifications = maxAllowed;
    }

    private void updateMaxDisplayedNotifications(boolean recompute) {
        if (recompute) {
            mMaxAllowedKeyguardNotifications = Math.max(computeMaxKeyguardNotifications(), 1);
        }

        if (mKeyguardShowing && !mKeyguardBypassController.getBypassEnabled()) {
            mNotificationStackScrollLayoutController.setMaxDisplayedNotifications(
                    mMaxAllowedKeyguardNotifications);
            mNotificationStackScrollLayoutController.setKeyguardBottomPaddingForDebug(
                    mKeyguardNotificationBottomPadding);
        } else {
            // no max when not on the keyguard
            mNotificationStackScrollLayoutController.setMaxDisplayedNotifications(-1);
            mNotificationStackScrollLayoutController.setKeyguardBottomPaddingForDebug(-1f);
        }
    }

    private boolean shouldAvoidChangingNotificationsCount() {
        return mHintAnimationRunning || mUnlockedScreenOffAnimationController.isAnimationPlaying();
    }

    public void setKeyguardIndicationController(KeyguardIndicationController indicationController) {
        mKeyguardIndicationController = indicationController;
        mKeyguardIndicationController.setIndicationArea(mKeyguardBottomArea);
    }

    private void updateGestureExclusionRect() {
        Rect exclusionRect = calculateGestureExclusionRect();
        mView.setSystemGestureExclusionRects(exclusionRect.isEmpty() ? Collections.EMPTY_LIST
                : Collections.singletonList(exclusionRect));
    }

    private Rect calculateGestureExclusionRect() {
        Rect exclusionRect = null;
        Region touchableRegion = mStatusBarTouchableRegionManager.calculateTouchableRegion();
        if (isFullyCollapsed() && touchableRegion != null) {
            // Note: The manager also calculates the non-pinned touchable region
            exclusionRect = touchableRegion.getBounds();
        }
        return exclusionRect != null ? exclusionRect : EMPTY_RECT;
    }

    private void setIsFullWidth(boolean isFullWidth) {
        mIsFullWidth = isFullWidth;
        mScrimController.setClipsQsScrim(isFullWidth);
        mNotificationStackScrollLayoutController.setIsFullWidth(isFullWidth);
    }

    private void startQsSizeChangeAnimation(int oldHeight, final int newHeight) {
        if (mQsSizeChangeAnimator != null) {
            oldHeight = (int) mQsSizeChangeAnimator.getAnimatedValue();
            mQsSizeChangeAnimator.cancel();
        }
        mQsSizeChangeAnimator = ValueAnimator.ofInt(oldHeight, newHeight);
        mQsSizeChangeAnimator.setDuration(300);
        mQsSizeChangeAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mQsSizeChangeAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                requestScrollerTopPaddingUpdate(false /* animate */);
                requestPanelHeightUpdate();
                int height = (int) mQsSizeChangeAnimator.getAnimatedValue();
                mQs.setHeightOverride(height);
            }
        });
        mQsSizeChangeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mQsSizeChangeAnimator = null;
            }
        });
        mQsSizeChangeAnimator.start();
    }

    /**
     * Positions the clock and notifications dynamically depending on how many notifications are
     * showing.
     */
    private void positionClockAndNotifications() {
        positionClockAndNotifications(false /* forceUpdate */);
    }

    /**
     * Positions the clock and notifications dynamically depending on how many notifications are
     * showing.
     *
     * @param forceClockUpdate Should the clock be updated even when not on keyguard
     */
    private void positionClockAndNotifications(boolean forceClockUpdate) {
        boolean animate = mNotificationStackScrollLayoutController.isAddOrRemoveAnimationPending();
        int stackScrollerPadding;
        boolean onKeyguard = isOnKeyguard();

        if (onKeyguard || forceClockUpdate) {
            updateClockAppearance();
        }
        if (!onKeyguard) {
            if (mSplitShadeEnabled) {
                // Quick settings are not on the top of the notifications
                // when in split shade mode (they are on the left side),
                // so we should not add a padding for them
                stackScrollerPadding = 0;
            } else {
                stackScrollerPadding = getUnlockedStackScrollerPadding();
            }
        } else {
            stackScrollerPadding = mClockPositionResult.stackScrollerPaddingExpanded;
        }

        mNotificationStackScrollLayoutController.setIntrinsicPadding(stackScrollerPadding);
        mKeyguardBottomArea.setAntiBurnInOffsetX(mClockPositionResult.clockX);

        mStackScrollerMeasuringPass++;
        requestScrollerTopPaddingUpdate(animate);
        mStackScrollerMeasuringPass = 0;
        mAnimateNextPositionUpdate = false;
    }

    private void updateClockAppearance() {
        int userSwitcherPreferredY = mStatusBarHeaderHeightKeyguard;
        boolean bypassEnabled = mKeyguardBypassController.getBypassEnabled();
        final boolean hasVisibleNotifications = mNotificationStackScrollLayoutController
                .getVisibleNotificationCount() != 0
                || mMediaDataManager.hasActiveMediaOrRecommendation();
        boolean splitShadeWithActiveMedia =
                mSplitShadeEnabled && mMediaDataManager.hasActiveMediaOrRecommendation();
        boolean shouldAnimateClockChange = mScreenOffAnimationController.shouldAnimateClockChange();
        if ((hasVisibleNotifications && !mSplitShadeEnabled)
                || (splitShadeWithActiveMedia && !mDozing)) {
            mKeyguardStatusViewController.displayClock(SMALL, shouldAnimateClockChange);
        } else {
            mKeyguardStatusViewController.displayClock(LARGE, shouldAnimateClockChange);
        }
        updateKeyguardStatusViewAlignment(true /* animate */);
        int userSwitcherHeight = mKeyguardQsUserSwitchController != null
                ? mKeyguardQsUserSwitchController.getUserIconHeight() : 0;
        if (mKeyguardUserSwitcherController != null) {
            userSwitcherHeight = mKeyguardUserSwitcherController.getHeight();
        }
        float expandedFraction =
                mScreenOffAnimationController.shouldExpandNotifications()
                        ? 1.0f : getExpandedFraction();
        float darkamount =
                mScreenOffAnimationController.shouldExpandNotifications()
                        ? 1.0f : mInterpolatedDarkAmount;

        float udfpsAodTopLocation = -1f;
        if (mUpdateMonitor.isUdfpsEnrolled() && mAuthController.getUdfpsProps().size() > 0) {
            FingerprintSensorPropertiesInternal props = mAuthController.getUdfpsProps().get(0);
            final SensorLocationInternal location = props.getLocation();
            udfpsAodTopLocation = location.sensorLocationY - location.sensorRadius
                    - mUdfpsMaxYBurnInOffset;
        }

        mClockPositionAlgorithm.setup(
                mStatusBarHeaderHeightKeyguard,
                expandedFraction,
                mKeyguardStatusViewController.getLockscreenHeight(),
                userSwitcherHeight,
                userSwitcherPreferredY,
                darkamount, mOverStretchAmount,
                bypassEnabled, getUnlockedStackScrollerPadding(),
                computeQsExpansionFraction(),
                mDisplayTopInset,
                mSplitShadeEnabled,
                udfpsAodTopLocation,
                mKeyguardStatusViewController.getClockBottom(mStatusBarHeaderHeightKeyguard),
                mKeyguardStatusViewController.isClockTopAligned());
        mClockPositionAlgorithm.run(mClockPositionResult);
        boolean animate = mNotificationStackScrollLayoutController.isAddOrRemoveAnimationPending();
        boolean animateClock = (animate || mAnimateNextPositionUpdate) && shouldAnimateClockChange;
        mKeyguardStatusViewController.updatePosition(
                mClockPositionResult.clockX, mClockPositionResult.clockY,
                mClockPositionResult.clockScale, animateClock);
        if (mKeyguardQsUserSwitchController != null) {
            mKeyguardQsUserSwitchController.updatePosition(
                    mClockPositionResult.clockX,
                    mClockPositionResult.userSwitchY,
                    animateClock);
        }
        if (mKeyguardUserSwitcherController != null) {
            mKeyguardUserSwitcherController.updatePosition(
                    mClockPositionResult.clockX,
                    mClockPositionResult.userSwitchY,
                    animateClock);
        }
        updateNotificationTranslucency();
        updateClock();
    }

    private void updateKeyguardStatusViewAlignment(boolean animate) {
        boolean hasVisibleNotifications = mNotificationStackScrollLayoutController
                .getVisibleNotificationCount() != 0
                || mMediaDataManager.hasActiveMediaOrRecommendation();
        boolean shouldBeCentered = !mSplitShadeEnabled || !hasVisibleNotifications || mDozing;
        if (mStatusViewCentered != shouldBeCentered) {
            mStatusViewCentered = shouldBeCentered;
            ConstraintSet constraintSet = new ConstraintSet();
            constraintSet.clone(mNotificationContainerParent);
            int statusConstraint = shouldBeCentered ? PARENT_ID : R.id.qs_edge_guideline;
            constraintSet.connect(R.id.keyguard_status_view, END, statusConstraint, END);
            if (animate) {
                ChangeBounds transition = new ChangeBounds();
                if (mSplitShadeEnabled) {
                    // Excluding media from the transition on split-shade, as it doesn't transition
                    // horizontally properly.
                    transition.excludeTarget(R.id.status_view_media_container, true);
                }
                transition.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
                transition.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
                TransitionManager.beginDelayedTransition(mNotificationContainerParent, transition);
            }

            constraintSet.applyTo(mNotificationContainerParent);
        }
        mKeyguardUnfoldTransition.ifPresent(t -> t.setStatusViewCentered(mStatusViewCentered));
    }

    /**
     * @return the padding of the stackscroller when unlocked
     */
    private int getUnlockedStackScrollerPadding() {
        return (mQs != null ? mQs.getHeader().getHeight() : 0) + mQsPeekHeight;
    }

    /**
     * @return Space available to show notifications on lockscreen.
     */
    @VisibleForTesting
    float getSpaceForLockscreenNotifications() {
        float staticTopPadding = mClockPositionAlgorithm.getLockscreenMinStackScrollerPadding()
                // getMinStackScrollerPadding is from the top of the screen,
                // but we need it from the top of the NSSL.
                - mNotificationStackScrollLayoutController.getTop();

        // Space between bottom of notifications and top of lock icon or udfps background.
        float lockIconPadding = mLockIconViewController.getTop();
        if (mLockIconViewController.getTop() != 0) {
            lockIconPadding = mNotificationStackScrollLayoutController.getBottom()
                    - mLockIconViewController.getTop()
                    - mShelfAndLockIconOverlap;
        }

        float bottomPadding = Math.max(lockIconPadding,
                Math.max(mIndicationBottomPadding, mAmbientIndicationBottomPadding));

        mKeyguardNotificationBottomPadding = bottomPadding;
        mKeyguardNotificationTopPadding = staticTopPadding;

        // To debug the available space, enable debug lines in this class. If you change how the
        // available space is calculated, please also update those lines.
        float availableSpace =
                mNotificationStackScrollLayoutController.getHeight()
                        - staticTopPadding
                        - bottomPadding;
        return availableSpace;
    }

    /**
     * @return Maximum number of notifications that can fit on keyguard.
     */
    @VisibleForTesting
    int computeMaxKeyguardNotifications() {
        if (mAmbientState.getFractionToShade() > 0 || mAmbientState.getDozeAmount() > 0) {
            return mMaxAllowedKeyguardNotifications;
        }

        final float shelfIntrinsicHeight =
                mNotificationShelfController.getVisibility() == View.GONE
                        ? 0
                        : mNotificationShelfController.getIntrinsicHeight();

        return mNotificationStackSizeCalculator.computeMaxKeyguardNotifications(
                mNotificationStackScrollLayoutController.getView(),
                getSpaceForLockscreenNotifications(),
                shelfIntrinsicHeight);
    }

    private void updateClock() {
        float alpha = mClockPositionResult.clockAlpha * mKeyguardOnlyContentAlpha;
        mKeyguardStatusViewController.setAlpha(alpha);
        mKeyguardStatusViewController
                .setTranslationYExcludingMedia(mKeyguardOnlyTransitionTranslationY);
        if (mKeyguardQsUserSwitchController != null) {
            mKeyguardQsUserSwitchController.setAlpha(alpha);
        }
        if (mKeyguardUserSwitcherController != null) {
            mKeyguardUserSwitcherController.setAlpha(alpha);
        }
    }

    public void animateToFullShade(long delay) {
        mNotificationStackScrollLayoutController.goToFullShade(delay);
        mView.requestLayout();
        mAnimateNextPositionUpdate = true;
    }

    private void setQsExpansionEnabled() {
        if (mQs == null) return;
        mQs.setHeaderClickable(isQsExpansionEnabled());
    }

    public void setQsExpansionEnabledPolicy(boolean qsExpansionEnabledPolicy) {
        mQsExpansionEnabledPolicy = qsExpansionEnabledPolicy;
        setQsExpansionEnabled();
    }

    @Override
    public void resetViews(boolean animate) {
        mIsLaunchTransitionFinished = false;
        mBlockTouches = false;
        if (!mLaunchingAffordance) {
            mAffordanceHelper.reset(false);
            mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_AFFORDANCE;
        }
        mCentralSurfaces.getGutsManager().closeAndSaveGuts(true /* leavebehind */, true /* force */,
                true /* controls */, -1 /* x */, -1 /* y */, true /* resetMenu */);
        if (animate && !isFullyCollapsed()) {
            animateCloseQs(true /* animateAway */);
        } else {
            closeQs();
        }
        mNotificationStackScrollLayoutController.setOverScrollAmount(0f, true /* onTop */, animate,
                !animate /* cancelAnimators */);
        mNotificationStackScrollLayoutController.resetScrollPosition();
    }

    /** Collapses the panel. */
    public void collapsePanel(boolean animate, boolean delayed, float speedUpFactor) {
        boolean waiting = false;
        if (animate && !isFullyCollapsed()) {
            collapse(delayed, speedUpFactor);
            waiting = true;
        } else {
            resetViews(false /* animate */);
            setExpandedFraction(0); // just in case
        }
        if (!waiting) {
            // it's possible that nothing animated, so we replicate the termination
            // conditions of panelExpansionChanged here
            // TODO(b/200063118): This can likely go away in a future refactor CL.
            getPanelExpansionStateManager().updateState(STATE_CLOSED);
        }
    }

    @Override
    public void collapse(boolean delayed, float speedUpFactor) {
        if (!canPanelBeCollapsed()) {
            return;
        }

        if (mQsExpanded) {
            mQsExpandImmediate = true;
            setShowShelfOnly(true);
        }
        super.collapse(delayed, speedUpFactor);
    }

    private void setShowShelfOnly(boolean shelfOnly) {
        mNotificationStackScrollLayoutController.setShouldShowShelfOnly(
                shelfOnly && !mSplitShadeEnabled);
    }

    public void closeQs() {
        cancelQsAnimation();
        setQsExpansion(mQsMinExpansionHeight);
    }

    public void cancelAnimation() {
        mView.animate().cancel();
    }


    /**
     * Animate QS closing by flinging it.
     * If QS is expanded, it will collapse into QQS and stop.
     *
     * @param animateAway Do not stop when QS becomes QQS. Fling until QS isn't visible anymore.
     */
    public void animateCloseQs(boolean animateAway) {
        if (mQsExpansionAnimator != null) {
            if (!mQsAnimatorExpand) {
                return;
            }
            float height = mQsExpansionHeight;
            mQsExpansionAnimator.cancel();
            setQsExpansion(height);
        }
        flingSettings(0 /* vel */, animateAway ? FLING_HIDE : FLING_COLLAPSE);
    }

    private boolean isQsExpansionEnabled() {
        return mQsExpansionEnabledPolicy && mQsExpansionEnabledAmbient
                && !mRemoteInputManager.isRemoteInputActive();
    }

    public void expandWithQs() {
        if (isQsExpansionEnabled()) {
            mQsExpandImmediate = true;
            setShowShelfOnly(true);
        }
        if (mSplitShadeEnabled && isOnKeyguard()) {
            // It's a special case as this method is likely to not be initiated by finger movement
            // but rather called from adb shell or accessibility service.
            // We're using LockscreenShadeTransitionController because on lockscreen that's the
            // source of truth for all shade motion. Not using it would make part of state to be
            // outdated and will cause bugs. Ideally we'd use this controller also for non-split
            // case but currently motion in portrait looks worse than when using flingSettings.
            // TODO: make below function transitioning smoothly also in portrait with null target
            mLockscreenShadeTransitionController.goToLockedShade(
                    /* expandedView= */null, /* needsQSAnimation= */false);
        } else if (isFullyCollapsed()) {
            expand(true /* animate */);
        } else {
            traceQsJank(true /* startTracing */, false /* wasCancelled */);
            flingSettings(0 /* velocity */, FLING_EXPAND);
        }
    }

    public void expandWithoutQs() {
        if (isQsExpanded()) {
            flingSettings(0 /* velocity */, FLING_COLLAPSE);
        } else {
            expand(true /* animate */);
        }
    }

    @Override
    public void fling(float vel, boolean expand) {
        GestureRecorder gr = mCentralSurfaces.getGestureRecorder();
        if (gr != null) {
            gr.tag("fling " + ((vel > 0) ? "open" : "closed"), "notifications,v=" + vel);
        }
        super.fling(vel, expand);
    }

    @Override
    protected void flingToHeight(float vel, boolean expand, float target,
            float collapseSpeedUpFactor, boolean expandBecauseOfFalsing) {
        mHeadsUpTouchHelper.notifyFling(!expand);
        mKeyguardStateController.notifyPanelFlingStart(!expand /* flingingToDismiss */);
        setClosingWithAlphaFadeout(!expand && !isOnKeyguard() && getFadeoutAlpha() == 1.0f);
        mAmbientState.setIsFlinging(true);
        super.flingToHeight(vel, expand, target, collapseSpeedUpFactor, expandBecauseOfFalsing);
    }

    @Override
    protected void onFlingEnd(boolean cancelled) {
        super.onFlingEnd(cancelled);
        mAmbientState.setIsFlinging(false);
    }

    private boolean onQsIntercept(MotionEvent event) {
        if (DEBUG) Log.d(TAG, "onQsIntercept");
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mInitialTouchY = y;
                mInitialTouchX = x;
                initVelocityTracker();
                trackMovement(event);
                if (mKeyguardShowing
                        && shouldQuickSettingsIntercept(mInitialTouchX, mInitialTouchY, 0)) {
                    // Dragging down on the lockscreen statusbar should prohibit other interactions
                    // immediately, otherwise we'll wait on the touchslop. This is to allow
                    // dragging down to expanded quick settings directly on the lockscreen.
                    mView.getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (mQsExpansionAnimator != null) {
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mQsTracking = true;
                    traceQsJank(true /* startTracing */, false /* wasCancelled */);
                    mNotificationStackScrollLayoutController.cancelLongPress();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialTouchX = event.getX(newIndex);
                    mInitialTouchY = event.getY(newIndex);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                final float h = y - mInitialTouchY;
                trackMovement(event);
                if (mQsTracking) {

                    // Already tracking because onOverscrolled was called. We need to update here
                    // so we don't stop for a frame until the next touch event gets handled in
                    // onTouchEvent.
                    setQsExpansion(h + mInitialHeightOnTouch);
                    trackMovement(event);
                    return true;
                }
                if ((h > getTouchSlop(event) || (h < -getTouchSlop(event) && mQsExpanded))
                        && Math.abs(h) > Math.abs(x - mInitialTouchX)
                        && shouldQuickSettingsIntercept(mInitialTouchX, mInitialTouchY, h)) {
                    if (DEBUG) Log.d(TAG, "onQsIntercept - start tracking expansion");
                    mView.getParent().requestDisallowInterceptTouchEvent(true);
                    mQsTracking = true;
                    traceQsJank(true /* startTracing */, false /* wasCancelled */);
                    onQsExpansionStarted();
                    notifyExpandingFinished();
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mNotificationStackScrollLayoutController.cancelLongPress();
                    return true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                trackMovement(event);
                mQsTracking = false;
                break;
        }
        return false;
    }

    @VisibleForTesting
    boolean isQsTracking() {
        return mQsTracking;
    }

    @Override
    protected boolean isInContentBounds(float x, float y) {
        float stackScrollerX = mNotificationStackScrollLayoutController.getX();
        return !mNotificationStackScrollLayoutController
                .isBelowLastNotification(x - stackScrollerX, y)
                && stackScrollerX < x
                && x < stackScrollerX + mNotificationStackScrollLayoutController.getWidth();
    }

    private void traceQsJank(boolean startTracing, boolean wasCancelled) {
        if (mInteractionJankMonitor == null) {
            return;
        }
        if (startTracing) {
            mInteractionJankMonitor.begin(mView, CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE);
        } else {
            if (wasCancelled) {
                mInteractionJankMonitor.cancel(CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE);
            } else {
                mInteractionJankMonitor.end(CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE);
            }
        }
    }

    private void initDownStates(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mOnlyAffordanceInThisMotion = false;
            mQsTouchAboveFalsingThreshold = mQsFullyExpanded;
            mDozingOnDown = isDozing();
            mDownX = event.getX();
            mDownY = event.getY();
            mCollapsedOnDown = isFullyCollapsed();
            mIsPanelCollapseOnQQS = canPanelCollapseOnQQS(mDownX, mDownY);
            mListenForHeadsUp = mCollapsedOnDown && mHeadsUpManager.hasPinnedHeadsUp();
            mAllowExpandForSmallExpansion = mExpectingSynthesizedDown;
            mTouchSlopExceededBeforeDown = mExpectingSynthesizedDown;
            if (mExpectingSynthesizedDown) {
                mLastEventSynthesizedDown = true;
            } else {
                // down but not synthesized motion event.
                mLastEventSynthesizedDown = false;
            }
            mLastDownEvents.insert(
                    mSystemClock.currentTimeMillis(),
                    mDownX,
                    mDownY,
                    mQsTouchAboveFalsingThreshold,
                    mDozingOnDown,
                    mCollapsedOnDown,
                    mIsPanelCollapseOnQQS,
                    mListenForHeadsUp,
                    mAllowExpandForSmallExpansion,
                    mTouchSlopExceededBeforeDown,
                    mLastEventSynthesizedDown
            );
        } else {
            // not down event at all.
            mLastEventSynthesizedDown = false;
        }
    }

    /**
     * Can the panel collapse in this motion because it was started on QQS?
     *
     * @param downX the x location where the touch started
     * @param downY the y location where the touch started
     *
     * @return true if the panel could be collapsed because it stared on QQS
     */
    private boolean canPanelCollapseOnQQS(float downX, float downY) {
        if (mCollapsedOnDown || mKeyguardShowing || mQsExpanded) {
            return false;
        }
        View header = mQs == null ? mKeyguardStatusBar : mQs.getHeader();
        return downX >= mQsFrame.getX() && downX <= mQsFrame.getX() + mQsFrame.getWidth()
                        && downY <= header.getBottom();

    }

    private void flingQsWithCurrentVelocity(float y, boolean isCancelMotionEvent) {
        float vel = getCurrentQSVelocity();
        boolean expandsQs = flingExpandsQs(vel);
        if (expandsQs) {
            if (mFalsingManager.isUnlockingDisabled() || isFalseTouch(QUICK_SETTINGS)) {
                expandsQs = false;
            } else {
                logQsSwipeDown(y);
            }
        } else if (vel < 0) {
            mFalsingManager.isFalseTouch(QS_COLLAPSE);
        }

        int flingType;
        if (expandsQs && !isCancelMotionEvent) {
            flingType = FLING_EXPAND;
        } else if (mSplitShadeEnabled) {
            flingType = FLING_HIDE;
        } else {
            flingType = FLING_COLLAPSE;
        }
        flingSettings(vel, flingType);
    }

    private void logQsSwipeDown(float y) {
        float vel = getCurrentQSVelocity();
        final int
                gesture =
                mBarState == KEYGUARD ? MetricsEvent.ACTION_LS_QS
                        : MetricsEvent.ACTION_SHADE_QS_PULL;
        mLockscreenGestureLogger.write(gesture,
                (int) ((y - mInitialTouchY) / mCentralSurfaces.getDisplayDensity()),
                (int) (vel / mCentralSurfaces.getDisplayDensity()));
    }

    private boolean flingExpandsQs(float vel) {
        if (Math.abs(vel) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            return computeQsExpansionFraction() > 0.5f;
        } else {
            return vel > 0;
        }
    }

    private boolean isFalseTouch(@Classifier.InteractionType int interactionType) {
        if (mFalsingManager.isClassifierEnabled()) {
            return mFalsingManager.isFalseTouch(interactionType);
        }
        return !mQsTouchAboveFalsingThreshold;
    }

    private float computeQsExpansionFraction() {
        if (mQSAnimatingHiddenFromCollapsed) {
            // When hiding QS from collapsed state, the expansion can sometimes temporarily
            // be larger than 0 because of the timing, leading to flickers.
            return 0.0f;
        }
        return Math.min(
                1f, (mQsExpansionHeight - mQsMinExpansionHeight) / (mQsMaxExpansionHeight
                        - mQsMinExpansionHeight));
    }

    @Override
    protected boolean shouldExpandWhenNotFlinging() {
        if (super.shouldExpandWhenNotFlinging()) {
            return true;
        }
        if (mAllowExpandForSmallExpansion) {
            // When we get a touch that came over from launcher, the velocity isn't always correct
            // Let's err on expanding if the gesture has been reasonably slow
            long timeSinceDown = mSystemClock.uptimeMillis() - mDownTime;
            return timeSinceDown <= MAX_TIME_TO_OPEN_WHEN_FLINGING_FROM_LAUNCHER;
        }
        return false;
    }

    @Override
    protected float getOpeningHeight() {
        return mNotificationStackScrollLayoutController.getOpeningHeight();
    }


    private boolean handleQsTouch(MotionEvent event) {
        if (mSplitShadeEnabled && touchXOutsideOfQs(event.getX())) {
            return false;
        }
        final int action = event.getActionMasked();
        boolean collapsedQs = !mQsExpanded && !mSplitShadeEnabled;
        boolean expandedShadeCollapsedQs = getExpandedFraction() == 1f && mBarState != KEYGUARD
                && collapsedQs && isQsExpansionEnabled();
        if (action == MotionEvent.ACTION_DOWN && expandedShadeCollapsedQs) {
            // Down in the empty area while fully expanded - go to QS.
            mQsTracking = true;
            traceQsJank(true /* startTracing */, false /* wasCancelled */);
            mConflictingQsExpansionGesture = true;
            onQsExpansionStarted();
            mInitialHeightOnTouch = mQsExpansionHeight;
            mInitialTouchY = event.getY();
            mInitialTouchX = event.getX();
        }
        if (!isFullyCollapsed()) {
            handleQsDown(event);
        }
        if (!mQsExpandImmediate && mQsTracking) {
            onQsTouch(event);
            if (!mConflictingQsExpansionGesture && !mSplitShadeEnabled) {
                return true;
            }
        }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            mConflictingQsExpansionGesture = false;
        }
        if (action == MotionEvent.ACTION_DOWN && isFullyCollapsed() && isQsExpansionEnabled()) {
            mTwoFingerQsExpandPossible = true;
        }
        if (mTwoFingerQsExpandPossible && isOpenQsEvent(event) && event.getY(event.getActionIndex())
                < mStatusBarMinHeight) {
            mMetricsLogger.count(COUNTER_PANEL_OPEN_QS, 1);
            mQsExpandImmediate = true;
            setShowShelfOnly(true);
            requestPanelHeightUpdate();

            // Normally, we start listening when the panel is expanded, but here we need to start
            // earlier so the state is already up to date when dragging down.
            setListening(true);
        }
        return false;
    }

    private boolean touchXOutsideOfQs(float touchX) {
        return touchX < mQsFrame.getX() || touchX > mQsFrame.getX() + mQsFrame.getWidth();
    }

    private boolean isInQsArea(float x, float y) {
        if (touchXOutsideOfQs(x)) {
            return false;
        }
        // Let's reject anything at the very bottom around the home handle in gesture nav
        if (mIsGestureNavigation && y > mView.getHeight() - mNavigationBarBottomHeight) {
            return false;
        }
        return y <= mNotificationStackScrollLayoutController.getBottomMostNotificationBottom()
                        || y <= mQs.getView().getY() + mQs.getView().getHeight();
    }

    private boolean isOpenQsEvent(MotionEvent event) {
        final int pointerCount = event.getPointerCount();
        final int action = event.getActionMasked();

        final boolean
                twoFingerDrag =
                action == MotionEvent.ACTION_POINTER_DOWN && pointerCount == 2;

        final boolean
                stylusButtonClickDrag =
                action == MotionEvent.ACTION_DOWN && (event.isButtonPressed(
                        MotionEvent.BUTTON_STYLUS_PRIMARY) || event.isButtonPressed(
                        MotionEvent.BUTTON_STYLUS_SECONDARY));

        final boolean
                mouseButtonClickDrag =
                action == MotionEvent.ACTION_DOWN && (event.isButtonPressed(
                        MotionEvent.BUTTON_SECONDARY) || event.isButtonPressed(
                        MotionEvent.BUTTON_TERTIARY));

        return twoFingerDrag || stylusButtonClickDrag || mouseButtonClickDrag;
    }

    private void handleQsDown(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && shouldQuickSettingsIntercept(
                event.getX(), event.getY(), -1)) {
            if (DEBUG) Log.d(TAG, "handleQsDown");
            mFalsingCollector.onQsDown();
            mQsTracking = true;
            onQsExpansionStarted();
            mInitialHeightOnTouch = mQsExpansionHeight;
            mInitialTouchY = event.getY();
            mInitialTouchX = event.getX();

            // If we interrupt an expansion gesture here, make sure to update the state correctly.
            notifyExpandingFinished();
        }
    }

    /**
     * Input focus transfer is about to happen.
     */
    public void startWaitingForOpenPanelGesture() {
        if (!isFullyCollapsed()) {
            return;
        }
        mExpectingSynthesizedDown = true;
        onTrackingStarted();
        updatePanelExpanded();
    }

    /**
     * Called when this view is no longer waiting for input focus transfer.
     *
     * There are two scenarios behind this function call. First, input focus transfer
     * has successfully happened and this view already received synthetic DOWN event.
     * (mExpectingSynthesizedDown == false). Do nothing.
     *
     * Second, before input focus transfer finished, user may have lifted finger
     * in previous window and this window never received synthetic DOWN event.
     * (mExpectingSynthesizedDown == true).
     * In this case, we use the velocity to trigger fling event.
     *
     * @param velocity unit is in px / millis
     */
    public void stopWaitingForOpenPanelGesture(boolean cancel, final float velocity) {
        if (mExpectingSynthesizedDown) {
            mExpectingSynthesizedDown = false;
            if (cancel) {
                collapse(false /* delayed */, 1.0f /* speedUpFactor */);
            } else {
                maybeVibrateOnOpening();
                fling(velocity > 1f ? 1000f * velocity : 0, true /* expand */);
            }
            onTrackingStopped(false);
        }
    }

    @Override
    protected boolean flingExpands(float vel, float vectorVel, float x, float y) {
        boolean expands = super.flingExpands(vel, vectorVel, x, y);

        // If we are already running a QS expansion, make sure that we keep the panel open.
        if (mQsExpansionAnimator != null) {
            expands = true;
        }
        return expands;
    }

    @Override
    protected boolean shouldGestureWaitForTouchSlop() {
        if (mExpectingSynthesizedDown) {
            mExpectingSynthesizedDown = false;
            return false;
        }
        return isFullyCollapsed() || mBarState != StatusBarState.SHADE;
    }

    @Override
    protected boolean shouldGestureIgnoreXTouchSlop(float x, float y) {
        return !mAffordanceHelper.isOnAffordanceIcon(x, y);
    }

    private void onQsTouch(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float y = event.getY(pointerIndex);
        final float x = event.getX(pointerIndex);
        final float h = y - mInitialTouchY;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mQsTracking = true;
                traceQsJank(true /* startTracing */, false /* wasCancelled */);
                mInitialTouchY = y;
                mInitialTouchX = x;
                onQsExpansionStarted();
                mInitialHeightOnTouch = mQsExpansionHeight;
                initVelocityTracker();
                trackMovement(event);
                break;

            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    final float newY = event.getY(newIndex);
                    final float newX = event.getX(newIndex);
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mInitialTouchY = newY;
                    mInitialTouchX = newX;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (DEBUG) Log.d(TAG, "onQSTouch move");
                setQsExpansion(h + mInitialHeightOnTouch);
                if (h >= getFalsingThreshold()) {
                    mQsTouchAboveFalsingThreshold = true;
                }
                trackMovement(event);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mQsTracking = false;
                mTrackingPointer = -1;
                trackMovement(event);
                float fraction = computeQsExpansionFraction();
                if (fraction != 0f || y >= mInitialTouchY) {
                    flingQsWithCurrentVelocity(y,
                            event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                } else {
                    traceQsJank(false /* startTracing */,
                            event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                }
                if (mQsVelocityTracker != null) {
                    mQsVelocityTracker.recycle();
                    mQsVelocityTracker = null;
                }
                break;
        }
    }

    private int getFalsingThreshold() {
        float factor = mCentralSurfaces.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
        return (int) (mQsFalsingThreshold * factor);
    }

    private void setOverScrolling(boolean overscrolling) {
        mStackScrollerOverscrolling = overscrolling;
        if (mQs == null) return;
        mQs.setOverscrolling(overscrolling);
    }

    private void onQsExpansionStarted() {
        onQsExpansionStarted(0);
    }

    protected void onQsExpansionStarted(int overscrollAmount) {
        cancelQsAnimation();
        cancelHeightAnimator();

        // Reset scroll position and apply that position to the expanded height.
        float height = mQsExpansionHeight - overscrollAmount;
        setQsExpansion(height);
        requestPanelHeightUpdate();
        mNotificationStackScrollLayoutController.checkSnoozeLeavebehind();

        // When expanding QS, let's authenticate the user if possible,
        // this will speed up notification actions.
        if (height == 0) {
            mCentralSurfaces.requestFaceAuth(false);
        }
    }

    @VisibleForTesting void setQsExpanded(boolean expanded) {
        boolean changed = mQsExpanded != expanded;
        if (changed) {
            mQsExpanded = expanded;
            updateQsState();
            requestPanelHeightUpdate();
            mFalsingCollector.setQsExpanded(expanded);
            mCentralSurfaces.setQsExpanded(expanded);
            mNotificationsQSContainerController.setQsExpanded(expanded);
            mPulseExpansionHandler.setQsExpanded(expanded);
            mKeyguardBypassController.setQSExpanded(expanded);
            mPrivacyDotViewController.setQsExpanded(expanded);
        }
    }

    private void maybeAnimateBottomAreaAlpha() {
        mBottomAreaShadeAlphaAnimator.cancel();
        if (mBarState == StatusBarState.SHADE_LOCKED) {
            mBottomAreaShadeAlphaAnimator.setFloatValues(mBottomAreaShadeAlpha, 0.0f);
            mBottomAreaShadeAlphaAnimator.start();
        } else {
            mBottomAreaShadeAlpha = 1f;
        }
    }

    private final Runnable mAnimateKeyguardBottomAreaInvisibleEndRunnable = new Runnable() {
        @Override
        public void run() {
            mKeyguardBottomArea.setVisibility(View.GONE);
        }
    };

    private void setKeyguardBottomAreaVisibility(int statusBarState, boolean goingToFullShade) {
        mKeyguardBottomArea.animate().cancel();
        if (goingToFullShade) {
            mKeyguardBottomArea.animate().alpha(0f).setStartDelay(
                    mKeyguardStateController.getKeyguardFadingAwayDelay()).setDuration(
                    mKeyguardStateController.getShortenedFadingAwayDuration()).setInterpolator(
                    Interpolators.ALPHA_OUT).withEndAction(
                    mAnimateKeyguardBottomAreaInvisibleEndRunnable).start();
        } else if (statusBarState == KEYGUARD
                || statusBarState == StatusBarState.SHADE_LOCKED) {
            mKeyguardBottomArea.setVisibility(View.VISIBLE);
            mKeyguardBottomArea.setAlpha(1f);
        } else {
            mKeyguardBottomArea.setVisibility(View.GONE);
        }
    }

    private void updateQsState() {
        boolean qsFullScreen = mQsExpanded && !mSplitShadeEnabled;
        mNotificationStackScrollLayoutController.setQsFullScreen(qsFullScreen);
        mNotificationStackScrollLayoutController.setScrollingEnabled(
                mBarState != KEYGUARD && (!qsFullScreen || mQsExpansionFromOverscroll));

        if (mKeyguardUserSwitcherController != null && mQsExpanded
                && !mStackScrollerOverscrolling) {
            mKeyguardUserSwitcherController.closeSwitcherIfOpenAndNotSimple(true);
        }
        if (mQs == null) return;
        mQs.setExpanded(mQsExpanded);
    }

    void setQsExpansion(float height) {
        height = Math.min(Math.max(height, mQsMinExpansionHeight), mQsMaxExpansionHeight);
        mQsFullyExpanded = height == mQsMaxExpansionHeight && mQsMaxExpansionHeight != 0;
        if (height > mQsMinExpansionHeight && !mQsExpanded && !mStackScrollerOverscrolling
                && !mDozing) {
            setQsExpanded(true);
        } else if (height <= mQsMinExpansionHeight && mQsExpanded) {
            setQsExpanded(false);
        }
        mQsExpansionHeight = height;
        updateQsExpansion();
        requestScrollerTopPaddingUpdate(false /* animate */);
        mKeyguardStatusBarViewController.updateViewState();
        if (mBarState == StatusBarState.SHADE_LOCKED || mBarState == KEYGUARD) {
            updateKeyguardBottomAreaAlpha();
            positionClockAndNotifications();
        }

        if (mAccessibilityManager.isEnabled()) {
            mView.setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
        }

        if (!mFalsingManager.isUnlockingDisabled() && mQsFullyExpanded
                && mFalsingCollector.shouldEnforceBouncer()) {
            mCentralSurfaces.executeRunnableDismissingKeyguard(null, null /* cancelAction */,
                    false /* dismissShade */, true /* afterKeyguardGone */, false /* deferred */);
        }
        if (DEBUG) {
            mView.invalidate();
        }
    }

    private void updateQsExpansion() {
        if (mQs == null) return;
        final float squishiness;
        if ((mQsExpandImmediate || mQsExpanded) && !mSplitShadeEnabled) {
            squishiness = 1;
        } else if (mLockscreenShadeTransitionController.getQSDragProgress() > 0) {
            squishiness = mLockscreenShadeTransitionController.getQSDragProgress();
        } else {
            squishiness = mNotificationStackScrollLayoutController
                    .getNotificationSquishinessFraction();
        }
        final float qsExpansionFraction = computeQsExpansionFraction();
        final float adjustedExpansionFraction = mSplitShadeEnabled
                ? 1f : computeQsExpansionFraction();
        mQs.setQsExpansion(adjustedExpansionFraction, getExpandedFraction(), getHeaderTranslation(),
                squishiness);
        mMediaHierarchyManager.setQsExpansion(qsExpansionFraction);
        int qsPanelBottomY = calculateQsBottomPosition(qsExpansionFraction);
        mScrimController.setQsPosition(qsExpansionFraction, qsPanelBottomY);
        setQSClippingBounds();

        if (mSplitShadeEnabled) {
            // In split shade we want to pretend that QS are always collapsed so their behaviour and
            // interactions don't influence notifications as they do in portrait. But we want to set
            // 0 explicitly in case we're rotating from non-split shade with QS expansion of 1.
            mNotificationStackScrollLayoutController.setQsExpansionFraction(0);
        } else {
            mNotificationStackScrollLayoutController.setQsExpansionFraction(qsExpansionFraction);
        }

        mDepthController.setQsPanelExpansion(qsExpansionFraction);
        mStatusBarKeyguardViewManager.setQsExpansion(qsExpansionFraction);

        // updateQsExpansion will get called whenever mTransitionToFullShadeProgress or
        // mLockscreenShadeTransitionController.getDragProgress change.
        // When in lockscreen, getDragProgress indicates the true expanded fraction of QS
        float shadeExpandedFraction = mTransitioningToFullShadeProgress > 0
                ? mLockscreenShadeTransitionController.getQSDragProgress()
                : getExpandedFraction();
        mLargeScreenShadeHeaderController.setShadeExpandedFraction(shadeExpandedFraction);
        mLargeScreenShadeHeaderController.setQsExpandedFraction(qsExpansionFraction);
        mLargeScreenShadeHeaderController.setShadeExpanded(mQsVisible);
    }

    private void onStackYChanged(boolean shouldAnimate) {
        if (mQs != null) {
            if (shouldAnimate) {
                animateNextNotificationBounds(StackStateAnimator.ANIMATION_DURATION_STANDARD,
                        0 /* delay */);
                mNotificationBoundsAnimationDelay = 0;
            }
            setQSClippingBounds();
        }
    };

    private void onNotificationScrolled(int newScrollPosition) {
        updateQSExpansionEnabledAmbient();
    }

    private void updateQSExpansionEnabledAmbient() {
        final float scrollRangeToTop = mAmbientState.getTopPadding() - mQuickQsHeaderHeight;
        mQsExpansionEnabledAmbient = mSplitShadeEnabled
                || (mAmbientState.getScrollY() <= scrollRangeToTop);
        setQsExpansionEnabled();
    }

    /**
     * Updates scrim bounds, QS clipping, and KSV clipping as well based on the bounds of the shade
     * and QS state.
     */
    private void setQSClippingBounds() {
        final int qsPanelBottomY = calculateQsBottomPosition(computeQsExpansionFraction());
        final boolean qsVisible = (computeQsExpansionFraction() > 0 || qsPanelBottomY > 0);

        int top = calculateTopQsClippingBound(qsPanelBottomY);
        int bottom = calculateBottomQsClippingBound(top);
        int left = calculateLeftQsClippingBound();
        int right = calculateRightQsClippingBound();
        // top should never be lower than bottom, otherwise it will be invisible.
        top = Math.min(top, bottom);
        applyQSClippingBounds(left, top, right, bottom, qsVisible);
    }

    private int calculateTopQsClippingBound(int qsPanelBottomY) {
        int top;
        if (mSplitShadeEnabled) {
            top = Math.min(qsPanelBottomY, mLargeScreenShadeHeaderHeight);
        } else {
            if (mTransitioningToFullShadeProgress > 0.0f) {
                // If we're transitioning, let's use the actual value. The else case
                // can be wrong during transitions when waiting for the keyguard to unlock
                top = mTransitionToFullShadeQSPosition;
            } else {
                final float notificationTop = getQSEdgePosition();
                if (isOnKeyguard()) {
                    if (mKeyguardBypassController.getBypassEnabled()) {
                        // When bypassing on the keyguard, let's use the panel bottom.
                        // this should go away once we unify the stackY position and don't have
                        // to do this min anymore below.
                        top = qsPanelBottomY;
                    } else {
                        top = (int) Math.min(qsPanelBottomY, notificationTop);
                    }
                } else {
                    top = (int) notificationTop;
                }
            }
            top += mOverStretchAmount;
            // Correction for instant expansion caused by HUN pull down/
            if (mMinFraction > 0f && mMinFraction < 1f) {
                float realFraction =
                        (getExpandedFraction() - mMinFraction) / (1f - mMinFraction);
                top *= MathUtils.saturate(realFraction / mMinFraction);
            }
        }
        return top;
    }

    private int calculateBottomQsClippingBound(int top) {
        if (mSplitShadeEnabled) {
            return top + mNotificationStackScrollLayoutController.getHeight()
                    + mSplitShadeNotificationsScrimMarginBottom;
        } else {
            return getView().getBottom();
        }
    }

    private int calculateLeftQsClippingBound() {
        if (isFullWidth()) {
            // left bounds can ignore insets, it should always reach the edge of the screen
            return 0;
        } else {
            return mNotificationStackScrollLayoutController.getLeft();
        }
    }

    private int calculateRightQsClippingBound() {
        if (isFullWidth()) {
            return getView().getRight() + mDisplayRightInset;
        } else {
            return mNotificationStackScrollLayoutController.getRight();
        }
    }

    private void applyQSClippingBounds(int left, int top, int right, int bottom,
            boolean qsVisible) {
        if (!mAnimateNextNotificationBounds || mKeyguardStatusAreaClipBounds.isEmpty()) {
            if (mQsClippingAnimation != null) {
                // update the end position of the animator
                mQsClippingAnimationEndBounds.set(left, top, right, bottom);
            } else {
                applyQSClippingImmediately(left, top, right, bottom, qsVisible);
            }
        } else {
            mQsClippingAnimationEndBounds.set(left, top, right, bottom);
            final int startLeft = mKeyguardStatusAreaClipBounds.left;
            final int startTop = mKeyguardStatusAreaClipBounds.top;
            final int startRight = mKeyguardStatusAreaClipBounds.right;
            final int startBottom = mKeyguardStatusAreaClipBounds.bottom;
            if (mQsClippingAnimation != null) {
                mQsClippingAnimation.cancel();
            }
            mQsClippingAnimation = ValueAnimator.ofFloat(0.0f, 1.0f);
            mQsClippingAnimation.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
            mQsClippingAnimation.setDuration(mNotificationBoundsAnimationDuration);
            mQsClippingAnimation.setStartDelay(mNotificationBoundsAnimationDelay);
            mQsClippingAnimation.addUpdateListener(animation -> {
                float fraction = animation.getAnimatedFraction();
                int animLeft = (int) MathUtils.lerp(startLeft,
                        mQsClippingAnimationEndBounds.left, fraction);
                int animTop = (int) MathUtils.lerp(startTop,
                        mQsClippingAnimationEndBounds.top, fraction);
                int animRight = (int) MathUtils.lerp(startRight,
                        mQsClippingAnimationEndBounds.right, fraction);
                int animBottom = (int) MathUtils.lerp(startBottom,
                        mQsClippingAnimationEndBounds.bottom, fraction);
                applyQSClippingImmediately(animLeft, animTop, animRight, animBottom,
                        qsVisible /* qsVisible */);
            });
            mQsClippingAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mQsClippingAnimation = null;
                    mIsQsTranslationResetAnimator = false;
                    mIsPulseExpansionResetAnimator = false;
                }
            });
            mQsClippingAnimation.start();
        }
        mAnimateNextNotificationBounds = false;
        mNotificationBoundsAnimationDelay = 0;
    }

    private void applyQSClippingImmediately(int left, int top, int right, int bottom,
            boolean qsVisible) {
        // Fancy clipping for quick settings
        int radius = mScrimCornerRadius;
        boolean clipStatusView = false;
        if (isFullWidth()) {
            // The padding on this area is large enough that we can use a cheaper clipping strategy
            mKeyguardStatusAreaClipBounds.set(left, top, right, bottom);
            clipStatusView = qsVisible;
            float screenCornerRadius = mRecordingController.isRecording() ? 0 : mScreenCornerRadius;
            radius = (int) MathUtils.lerp(screenCornerRadius, mScrimCornerRadius,
                    Math.min(top / (float) mScrimCornerRadius, 1f));
        }
        if (mQs != null) {
            float qsTranslation = 0;
            boolean pulseExpanding = mPulseExpansionHandler.isExpanding();
            if (mTransitioningToFullShadeProgress > 0.0f || pulseExpanding
                    || (mQsClippingAnimation != null
                        && (mIsQsTranslationResetAnimator || mIsPulseExpansionResetAnimator))) {
                if (pulseExpanding || mIsPulseExpansionResetAnimator) {
                    // qsTranslation should only be positive during pulse expansion because it's
                    // already translating in from the top
                    qsTranslation = Math.max(0, (top - mQs.getHeader().getHeight()) / 2.0f);
                } else if (!mSplitShadeEnabled) {
                    qsTranslation = (top - mQs.getHeader().getHeight()) * QS_PARALLAX_AMOUNT;
                }
            }
            mQsTranslationForFullShadeTransition = qsTranslation;
            updateQsFrameTranslation();
            float currentTranslation = mQsFrame.getTranslationY();
            mQsClipTop = (int) (top - currentTranslation - mQsFrame.getTop());
            mQsClipBottom = (int) (bottom - currentTranslation - mQsFrame.getTop());
            mQsVisible = qsVisible;
            mQs.setFancyClipping(
                    mQsClipTop,
                    mQsClipBottom,
                    radius, qsVisible
                    && !mSplitShadeEnabled);
        }
        mKeyguardStatusViewController.setClipBounds(
                clipStatusView ? mKeyguardStatusAreaClipBounds : null);
        if (!qsVisible && mSplitShadeEnabled) {
            // On the lockscreen when qs isn't visible, we don't want the bounds of the shade to
            // be visible, otherwise you can see the bounds once swiping up to see bouncer
            mScrimController.setNotificationsBounds(0, 0, 0, 0);
        } else {
            // Increase the height of the notifications scrim when not in split shade
            // (e.g. portrait tablet) so the rounded corners are not visible at the bottom,
            // in this case they are rendered off-screen
            final int notificationsScrimBottom = mSplitShadeEnabled ? bottom : bottom + radius;
            mScrimController.setNotificationsBounds(left, top, right, notificationsScrimBottom);
        }

        if (mSplitShadeEnabled) {
            mKeyguardStatusBarViewController.setNoTopClipping();
        } else {
            mKeyguardStatusBarViewController.updateTopClipping(top);
        }

        mScrimController.setScrimCornerRadius(radius);

        // Convert global clipping coordinates to local ones,
        // relative to NotificationStackScrollLayout
        int nsslLeft = left - mNotificationStackScrollLayoutController.getLeft();
        int nsslRight = right - mNotificationStackScrollLayoutController.getLeft();
        int nsslTop = top - mNotificationStackScrollLayoutController.getTop();
        int nsslBottom = bottom - mNotificationStackScrollLayoutController.getTop();
        int bottomRadius = mSplitShadeEnabled ? radius : 0;
        mNotificationStackScrollLayoutController.setRoundedClippingBounds(
                nsslLeft, nsslTop, nsslRight, nsslBottom, radius, bottomRadius);
    }

    private float getQSEdgePosition() {
        // TODO: replace StackY with unified calculation
        return Math.max(mQuickQsHeaderHeight * mAmbientState.getExpansionFraction(),
                mAmbientState.getStackY()
                        // need to adjust for extra margin introduced by large screen shade header
                        + mAmbientState.getStackTopMargin() * mAmbientState.getExpansionFraction()
                        - mAmbientState.getScrollY());
    }

    private int calculateQsBottomPosition(float qsExpansionFraction) {
        if (mTransitioningToFullShadeProgress > 0.0f) {
            return mTransitionToFullShadeQSPosition;
        } else {
            int qsBottomY = (int) getHeaderTranslation() + mQs.getQsMinExpansionHeight();
            if (qsExpansionFraction != 0.0) {
                qsBottomY = (int) MathUtils.lerp(
                        qsBottomY, mQs.getDesiredHeight(), qsExpansionFraction);
            }
            return qsBottomY;
        }
    }

    private String determineAccessibilityPaneTitle() {
        if (mQs != null && mQs.isCustomizing()) {
            return mResources.getString(R.string.accessibility_desc_quick_settings_edit);
        } else if (mQsExpansionHeight != 0.0f && mQsFullyExpanded) {
            // Upon initialisation when we are not layouted yet we don't want to announce that we
            // are fully expanded, hence the != 0.0f check.
            return mResources.getString(R.string.accessibility_desc_quick_settings);
        } else if (mBarState == KEYGUARD) {
            return mResources.getString(R.string.accessibility_desc_lock_screen);
        } else {
            return mResources.getString(R.string.accessibility_desc_notification_shade);
        }
    }

    private float calculateNotificationsTopPadding() {
        if (mSplitShadeEnabled && !mKeyguardShowing) {
            return 0;
        }
        if (mKeyguardShowing && (mQsExpandImmediate
                || mIsExpanding && mQsExpandedWhenExpandingStarted)) {

            // Either QS pushes the notifications down when fully expanded, or QS is fully above the
            // notifications (mostly on tablets). maxNotificationPadding denotes the normal top
            // padding on Keyguard, maxQsPadding denotes the top padding from the quick settings
            // panel. We need to take the maximum and linearly interpolate with the panel expansion
            // for a nice motion.
            int maxNotificationPadding = getKeyguardNotificationStaticPadding();
            int maxQsPadding = mQsMaxExpansionHeight;
            int max = mBarState == KEYGUARD ? Math.max(
                    maxNotificationPadding, maxQsPadding) : maxQsPadding;
            return (int) MathUtils.lerp((float) mQsMinExpansionHeight, (float) max,
                    getExpandedFraction());
        } else if (mQsSizeChangeAnimator != null) {
            return Math.max(
                    (int) mQsSizeChangeAnimator.getAnimatedValue(),
                    getKeyguardNotificationStaticPadding());
        } else if (mKeyguardShowing) {
            // We can only do the smoother transition on Keyguard when we also are not collapsing
            // from a scrolled quick settings.
            return MathUtils.lerp((float) getKeyguardNotificationStaticPadding(),
                    (float) (mQsMaxExpansionHeight),
                    computeQsExpansionFraction());
        } else {
            return mQsFrameTranslateController.getNotificationsTopPadding(mQsExpansionHeight,
                    mNotificationStackScrollLayoutController);
        }
    }

    /**
     * @return the topPadding of notifications when on keyguard not respecting quick settings
     * expansion
     */
    private int getKeyguardNotificationStaticPadding() {
        if (!mKeyguardShowing) {
            return 0;
        }
        if (!mKeyguardBypassController.getBypassEnabled()) {
            return mClockPositionResult.stackScrollerPadding;
        }
        int collapsedPosition = mHeadsUpInset;
        if (!mNotificationStackScrollLayoutController.isPulseExpanding()) {
            return collapsedPosition;
        } else {
            int expandedPosition = mClockPositionResult.stackScrollerPadding;
            return (int) MathUtils.lerp(collapsedPosition, expandedPosition,
                    mNotificationStackScrollLayoutController.calculateAppearFractionBypass());
        }
    }


    protected void requestScrollerTopPaddingUpdate(boolean animate) {
        mNotificationStackScrollLayoutController.updateTopPadding(
                calculateNotificationsTopPadding(), animate);
        if (mKeyguardShowing && mKeyguardBypassController.getBypassEnabled()) {
            // update the position of the header
            updateQsExpansion();
        }
    }

    /**
     * Set the amount of pixels we have currently dragged down if we're transitioning to the full
     * shade. 0.0f means we're not transitioning yet.
     */
    public void setTransitionToFullShadeAmount(float pxAmount, boolean animate, long delay) {
        if (animate && isFullWidth()) {
            animateNextNotificationBounds(StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE,
                    delay);
            mIsQsTranslationResetAnimator = mQsTranslationForFullShadeTransition > 0.0f;
        }

        float endPosition = 0;
        if (pxAmount > 0.0f) {
            if (mNotificationStackScrollLayoutController.getVisibleNotificationCount() == 0
                    && !mMediaDataManager.hasActiveMediaOrRecommendation()) {
                // No notifications are visible, let's animate to the height of qs instead
                if (mQs != null) {
                    // Let's interpolate to the header height instead of the top padding,
                    // because the toppadding is way too low because of the large clock.
                    // we still want to take into account the edgePosition though as that nicely
                    // overshoots in the stackscroller
                    endPosition = getQSEdgePosition()
                            - mNotificationStackScrollLayoutController.getTopPadding()
                            + mQs.getHeader().getHeight();
                }
            } else {
                // Interpolating to the new bottom edge position!
                endPosition = getQSEdgePosition()
                        + mNotificationStackScrollLayoutController.getFullShadeTransitionInset();
                if (isOnKeyguard()) {
                    endPosition -= mLockscreenNotificationQSPadding;
                }
            }
        }

        // Calculate the overshoot amount such that we're reaching the target after our desired
        // distance, but only reach it fully once we drag a full shade length.
        mTransitioningToFullShadeProgress = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                MathUtils.saturate(pxAmount / mDistanceForQSFullShadeTransition));

        int position = (int) MathUtils.lerp((float) 0, endPosition,
                mTransitioningToFullShadeProgress);
        if (mTransitioningToFullShadeProgress > 0.0f) {
            // we want at least 1 pixel otherwise the panel won't be clipped
            position = Math.max(1, position);
        }
        mTransitionToFullShadeQSPosition = position;
        updateQsExpansion();
    }

    /**
     * Notify the panel that the pulse expansion has finished and that we're going to the full
     * shade
     */
    public void onPulseExpansionFinished() {
        animateNextNotificationBounds(StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE, 0);
        mIsPulseExpansionResetAnimator = true;
    }

    /**
     * Set the alpha and translationY of the keyguard elements which only show on the lockscreen,
     * but not in shade locked / shade. This is used when dragging down to the full shade.
     */
    public void setKeyguardTransitionProgress(float keyguardAlpha, int keyguardTranslationY) {
        mKeyguardOnlyContentAlpha = Interpolators.ALPHA_IN.getInterpolation(keyguardAlpha);
        mKeyguardOnlyTransitionTranslationY = keyguardTranslationY;
        if (mBarState == KEYGUARD) {
            // If the animator is running, it's already fading out the content and this is a reset
            mBottomAreaShadeAlpha = mKeyguardOnlyContentAlpha;
            updateKeyguardBottomAreaAlpha();
        }
        updateClock();
    }

    /**
     * Sets the alpha value to be set on the keyguard status bar.
     *
     * @param alpha value between 0 and 1. -1 if the value is to be reset.
     */
    public void setKeyguardStatusBarAlpha(float alpha) {
        mKeyguardStatusBarViewController.setAlpha(alpha);
    }

    private void trackMovement(MotionEvent event) {
        if (mQsVelocityTracker != null) mQsVelocityTracker.addMovement(event);
    }

    private void initVelocityTracker() {
        if (mQsVelocityTracker != null) {
            mQsVelocityTracker.recycle();
        }
        mQsVelocityTracker = VelocityTracker.obtain();
    }

    private float getCurrentQSVelocity() {
        if (mQsVelocityTracker == null) {
            return 0;
        }
        mQsVelocityTracker.computeCurrentVelocity(1000);
        return mQsVelocityTracker.getYVelocity();
    }

    private void cancelQsAnimation() {
        if (mQsExpansionAnimator != null) {
            mQsExpansionAnimator.cancel();
        }
    }

    /**
     * @see #flingSettings(float, int, Runnable, boolean)
     */
    public void flingSettings(float vel, int type) {
        flingSettings(vel, type, null /* onFinishRunnable */, false /* isClick */);
    }

    /**
     * Animates QS or QQS as if the user had swiped up or down.
     *
     * @param vel              Finger velocity or 0 when not initiated by touch events.
     * @param type             Either {@link #FLING_EXPAND}, {@link #FLING_COLLAPSE} or {@link
     *                         #FLING_HIDE}.
     * @param onFinishRunnable Runnable to be executed at the end of animation.
     * @param isClick          If originated by click (different interpolator and duration.)
     */
    protected void flingSettings(float vel, int type, final Runnable onFinishRunnable,
            boolean isClick) {
        float target;
        switch (type) {
            case FLING_EXPAND:
                target = mQsMaxExpansionHeight;
                break;
            case FLING_COLLAPSE:
                target = mQsMinExpansionHeight;
                break;
            case FLING_HIDE:
            default:
                if (mQs != null) {
                    mQs.closeDetail();
                }
                target = 0;
        }
        if (target == mQsExpansionHeight) {
            if (onFinishRunnable != null) {
                onFinishRunnable.run();
            }
            traceQsJank(false /* startTracing */, type != FLING_EXPAND /* wasCancelled */);
            return;
        }

        // If we move in the opposite direction, reset velocity and use a different duration.
        boolean oppositeDirection = false;
        boolean expanding = type == FLING_EXPAND;
        if (vel > 0 && !expanding || vel < 0 && expanding) {
            vel = 0;
            oppositeDirection = true;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(mQsExpansionHeight, target);
        if (isClick) {
            animator.setInterpolator(Interpolators.TOUCH_RESPONSE);
            animator.setDuration(368);
        } else {
            mFlingAnimationUtils.apply(animator, mQsExpansionHeight, target, vel);
        }
        if (oppositeDirection) {
            animator.setDuration(350);
        }
        animator.addUpdateListener(animation -> {
            setQsExpansion((Float) animation.getAnimatedValue());
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mIsCanceled;
            @Override
            public void onAnimationStart(Animator animation) {
                notifyExpandingStarted();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mIsCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mQSAnimatingHiddenFromCollapsed = false;
                mAnimatingQS = false;
                notifyExpandingFinished();
                mNotificationStackScrollLayoutController.resetCheckSnoozeLeavebehind();
                mQsExpansionAnimator = null;
                if (onFinishRunnable != null) {
                    onFinishRunnable.run();
                }
                traceQsJank(false /* startTracing */, mIsCanceled /* wasCancelled */);
            }
        });
        // Let's note that we're animating QS. Moving the animator here will cancel it immediately,
        // so we need a separate flag.
        mAnimatingQS = true;
        animator.start();
        mQsExpansionAnimator = animator;
        mQsAnimatorExpand = expanding;
        mQSAnimatingHiddenFromCollapsed = computeQsExpansionFraction() == 0.0f && target == 0;
    }

    /**
     * @return Whether we should intercept a gesture to open Quick Settings.
     */
    private boolean shouldQuickSettingsIntercept(float x, float y, float yDiff) {
        if (!isQsExpansionEnabled() || mCollapsedOnDown
                || (mKeyguardShowing && mKeyguardBypassController.getBypassEnabled())
                || mSplitShadeEnabled) {
            return false;
        }
        View header = mKeyguardShowing || mQs == null ? mKeyguardStatusBar : mQs.getHeader();
        int frameTop = mKeyguardShowing || mQs == null ? 0 : mQsFrame.getTop();
        mQsInterceptRegion.set(
                /* left= */ (int) mQsFrame.getX(),
                /* top= */ header.getTop() + frameTop,
                /* right= */ (int) mQsFrame.getX() + mQsFrame.getWidth(),
                /* bottom= */ header.getBottom() + frameTop);
        // Also allow QS to intercept if the touch is near the notch.
        mStatusBarTouchableRegionManager.updateRegionForNotch(mQsInterceptRegion);
        final boolean onHeader = mQsInterceptRegion.contains((int) x, (int) y);

        if (mQsExpanded) {
            return onHeader || (yDiff < 0 && isInQsArea(x, y));
        } else {
            return onHeader;
        }
    }

    @Override
    protected boolean canCollapsePanelOnTouch() {
        if (!isInSettings() && mBarState == KEYGUARD) {
            return true;
        }

        if (mNotificationStackScrollLayoutController.isScrolledToBottom()) {
            return true;
        }

        return !mSplitShadeEnabled && (isInSettings() || mIsPanelCollapseOnQQS);
    }

    @Override
    protected int getMaxPanelHeight() {
        int min = mStatusBarMinHeight;
        if (!(mBarState == KEYGUARD)
                && mNotificationStackScrollLayoutController.getNotGoneChildCount() == 0) {
            int minHeight = mQsMinExpansionHeight;
            min = Math.max(min, minHeight);
        }
        int maxHeight;
        if (mQsExpandImmediate || mQsExpanded || mIsExpanding && mQsExpandedWhenExpandingStarted
                || mPulsing) {
            maxHeight = calculatePanelHeightQsExpanded();
        } else {
            maxHeight = calculatePanelHeightShade();
        }
        maxHeight = Math.max(min, maxHeight);
        if (maxHeight == 0 || isNaN(maxHeight)) {
            Log.wtf(TAG, "maxPanelHeight is invalid. mOverExpansion: "
                    + mOverExpansion + ", calculatePanelHeightQsExpanded: "
                    + calculatePanelHeightQsExpanded() + ", calculatePanelHeightShade: "
                    + calculatePanelHeightShade() + ", mStatusBarMinHeight = "
                    + mStatusBarMinHeight + ", mQsMinExpansionHeight = " + mQsMinExpansionHeight);
        }
        return maxHeight;
    }

    public boolean isInSettings() {
        return mQsExpanded;
    }

    public boolean isExpanding() {
        return mIsExpanding;
    }

    @Override
    protected void onHeightUpdated(float expandedHeight) {
        if (!mQsExpanded || mQsExpandImmediate || mIsExpanding && mQsExpandedWhenExpandingStarted) {
            // Updating the clock position will set the top padding which might
            // trigger a new panel height and re-position the clock.
            // This is a circular dependency and should be avoided, otherwise we'll have
            // a stack overflow.
            if (mStackScrollerMeasuringPass > 2) {
                if (DEBUG) Log.d(TAG, "Unstable notification panel height. Aborting.");
            } else {
                positionClockAndNotifications();
            }
        }
        if (mQsExpandImmediate || mQsExpanded && !mQsTracking && mQsExpansionAnimator == null
                && !mQsExpansionFromOverscroll) {
            float t;
            if (mKeyguardShowing) {

                // On Keyguard, interpolate the QS expansion linearly to the panel expansion
                t = expandedHeight / (getMaxPanelHeight());
            } else {
                // In Shade, interpolate linearly such that QS is closed whenever panel height is
                // minimum QS expansion + minStackHeight
                float
                        panelHeightQsCollapsed =
                        mNotificationStackScrollLayoutController.getIntrinsicPadding()
                                + mNotificationStackScrollLayoutController.getLayoutMinHeight();
                float panelHeightQsExpanded = calculatePanelHeightQsExpanded();
                t =
                        (expandedHeight - panelHeightQsCollapsed) / (panelHeightQsExpanded
                                - panelHeightQsCollapsed);
            }
            float
                    targetHeight =
                    mQsMinExpansionHeight + t * (mQsMaxExpansionHeight - mQsMinExpansionHeight);
            setQsExpansion(targetHeight);
        }
        updateExpandedHeight(expandedHeight);
        updateHeader();
        updateNotificationTranslucency();
        updatePanelExpanded();
        updateGestureExclusionRect();
        if (DEBUG) {
            mView.invalidate();
        }
    }

    private void updatePanelExpanded() {
        boolean isExpanded = !isFullyCollapsed() || mExpectingSynthesizedDown;
        if (mPanelExpanded != isExpanded) {
            mHeadsUpManager.setIsPanelExpanded(isExpanded);
            mStatusBarTouchableRegionManager.setPanelExpanded(isExpanded);
            mCentralSurfaces.setPanelExpanded(isExpanded);
            mPanelExpanded = isExpanded;

            if (!isExpanded && mQs != null && mQs.isCustomizing()) {
                mQs.closeCustomizer();
            }
        }
    }

    private int calculatePanelHeightShade() {
        final int maxHeight = mNotificationStackScrollLayoutController.getHeight();
        if (mBarState == KEYGUARD) {
            int minKeyguardPanelBottom = mClockPositionAlgorithm.getLockscreenStatusViewHeight()
                    + mNotificationStackScrollLayoutController.getIntrinsicContentHeight();
            return Math.max(maxHeight, minKeyguardPanelBottom);
        } else {
            return maxHeight;
        }
    }

    private int calculatePanelHeightQsExpanded() {
        float
                notificationHeight =
                mNotificationStackScrollLayoutController.getHeight()
                        - mNotificationStackScrollLayoutController.getEmptyBottomMargin()
                        - mNotificationStackScrollLayoutController.getTopPadding();

        // When only empty shade view is visible in QS collapsed state, simulate that we would have
        // it in expanded QS state as well so we don't run into troubles when fading the view in/out
        // and expanding/collapsing the whole panel from/to quick settings.
        if (mNotificationStackScrollLayoutController.getNotGoneChildCount() == 0
                && mNotificationStackScrollLayoutController.isShowingEmptyShadeView()) {
            notificationHeight = mNotificationStackScrollLayoutController.getEmptyShadeViewHeight();
        }
        int maxQsHeight = mQsMaxExpansionHeight;

        // If an animation is changing the size of the QS panel, take the animated value.
        if (mQsSizeChangeAnimator != null) {
            maxQsHeight = (int) mQsSizeChangeAnimator.getAnimatedValue();
        }
        float totalHeight = Math.max(maxQsHeight,
                mBarState == KEYGUARD ? mClockPositionResult.stackScrollerPadding
                        : 0) + notificationHeight
                + mNotificationStackScrollLayoutController.getTopPaddingOverflow();
        if (totalHeight > mNotificationStackScrollLayoutController.getHeight()) {
            float
                    fullyCollapsedHeight =
                    maxQsHeight + mNotificationStackScrollLayoutController.getLayoutMinHeight();
            totalHeight = Math.max(fullyCollapsedHeight,
                    mNotificationStackScrollLayoutController.getHeight());
        }
        return (int) totalHeight;
    }

    private void updateNotificationTranslucency() {
        float alpha = 1f;
        if (mClosingWithAlphaFadeOut && !mExpandingFromHeadsUp
                && !mHeadsUpManager.hasPinnedHeadsUp()) {
            alpha = getFadeoutAlpha();
        }
        if (mBarState == KEYGUARD && !mHintAnimationRunning
                && !mKeyguardBypassController.getBypassEnabled()) {
            alpha *= mClockPositionResult.clockAlpha;
        }
        mNotificationStackScrollLayoutController.setAlpha(alpha);
    }

    private float getFadeoutAlpha() {
        float alpha;
        if (mQsMinExpansionHeight == 0) {
            return 1.0f;
        }
        alpha = getExpandedHeight() / mQsMinExpansionHeight;
        alpha = Math.max(0, Math.min(alpha, 1));
        alpha = (float) Math.pow(alpha, 0.75);
        return alpha;
    }

    /**
     * Hides the header when notifications are colliding with it.
     */
    private void updateHeader() {
        if (mBarState == KEYGUARD) {
            mKeyguardStatusBarViewController.updateViewState();
        }
        updateQsExpansion();
    }

    protected float getHeaderTranslation() {
        if (mBarState == KEYGUARD && !mKeyguardBypassController.getBypassEnabled()) {
            return -mQs.getQsMinExpansionHeight();
        }
        float appearAmount = mNotificationStackScrollLayoutController
                .calculateAppearFraction(mExpandedHeight);
        float startHeight = -mQsExpansionHeight;
        if (!mSplitShadeEnabled && mBarState == StatusBarState.SHADE) {
            // Small parallax as we pull down and clip QS
            startHeight = -mQsExpansionHeight * QS_PARALLAX_AMOUNT;
        }
        if (mKeyguardBypassController.getBypassEnabled() && isOnKeyguard()) {
            appearAmount = mNotificationStackScrollLayoutController.calculateAppearFractionBypass();
            startHeight = -mQs.getQsMinExpansionHeight();
        }
        float translation = MathUtils.lerp(startHeight, 0, Math.min(1.0f, appearAmount));
        return Math.min(0, translation);
    }

    private void updateKeyguardBottomAreaAlpha() {
        // There are two possible panel expansion behaviors:
        // • User dragging up to unlock: we want to fade out as quick as possible
        //   (ALPHA_EXPANSION_THRESHOLD) to avoid seeing the bouncer over the bottom area.
        // • User tapping on lock screen: bouncer won't be visible but panel expansion will
        //   change due to "unlock hint animation." In this case, fading out the bottom area
        //   would also hide the message that says "swipe to unlock," we don't want to do that.
        float expansionAlpha = MathUtils.map(
                isUnlockHintRunning() ? 0 : KeyguardBouncer.ALPHA_EXPANSION_THRESHOLD, 1f, 0f, 1f,
                getExpandedFraction());
        float alpha = Math.min(expansionAlpha, 1 - computeQsExpansionFraction());
        alpha *= mBottomAreaShadeAlpha;
        mKeyguardBottomArea.setAffordanceAlpha(alpha);
        mKeyguardBottomArea.setImportantForAccessibility(
                alpha == 0f ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        View ambientIndicationContainer = mCentralSurfaces.getAmbientIndicationContainer();
        if (ambientIndicationContainer != null) {
            ambientIndicationContainer.setAlpha(alpha);
        }
        mLockIconViewController.setAlpha(alpha);
    }

    @Override
    protected void onExpandingStarted() {
        super.onExpandingStarted();
        mNotificationStackScrollLayoutController.onExpansionStarted();
        mIsExpanding = true;
        mQsExpandedWhenExpandingStarted = mQsFullyExpanded;
        mMediaHierarchyManager.setCollapsingShadeFromQS(mQsExpandedWhenExpandingStarted &&
                /* We also start expanding when flinging closed Qs. Let's exclude that */
                !mAnimatingQS);
        if (mQsExpanded) {
            onQsExpansionStarted();
        }
        // Since there are QS tiles in the header now, we need to make sure we start listening
        // immediately so they can be up to date.
        if (mQs == null) return;
        mQs.setHeaderListening(true);
    }

    @Override
    protected void onExpandingFinished() {
        mScrimController.onExpandingFinished();
        mNotificationStackScrollLayoutController.onExpansionStopped();
        mHeadsUpManager.onExpandingFinished();
        mConversationNotificationManager.onNotificationPanelExpandStateChanged(isFullyCollapsed());
        mIsExpanding = false;
        mMediaHierarchyManager.setCollapsingShadeFromQS(false);
        mMediaHierarchyManager.setQsExpanded(mQsExpanded);
        if (isFullyCollapsed()) {
            DejankUtils.postAfterTraversal(new Runnable() {
                @Override
                public void run() {
                    setListening(false);
                }
            });

            // Workaround b/22639032: Make sure we invalidate something because else RenderThread
            // thinks we are actually drawing a frame put in reality we don't, so RT doesn't go
            // ahead with rendering and we jank.
            mView.postOnAnimation(new Runnable() {
                @Override
                public void run() {
                    mView.getParent().invalidateChild(mView, M_DUMMY_DIRTY_RECT);
                }
            });
        } else {
            setListening(true);
        }
        mQsExpandImmediate = false;
        setShowShelfOnly(false);
        mTwoFingerQsExpandPossible = false;
        updateTrackingHeadsUp(null);
        mExpandingFromHeadsUp = false;
        setPanelScrimMinFraction(0.0f);
        // Reset status bar alpha so alpha can be calculated upon updating view state.
        setKeyguardStatusBarAlpha(-1f);
    }

    private void updateTrackingHeadsUp(@Nullable ExpandableNotificationRow pickedChild) {
        mTrackedHeadsUpNotification = pickedChild;
        for (int i = 0; i < mTrackingHeadsUpListeners.size(); i++) {
            Consumer<ExpandableNotificationRow> listener = mTrackingHeadsUpListeners.get(i);
            listener.accept(pickedChild);
        }
    }

    @Nullable
    public ExpandableNotificationRow getTrackedHeadsUpNotification() {
        return mTrackedHeadsUpNotification;
    }

    private void setListening(boolean listening) {
        mKeyguardStatusBarViewController.setBatteryListening(listening);
        if (mQs == null) return;
        mQs.setListening(listening);
    }

    @Override
    public void expand(boolean animate) {
        super.expand(animate);
        setListening(true);
    }

    @Override
    public void setOverExpansion(float overExpansion) {
        if (overExpansion == mOverExpansion) {
            return;
        }
        super.setOverExpansion(overExpansion);
        // Translating the quick settings by half the overexpansion to center it in the background
        // frame
        updateQsFrameTranslation();
        mNotificationStackScrollLayoutController.setOverExpansion(overExpansion);
    }

    private void updateQsFrameTranslation() {
        mQsFrameTranslateController.translateQsFrame(mQsFrame, mQs, mOverExpansion,
                mQsTranslationForFullShadeTransition);
    }

    @Override
    protected void onTrackingStarted() {
        mFalsingCollector.onTrackingStarted(!mKeyguardStateController.canDismissLockScreen());
        super.onTrackingStarted();
        mScrimController.onTrackingStarted();
        if (mQsFullyExpanded) {
            mQsExpandImmediate = true;
            setShowShelfOnly(true);
        }
        if (mBarState == KEYGUARD || mBarState == StatusBarState.SHADE_LOCKED) {
            mAffordanceHelper.animateHideLeftRightIcon();
        }
        mNotificationStackScrollLayoutController.onPanelTrackingStarted();
        cancelPendingPanelCollapse();
    }

    @Override
    protected void onTrackingStopped(boolean expand) {
        mFalsingCollector.onTrackingStopped();
        super.onTrackingStopped(expand);
        if (expand) {
            mNotificationStackScrollLayoutController.setOverScrollAmount(0.0f, true /* onTop */,
                    true /* animate */);
        }
        mNotificationStackScrollLayoutController.onPanelTrackingStopped();
        if (expand && (mBarState == KEYGUARD
                || mBarState == StatusBarState.SHADE_LOCKED)) {
            if (!mHintAnimationRunning) {
                mAffordanceHelper.reset(true);
            }
        }

        // If we unlocked from a swipe, the user's finger might still be down after the
        // unlock animation ends. We need to wait until ACTION_UP to enable blurs again.
        mDepthController.setBlursDisabledForUnlock(false);
    }

    private void updateMaxHeadsUpTranslation() {
        mNotificationStackScrollLayoutController.setHeadsUpBoundaries(
                getHeight(), mNavigationBarBottomHeight);
    }

    @Override
    protected void startUnlockHintAnimation() {
        if (mPowerManager.isPowerSaveMode() || mAmbientState.getDozeAmount() > 0f) {
            onUnlockHintStarted();
            onUnlockHintFinished();
            return;
        }
        super.startUnlockHintAnimation();
    }

    @Override
    protected void onUnlockHintFinished() {
        super.onUnlockHintFinished();
        mScrimController.setExpansionAffectsAlpha(true);
        mNotificationStackScrollLayoutController.setUnlockHintRunning(false);
    }

    @Override
    protected void onUnlockHintStarted() {
        super.onUnlockHintStarted();
        mScrimController.setExpansionAffectsAlpha(false);
        mNotificationStackScrollLayoutController.setUnlockHintRunning(true);
    }

    @Override
    protected boolean shouldUseDismissingAnimation() {
        return mBarState != StatusBarState.SHADE && (mKeyguardStateController.canDismissLockScreen()
                || !isTracking());
    }

    @Override
    protected boolean isTrackingBlocked() {
        return mConflictingQsExpansionGesture && mQsExpanded || mBlockingExpansionForCurrentTouch;
    }

    public boolean isQsExpanded() {
        return mQsExpanded;
    }

    public boolean isQsDetailShowing() {
        return mQs.isShowingDetail();
    }

    /** Returns whether the QS customizer is currently active. */
    public boolean isQsCustomizing() {
        return mQs.isCustomizing();
    }

    public void closeQsDetail() {
        mQs.closeDetail();
    }

    /** Close the QS customizer if it is open. */
    public void closeQsCustomizer() {
        mQs.closeCustomizer();
    }

    public boolean isLaunchTransitionFinished() {
        return mIsLaunchTransitionFinished;
    }

    public boolean isLaunchTransitionRunning() {
        return mIsLaunchTransitionRunning;
    }

    @Override
    public void setIsLaunchAnimationRunning(boolean running) {
        boolean wasRunning = mIsLaunchAnimationRunning;
        super.setIsLaunchAnimationRunning(running);
        if (wasRunning != mIsLaunchAnimationRunning) {
            mPanelEventsEmitter.notifyLaunchingActivityChanged(running);
        }
    }

    @Override
    protected void setIsClosing(boolean isClosing) {
        boolean wasClosing = isClosing();
        super.setIsClosing(isClosing);
        if (wasClosing != isClosing) {
            mPanelEventsEmitter.notifyPanelCollapsingChanged(isClosing);
        }
    }

    public void setLaunchTransitionEndRunnable(Runnable r) {
        mLaunchAnimationEndRunnable = r;
    }

    private void updateDozingVisibilities(boolean animate) {
        mKeyguardBottomArea.setDozing(mDozing, animate);
        if (!mDozing && animate) {
            mKeyguardStatusBarViewController.animateKeyguardStatusBarIn();
        }
    }

    @Override
    public boolean isDozing() {
        return mDozing;
    }

    public void setQsScrimEnabled(boolean qsScrimEnabled) {
        boolean changed = mQsScrimEnabled != qsScrimEnabled;
        mQsScrimEnabled = qsScrimEnabled;
        if (changed) {
            updateQsState();
        }
    }

    public void onScreenTurningOn() {
        mKeyguardStatusViewController.dozeTimeTick();
    }

    @Override
    protected boolean onMiddleClicked() {
        switch (mBarState) {
            case KEYGUARD:
                if (!mDozingOnDown) {
                    if (mUpdateMonitor.isFaceEnrolled()
                            && !mUpdateMonitor.isFaceDetectionRunning()
                            && !mUpdateMonitor.getUserCanSkipBouncer(
                                    KeyguardUpdateMonitor.getCurrentUser())) {
                        mUpdateMonitor.requestFaceAuth(true);
                    } else {
                        mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_HINT,
                                0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
                        mLockscreenGestureLogger
                            .log(LockscreenUiEvent.LOCKSCREEN_LOCK_SHOW_HINT);
                        startUnlockHintAnimation();
                    }
                    if (mUpdateMonitor.isFaceEnrolled()) {
                        mUpdateMonitor.requestActiveUnlock(
                                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.UNLOCK_INTENT,
                                "lockScreenEmptySpaceTap");
                    }
                }
                return true;
            case StatusBarState.SHADE_LOCKED:
                if (!mQsExpanded) {
                    mStatusBarStateController.setState(KEYGUARD);
                }
                return true;
            default:
                return true;
        }
    }

    public void setPanelAlpha(int alpha, boolean animate) {
        if (mPanelAlpha != alpha) {
            mPanelAlpha = alpha;
            PropertyAnimator.setProperty(mView, mPanelAlphaAnimator, alpha, alpha == 255
                            ? mPanelAlphaInPropertiesAnimator : mPanelAlphaOutPropertiesAnimator,
                    animate);
        }
    }

    public void setPanelAlphaEndAction(Runnable r) {
        mPanelAlphaEndAction = r;
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mHeadsUpAnimatingAway = headsUpAnimatingAway;
        mNotificationStackScrollLayoutController.setHeadsUpAnimatingAway(headsUpAnimatingAway);
        updateVisibility();
    }

    /** Set whether the bouncer is showing. */
    public void setBouncerShowing(boolean bouncerShowing) {
        mBouncerShowing = bouncerShowing;
        updateVisibility();
    }

    @Override
    protected boolean shouldPanelBeVisible() {
        boolean headsUpVisible = mHeadsUpAnimatingAway || mHeadsUpPinnedMode;
        return headsUpVisible || isExpanded() || mBouncerShowing;
    }

    @Override
    public void setHeadsUpManager(HeadsUpManagerPhone headsUpManager) {
        super.setHeadsUpManager(headsUpManager);
        mHeadsUpTouchHelper = new HeadsUpTouchHelper(headsUpManager,
                mNotificationStackScrollLayoutController.getHeadsUpCallback(),
                NotificationPanelViewController.this);
    }

    public void setTrackedHeadsUp(ExpandableNotificationRow pickedChild) {
        if (pickedChild != null) {
            updateTrackingHeadsUp(pickedChild);
            mExpandingFromHeadsUp = true;
        }
        // otherwise we update the state when the expansion is finished
    }

    @Override
    protected void onClosingFinished() {
        mCentralSurfaces.onClosingFinished();
        setClosingWithAlphaFadeout(false);
        mMediaHierarchyManager.closeGuts();
    }

    private void setClosingWithAlphaFadeout(boolean closing) {
        mClosingWithAlphaFadeOut = closing;
        mNotificationStackScrollLayoutController.forceNoOverlappingRendering(closing);
    }

    protected void updateExpandedHeight(float expandedHeight) {
        if (mTracking) {
            mNotificationStackScrollLayoutController
                    .setExpandingVelocity(getCurrentExpandVelocity());
        }
        if (mKeyguardBypassController.getBypassEnabled() && isOnKeyguard()) {
            // The expandedHeight is always the full panel Height when bypassing
            expandedHeight = getMaxPanelHeight();
        }
        mNotificationStackScrollLayoutController.setExpandedHeight(expandedHeight);
        updateKeyguardBottomAreaAlpha();
        updateStatusBarIcons();
    }

    /**
     * @return whether the notifications are displayed full width and don't have any margins on
     * the side.
     */
    public boolean isFullWidth() {
        return mIsFullWidth;
    }

    private void updateStatusBarIcons() {
        boolean
                showIconsWhenExpanded =
                (isPanelVisibleBecauseOfHeadsUp() || isFullWidth())
                        && getExpandedHeight() < getOpeningHeight();
        boolean noVisibleNotifications = true;
        if (showIconsWhenExpanded && noVisibleNotifications && isOnKeyguard()) {
            showIconsWhenExpanded = false;
        }
        if (showIconsWhenExpanded != mShowIconsWhenExpanded) {
            mShowIconsWhenExpanded = showIconsWhenExpanded;
            mCommandQueue.recomputeDisableFlags(mDisplayId, false);
        }
    }

    private boolean isOnKeyguard() {
        return mBarState == KEYGUARD;
    }

    /**
     * Sets the minimum fraction for the panel expansion offset. This may be non-zero in certain
     * cases, such as if there's a heads-up notification.
     */
    public void setPanelScrimMinFraction(float minFraction) {
        mMinFraction = minFraction;
        mDepthController.setPanelPullDownMinFraction(mMinFraction);
        mScrimController.setPanelScrimMinFraction(mMinFraction);
    }

    public void clearNotificationEffects() {
        mCentralSurfaces.clearNotificationEffects();
    }

    @Override
    protected boolean isPanelVisibleBecauseOfHeadsUp() {
        return (mHeadsUpManager.hasPinnedHeadsUp() || mHeadsUpAnimatingAway)
                && mBarState == StatusBarState.SHADE;
    }

    public void launchCamera(boolean animate, int source) {
        if (source == StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP) {
            mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP;
        } else if (source == StatusBarManager.CAMERA_LAUNCH_SOURCE_WIGGLE) {
            mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_WIGGLE;
        } else if (source == StatusBarManager.CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER) {
            mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER;
        } else {

            // Default.
            mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_AFFORDANCE;
        }

        // If we are launching it when we are occluded already we don't want it to animate,
        // nor setting these flags, since the occluded state doesn't change anymore, hence it's
        // never reset.
        if (!isFullyCollapsed()) {
            setLaunchingAffordance(true);
        } else {
            animate = false;
        }
        mAffordanceHasPreview = mKeyguardBottomArea.getRightPreview() != null;
        mAffordanceHelper.launchAffordance(
                animate, mView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
    }

    public void onAffordanceLaunchEnded() {
        setLaunchingAffordance(false);
    }

    /**
     * Set whether we are currently launching an affordance. This is currently only set when
     * launched via a camera gesture.
     */
    private void setLaunchingAffordance(boolean launchingAffordance) {
        mLaunchingAffordance = launchingAffordance;
        mKeyguardAffordanceHelperCallback.getLeftIcon().setLaunchingAffordance(launchingAffordance);
        mKeyguardAffordanceHelperCallback.getRightIcon().setLaunchingAffordance(
                launchingAffordance);
        mKeyguardBypassController.setLaunchingAffordance(launchingAffordance);
    }

    /**
     * Return true when a bottom affordance is launching an occluded activity with a splash screen.
     */
    public boolean isLaunchingAffordanceWithPreview() {
        return mLaunchingAffordance && mAffordanceHasPreview;
    }

    /**
     * Whether the camera application can be launched for the camera launch gesture.
     */
    public boolean canCameraGestureBeLaunched() {
        if (!mCentralSurfaces.isCameraAllowedByAdmin()) {
            return false;
        }

        ResolveInfo resolveInfo = mKeyguardBottomArea.resolveCameraIntent();
        String
                packageToLaunch =
                (resolveInfo == null || resolveInfo.activityInfo == null) ? null
                        : resolveInfo.activityInfo.packageName;
        return packageToLaunch != null && (mBarState != StatusBarState.SHADE || !isForegroundApp(
                packageToLaunch)) && !mAffordanceHelper.isSwipingInProgress();
    }

    /**
     * Return true if the applications with the package name is running in foreground.
     *
     * @param pkgName application package name.
     */
    private boolean isForegroundApp(String pkgName) {
        List<ActivityManager.RunningTaskInfo> tasks = mActivityManager.getRunningTasks(1);
        return !tasks.isEmpty() && pkgName.equals(tasks.get(0).topActivity.getPackageName());
    }

    public boolean hideStatusBarIconsWhenExpanded() {
        if (mIsLaunchAnimationRunning) {
            return mHideIconsDuringLaunchAnimation;
        }
        if (mHeadsUpAppearanceController != null
                && mHeadsUpAppearanceController.shouldBeVisible()) {
            return false;
        }
        return !isFullWidth() || !mShowIconsWhenExpanded;
    }

    public final QS.ScrollListener mScrollListener = new QS.ScrollListener() {
        @Override
        public void onQsPanelScrollChanged(int scrollY) {
            mLargeScreenShadeHeaderController.setQsScrollY(scrollY);
            if (scrollY > 0 && !mQsFullyExpanded) {
                if (DEBUG) Log.d(TAG, "Scrolling while not expanded. Forcing expand");
                // If we are scrolling QS, we should be fully expanded.
                expandWithQs();
            }
        }
    };

    private final FragmentListener mFragmentListener = new FragmentListener() {
        @Override
        public void onFragmentViewCreated(String tag, Fragment fragment) {
            mQs = (QS) fragment;
            mQs.setPanelView(mHeightListener);
            mQs.setCollapseExpandAction(mCollapseExpandAction);
            mQs.setHeaderClickable(isQsExpansionEnabled());
            mQs.setOverscrolling(mStackScrollerOverscrolling);
            mQs.setInSplitShade(mSplitShadeEnabled);

            // recompute internal state when qspanel height changes
            mQs.getView().addOnLayoutChangeListener(
                    (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                        final int height = bottom - top;
                        final int oldHeight = oldBottom - oldTop;
                        if (height != oldHeight) {
                            mHeightListener.onQsHeightChanged();
                        }
                    });
            mQs.setCollapsedMediaVisibilityChangedListener((visible) -> {
                if (mQs.getHeader().isShown()) {
                    animateNextNotificationBounds(StackStateAnimator.ANIMATION_DURATION_STANDARD,
                            0 /* delay */);
                    mNotificationStackScrollLayoutController.animateNextTopPaddingChange();
                }
            });
            mLockscreenShadeTransitionController.setQS(mQs);
            mShadeTransitionController.setQs(mQs);
            mNotificationStackScrollLayoutController.setQsHeader((ViewGroup) mQs.getHeader());
            mQs.setScrollListener(mScrollListener);
            updateQsExpansion();
        }

        @Override
        public void onFragmentViewDestroyed(String tag, Fragment fragment) {
            // Manual handling of fragment lifecycle is only required because this bridges
            // non-fragment and fragment code. Once we are using a fragment for the notification
            // panel, mQs will not need to be null cause it will be tied to the same lifecycle.
            if (fragment == mQs) {
                mQs = null;
            }
        }
    };

    private void animateNextNotificationBounds(long duration, long delay) {
        mAnimateNextNotificationBounds = true;
        mNotificationBoundsAnimationDuration = duration;
        mNotificationBoundsAnimationDelay = delay;
    }

    @Override
    public void setTouchAndAnimationDisabled(boolean disabled) {
        super.setTouchAndAnimationDisabled(disabled);
        if (disabled && mAffordanceHelper.isSwipingInProgress() && !mIsLaunchTransitionRunning) {
            mAffordanceHelper.reset(false /* animate */);
        }
        mNotificationStackScrollLayoutController.setAnimationsEnabled(!disabled);
    }

    /**
     * Sets the dozing state.
     *
     * @param dozing              {@code true} when dozing.
     * @param animate             if transition should be animated.
     * @param wakeUpTouchLocation touch event location - if woken up by SLPI sensor.
     */
    public void setDozing(boolean dozing, boolean animate, PointF wakeUpTouchLocation) {
        if (dozing == mDozing) return;
        mView.setDozing(dozing);
        mDozing = dozing;
        mNotificationStackScrollLayoutController.setDozing(mDozing, animate, wakeUpTouchLocation);
        mKeyguardBottomArea.setDozing(mDozing, animate);
        mKeyguardStatusBarViewController.setDozing(mDozing);

        if (dozing) {
            mBottomAreaShadeAlphaAnimator.cancel();
        }

        if (mBarState == KEYGUARD || mBarState == StatusBarState.SHADE_LOCKED) {
            updateDozingVisibilities(animate);
        }

        final float dozeAmount = dozing ? 1 : 0;
        mStatusBarStateController.setAndInstrumentDozeAmount(mView, dozeAmount, animate);
    }

    public void setPulsing(boolean pulsing) {
        mPulsing = pulsing;
        final boolean
                animatePulse =
                !mDozeParameters.getDisplayNeedsBlanking() && mDozeParameters.getAlwaysOn();
        if (animatePulse) {
            mAnimateNextPositionUpdate = true;
        }
        // Do not animate the clock when waking up from a pulse.
        // The height callback will take care of pushing the clock to the right position.
        if (!mPulsing && !mDozing) {
            mAnimateNextPositionUpdate = false;
        }
        mNotificationStackScrollLayoutController.setPulsing(pulsing, animatePulse);
    }

    public void setAmbientIndicationTop(int ambientIndicationTop, boolean ambientTextVisible) {
        int ambientIndicationBottomPadding = 0;
        if (ambientTextVisible) {
            int stackBottom = mNotificationStackScrollLayoutController.getView().getBottom();
            ambientIndicationBottomPadding = stackBottom - ambientIndicationTop;
        }
        if (mAmbientIndicationBottomPadding != ambientIndicationBottomPadding) {
            mAmbientIndicationBottomPadding = ambientIndicationBottomPadding;
            updateMaxDisplayedNotifications(true);
        }
    }

    public void dozeTimeTick() {
        mLockIconViewController.dozeTimeTick();
        mKeyguardBottomArea.dozeTimeTick();
        mKeyguardStatusViewController.dozeTimeTick();
        if (mInterpolatedDarkAmount > 0) {
            positionClockAndNotifications();
        }
    }

    public void setStatusAccessibilityImportance(int mode) {
        mKeyguardStatusViewController.setStatusAccessibilityImportance(mode);
    }

    /**
     * TODO: this should be removed.
     * It's not correct to pass this view forward because other classes will end up adding
     * children to it. Theme will be out of sync.
     *
     * @return bottom area view
     */
    public KeyguardBottomAreaView getKeyguardBottomAreaView() {
        return mKeyguardBottomArea;
    }

    public void setUserSetupComplete(boolean userSetupComplete) {
        mUserSetupComplete = userSetupComplete;
        mKeyguardBottomArea.setUserSetupComplete(userSetupComplete);
    }

    public void applyLaunchAnimationProgress(float linearProgress) {
        boolean hideIcons = LaunchAnimator.getProgress(ActivityLaunchAnimator.TIMINGS,
                linearProgress, ANIMATION_DELAY_ICON_FADE_IN, 100) == 0.0f;
        if (hideIcons != mHideIconsDuringLaunchAnimation) {
            mHideIconsDuringLaunchAnimation = hideIcons;
            if (!hideIcons) {
                mCommandQueue.recomputeDisableFlags(mDisplayId, true /* animate */);
            }
        }
    }

    public void addTrackingHeadsUpListener(Consumer<ExpandableNotificationRow> listener) {
        mTrackingHeadsUpListeners.add(listener);
    }

    public void removeTrackingHeadsUpListener(Consumer<ExpandableNotificationRow> listener) {
        mTrackingHeadsUpListeners.remove(listener);
    }

    public void setHeadsUpAppearanceController(
            HeadsUpAppearanceController headsUpAppearanceController) {
        mHeadsUpAppearanceController = headsUpAppearanceController;
    }

    /**
     * Starts the animation before we dismiss Keyguard, i.e. an disappearing animation on the
     * security view of the bouncer.
     */
    public void onBouncerPreHideAnimation() {
        if (mKeyguardQsUserSwitchController != null) {
            mKeyguardQsUserSwitchController.setKeyguardQsUserSwitchVisibility(
                    mBarState,
                    true /* keyguardFadingAway */,
                    false /* goingToFullShade */,
                    mBarState);
        }
        if (mKeyguardUserSwitcherController != null) {
            mKeyguardUserSwitcherController.setKeyguardUserSwitcherVisibility(
                    mBarState,
                    true /* keyguardFadingAway */,
                    false /* goingToFullShade */,
                    mBarState);
        }
    }

    /**
     * Updates the views to the initial state for the fold to AOD animation
     */
    public void prepareFoldToAodAnimation() {
        // Force show AOD UI even if we are not locked
        showAodUi();

        // Move the content of the AOD all the way to the left
        // so we can animate to the initial position
        final int translationAmount = mView.getResources().getDimensionPixelSize(
                R.dimen.below_clock_padding_start);
        mView.setTranslationX(-translationAmount);
        mView.setAlpha(0);
    }

    /**
     * Starts fold to AOD animation
     */
    public void startFoldToAodAnimation(Runnable endAction) {
        mView.animate()
            .translationX(0)
            .alpha(1f)
            .setDuration(ANIMATION_DURATION_FOLD_TO_AOD)
            .setInterpolator(EMPHASIZED_DECELERATE)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(Animator animation) {
                    endAction.run();
                }
                @Override
                public void onAnimationEnd(Animator animation) {
                    endAction.run();
                }
            })
            .start();

        mKeyguardStatusViewController.animateFoldToAod();
    }

    /**
     * Cancels fold to AOD transition and resets view state
     */
    public void cancelFoldToAodAnimation() {
        cancelAnimation();
        resetAlpha();
        resetTranslation();
    }

    /** */
    public void setImportantForAccessibility(int mode) {
        mView.setImportantForAccessibility(mode);
    }

    /**
     * Do not let the user drag the shade up and down for the current touch session.
     * This is necessary to avoid shade expansion while/after the bouncer is dismissed.
     */
    public void blockExpansionForCurrentTouch() {
        mBlockingExpansionForCurrentTouch = mTracking;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        super.dump(pw, args);
        IndentingPrintWriter ipw = asIndenting(pw);
        ipw.increaseIndent();
        ipw.println("gestureExclusionRect:" + calculateGestureExclusionRect());
        ipw.println("applyQSClippingImmediately: top(" + mQsClipTop + ") bottom(" + mQsClipBottom
                + ")");
        ipw.println("qsVisible:" + mQsVisible);
        new DumpsysTableLogger(
                TAG,
                NPVCDownEventState.TABLE_HEADERS,
                mLastDownEvents.toList()
        ).printTableData(ipw);
        ipw.decreaseIndent();
        if (mKeyguardStatusBarViewController != null) {
            mKeyguardStatusBarViewController.dump(pw, args);
        }
    }

    public RemoteInputController.Delegate createRemoteInputDelegate() {
        return mNotificationStackScrollLayoutController.createDelegate();
    }

    /**
     * Updates the notification views' sections and status bar icons. This is
     * triggered by the NotificationPresenter whenever there are changes to the underlying
     * notification data being displayed. In the new notification pipeline, this is handled in
     * {@link ShadeViewManager}.
     */
    public void updateNotificationViews(String reason) {
        mNotificationStackScrollLayoutController.updateSectionBoundaries(reason);
        mNotificationStackScrollLayoutController.updateFooter();

        mNotificationIconAreaController.updateNotificationIcons(createVisibleEntriesList());
    }

    private List<ListEntry> createVisibleEntriesList() {
        List<ListEntry> entries = new ArrayList<>(
                mNotificationStackScrollLayoutController.getChildCount());
        for (int i = 0; i < mNotificationStackScrollLayoutController.getChildCount(); i++) {
            View view = mNotificationStackScrollLayoutController.getChildAt(i);
            if (view instanceof ExpandableNotificationRow) {
                entries.add(((ExpandableNotificationRow) view).getEntry());
            }
        }
        return entries;
    }

    public void onUpdateRowStates() {
        mNotificationStackScrollLayoutController.onUpdateRowStates();
    }

    public boolean hasPulsingNotifications() {
        return mNotificationListContainer.hasPulsingNotifications();
    }

    public ActivatableNotificationView getActivatedChild() {
        return mNotificationStackScrollLayoutController.getActivatedChild();
    }

    public void setActivatedChild(ActivatableNotificationView o) {
        mNotificationStackScrollLayoutController.setActivatedChild(o);
    }

    public void runAfterAnimationFinished(Runnable r) {
        mNotificationStackScrollLayoutController.runAfterAnimationFinished(r);
    }

    private Runnable mHideExpandedRunnable;
    private final Runnable mMaybeHideExpandedRunnable = new Runnable() {
        @Override
        public void run() {
            if (getExpansionFraction() == 0.0f) {
                mView.post(mHideExpandedRunnable);
            }
        }
    };

    /**
     * Initialize objects instead of injecting to avoid circular dependencies.
     *
     * @param hideExpandedRunnable a runnable to run when we need to hide the expanded panel.
     */
    public void initDependencies(
            CentralSurfaces centralSurfaces,
            Runnable hideExpandedRunnable,
            NotificationShelfController notificationShelfController) {
        setCentralSurfaces(centralSurfaces);
        mHideExpandedRunnable = hideExpandedRunnable;
        mNotificationStackScrollLayoutController.setShelfController(notificationShelfController);
        mNotificationShelfController = notificationShelfController;
        mLockscreenShadeTransitionController.bindController(notificationShelfController);
        updateMaxDisplayedNotifications(true);
    }

    public void setAlpha(float alpha) {
        mView.setAlpha(alpha);
    }

    public void resetTranslation() {
        mView.setTranslationX(0f);
    }

    public void resetAlpha() {
        mView.setAlpha(1f);
    }

    public ViewPropertyAnimator fadeOut(long startDelayMs, long durationMs, Runnable endAction) {
        return mView.animate().alpha(0).setStartDelay(startDelayMs).setDuration(
                durationMs).setInterpolator(Interpolators.ALPHA_OUT).withLayer().withEndAction(
                endAction);
    }

    public void resetViewGroupFade() {
        ViewGroupFadeHelper.reset(mView);
    }

    public void addOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener listener) {
        mView.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    public void removeOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener listener) {
        mView.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
    }

    public MyOnHeadsUpChangedListener getOnHeadsUpChangedListener() {
        return mOnHeadsUpChangedListener;
    }

    public int getHeight() {
        return mView.getHeight();
    }

    public void setHeaderDebugInfo(String text) {
        if (DEBUG) mHeaderDebugInfo = text;
    }

    public void onThemeChanged() {
        mConfigurationListener.onThemeChanged();
    }

    @Override
    public OnLayoutChangeListener createLayoutChangeListener() {
        return new OnLayoutChangeListener();
    }

    @Override
    protected TouchHandler createTouchHandler() {
        return new TouchHandler() {

            private long mLastTouchDownTime = -1L;

            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
                if (mBlockTouches || mQs.disallowPanelTouches()) {
                    return false;
                }
                initDownStates(event);
                // Do not let touches go to shade or QS if the bouncer is visible,
                // but still let user swipe down to expand the panel, dismissing the bouncer.
                if (mCentralSurfaces.isBouncerShowing()) {
                    return true;
                }
                if (mCommandQueue.panelsEnabled()
                        && !mNotificationStackScrollLayoutController.isLongPressInProgress()
                        && mHeadsUpTouchHelper.onInterceptTouchEvent(event)) {
                    mMetricsLogger.count(COUNTER_PANEL_OPEN, 1);
                    mMetricsLogger.count(COUNTER_PANEL_OPEN_PEEK, 1);
                    return true;
                }
                if (!shouldQuickSettingsIntercept(mDownX, mDownY, 0)
                        && mPulseExpansionHandler.onInterceptTouchEvent(event)) {
                    return true;
                }

                if (!isFullyCollapsed() && onQsIntercept(event)) {
                    if (DEBUG) Log.d(TAG, "onQsIntercept true");
                    return true;
                }
                return super.onInterceptTouchEvent(event);
            }

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    if (event.getDownTime() == mLastTouchDownTime) {
                        // An issue can occur when swiping down after unlock, where multiple down
                        // events are received in this handler with identical downTimes. Until the
                        // source of the issue can be located, detect this case and ignore.
                        // see b/193350347
                        Log.w(TAG, "Duplicate down event detected... ignoring");
                        return true;
                    }
                    mLastTouchDownTime = event.getDownTime();
                }


                if (mBlockTouches || (mQsFullyExpanded && mQs != null
                        && mQs.disallowPanelTouches())) {
                    return false;
                }

                // Do not allow panel expansion if bouncer is scrimmed or showing over a dream,
                // otherwise user would be able to pull down QS or expand the shade.
                if (mCentralSurfaces.isBouncerShowingScrimmed()
                        || mCentralSurfaces.isBouncerShowingOverDream()) {
                    return false;
                }

                // Make sure the next touch won't the blocked after the current ends.
                if (event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    mBlockingExpansionForCurrentTouch = false;
                }
                // When touch focus transfer happens, ACTION_DOWN->ACTION_UP may happen immediately
                // without any ACTION_MOVE event.
                // In such case, simply expand the panel instead of being stuck at the bottom bar.
                if (mLastEventSynthesizedDown && event.getAction() == MotionEvent.ACTION_UP) {
                    expand(true /* animate */);
                }
                initDownStates(event);

                // If pulse is expanding already, let's give it the touch. There are situations
                // where the panel starts expanding even though we're also pulsing
                boolean pulseShouldGetTouch = (!mIsExpanding
                        && !shouldQuickSettingsIntercept(mDownX, mDownY, 0))
                        || mPulseExpansionHandler.isExpanding();
                if (pulseShouldGetTouch && mPulseExpansionHandler.onTouchEvent(event)) {
                    // We're expanding all the other ones shouldn't get this anymore
                    return true;
                }
                if (mListenForHeadsUp && !mHeadsUpTouchHelper.isTrackingHeadsUp()
                        && !mNotificationStackScrollLayoutController.isLongPressInProgress()
                        && mHeadsUpTouchHelper.onInterceptTouchEvent(event)) {
                    mMetricsLogger.count(COUNTER_PANEL_OPEN_PEEK, 1);
                }
                boolean handled = false;
                if ((!mIsExpanding || mHintAnimationRunning) && !mQsExpanded
                        && mBarState != StatusBarState.SHADE && !mDozing) {
                    handled |= mAffordanceHelper.onTouchEvent(event);
                }
                if (mOnlyAffordanceInThisMotion) {
                    return true;
                }
                handled |= mHeadsUpTouchHelper.onTouchEvent(event);

                if (!mHeadsUpTouchHelper.isTrackingHeadsUp() && handleQsTouch(event)) {
                    if (DEBUG) Log.d(TAG, "handleQsTouch true");
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isFullyCollapsed()) {
                    mMetricsLogger.count(COUNTER_PANEL_OPEN, 1);
                    handled = true;
                }

                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isFullyExpanded()
                        && mStatusBarKeyguardViewManager.isShowing()) {
                    mStatusBarKeyguardViewManager.updateKeyguardPosition(event.getX());
                }

                handled |= super.onTouch(v, event);
                return !mDozing || mPulsing || handled;
            }
        };
    }

    private final PhoneStatusBarView.TouchEventHandler mStatusBarViewTouchEventHandler =
            new PhoneStatusBarView.TouchEventHandler() {
                @Override
                public void onInterceptTouchEvent(MotionEvent event) {
                    mCentralSurfaces.onTouchEvent(event);
                }

                @Override
                public boolean handleTouchEvent(MotionEvent event) {
                    mCentralSurfaces.onTouchEvent(event);

                    // TODO(b/202981994): Move the touch debugging in this method to a central
                    //  location. (Right now, it's split between CentralSurfaces and here.)

                    // If panels aren't enabled, ignore the gesture and don't pass it down to the
                    // panel view.
                    if (!mCommandQueue.panelsEnabled()) {
                        if (event.getAction() == MotionEvent.ACTION_DOWN) {
                            Log.v(
                                    TAG,
                                    String.format(
                                            "onTouchForwardedFromStatusBar: "
                                                    + "panel disabled, ignoring touch at (%d,%d)",
                                            (int) event.getX(),
                                            (int) event.getY()
                                    )
                            );
                        }
                        return false;
                    }

                    // If the view that would receive the touch is disabled, just have status bar
                    // eat the gesture.
                    if (event.getAction() == MotionEvent.ACTION_DOWN && !mView.isEnabled()) {
                        Log.v(TAG,
                                String.format(
                                        "onTouchForwardedFromStatusBar: "
                                                + "panel view disabled, eating touch at (%d,%d)",
                                        (int) event.getX(),
                                        (int) event.getY()
                                )
                        );
                        return true;
                    }

                    return mView.dispatchTouchEvent(event);
                }
            };

    @Override
    protected PanelViewController.OnConfigurationChangedListener
            createOnConfigurationChangedListener() {
        return new OnConfigurationChangedListener();
    }

    public NotificationStackScrollLayoutController getNotificationStackScrollLayoutController() {
        return mNotificationStackScrollLayoutController;
    }

    public void disable(int state1, int state2, boolean animated) {
        mLargeScreenShadeHeaderController.disable(state1, state2, animated);
    }

    /**
     * Close the keyguard user switcher if it is open and capable of closing.
     *
     * Has no effect if user switcher isn't supported, if the user switcher is already closed, or
     * if the user switcher uses "simple" mode. The simple user switcher cannot be closed.
     *
     * @return true if the keyguard user switcher was open, and is now closed
     */
    public boolean closeUserSwitcherIfOpen() {
        if (mKeyguardUserSwitcherController != null) {
            return mKeyguardUserSwitcherController.closeSwitcherIfOpenAndNotSimple(
                    true /* animate */);
        }
        return false;
    }

    private void updateUserSwitcherFlags() {
        mKeyguardUserSwitcherEnabled = mResources.getBoolean(
                com.android.internal.R.bool.config_keyguardUserSwitcher);
        mKeyguardQsUserSwitchEnabled =
                mKeyguardUserSwitcherEnabled
                        && mFeatureFlags.isEnabled(Flags.QS_USER_DETAIL_SHORTCUT);
    }

    private void registerSettingsChangeListener() {
        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.USER_SWITCHER_ENABLED),
                /* notifyForDescendants */ false,
                mSettingsChangeObserver
        );
    }

    private void unregisterSettingsChangeListener() {
        mContentResolver.unregisterContentObserver(mSettingsChangeObserver);
    }

    /**
     * Updates notification panel-specific flags on {@link SysUiState}.
     */
    public void updateSystemUiStateFlags() {
        if (SysUiState.DEBUG) {
            Log.d(TAG, "Updating panel sysui state flags: fullyExpanded="
                    + isFullyExpanded() + " inQs=" + isInSettings());
        }
        mSysUiState.setFlag(SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED,
                isFullyExpanded() && !isInSettings())
                .setFlag(SYSUI_STATE_QUICK_SETTINGS_EXPANDED, isInSettings())
                .commitUpdate(mDisplayId);
    }

    private class OnHeightChangedListener implements ExpandableView.OnHeightChangedListener {
        @Override
        public void onHeightChanged(ExpandableView view, boolean needsAnimation) {

            // Block update if we are in quick settings and just the top padding changed
            // (i.e. view == null).
            if (view == null && mQsExpanded) {
                return;
            }
            if (needsAnimation && mInterpolatedDarkAmount == 0) {
                mAnimateNextPositionUpdate = true;
            }
            ExpandableView firstChildNotGone =
                    mNotificationStackScrollLayoutController.getFirstChildNotGone();
            ExpandableNotificationRow
                    firstRow =
                    firstChildNotGone instanceof ExpandableNotificationRow
                            ? (ExpandableNotificationRow) firstChildNotGone : null;
            if (firstRow != null && (view == firstRow || (firstRow.getNotificationParent()
                    == firstRow))) {
                requestScrollerTopPaddingUpdate(false /* animate */);
            }
            requestPanelHeightUpdate();
        }

        @Override
        public void onReset(ExpandableView view) {
        }
    }

    private class CollapseExpandAction implements Runnable {
        @Override
        public void run() {
            onQsExpansionStarted();
            if (mQsExpanded) {
                flingSettings(0 /* vel */, FLING_COLLAPSE, null /* onFinishRunnable */,
                        true /* isClick */);
            } else if (isQsExpansionEnabled()) {
                mLockscreenGestureLogger.write(MetricsEvent.ACTION_SHADE_QS_TAP, 0, 0);
                flingSettings(0 /* vel */, FLING_EXPAND, null /* onFinishRunnable */,
                        true /* isClick */);
            }
        }
    }

    private class OnOverscrollTopChangedListener implements
            NotificationStackScrollLayout.OnOverscrollTopChangedListener {
        @Override
        public void onOverscrollTopChanged(float amount, boolean isRubberbanded) {
            // When in split shade, overscroll shouldn't carry through to QS
            if (mSplitShadeEnabled) {
                return;
            }
            cancelQsAnimation();
            if (!isQsExpansionEnabled()) {
                amount = 0f;
            }
            float rounded = amount >= 1f ? amount : 0f;
            setOverScrolling(rounded != 0f && isRubberbanded);
            mQsExpansionFromOverscroll = rounded != 0f;
            mLastOverscroll = rounded;
            updateQsState();
            setQsExpansion(mQsMinExpansionHeight + rounded);
        }

        @Override
        public void flingTopOverscroll(float velocity, boolean open) {
            // in split shade mode we want to expand/collapse QS only when touch happens within QS
            if (mSplitShadeEnabled && touchXOutsideOfQs(mInitialTouchX)) {
                return;
            }
            mLastOverscroll = 0f;
            mQsExpansionFromOverscroll = false;
            if (open) {
                // During overscrolling, qsExpansion doesn't actually change that the qs is
                // becoming expanded. Any layout could therefore reset the position again. Let's
                // make sure we can expand
                setOverScrolling(false);
            }
            setQsExpansion(mQsExpansionHeight);
            boolean canExpand = isQsExpansionEnabled();
            flingSettings(!canExpand && open ? 0f : velocity,
                    open && canExpand ? FLING_EXPAND : FLING_COLLAPSE, () -> {
                        setOverScrolling(false);
                        updateQsState();
                    }, false /* isClick */);
        }
    }

    private class DynamicPrivacyControlListener implements DynamicPrivacyController.Listener {
        @Override
        public void onDynamicPrivacyChanged() {
            // Do not request animation when pulsing or waking up, otherwise the clock wiill be out
            // of sync with the notification panel.
            if (mLinearDarkAmount != 0) {
                return;
            }
            mAnimateNextPositionUpdate = true;
        }
    }

    private class KeyguardAffordanceHelperCallback implements KeyguardAffordanceHelper.Callback {
        @Override
        public void onAnimationToSideStarted(boolean rightPage, float translation, float vel) {
            boolean
                    start =
                    mView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? rightPage
                            : !rightPage;
            mIsLaunchTransitionRunning = true;
            mLaunchAnimationEndRunnable = null;
            float displayDensity = mCentralSurfaces.getDisplayDensity();
            int lengthDp = Math.abs((int) (translation / displayDensity));
            int velocityDp = Math.abs((int) (vel / displayDensity));
            if (start) {
                mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_DIALER, lengthDp, velocityDp);
                mLockscreenGestureLogger.log(LockscreenUiEvent.LOCKSCREEN_DIALER);
                mFalsingCollector.onLeftAffordanceOn();
                if (mFalsingCollector.shouldEnforceBouncer()) {
                    mCentralSurfaces.executeRunnableDismissingKeyguard(
                            () -> mKeyguardBottomArea.launchLeftAffordance(), null,
                            true /* dismissShade */, false /* afterKeyguardGone */,
                            true /* deferred */);
                } else {
                    mKeyguardBottomArea.launchLeftAffordance();
                }
            } else {
                if (KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_AFFORDANCE.equals(
                        mLastCameraLaunchSource)) {
                    mLockscreenGestureLogger.write(
                            MetricsEvent.ACTION_LS_CAMERA, lengthDp, velocityDp);
                    mLockscreenGestureLogger.log(LockscreenUiEvent.LOCKSCREEN_CAMERA);
                }
                mFalsingCollector.onCameraOn();
                if (mFalsingCollector.shouldEnforceBouncer()) {
                    mCentralSurfaces.executeRunnableDismissingKeyguard(
                            () -> mKeyguardBottomArea.launchCamera(mLastCameraLaunchSource), null,
                            true /* dismissShade */, false /* afterKeyguardGone */,
                            true /* deferred */);
                } else {
                    mKeyguardBottomArea.launchCamera(mLastCameraLaunchSource);
                }
            }
            mCentralSurfaces.startLaunchTransitionTimeout();
            mBlockTouches = true;
        }

        @Override
        public void onAnimationToSideEnded() {
            mIsLaunchTransitionRunning = false;
            mIsLaunchTransitionFinished = true;
            if (mLaunchAnimationEndRunnable != null) {
                mLaunchAnimationEndRunnable.run();
                mLaunchAnimationEndRunnable = null;
            }
            mCentralSurfaces.readyForKeyguardDone();
        }

        @Override
        public float getMaxTranslationDistance() {
            return (float) Math.hypot(mView.getWidth(), getHeight());
        }

        @Override
        public void onSwipingStarted(boolean rightIcon) {
            mFalsingCollector.onAffordanceSwipingStarted(rightIcon);
            boolean
                    camera =
                    mView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? !rightIcon
                            : rightIcon;
            if (camera) {
                mKeyguardBottomArea.bindCameraPrewarmService();
            }
            mView.requestDisallowInterceptTouchEvent(true);
            mOnlyAffordanceInThisMotion = true;
            mQsTracking = false;
        }

        @Override
        public void onSwipingAborted() {
            mFalsingCollector.onAffordanceSwipingAborted();
            mKeyguardBottomArea.unbindCameraPrewarmService(false /* launched */);
        }

        @Override
        public void onIconClicked(boolean rightIcon) {
            if (mHintAnimationRunning) {
                return;
            }
            mHintAnimationRunning = true;
            mAffordanceHelper.startHintAnimation(rightIcon, () -> {
                mHintAnimationRunning = false;
                mCentralSurfaces.onHintFinished();
            });
            rightIcon =
                    mView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? !rightIcon
                            : rightIcon;
            if (rightIcon) {
                mCentralSurfaces.onCameraHintStarted();
            } else {
                if (mKeyguardBottomArea.isLeftVoiceAssist()) {
                    mCentralSurfaces.onVoiceAssistHintStarted();
                } else {
                    mCentralSurfaces.onPhoneHintStarted();
                }
            }
        }

        @Override
        public KeyguardAffordanceView getLeftIcon() {
            return mView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? mKeyguardBottomArea.getRightView() : mKeyguardBottomArea.getLeftView();
        }

        @Override
        public KeyguardAffordanceView getRightIcon() {
            return mView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? mKeyguardBottomArea.getLeftView() : mKeyguardBottomArea.getRightView();
        }

        @Override
        public View getLeftPreview() {
            return mView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? mKeyguardBottomArea.getRightPreview() : mKeyguardBottomArea.getLeftPreview();
        }

        @Override
        public View getRightPreview() {
            return mView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? mKeyguardBottomArea.getLeftPreview() : mKeyguardBottomArea.getRightPreview();
        }

        @Override
        public float getAffordanceFalsingFactor() {
            return mCentralSurfaces.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
        }

        @Override
        public boolean needsAntiFalsing() {
            return mBarState == KEYGUARD;
        }
    }

    private class OnEmptySpaceClickListener implements
            NotificationStackScrollLayout.OnEmptySpaceClickListener {
        @Override
        public void onEmptySpaceClicked(float x, float y) {
            onEmptySpaceClick(x);
        }
    }

    private class MyOnHeadsUpChangedListener implements OnHeadsUpChangedListener {
        @Override
        public void onHeadsUpPinnedModeChanged(final boolean inPinnedMode) {
            if (inPinnedMode) {
                mHeadsUpExistenceChangedRunnable.run();
                updateNotificationTranslucency();
            } else {
                setHeadsUpAnimatingAway(true);
                mNotificationStackScrollLayoutController.runAfterAnimationFinished(
                        mHeadsUpExistenceChangedRunnable);
            }
            updateGestureExclusionRect();
            mHeadsUpPinnedMode = inPinnedMode;
            updateVisibility();
            mKeyguardStatusBarViewController.updateForHeadsUp();
        }

        @Override
        public void onHeadsUpPinned(NotificationEntry entry) {
            if (!isOnKeyguard()) {
                mNotificationStackScrollLayoutController.generateHeadsUpAnimation(
                        entry.getHeadsUpAnimationView(), true);
            }
        }

        @Override
        public void onHeadsUpUnPinned(NotificationEntry entry) {

            // When we're unpinning the notification via active edge they remain heads-upped,
            // we need to make sure that an animation happens in this case, otherwise the
            // notification
            // will stick to the top without any interaction.
            if (isFullyCollapsed() && entry.isRowHeadsUp() && !isOnKeyguard()) {
                mNotificationStackScrollLayoutController.generateHeadsUpAnimation(
                        entry.getHeadsUpAnimationView(), false);
                entry.setHeadsUpIsVisible();
            }
        }
    }

    private class HeightListener implements QS.HeightListener {
        public void onQsHeightChanged() {
            mQsMaxExpansionHeight = mQs != null ? mQs.getDesiredHeight() : 0;
            if (mQsExpanded && mQsFullyExpanded) {
                mQsExpansionHeight = mQsMaxExpansionHeight;
                requestScrollerTopPaddingUpdate(false /* animate */);
                requestPanelHeightUpdate();
            }
            if (mAccessibilityManager.isEnabled()) {
                mView.setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
            }
            mNotificationStackScrollLayoutController.setMaxTopPadding(mQsMaxExpansionHeight);
        }
    }

    private class ConfigurationListener implements ConfigurationController.ConfigurationListener {
        @Override
        public void onThemeChanged() {
            if (DEBUG) Log.d(TAG, "onThemeChanged");
            mThemeResId = mView.getContext().getThemeResId();
            reInflateViews();
        }

        @Override
        public void onSmallestScreenWidthChanged() {
            Trace.beginSection("onSmallestScreenWidthChanged");
            if (DEBUG) Log.d(TAG, "onSmallestScreenWidthChanged");

            // Can affect multi-user switcher visibility as it depends on screen size by default:
            // it is enabled only for devices with large screens (see config_keyguardUserSwitcher)
            boolean prevKeyguardUserSwitcherEnabled = mKeyguardUserSwitcherEnabled;
            boolean prevKeyguardQsUserSwitchEnabled = mKeyguardQsUserSwitchEnabled;
            updateUserSwitcherFlags();
            if (prevKeyguardUserSwitcherEnabled != mKeyguardUserSwitcherEnabled
                    || prevKeyguardQsUserSwitchEnabled != mKeyguardQsUserSwitchEnabled) {
                reInflateViews();
            }

            Trace.endSection();
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            if (DEBUG) Log.d(TAG, "onDensityOrFontScaleChanged");
            reInflateViews();
        }
    }

    private class SettingsChangeObserver extends ContentObserver {

        SettingsChangeObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            if (DEBUG) Log.d(TAG, "onSettingsChanged");

            // Can affect multi-user switcher visibility
            reInflateViews();
        }
    }

    private class StatusBarStateListener implements StateListener {
        @Override
        public void onStateChanged(int statusBarState) {
            boolean goingToFullShade = mStatusBarStateController.goingToFullShade();
            boolean keyguardFadingAway = mKeyguardStateController.isKeyguardFadingAway();
            int oldState = mBarState;
            boolean keyguardShowing = statusBarState == KEYGUARD;

            if (mDozeParameters.shouldDelayKeyguardShow()
                    && oldState == StatusBarState.SHADE
                    && statusBarState == KEYGUARD) {
                // This means we're doing the screen off animation - position the keyguard status
                // view where it'll be on AOD, so we can animate it in.
                mKeyguardStatusViewController.updatePosition(
                        mClockPositionResult.clockX,
                        mClockPositionResult.clockYFullyDozing,
                        mClockPositionResult.clockScale,
                        false /* animate */);
            }

            mKeyguardStatusViewController.setKeyguardStatusViewVisibility(
                    statusBarState,
                    keyguardFadingAway,
                    goingToFullShade,
                    mBarState);

            setKeyguardBottomAreaVisibility(statusBarState, goingToFullShade);

            mBarState = statusBarState;
            mKeyguardShowing = keyguardShowing;

            if (oldState == KEYGUARD && (goingToFullShade
                    || statusBarState == StatusBarState.SHADE_LOCKED)) {

                long startDelay;
                long duration;
                if (mKeyguardStateController.isKeyguardFadingAway()) {
                    startDelay = mKeyguardStateController.getKeyguardFadingAwayDelay();
                    duration = mKeyguardStateController.getShortenedFadingAwayDuration();
                } else {
                    startDelay = 0;
                    duration = StackStateAnimator.ANIMATION_DURATION_STANDARD;
                }
                mKeyguardStatusBarViewController.animateKeyguardStatusBarOut(startDelay, duration);
                updateQSMinHeight();
            } else if (oldState == StatusBarState.SHADE_LOCKED
                    && statusBarState == KEYGUARD) {
                mKeyguardStatusBarViewController.animateKeyguardStatusBarIn();

                mNotificationStackScrollLayoutController.resetScrollPosition();
                // Only animate header if the header is visible. If not, it will partially
                // animate out
                // the top of QS
                if (!mQsExpanded) {
                    // TODO(b/185683835) Nicer clipping when using new spacial model
                    if (mSplitShadeEnabled) {
                        mQs.animateHeaderSlidingOut();
                    }
                }
            } else {
                final boolean animatingUnlockedShadeToKeyguard = oldState == SHADE
                        && statusBarState == KEYGUARD
                        && mScreenOffAnimationController.isKeyguardShowDelayed();
                if (!animatingUnlockedShadeToKeyguard) {
                    // Only make the status bar visible if we're not animating the screen off, since
                    // we only want to be showing the clock/notifications during the animation.
                    mKeyguardStatusBarViewController.updateViewState(
                            /* alpha= */ 1f,
                            keyguardShowing ? View.VISIBLE : View.INVISIBLE);
                }
                if (keyguardShowing && oldState != mBarState) {
                    if (mQs != null) {
                        mQs.hideImmediately();
                    }
                }
            }
            mKeyguardStatusBarViewController.updateForHeadsUp();
            if (keyguardShowing) {
                updateDozingVisibilities(false /* animate */);
            }

            updateMaxDisplayedNotifications(false);
            // The update needs to happen after the headerSlide in above, otherwise the translation
            // would reset
            maybeAnimateBottomAreaAlpha();
            updateQsState();
        }

        @Override
        public void onDozeAmountChanged(float linearAmount, float amount) {
            mInterpolatedDarkAmount = amount;
            mLinearDarkAmount = linearAmount;
            mKeyguardStatusViewController.setDarkAmount(mInterpolatedDarkAmount);
            mKeyguardBottomArea.setDarkAmount(mInterpolatedDarkAmount);
            positionClockAndNotifications();
        }
    }

    /**
     * An interface that provides the current state of the notification panel and related views,
     * which is needed to calculate {@link KeyguardStatusBarView}'s state in
     * {@link KeyguardStatusBarViewController}.
     */
    public interface NotificationPanelViewStateProvider {
        /** Returns the expanded height of the panel view. */
        float getPanelViewExpandedHeight();
        /**
         * Returns true if heads up should be visible.
         *
         * TODO(b/138786270): If HeadsUpAppearanceController was injectable, we could inject it into
         * {@link KeyguardStatusBarViewController} and remove this method.
         */
        boolean shouldHeadsUpBeVisible();

        /** Return the fraction of the shade that's expanded, when in lockscreen. */
        float getLockscreenShadeDragProgress();
    }

    private final NotificationPanelViewStateProvider mNotificationPanelViewStateProvider =
            new NotificationPanelViewStateProvider() {
                @Override
                public float getPanelViewExpandedHeight() {
                    return getExpandedHeight();
                }

                @Override
                public boolean shouldHeadsUpBeVisible() {
                    return mHeadsUpAppearanceController.shouldBeVisible();
                }

                @Override
                public float getLockscreenShadeDragProgress() {
                    return mTransitioningToFullShadeProgress > 0
                            ? mLockscreenShadeTransitionController.getQSDragProgress()
                            : computeQsExpansionFraction();
                }
            };

    /**
     * Reconfigures the shade to show the AOD UI (clock, smartspace, etc). This is called by the
     * screen off animation controller in order to animate in AOD without "actually" fully switching
     * to the KEYGUARD state, which is a heavy transition that causes jank as 10+ files react to the
     * change.
     */
    public void showAodUi() {
        setDozing(true /* dozing */, false /* animate */, null);
        mStatusBarStateController.setUpcomingState(KEYGUARD);
        mEntryManager.updateNotifications("showAodUi");
        mStatusBarStateListener.onStateChanged(KEYGUARD);
        mStatusBarStateListener.onDozeAmountChanged(1f, 1f);
        setExpandedFraction(1f);
    }

    /**
     * Sets the overstretch amount in raw pixels when dragging down.
     */
    public void setOverStrechAmount(float amount) {
        float progress = amount / mView.getHeight();
        float overstretch = Interpolators.getOvershootInterpolation(progress);
        mOverStretchAmount = overstretch * mMaxOverscrollAmountForPulse;
        positionClockAndNotifications(true /* forceUpdate */);
    }

    private class OnAttachStateChangeListener implements View.OnAttachStateChangeListener {
        @Override
        public void onViewAttachedToWindow(View v) {
            mFragmentService.getFragmentHostManager(mView)
                            .addTagListener(QS.TAG, mFragmentListener);
            mStatusBarStateController.addCallback(mStatusBarStateListener);
            mConfigurationController.addCallback(mConfigurationListener);
            // Theme might have changed between inflating this view and attaching it to the
            // window, so
            // force a call to onThemeChanged
            mConfigurationListener.onThemeChanged();
            mFalsingManager.addTapListener(mFalsingTapListener);
            mKeyguardIndicationController.init();
            registerSettingsChangeListener();
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            unregisterSettingsChangeListener();
            mFragmentService.getFragmentHostManager(mView)
                            .removeTagListener(QS.TAG, mFragmentListener);
            mStatusBarStateController.removeCallback(mStatusBarStateListener);
            mConfigurationController.removeCallback(mConfigurationListener);
            mFalsingManager.removeTapListener(mFalsingTapListener);
        }
    }

    private class OnLayoutChangeListener extends PanelViewController.OnLayoutChangeListener {

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                int oldTop, int oldRight, int oldBottom) {
            DejankUtils.startDetectingBlockingIpcs("NVP#onLayout");
            super.onLayoutChange(v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom);
            updateMaxDisplayedNotifications(!shouldAvoidChangingNotificationsCount());
            setIsFullWidth(mNotificationStackScrollLayoutController.getWidth() == mView.getWidth());

            // Update Clock Pivot
            mKeyguardStatusViewController.setPivotX(mView.getWidth() / 2);
            mKeyguardStatusViewController.setPivotY(
                    (FONT_HEIGHT - CAP_HEIGHT) / 2048f
                            * mKeyguardStatusViewController.getClockTextSize());

            // Calculate quick setting heights.
            int oldMaxHeight = mQsMaxExpansionHeight;
            if (mQs != null) {
                updateQSMinHeight();
                mQsMaxExpansionHeight = mQs.getDesiredHeight();
                mNotificationStackScrollLayoutController.setMaxTopPadding(mQsMaxExpansionHeight);
            }
            positionClockAndNotifications();
            if (mQsExpanded && mQsFullyExpanded) {
                mQsExpansionHeight = mQsMaxExpansionHeight;
                requestScrollerTopPaddingUpdate(false /* animate */);
                requestPanelHeightUpdate();

                // Size has changed, start an animation.
                if (mQsMaxExpansionHeight != oldMaxHeight) {
                    startQsSizeChangeAnimation(oldMaxHeight, mQsMaxExpansionHeight);
                }
            } else if (!mQsExpanded && mQsExpansionAnimator == null) {
                setQsExpansion(mQsMinExpansionHeight + mLastOverscroll);
            }
            updateExpandedHeight(getExpandedHeight());
            updateHeader();

            // If we are running a size change animation, the animation takes care of the height of
            // the container. However, if we are not animating, we always need to make the QS
            // container
            // the desired height so when closing the QS detail, it stays smaller after the size
            // change
            // animation is finished but the detail view is still being animated away (this
            // animation
            // takes longer than the size change animation).
            if (mQsSizeChangeAnimator == null && mQs != null) {
                mQs.setHeightOverride(mQs.getDesiredHeight());
            }
            updateMaxHeadsUpTranslation();
            updateGestureExclusionRect();
            if (mExpandAfterLayoutRunnable != null) {
                mExpandAfterLayoutRunnable.run();
                mExpandAfterLayoutRunnable = null;
            }
            DejankUtils.stopDetectingBlockingIpcs("NVP#onLayout");
        }
    }

    private void updateQSMinHeight() {
        float previousMin = mQsMinExpansionHeight;
        if (mKeyguardShowing || mSplitShadeEnabled) {
            mQsMinExpansionHeight = 0;
        } else {
            mQsMinExpansionHeight = mQs.getQsMinExpansionHeight();
        }
        if (mQsExpansionHeight == previousMin) {
            mQsExpansionHeight = mQsMinExpansionHeight;
        }
    }

    private class DebugDrawable extends Drawable {

        private final Set<Integer> mDebugTextUsedYPositions = new HashSet<>();
        private final Paint mDebugPaint = new Paint();

        @Override
        public void draw(@NonNull Canvas canvas) {
            mDebugTextUsedYPositions.clear();

            mDebugPaint.setColor(Color.RED);
            mDebugPaint.setStrokeWidth(2);
            mDebugPaint.setStyle(Paint.Style.STROKE);
            mDebugPaint.setTextSize(24);
            if (mHeaderDebugInfo != null) canvas.drawText(mHeaderDebugInfo, 50, 100, mDebugPaint);

            drawDebugInfo(canvas, getMaxPanelHeight(), Color.RED, "getMaxPanelHeight()");
            drawDebugInfo(canvas, (int) getExpandedHeight(), Color.BLUE, "getExpandedHeight()");
            drawDebugInfo(canvas, calculatePanelHeightQsExpanded(), Color.GREEN,
                    "calculatePanelHeightQsExpanded()");
            drawDebugInfo(canvas, calculatePanelHeightShade(), Color.YELLOW,
                    "calculatePanelHeightShade()");
            drawDebugInfo(canvas, (int) calculateNotificationsTopPadding(), Color.MAGENTA,
                    "calculateNotificationsTopPadding()");
            drawDebugInfo(canvas, mClockPositionResult.clockY, Color.GRAY,
                    "mClockPositionResult.clockY");
            drawDebugInfo(canvas, (int) mLockIconViewController.getTop(), Color.GRAY,
                    "mLockIconViewController.getTop()");

            if (mKeyguardShowing) {
                // Notifications have the space between those two lines.
                drawDebugInfo(canvas,
                        mNotificationStackScrollLayoutController.getTop() +
                                (int) mKeyguardNotificationTopPadding,
                        Color.RED,
                        "NSSL.getTop() + mKeyguardNotificationTopPadding");

                drawDebugInfo(canvas, mNotificationStackScrollLayoutController.getBottom() -
                                (int) mKeyguardNotificationBottomPadding,
                        Color.RED,
                        "NSSL.getBottom() - mKeyguardNotificationBottomPadding");
            }

            mDebugPaint.setColor(Color.CYAN);
            canvas.drawLine(0, mClockPositionResult.stackScrollerPadding, mView.getWidth(),
                    mNotificationStackScrollLayoutController.getTopPadding(), mDebugPaint);
        }

        private void drawDebugInfo(Canvas canvas, int y, int color, String label) {
            mDebugPaint.setColor(color);
            canvas.drawLine(/* startX= */ 0, /* startY= */ y, /* stopX= */ mView.getWidth(),
                    /* stopY= */ y, mDebugPaint);
            canvas.drawText(label, /* x= */ 0, /* y= */ computeDebugYTextPosition(y), mDebugPaint);
        }

        private int computeDebugYTextPosition(int lineY) {
            if (lineY - mDebugPaint.getTextSize() < 0) {
                // Avoiding drawing out of bounds
                lineY += mDebugPaint.getTextSize();
            }
            int textY = lineY;
            while (mDebugTextUsedYPositions.contains(textY)) {
                textY = (int) (textY + mDebugPaint.getTextSize());
            }
            mDebugTextUsedYPositions.add(textY);
            return textY;
        }

        @Override
        public void setAlpha(int alpha) {

        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {

        }

        @Override
        public int getOpacity() {
            return 0;
        }
    }

    private class OnConfigurationChangedListener extends
            PanelViewController.OnConfigurationChangedListener {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            mAffordanceHelper.onConfigurationChanged();
        }
    }

    private class OnApplyWindowInsetsListener implements View.OnApplyWindowInsetsListener {
        public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            // the same types of insets that are handled in NotificationShadeWindowView
            int insetTypes = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
            Insets combinedInsets = insets.getInsetsIgnoringVisibility(insetTypes);
            mDisplayTopInset = combinedInsets.top;
            mDisplayRightInset = combinedInsets.right;

            mNavigationBarBottomHeight = insets.getStableInsetBottom();
            updateMaxHeadsUpTranslation();
            return insets;
        }
    }

    /** Removes any pending runnables that would collapse the panel. */
    public void cancelPendingPanelCollapse() {
        mView.removeCallbacks(mMaybeHideExpandedRunnable);
    }

    @PanelState
    private int mCurrentPanelState = STATE_CLOSED;

    private void onPanelStateChanged(@PanelState int state) {
        updateQSExpansionEnabledAmbient();

        if (state == STATE_OPEN && mCurrentPanelState != state) {
            mView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
        if (state == STATE_OPENING) {
            // we need to ignore it on keyguard as this is a false alarm - transition from unlocked
            // to locked will trigger this event and we're not actually in the process of opening
            // the shade, lockscreen is just always expanded
            if (mSplitShadeEnabled && !isOnKeyguard()) {
                mQsExpandImmediate = true;
            }
            mCentralSurfaces.makeExpandedVisible(false);
        }
        if (state == STATE_CLOSED) {
            // Close the status bar in the next frame so we can show the end of the
            // animation.
            mView.post(mMaybeHideExpandedRunnable);
        }
        mCurrentPanelState = state;
    }

    /** Returns the handler that the status bar should forward touches to. */
    public PhoneStatusBarView.TouchEventHandler getStatusBarTouchEventHandler() {
        return mStatusBarViewTouchEventHandler;
    }

    private void onStatusBarWindowStateChanged(@StatusBarManager.WindowVisibleState int state) {
        if (state != WINDOW_STATE_SHOWING
                && mStatusBarStateController.getState() == StatusBarState.SHADE) {
            collapsePanel(
                    false /* animate */,
                    false /* delayed */,
                    1.0f /* speedUpFactor */);
        }
    }

    @SysUISingleton
    static class PanelEventsEmitter implements NotifPanelEvents {

        private final ListenerSet<Listener> mListeners = new ListenerSet<>();

        @Inject
        PanelEventsEmitter() {}

        @Override
        public void registerListener(@NonNull Listener listener) {
            mListeners.addIfAbsent(listener);
        }

        @Override
        public void unregisterListener(@NonNull Listener listener) {
            mListeners.remove(listener);
        }

        private void notifyLaunchingActivityChanged(boolean isLaunchingActivity) {
            for (Listener cb : mListeners) {
                cb.onLaunchingActivityChanged(isLaunchingActivity);
            }
        }

        private void notifyPanelCollapsingChanged(boolean isCollapsing) {
            for (NotifPanelEvents.Listener cb : mListeners) {
                cb.onPanelCollapsingChanged(isCollapsing);
            }
        }
    }
}
