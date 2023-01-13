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

package com.android.systemui.shade;

import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import static androidx.constraintlayout.widget.ConstraintSet.END;
import static androidx.constraintlayout.widget.ConstraintSet.PARENT_ID;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE;
import static com.android.keyguard.KeyguardClockSwitch.LARGE;
import static com.android.keyguard.KeyguardClockSwitch.SMALL;
import static com.android.systemui.animation.Interpolators.EMPHASIZED_ACCELERATE;
import static com.android.systemui.animation.Interpolators.EMPHASIZED_DECELERATE;
import static com.android.systemui.classifier.Classifier.BOUNCER_UNLOCK;
import static com.android.systemui.classifier.Classifier.GENERIC;
import static com.android.systemui.classifier.Classifier.QS_COLLAPSE;
import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;
import static com.android.systemui.classifier.Classifier.UNLOCK;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_CLOSED;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_OPEN;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_OPENING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;
import static com.android.systemui.statusbar.VibratorHelper.TOUCH_VIBRATION_ATTRIBUTES;
import static com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_FOLD_TO_AOD;
import static com.android.systemui.util.DumpUtilsKt.asIndenting;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import static java.lang.Float.isNaN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Fragment;
import android.app.StatusBarManager;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.Trace;
import android.os.UserManager;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.transition.ChangeBounds;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.transition.TransitionValues;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.MathUtils;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

import androidx.constraintlayout.widget.ConstraintSet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.policy.SystemBarUtils;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.ActiveUnlockConfig;
import com.android.keyguard.FaceAuthApiRequestReason;
import com.android.keyguard.KeyguardClockSwitch.ClockSize;
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
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.animation.LaunchAnimator;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.dump.DumpsysTableLogger;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.domain.interactor.KeyguardBottomAreaInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.TransitionState;
import com.android.systemui.keyguard.shared.model.TransitionStep;
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBottomAreaViewModel;
import com.android.systemui.keyguard.ui.viewmodel.OccludedToLockscreenTransitionViewModel;
import com.android.systemui.media.controls.pipeline.MediaDataManager;
import com.android.systemui.media.controls.ui.KeyguardMediaController;
import com.android.systemui.media.controls.ui.MediaHierarchyManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.FalsingManager.FalsingTapListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.shade.transition.ShadeTransitionController;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.GestureRecorder;
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
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.ConversationNotificationManager;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.ViewGroupFadeHelper;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.BounceInterpolator;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.HeadsUpTouchHelper;
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView;
import com.android.systemui.statusbar.phone.KeyguardBottomAreaViewController;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardClockPositionAlgorithm;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.KeyguardStatusBarViewController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger.LockscreenUiEvent;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarTouchableRegionManager;
import com.android.systemui.statusbar.phone.TapAgainViewController;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardQsUserSwitchController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcherController;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcherView;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.unfold.SysUIUnfoldComponent;
import com.android.systemui.util.Compile;
import com.android.systemui.util.LargeScreenUtils;
import com.android.systemui.util.Utils;
import com.android.systemui.util.time.SystemClock;
import com.android.wm.shell.animation.FlingAnimationUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Provider;

import kotlinx.coroutines.CoroutineDispatcher;

@CentralSurfacesComponent.CentralSurfacesScope
public final class NotificationPanelViewController implements Dumpable {

    public static final String TAG = NotificationPanelView.class.getSimpleName();
    public static final float FLING_MAX_LENGTH_SECONDS = 0.6f;
    public static final float FLING_SPEED_UP_FACTOR = 0.6f;
    public static final float FLING_CLOSING_MAX_LENGTH_SECONDS = 0.6f;
    public static final float FLING_CLOSING_SPEED_UP_FACTOR = 0.6f;
    private static final boolean DEBUG_LOGCAT = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean SPEW_LOGCAT = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.VERBOSE);
    private static final boolean DEBUG_DRAWABLE = false;
    private static final VibrationEffect ADDITIONAL_TAP_REQUIRED_VIBRATION_EFFECT =
            VibrationEffect.get(VibrationEffect.EFFECT_STRENGTH_MEDIUM, false);
    /** The parallax amount of the quick settings translation when dragging down the panel. */
    private static final float QS_PARALLAX_AMOUNT = 0.175f;
    /** Fling expanding QS. */
    public static final int FLING_EXPAND = 0;
    /** Fling collapsing QS, potentially stopping when QS becomes QQS. */
    private static final int FLING_COLLAPSE = 1;
    /** Fling until QS is completely hidden. */
    private static final int FLING_HIDE = 2;
    /** The delay to reset the hint text when the hint animation is finished running. */
    private static final int HINT_RESET_DELAY_MS = 1200;
    private static final long ANIMATION_DELAY_ICON_FADE_IN =
            ActivityLaunchAnimator.TIMINGS.getTotalDuration()
                    - CollapsedStatusBarFragment.FADE_IN_DURATION
                    - CollapsedStatusBarFragment.FADE_IN_DELAY - 48;
    private static final int NO_FIXED_DURATION = -1;
    private static final long SHADE_OPEN_SPRING_OUT_DURATION = 350L;
    private static final long SHADE_OPEN_SPRING_BACK_DURATION = 400L;
    /**
     * The factor of the usual high velocity that is needed in order to reach the maximum overshoot
     * when flinging. A low value will make it that most flings will reach the maximum overshoot.
     */
    private static final float FACTOR_OF_HIGH_VELOCITY_FOR_MAX_OVERSHOOT = 0.5f;
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
    /**
     * Duration to use for the animator when the keyguard status view alignment changes, and a
     * custom clock animation is in use.
     */
    private static final int KEYGUARD_STATUS_VIEW_CUSTOM_CLOCK_MOVE_DURATION = 1000;

    private final StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    private final Resources mResources;
    private final KeyguardStateController mKeyguardStateController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final AmbientState mAmbientState;
    private final LockscreenGestureLogger mLockscreenGestureLogger;
    private final SystemClock mSystemClock;
    private final ShadeLogger mShadeLog;
    private final DozeParameters mDozeParameters;
    private final Runnable mCollapseExpandAction = this::collapseOrExpand;
    private final NsslOverscrollTopChangedListener mOnOverscrollTopChangedListener =
            new NsslOverscrollTopChangedListener();
    private final NotificationStackScrollLayout.OnEmptySpaceClickListener
            mOnEmptySpaceClickListener = (x, y) -> onEmptySpaceClick();
    private final ShadeHeadsUpChangedListener mOnHeadsUpChangedListener =
            new ShadeHeadsUpChangedListener();
    private final QS.HeightListener mHeightListener = this::onQsHeightChanged;
    private final ConfigurationListener mConfigurationListener = new ConfigurationListener();
    private final SettingsChangeObserver mSettingsChangeObserver;
    private final StatusBarStateListener mStatusBarStateListener = new StatusBarStateListener();
    private final NotificationPanelView mView;
    private final VibratorHelper mVibratorHelper;
    private final MetricsLogger mMetricsLogger;
    private final ConfigurationController mConfigurationController;
    private final Provider<FlingAnimationUtils.Builder> mFlingAnimationUtilsBuilder;
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
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
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final ShadeTransitionController mShadeTransitionController;
    private final TapAgainViewController mTapAgainViewController;
    private final LargeScreenShadeHeaderController mLargeScreenShadeHeaderController;
    private final RecordingController mRecordingController;
    private final boolean mVibrateOnOpening;
    private final VelocityTracker mVelocityTracker = VelocityTracker.obtain();
    private final FlingAnimationUtils mFlingAnimationUtilsClosing;
    private final FlingAnimationUtils mFlingAnimationUtilsDismissing;
    private final LatencyTracker mLatencyTracker;
    private final DozeLog mDozeLog;
    /** Whether or not the NotificationPanelView can be expanded or collapsed with a drag. */
    private final boolean mNotificationsDragEnabled;
    private final Interpolator mBounceInterpolator;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final ShadeExpansionStateManager mShadeExpansionStateManager;
    private final QS.ScrollListener mQsScrollListener = this::onQsPanelScrollChanged;
    private final FalsingTapListener mFalsingTapListener = this::falsingAdditionalTapRequired;
    private final FragmentListener mQsFragmentListener = new QsFragmentListener();
    private final AccessibilityDelegate mAccessibilityDelegate = new ShadeAccessibilityDelegate();
    private final NotificationGutsManager mGutsManager;

    private long mDownTime;
    private boolean mTouchSlopExceededBeforeDown;
    private boolean mIsLaunchAnimationRunning;
    private float mOverExpansion;
    private CentralSurfaces mCentralSurfaces;
    private HeadsUpManagerPhone mHeadsUpManager;
    private float mExpandedHeight = 0;
    private boolean mTracking;
    private boolean mHintAnimationRunning;
    private KeyguardBottomAreaView mKeyguardBottomArea;
    private boolean mExpanding;
    private boolean mSplitShadeEnabled;
    /** The bottom padding reserved for elements of the keyguard measuring notifications. */
    private float mKeyguardNotificationBottomPadding;
    /**
     * The top padding from where notification should start in lockscreen.
     * Should be static also during animations and should match the Y of the first notification.
     */
    private float mKeyguardNotificationTopPadding;
    /** Current max allowed keyguard notifications determined by measuring the panel. */
    private int mMaxAllowedKeyguardNotifications;
    private KeyguardQsUserSwitchController mKeyguardQsUserSwitchController;
    private KeyguardUserSwitcherController mKeyguardUserSwitcherController;
    private KeyguardStatusBarView mKeyguardStatusBar;
    private KeyguardStatusBarViewController mKeyguardStatusBarViewController;
    private QS mQs;
    private FrameLayout mQsFrame;
    private final QsFrameTranslateController mQsFrameTranslateController;
    private KeyguardStatusViewController mKeyguardStatusViewController;
    private final LockIconViewController mLockIconViewController;
    private NotificationsQuickSettingsContainer mNotificationContainerParent;
    private final NotificationsQSContainerController mNotificationsQSContainerController;
    private final Provider<KeyguardBottomAreaViewController>
            mKeyguardBottomAreaViewControllerProvider;
    private boolean mAnimateNextPositionUpdate;
    private float mQuickQsHeaderHeight;
    private final ScreenOffAnimationController mScreenOffAnimationController;
    private final UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    private int mQsTrackingPointer;
    private VelocityTracker mQsVelocityTracker;
    private TrackingStartedListener mTrackingStartedListener;
    private OpenCloseListener mOpenCloseListener;
    private GestureRecorder mGestureRecorder;
    private boolean mQsTracking;
    /** Whether the ongoing gesture might both trigger the expansion in both the view and QS. */
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

    private final KeyguardClockPositionAlgorithm
            mClockPositionAlgorithm =
            new KeyguardClockPositionAlgorithm();
    private final KeyguardClockPositionAlgorithm.Result
            mClockPositionResult =
            new KeyguardClockPositionAlgorithm.Result();
    private boolean mIsExpanding;

    /**
     * Determines if QS should be already expanded when expanding shade.
     * Used for split shade, two finger gesture as well as accessibility shortcut to QS.
     * It needs to be set when movement starts as it resets at the end of expansion/collapse.
     */
    private boolean mQsExpandImmediate;
    private boolean mTwoFingerQsExpandPossible;
    private String mHeaderDebugInfo;
    /**
     * If we are in a panel collapsing motion, we reset scrollY of our scroll view but still
     * need to take this into account in our panel height calculation.
     */
    private boolean mQsAnimatorExpand;
    private ValueAnimator mQsSizeChangeAnimator;
    private boolean mQsScrimEnabled = true;
    private boolean mQsTouchAboveFalsingThreshold;
    private int mQsFalsingThreshold;

    /**
     * Indicates drag starting height when swiping down or up on heads-up notifications.
     * This usually serves as a threshold from when shade expansion should really start. Otherwise
     * this value would be height of shade and it will be immediately expanded to some extent.
     */
    private int mHeadsUpStartHeight;
    private HeadsUpTouchHelper mHeadsUpTouchHelper;
    private boolean mListenForHeadsUp;
    private int mNavigationBarBottomHeight;
    private boolean mExpandingFromHeadsUp;
    private boolean mCollapsedOnDown;
    private boolean mClosingWithAlphaFadeOut;
    private boolean mHeadsUpAnimatingAway;
    private final FalsingManager mFalsingManager;
    private final FalsingCollector mFalsingCollector;

    private boolean mShowIconsWhenExpanded;
    private int mIndicationBottomPadding;
    private int mAmbientIndicationBottomPadding;
    /** Whether the notifications are displayed full width (no margins on the side). */
    private boolean mIsFullWidth;
    private boolean mBlockingExpansionForCurrentTouch;
     // Following variables maintain state of events when input focus transfer may occur.
    private boolean mExpectingSynthesizedDown;
    private boolean mLastEventSynthesizedDown;

    /** Current dark amount that follows regular interpolation curve of animation. */
    private float mInterpolatedDarkAmount;
    /**
     * Dark amount that animates from 0 to 1 or vice-versa in linear manner, even if the
     * interpolation curve is different.
     */
    private float mLinearDarkAmount;
    private boolean mPulsing;
    private boolean mHideIconsDuringLaunchAnimation = true;
    private int mStackScrollerMeasuringPass;
    /** Non-null if a heads-up notification's position is being tracked. */
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

    private final CommandQueue mCommandQueue;
    private final UserManager mUserManager;
    private final MediaDataManager mMediaDataManager;
    @PanelState
    private int mCurrentPanelState = STATE_CLOSED;
    private final SysUiState mSysUiState;
    private final NotificationShadeDepthController mDepthController;
    private final NavigationBarController mNavigationBarController;
    private final int mDisplayId;

    private final KeyguardIndicationController mKeyguardIndicationController;
    private int mHeadsUpInset;
    private boolean mHeadsUpPinnedMode;
    private boolean mAllowExpandForSmallExpansion;
    private Runnable mExpandAfterLayoutRunnable;
    private Runnable mHideExpandedRunnable;

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
    /** Distance a full shade transition takes in order for qs to fully transition to the shade. */
    private int mDistanceForQSFullShadeTransition;
    /** The translation amount for QS for the full shade transition. */
    private float mQsTranslationForFullShadeTransition;

    /** The maximum overshoot allowed for the top padding for the full shade transition. */
    private int mMaxOverscrollAmountForPulse;
    /** Should we animate the next bounds update. */
    private boolean mAnimateNextNotificationBounds;
    /** The delay for the next bounds animation. */
    private long mNotificationBoundsAnimationDelay;
    /** The duration of the notification bounds animation. */
    private long mNotificationBoundsAnimationDuration;

    /** Whether a collapse that started on the panel should allow the panel to intercept. */
    private boolean mIsPanelCollapseOnQQS;
    private boolean mAnimatingQS;
    /** The end bounds of a clipping animation. */
    private final Rect mQsClippingAnimationEndBounds = new Rect();
    /** The animator for the qs clipping bounds. */
    private ValueAnimator mQsClippingAnimation = null;
    /** Whether the current animator is resetting the qs translation. */
    private boolean mIsQsTranslationResetAnimator;

    /** Whether the current animator is resetting the pulse expansion after a drag down. */
    private boolean mIsPulseExpansionResetAnimator;
    private final Rect mLastQsClipBounds = new Rect();
    private final Region mQsInterceptRegion = new Region();
    /** Alpha of the views which only show on the keyguard but not in shade / shade locked. */
    private float mKeyguardOnlyContentAlpha = 1.0f;
    /** Y translation of the views that only show on the keyguard but in shade / shade locked. */
    private int mKeyguardOnlyTransitionTranslationY = 0;
    private float mUdfpsMaxYBurnInOffset;
    /** Are we currently in gesture navigation. */
    private boolean mIsGestureNavigation;
    private int mOldLayoutDirection;
    private NotificationShelfController mNotificationShelfController;
    private int mScrimCornerRadius;
    private int mScreenCornerRadius;
    private boolean mQSAnimatingHiddenFromCollapsed;
    private boolean mUseLargeScreenShadeHeader;
    private boolean mEnableQsClipping;

    private int mQsClipTop;
    private int mQsClipBottom;
    private boolean mQsVisible;

    private final ContentResolver mContentResolver;
    private float mMinFraction;

    private final KeyguardMediaController mKeyguardMediaController;

    private boolean mStatusViewCentered = true;

    private final Optional<KeyguardUnfoldTransition> mKeyguardUnfoldTransition;
    private final Optional<NotificationPanelUnfoldAnimationController>
            mNotificationPanelUnfoldAnimationController;

    /** The drag distance required to fully expand the split shade. */
    private int mSplitShadeFullTransitionDistance;
    /** The drag distance required to fully transition scrims. */
    private int mSplitShadeScrimTransitionDistance;

    private final NotificationListContainer mNotificationListContainer;
    private final NotificationStackSizeCalculator mNotificationStackSizeCalculator;
    private final NPVCDownEventState.Buffer mLastDownEvents;
    private final KeyguardBottomAreaViewModel mKeyguardBottomAreaViewModel;
    private final KeyguardBottomAreaInteractor mKeyguardBottomAreaInteractor;
    private float mMinExpandHeight;
    private ShadeHeightLogger mShadeHeightLogger;
    private boolean mPanelUpdateWhenAnimatorEnds;
    private boolean mHasVibratedOnOpen = false;
    private int mFixedDuration = NO_FIXED_DURATION;
    /** The overshoot amount when the panel flings open. */
    private float mPanelFlingOvershootAmount;
    /** The amount of pixels that we have overexpanded the last time with a gesture. */
    private float mLastGesturedOverExpansion = -1;
    /** Whether the current animator is the spring back animation. */
    private boolean mIsSpringBackAnimation;
    private float mHintDistance;
    private float mInitialOffsetOnTouch;
    private boolean mCollapsedAndHeadsUpOnDown;
    private float mExpandedFraction = 0;
    private float mExpansionDragDownAmountPx = 0;
    private boolean mPanelClosedOnDown;
    private boolean mHasLayoutedSinceDown;
    private float mUpdateFlingVelocity;
    private boolean mUpdateFlingOnLayout;
    private boolean mClosing;
    private boolean mTouchSlopExceeded;
    private int mTrackingPointer;
    private int mTouchSlop;
    private float mSlopMultiplier;
    private boolean mTouchAboveFalsingThreshold;
    private boolean mTouchStartedInEmptyArea;
    private boolean mMotionAborted;
    private boolean mUpwardsWhenThresholdReached;
    private boolean mAnimatingOnDown;
    private boolean mHandlingPointerUp;
    private ValueAnimator mHeightAnimator;
    /** Whether an instant expand request is currently pending and we are waiting for layout. */
    private boolean mInstantExpanding;
    private boolean mAnimateAfterExpanding;
    private boolean mIsFlinging;
    private String mViewName;
    private float mInitialExpandY;
    private float mInitialExpandX;
    private boolean mTouchDisabled;
    private boolean mInitialTouchFromKeyguard;
    /** Speed-up factor to be used when {@link #mFlingCollapseRunnable} runs the next time. */
    private float mNextCollapseSpeedUpFactor = 1.0f;
    private boolean mGestureWaitForTouchSlop;
    private boolean mIgnoreXTouchSlop;
    private boolean mExpandLatencyTracking;
    private DreamingToLockscreenTransitionViewModel mDreamingToLockscreenTransitionViewModel;
    private OccludedToLockscreenTransitionViewModel mOccludedToLockscreenTransitionViewModel;

    private KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    private CoroutineDispatcher mMainDispatcher;
    private boolean mIsToLockscreenTransitionRunning = false;
    private int mDreamingToLockscreenTransitionTranslationY;
    private int mOccludedToLockscreenTransitionTranslationY;
    private boolean mUnocclusionTransitionFlagEnabled = false;

    private final Runnable mFlingCollapseRunnable = () -> fling(0, false /* expand */,
            mNextCollapseSpeedUpFactor, false /* expandBecauseOfFalsing */);
    private final Runnable mAnimateKeyguardBottomAreaInvisibleEndRunnable =
            () -> mKeyguardBottomArea.setVisibility(View.GONE);
    private final Runnable mHeadsUpExistenceChangedRunnable = () -> {
        setHeadsUpAnimatingAway(false);
        updatePanelExpansionAndVisibility();
    };
    private final Runnable mMaybeHideExpandedRunnable = () -> {
        if (getExpandedFraction() == 0.0f) {
            postToView(mHideExpandedRunnable);
        }
    };

    private final Consumer<TransitionStep> mDreamingToLockscreenTransition =
            (TransitionStep step) -> {
                mIsToLockscreenTransitionRunning =
                    step.getTransitionState() == TransitionState.RUNNING;
            };

    private final Consumer<TransitionStep> mOccludedToLockscreenTransition =
            (TransitionStep step) -> {
                mIsToLockscreenTransitionRunning =
                    step.getTransitionState() == TransitionState.RUNNING;
            };

    @Inject
    public NotificationPanelViewController(NotificationPanelView view,
            @Main Handler handler,
            LayoutInflater layoutInflater,
            FeatureFlags featureFlags,
            NotificationWakeUpCoordinator coordinator, PulseExpansionHandler pulseExpansionHandler,
            DynamicPrivacyController dynamicPrivacyController,
            KeyguardBypassController bypassController, FalsingManager falsingManager,
            FalsingCollector falsingCollector,
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
            ShadeLogger shadeLogger,
            ShadeHeightLogger shadeHeightLogger,
            ConfigurationController configurationController,
            Provider<FlingAnimationUtils.Builder> flingAnimationUtilsBuilder,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager,
            ConversationNotificationManager conversationNotificationManager,
            MediaHierarchyManager mediaHierarchyManager,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            NotificationGutsManager gutsManager,
            NotificationsQSContainerController notificationsQSContainerController,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            KeyguardStatusViewComponent.Factory keyguardStatusViewComponentFactory,
            KeyguardQsUserSwitchComponent.Factory keyguardQsUserSwitchComponentFactory,
            KeyguardUserSwitcherComponent.Factory keyguardUserSwitcherComponentFactory,
            KeyguardStatusBarViewComponent.Factory keyguardStatusBarViewComponentFactory,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            AuthController authController,
            ScrimController scrimController,
            UserManager userManager,
            MediaDataManager mediaDataManager,
            NotificationShadeDepthController notificationShadeDepthController,
            AmbientState ambientState,
            LockIconViewController lockIconViewController,
            KeyguardMediaController keyguardMediaController,
            TapAgainViewController tapAgainViewController,
            NavigationModeController navigationModeController,
            NavigationBarController navigationBarController,
            FragmentService fragmentService,
            ContentResolver contentResolver,
            RecordingController recordingController,
            LargeScreenShadeHeaderController largeScreenShadeHeaderController,
            ScreenOffAnimationController screenOffAnimationController,
            LockscreenGestureLogger lockscreenGestureLogger,
            ShadeExpansionStateManager shadeExpansionStateManager,
            NotificationRemoteInputManager remoteInputManager,
            Optional<SysUIUnfoldComponent> unfoldComponent,
            InteractionJankMonitor interactionJankMonitor,
            QsFrameTranslateController qsFrameTranslateController,
            SysUiState sysUiState,
            Provider<KeyguardBottomAreaViewController> keyguardBottomAreaViewControllerProvider,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            KeyguardIndicationController keyguardIndicationController,
            NotificationListContainer notificationListContainer,
            NotificationStackSizeCalculator notificationStackSizeCalculator,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            ShadeTransitionController shadeTransitionController,
            SystemClock systemClock,
            KeyguardBottomAreaViewModel keyguardBottomAreaViewModel,
            KeyguardBottomAreaInteractor keyguardBottomAreaInteractor,
            DreamingToLockscreenTransitionViewModel dreamingToLockscreenTransitionViewModel,
            OccludedToLockscreenTransitionViewModel occludedToLockscreenTransitionViewModel,
            @Main CoroutineDispatcher mainDispatcher,
            KeyguardTransitionInteractor keyguardTransitionInteractor,
            DumpManager dumpManager) {
        keyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onKeyguardFadingAwayChanged() {
                updateExpandedHeightToMaxHeight();
            }
        });
        mAmbientState = ambientState;
        mView = view;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mLockscreenGestureLogger = lockscreenGestureLogger;
        mShadeExpansionStateManager = shadeExpansionStateManager;
        mShadeLog = shadeLogger;
        mShadeHeightLogger = shadeHeightLogger;
        mGutsManager = gutsManager;
        mDreamingToLockscreenTransitionViewModel = dreamingToLockscreenTransitionViewModel;
        mOccludedToLockscreenTransitionViewModel = occludedToLockscreenTransitionViewModel;
        mKeyguardTransitionInteractor = keyguardTransitionInteractor;
        mView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                mViewName = mResources.getResourceName(mView.getId());
            }

            @Override
            public void onViewDetachedFromWindow(View v) {}
        });

        mView.addOnLayoutChangeListener(new ShadeLayoutChangeListener());
        mView.setOnTouchListener(createTouchHandler());
        mView.setOnConfigurationChangedListener(config -> loadDimens());

        mResources = mView.getResources();
        mKeyguardStateController = keyguardStateController;
        mKeyguardIndicationController = keyguardIndicationController;
        mStatusBarStateController = (SysuiStatusBarStateController) statusBarStateController;
        mNotificationShadeWindowController = notificationShadeWindowController;
        FlingAnimationUtils.Builder fauBuilder = flingAnimationUtilsBuilder.get();
        mFlingAnimationUtils = fauBuilder
                .reset()
                .setMaxLengthSeconds(FLING_MAX_LENGTH_SECONDS)
                .setSpeedUpFactor(FLING_SPEED_UP_FACTOR)
                .build();
        mFlingAnimationUtilsClosing = fauBuilder
                .reset()
                .setMaxLengthSeconds(FLING_CLOSING_MAX_LENGTH_SECONDS)
                .setSpeedUpFactor(FLING_CLOSING_SPEED_UP_FACTOR)
                .build();
        mFlingAnimationUtilsDismissing = fauBuilder
                .reset()
                .setMaxLengthSeconds(0.5f)
                .setSpeedUpFactor(0.6f)
                .setX2(0.6f)
                .setY2(0.84f)
                .build();
        mLatencyTracker = latencyTracker;
        mBounceInterpolator = new BounceInterpolator();
        mFalsingManager = falsingManager;
        mDozeLog = dozeLog;
        mNotificationsDragEnabled = mResources.getBoolean(
                R.bool.config_enableNotificationShadeDrag);
        mVibratorHelper = vibratorHelper;
        mVibrateOnOpening = mResources.getBoolean(R.bool.config_vibrateOnIconAnimation);
        mStatusBarTouchableRegionManager = statusBarTouchableRegionManager;
        mInteractionJankMonitor = interactionJankMonitor;
        mSystemClock = systemClock;
        mKeyguardMediaController = keyguardMediaController;
        mMetricsLogger = metricsLogger;
        mConfigurationController = configurationController;
        mFlingAnimationUtilsBuilder = flingAnimationUtilsBuilder;
        mMediaHierarchyManager = mediaHierarchyManager;
        mNotificationsQSContainerController = notificationsQSContainerController;
        mNotificationListContainer = notificationListContainer;
        mNotificationStackSizeCalculator = notificationStackSizeCalculator;
        mNavigationBarController = navigationBarController;
        mKeyguardBottomAreaViewControllerProvider = keyguardBottomAreaViewControllerProvider;
        mNotificationsQSContainerController.init();
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
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
        mView.setWillNotDraw(!DEBUG_DRAWABLE);
        mLargeScreenShadeHeaderController = largeScreenShadeHeaderController;
        mLayoutInflater = layoutInflater;
        mFeatureFlags = featureFlags;
        mFalsingCollector = falsingCollector;
        mPowerManager = powerManager;
        mWakeUpCoordinator = coordinator;
        mMainDispatcher = mainDispatcher;
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
        mSysUiState = sysUiState;
        pulseExpansionHandler.setPulseExpandAbortListener(() -> {
            if (mQs != null) {
                mQs.animateHeaderSlidingOut();
            }
        });
        statusBarWindowStateController.addListener(this::onStatusBarWindowStateChanged);
        mKeyguardBypassController = bypassController;
        mUpdateMonitor = keyguardUpdateMonitor;
        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        mShadeTransitionController = shadeTransitionController;
        lockscreenShadeTransitionController.setNotificationPanelController(this);
        shadeTransitionController.setNotificationPanelViewController(this);
        dynamicPrivacyController.addListener(this::onDynamicPrivacyChanged);

        shadeExpansionStateManager.addStateListener(this::onPanelStateChanged);

        mBottomAreaShadeAlphaAnimator = ValueAnimator.ofFloat(1f, 0);
        mBottomAreaShadeAlphaAnimator.addUpdateListener(animation -> {
            mBottomAreaShadeAlpha = (float) animation.getAnimatedValue();
            updateKeyguardBottomAreaAlpha();
        });
        mBottomAreaShadeAlphaAnimator.setDuration(160);
        mBottomAreaShadeAlphaAnimator.setInterpolator(Interpolators.ALPHA_OUT);
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
        ShadeAttachStateChangeListener
                onAttachStateChangeListener = new ShadeAttachStateChangeListener();
        mView.addOnAttachStateChangeListener(onAttachStateChangeListener);
        if (mView.isAttachedToWindow()) {
            onAttachStateChangeListener.onViewAttachedToWindow(mView);
        }

        mView.setOnApplyWindowInsetsListener((v, insets) -> onApplyShadeWindowInsets(insets));

        if (DEBUG_DRAWABLE) {
            mView.getOverlay().add(new DebugDrawable(this, mView,
                    mNotificationStackScrollLayoutController, mLockIconViewController));
        }

        mKeyguardUnfoldTransition = unfoldComponent.map(
                SysUIUnfoldComponent::getKeyguardUnfoldTransition);
        mNotificationPanelUnfoldAnimationController = unfoldComponent.map(
                SysUIUnfoldComponent::getNotificationPanelUnfoldAnimationController);

        mUnocclusionTransitionFlagEnabled = featureFlags.isEnabled(Flags.UNOCCLUSION_TRANSITION);

        mQsFrameTranslateController = qsFrameTranslateController;
        updateUserSwitcherFlags();
        mKeyguardBottomAreaViewModel = keyguardBottomAreaViewModel;
        mKeyguardBottomAreaInteractor = keyguardBottomAreaInteractor;
        onFinishInflate();
        keyguardUnlockAnimationController.addKeyguardUnlockAnimationListener(
                new KeyguardUnlockAnimationController.KeyguardUnlockAnimationListener() {
                    @Override
                    public void onUnlockAnimationFinished() {
                        unlockAnimationFinished();
                    }

                    @Override
                    public void onUnlockAnimationStarted(
                            boolean playingCannedAnimation,
                            boolean isWakeAndUnlock,
                            long startDelay,
                            long unlockAnimationDuration) {
                        unlockAnimationStarted(playingCannedAnimation, isWakeAndUnlock, startDelay);
                    }
                });
        dumpManager.registerDumpable(this);
    }

    private void unlockAnimationFinished() {
        // Make sure the clock is in the correct position after the unlock animation
        // so that it's not in the wrong place when we show the keyguard again.
        positionClockAndNotifications(true /* forceClockUpdate */);
    }

    private void unlockAnimationStarted(
            boolean playingCannedAnimation,
            boolean isWakeAndUnlock,
            long unlockAnimationStartDelay) {
        // Disable blurs while we're unlocking so that panel expansion does not
        // cause blurring. This will eventually be re-enabled by the panel view on
        // ACTION_UP, since the user's finger might still be down after a swipe to
        // unlock gesture, and we don't want that to cause blurring either.
        mDepthController.setBlursDisabledForUnlock(mTracking);

        if (playingCannedAnimation && !isWakeAndUnlock) {
            // Hide the panel so it's not in the way or the surface behind the
            // keyguard, which will be appearing. If we're wake and unlocking, the
            // lock screen is hidden instantly so should not be flung away.
            if (isTracking() || mIsFlinging) {
                // Instant collapse the notification panel since the notification
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

    @VisibleForTesting
    void onFinishInflate() {
        loadDimens();
        mKeyguardStatusBar = mView.findViewById(R.id.keyguard_header);

        FrameLayout userAvatarContainer = null;
        KeyguardUserSwitcherView keyguardUserSwitcherView = null;

        if (mKeyguardUserSwitcherEnabled && mUserManager.isUserSwitcherEnabled(
                mResources.getBoolean(R.bool.qs_show_user_switcher_for_single_user))) {
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
                new NsslHeightChangedListener());
        mNotificationStackScrollLayoutController.setOverscrollTopChangedListener(
                mOnOverscrollTopChangedListener);
        mNotificationStackScrollLayoutController.setOnScrollListener(this::onNotificationScrolled);
        mNotificationStackScrollLayoutController.setOnStackYChanged(this::onStackYChanged);
        mNotificationStackScrollLayoutController.setOnEmptySpaceClickListener(
                mOnEmptySpaceClickListener);
        addTrackingHeadsUpListener(mNotificationStackScrollLayoutController::setTrackingHeadsUp);
        setKeyguardBottomArea(mView.findViewById(R.id.keyguard_bottom_area));

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
                mOldLayoutDirection = layoutDirection;
            }
        });

        mView.setAccessibilityDelegate(mAccessibilityDelegate);
        if (mSplitShadeEnabled) {
            updateResources();
        }

        mTapAgainViewController.init();
        mLargeScreenShadeHeaderController.init();
        mKeyguardUnfoldTransition.ifPresent(u -> u.setup(mView));
        mNotificationPanelUnfoldAnimationController.ifPresent(controller ->
                controller.setup(mNotificationContainerParent));

        if (mUnocclusionTransitionFlagEnabled) {
            // Dreaming->Lockscreen
            collectFlow(mView, mKeyguardTransitionInteractor.getDreamingToLockscreenTransition(),
                    mDreamingToLockscreenTransition, mMainDispatcher);
            collectFlow(mView, mDreamingToLockscreenTransitionViewModel.getLockscreenAlpha(),
                    toLockscreenTransitionAlpha(mNotificationStackScrollLayoutController),
                    mMainDispatcher);
            collectFlow(mView, mDreamingToLockscreenTransitionViewModel.lockscreenTranslationY(
                    mDreamingToLockscreenTransitionTranslationY),
                    toLockscreenTransitionY(mNotificationStackScrollLayoutController),
                    mMainDispatcher);

            // Occluded->Lockscreen
            collectFlow(mView, mKeyguardTransitionInteractor.getOccludedToLockscreenTransition(),
                    mOccludedToLockscreenTransition, mMainDispatcher);
            collectFlow(mView, mOccludedToLockscreenTransitionViewModel.getLockscreenAlpha(),
                    toLockscreenTransitionAlpha(mNotificationStackScrollLayoutController),
                    mMainDispatcher);
            collectFlow(mView, mOccludedToLockscreenTransitionViewModel.lockscreenTranslationY(
                    mOccludedToLockscreenTransitionTranslationY),
                    toLockscreenTransitionY(mNotificationStackScrollLayoutController),
                    mMainDispatcher);
        }
    }

    @VisibleForTesting
    void loadDimens() {
        final ViewConfiguration configuration = ViewConfiguration.get(this.mView.getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mSlopMultiplier = configuration.getScaledAmbiguousGestureMultiplier();
        mHintDistance = mResources.getDimension(R.dimen.hint_move_distance);
        mPanelFlingOvershootAmount = mResources.getDimension(R.dimen.panel_overshoot_amount);
        mFlingAnimationUtils = mFlingAnimationUtilsBuilder.get()
                .setMaxLengthSeconds(0.4f).build();
        mStatusBarMinHeight = SystemBarUtils.getStatusBarHeight(mView.getContext());
        mStatusBarHeaderHeightKeyguard = Utils.getStatusBarHeaderHeightKeyguard(mView.getContext());
        mQsPeekHeight = mResources.getDimensionPixelSize(R.dimen.qs_peek_height);
        mClockPositionAlgorithm.loadDimens(mResources);
        mQsFalsingThreshold = mResources.getDimensionPixelSize(R.dimen.qs_falsing_threshold);
        mIndicationBottomPadding = mResources.getDimensionPixelSize(
                R.dimen.keyguard_indication_bottom_padding);
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
        mSplitShadeScrimTransitionDistance = mResources.getDimensionPixelSize(
                R.dimen.split_shade_scrim_transition_distance);
        mDreamingToLockscreenTransitionTranslationY = mResources.getDimensionPixelSize(
                R.dimen.dreaming_to_lockscreen_transition_lockscreen_translation_y);
        mOccludedToLockscreenTransitionTranslationY = mResources.getDimensionPixelSize(
                R.dimen.occluded_to_lockscreen_transition_lockscreen_translation_y);
    }

    private void updateViewControllers(KeyguardStatusView keyguardStatusView,
            FrameLayout userAvatarView,
            KeyguardUserSwitcherView keyguardUserSwitcherView) {
        // Re-associate the KeyguardStatusViewController
        KeyguardStatusViewComponent statusViewComponent =
                mKeyguardStatusViewComponentFactory.build(keyguardStatusView);
        mKeyguardStatusViewController = statusViewComponent.getKeyguardStatusViewController();
        mKeyguardStatusViewController.init();
        updateClockAppearance();

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

    public void updateResources() {
        mSplitShadeNotificationsScrimMarginBottom =
                mResources.getDimensionPixelSize(
                        R.dimen.split_shade_notifications_scrim_margin_bottom);
        final boolean newSplitShadeEnabled =
                LargeScreenUtils.shouldUseSplitNotificationShade(mResources);
        final boolean splitShadeChanged = mSplitShadeEnabled != newSplitShadeEnabled;
        mSplitShadeEnabled = newSplitShadeEnabled;

        if (mQs != null) {
            mQs.setInSplitShade(mSplitShadeEnabled);
        }

        mUseLargeScreenShadeHeader =
                LargeScreenUtils.shouldUseLargeScreenShadeHeader(mView.getResources());

        mLargeScreenShadeHeaderHeight =
                mResources.getDimensionPixelSize(R.dimen.large_screen_shade_header_height);
        // TODO: When the flag is eventually removed, it means that we have a single view that is
        // the same height in QQS and in Large Screen (large_screen_shade_header_height). Eventually
        // the concept of largeScreenHeader or quickQsHeader will disappear outside of the class
        // that controls the view as the offset needs to be the same regardless.
        if (mUseLargeScreenShadeHeader || mFeatureFlags.isEnabled(Flags.COMBINED_QS_HEADERS)) {
            mQuickQsHeaderHeight = mLargeScreenShadeHeaderHeight;
        } else {
            mQuickQsHeaderHeight = SystemBarUtils.getQuickQsOffsetHeight(mView.getContext());
        }
        int topMargin = mUseLargeScreenShadeHeader ? mLargeScreenShadeHeaderHeight :
                mResources.getDimensionPixelSize(R.dimen.notification_panel_margin_top);
        mLargeScreenShadeHeaderController.setLargeScreenActive(mUseLargeScreenShadeHeader);
        mAmbientState.setStackTopMargin(topMargin);
        mNotificationsQSContainerController.updateResources();

        updateKeyguardStatusViewAlignment(/* animate= */false);

        mKeyguardMediaController.refreshMediaPosition();

        if (splitShadeChanged) {
            onSplitShadeEnabledChanged();
        }

        mSplitShadeFullTransitionDistance =
                mResources.getDimensionPixelSize(R.dimen.split_shade_full_transition_distance);

        mEnableQsClipping = mResources.getBoolean(R.bool.qs_enable_clipping);
    }

    private void onSplitShadeEnabledChanged() {
        // when we switch between split shade and regular shade we want to enforce setting qs to
        // the default state: expanded for split shade and collapsed otherwise
        if (!isOnKeyguard() && mPanelExpanded) {
            setQsExpanded(mSplitShadeEnabled);
        }
        if (isOnKeyguard() && mQsExpanded && mSplitShadeEnabled) {
            // In single column keyguard - when you swipe from the top - QS is fully expanded and
            // StatusBarState is KEYGUARD. That state doesn't make sense for split shade,
            // where notifications are always visible and we effectively go to fully expanded
            // shade, that is SHADE_LOCKED.
            // Also we might just be switching from regular expanded shade, so we don't want
            // to force state transition if it's already correct.
            mStatusBarStateController.setState(StatusBarState.SHADE_LOCKED, /* force= */false);
        }
        updateClockAppearance();
        updateQsState();
        mNotificationStackScrollLayoutController.updateFooter();
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

    @VisibleForTesting
    void reInflateViews() {
        debugLog("reInflateViews");
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
        boolean isUserSwitcherEnabled = mUserManager.isUserSwitcherEnabled(
                mResources.getBoolean(R.bool.qs_show_user_switcher_for_single_user));
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
        setKeyguardBottomArea(mKeyguardBottomAreaViewControllerProvider.get().getView());
        mKeyguardBottomArea.initFrom(oldBottomArea);
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

    @VisibleForTesting
    void setQs(QS qs) {
        mQs = qs;
    }

    private void attachSplitShadeMediaPlayerContainer(FrameLayout container) {
        mKeyguardMediaController.attachSplitShadeContainer(container);
    }

    private void initBottomArea() {
        mKeyguardBottomArea.init(
                mKeyguardBottomAreaViewModel,
                mFalsingManager,
                mLockIconViewController,
                stringResourceId ->
                        mKeyguardIndicationController.showTransientIndication(stringResourceId),
                mVibratorHelper);
    }

    @VisibleForTesting
    void setMaxDisplayedNotifications(int maxAllowed) {
        mMaxAllowedKeyguardNotifications = maxAllowed;
    }

    @VisibleForTesting
    boolean isFlinging() {
        return mIsFlinging;
    }

    private void updateMaxDisplayedNotifications(boolean recompute) {
        if (recompute) {
            setMaxDisplayedNotifications(Math.max(computeMaxKeyguardNotifications(), 1));
        } else {
            if (SPEW_LOGCAT) Log.d(TAG, "Skipping computeMaxKeyguardNotifications() by request");
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

    private void setKeyguardBottomArea(KeyguardBottomAreaView keyguardBottomArea) {
        mKeyguardBottomArea = keyguardBottomArea;
        mKeyguardIndicationController.setIndicationArea(mKeyguardBottomArea);
    }

    void setOpenCloseListener(OpenCloseListener openCloseListener) {
        mOpenCloseListener = openCloseListener;
    }

    void setTrackingStartedListener(TrackingStartedListener trackingStartedListener) {
        mTrackingStartedListener = trackingStartedListener;
    }

    private void updateGestureExclusionRect() {
        Rect exclusionRect = calculateGestureExclusionRect();
        mView.setSystemGestureExclusionRects(exclusionRect.isEmpty() ? Collections.emptyList()
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
        if (mQs != null) {
            mQs.setIsNotificationPanelFullWidth(isFullWidth);
        }
    }

    private void startQsSizeChangeAnimation(int oldHeight, final int newHeight) {
        if (mQsSizeChangeAnimator != null) {
            oldHeight = (int) mQsSizeChangeAnimator.getAnimatedValue();
            mQsSizeChangeAnimator.cancel();
        }
        mQsSizeChangeAnimator = ValueAnimator.ofInt(oldHeight, newHeight);
        mQsSizeChangeAnimator.setDuration(300);
        mQsSizeChangeAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mQsSizeChangeAnimator.addUpdateListener(animation -> {
            requestScrollerTopPaddingUpdate(false /* animate */);
            updateExpandedHeightToMaxHeight();
            int height = (int) mQsSizeChangeAnimator.getAnimatedValue();
            mQs.setHeightOverride(height);
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

        mStackScrollerMeasuringPass++;
        requestScrollerTopPaddingUpdate(animate);
        mStackScrollerMeasuringPass = 0;
        mAnimateNextPositionUpdate = false;
    }

    private void updateClockAppearance() {
        int userSwitcherPreferredY = mStatusBarHeaderHeightKeyguard;
        boolean bypassEnabled = mKeyguardBypassController.getBypassEnabled();
        boolean shouldAnimateClockChange = mScreenOffAnimationController.shouldAnimateClockChange();
        mKeyguardStatusViewController.displayClock(computeDesiredClockSize(),
                shouldAnimateClockChange);
        updateKeyguardStatusViewAlignment(/* animate= */true);
        int userSwitcherHeight = mKeyguardQsUserSwitchController != null
                ? mKeyguardQsUserSwitchController.getUserIconHeight() : 0;
        if (mKeyguardUserSwitcherController != null) {
            userSwitcherHeight = mKeyguardUserSwitcherController.getHeight();
        }
        float expandedFraction =
                mScreenOffAnimationController.shouldExpandNotifications()
                        ? 1.0f : getExpandedFraction();
        float darkAmount =
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
                darkAmount, mOverStretchAmount,
                bypassEnabled, getUnlockedStackScrollerPadding(),
                computeQsExpansionFraction(),
                mDisplayTopInset,
                mSplitShadeEnabled,
                udfpsAodTopLocation,
                mKeyguardStatusViewController.getClockBottom(mStatusBarHeaderHeightKeyguard),
                mKeyguardStatusViewController.isClockTopAligned());
        mClockPositionAlgorithm.run(mClockPositionResult);
        mKeyguardBottomAreaInteractor.setClockPosition(
                mClockPositionResult.clockX, mClockPositionResult.clockY);
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

    public KeyguardClockPositionAlgorithm.Result getClockPositionResult() {
        return mClockPositionResult;
    }

    @ClockSize
    private int computeDesiredClockSize() {
        if (mSplitShadeEnabled) {
            return computeDesiredClockSizeForSplitShade();
        }
        return computeDesiredClockSizeForSingleShade();
    }

    @ClockSize
    private int computeDesiredClockSizeForSingleShade() {
        if (hasVisibleNotifications()) {
            return SMALL;
        }
        return LARGE;
    }

    @ClockSize
    private int computeDesiredClockSizeForSplitShade() {
        // Media is not visible to the user on AOD.
        boolean isMediaVisibleToUser =
                mMediaDataManager.hasActiveMediaOrRecommendation() && !isOnAod();
        if (isMediaVisibleToUser) {
            // When media is visible, it overlaps with the large clock. Use small clock instead.
            return SMALL;
        }
        return LARGE;
    }

    private void updateKeyguardStatusViewAlignment(boolean animate) {
        boolean shouldBeCentered = shouldKeyguardStatusViewBeCentered();
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

                boolean customClockAnimation =
                            mKeyguardStatusViewController.getClockAnimations() != null
                            && mKeyguardStatusViewController.getClockAnimations()
                                    .getHasCustomPositionUpdatedAnimation();

                if (mFeatureFlags.isEnabled(Flags.STEP_CLOCK_ANIMATION) && customClockAnimation) {
                    // Find the clock, so we can exclude it from this transition.
                    FrameLayout clockContainerView =
                            mView.findViewById(R.id.lockscreen_clock_view_large);

                    // The clock container can sometimes be null. If it is, just fall back to the
                    // old animation rather than setting up the custom animations.
                    if (clockContainerView == null || clockContainerView.getChildCount() == 0) {
                        TransitionManager.beginDelayedTransition(
                                mNotificationContainerParent, transition);
                    } else {
                        View clockView = clockContainerView.getChildAt(0);

                        transition.excludeTarget(clockView, /* exclude= */ true);

                        TransitionSet set = new TransitionSet();
                        set.addTransition(transition);

                        SplitShadeTransitionAdapter adapter =
                                new SplitShadeTransitionAdapter(mKeyguardStatusViewController);

                        // Use linear here, so the actual clock can pick its own interpolator.
                        adapter.setInterpolator(Interpolators.LINEAR);
                        adapter.setDuration(KEYGUARD_STATUS_VIEW_CUSTOM_CLOCK_MOVE_DURATION);
                        adapter.addTarget(clockView);
                        set.addTransition(adapter);

                        TransitionManager.beginDelayedTransition(mNotificationContainerParent, set);
                    }
                } else {
                    TransitionManager.beginDelayedTransition(
                            mNotificationContainerParent, transition);
                }
            }

            constraintSet.applyTo(mNotificationContainerParent);
        }
        mKeyguardUnfoldTransition.ifPresent(t -> t.setStatusViewCentered(mStatusViewCentered));
    }

    private boolean shouldKeyguardStatusViewBeCentered() {
        if (mSplitShadeEnabled) {
            return shouldKeyguardStatusViewBeCenteredInSplitShade();
        }
        return true;
    }

    private boolean shouldKeyguardStatusViewBeCenteredInSplitShade() {
        if (!hasVisibleNotifications()) {
            // No notifications visible. It is safe to have the clock centered as there will be no
            // overlap.
            return true;
        }
        if (hasPulsingNotifications()) {
            // Pulsing notification appears on the right. Move clock left to avoid overlap.
            return false;
        }
        // "Visible" notifications are actually not visible on AOD (unless pulsing), so it is safe
        // to center the clock without overlap.
        return isOnAod();
    }

    private boolean isOnAod() {
        return mDozing && mDozeParameters.getAlwaysOn();
    }

    private boolean hasVisibleNotifications() {
        return mNotificationStackScrollLayoutController
                .getVisibleNotificationCount() != 0
                || mMediaDataManager.hasActiveMediaOrRecommendation();
    }

    /**
     * @return the padding of the stackscroller when unlocked
     */
    private int getUnlockedStackScrollerPadding() {
        return (mQs != null ? mQs.getHeader().getHeight() : 0) + mQsPeekHeight;
    }

    /** Returns space between top of lock icon and bottom of NotificationStackScrollLayout. */
    private float getLockIconPadding() {
        float lockIconPadding = 0f;
        if (mLockIconViewController.getTop() != 0f) {
            lockIconPadding = mNotificationStackScrollLayoutController.getBottom()
                    - mLockIconViewController.getTop();
        }
        return lockIconPadding;
    }

    /** Returns space available to show notifications on lockscreen. */
    @VisibleForTesting
    float getVerticalSpaceForLockscreenNotifications() {
        final float lockIconPadding = getLockIconPadding();

        float bottomPadding = Math.max(lockIconPadding,
                Math.max(mIndicationBottomPadding, mAmbientIndicationBottomPadding));
        mKeyguardNotificationBottomPadding = bottomPadding;

        float staticTopPadding = mClockPositionAlgorithm.getLockscreenMinStackScrollerPadding()
                // getMinStackScrollerPadding is from the top of the screen,
                // but we need it from the top of the NSSL.
                - mNotificationStackScrollLayoutController.getTop();
        mKeyguardNotificationTopPadding = staticTopPadding;

        // To debug the available space, enable debug lines in this class. If you change how the
        // available space is calculated, please also update those lines.
        final float verticalSpace =
                mNotificationStackScrollLayoutController.getHeight()
                        - staticTopPadding
                        - bottomPadding;

        if (SPEW_LOGCAT) {
            Log.i(TAG, "\n");
            Log.i(TAG, "staticTopPadding[" + staticTopPadding
                    + "] = Clock.padding["
                    + mClockPositionAlgorithm.getLockscreenMinStackScrollerPadding()
                    + "] - NSSLC.top[" + mNotificationStackScrollLayoutController.getTop()
                    + "]"
            );
            Log.i(TAG, "bottomPadding[" + bottomPadding
                    + "] = max(ambientIndicationBottomPadding[" + mAmbientIndicationBottomPadding
                    + "], mIndicationBottomPadding[" + mIndicationBottomPadding
                    + "], lockIconPadding[" + lockIconPadding
                    + "])"
            );
            Log.i(TAG, "verticalSpaceForNotifications[" + verticalSpace
                    + "] = NSSL.height[" + mNotificationStackScrollLayoutController.getHeight()
                    + "] - staticTopPadding[" + staticTopPadding
                    + "] - bottomPadding[" + bottomPadding
                    + "]"
            );
        }
        return verticalSpace;
    }

    /** Returns extra space available to show the shelf on lockscreen */
    @VisibleForTesting
    float getVerticalSpaceForLockscreenShelf() {
        final float lockIconPadding = getLockIconPadding();

        final float noShelfOverlapBottomPadding =
                Math.max(mIndicationBottomPadding, mAmbientIndicationBottomPadding);

        final float extraSpaceForShelf = lockIconPadding - noShelfOverlapBottomPadding;

        if (extraSpaceForShelf > 0f) {
            return Math.min(mNotificationShelfController.getIntrinsicHeight(),
                    extraSpaceForShelf);
        }
        return 0f;
    }

    /**
     * @return Maximum number of notifications that can fit on keyguard.
     */
    @VisibleForTesting
    int computeMaxKeyguardNotifications() {
        if (mAmbientState.getFractionToShade() > 0) {
            if (SPEW_LOGCAT) {
                Log.v(TAG, "Internally skipping computeMaxKeyguardNotifications()"
                        + " fractionToShade=" + mAmbientState.getFractionToShade()
                );
            }
            return mMaxAllowedKeyguardNotifications;
        }
        return mNotificationStackSizeCalculator.computeMaxKeyguardNotifications(
                mNotificationStackScrollLayoutController.getView(),
                getVerticalSpaceForLockscreenNotifications(),
                getVerticalSpaceForLockscreenShelf(),
                mNotificationShelfController.getIntrinsicHeight()
        );
    }

    private void updateClock() {
        if (mIsToLockscreenTransitionRunning) {
            return;
        }
        float alpha = mClockPositionResult.clockAlpha * mKeyguardOnlyContentAlpha;
        mKeyguardStatusViewController.setAlpha(alpha);
        mKeyguardStatusViewController
            .setTranslationY(mKeyguardOnlyTransitionTranslationY, /* excludeMedia= */true);

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

    public void resetViews(boolean animate) {
        mGutsManager.closeAndSaveGuts(true /* leavebehind */, true /* force */,
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
            mShadeHeightLogger.logFunctionCall("collapsePanel");
            setExpandedFraction(0); // just in case
        }
        if (!waiting) {
            // it's possible that nothing animated, so we replicate the termination
            // conditions of panelExpansionChanged here
            // TODO(b/200063118): This can likely go away in a future refactor CL.
            getShadeExpansionStateManager().updateState(STATE_CLOSED);
        }
    }

    public void collapse(boolean delayed, float speedUpFactor) {
        if (!canPanelBeCollapsed()) {
            return;
        }

        if (mQsExpanded) {
            setQsExpandImmediate(true);
            setShowShelfOnly(true);
        }
        debugLog("collapse: %s", this);
        if (canPanelBeCollapsed()) {
            cancelHeightAnimator();
            notifyExpandingStarted();

            // Set after notifyExpandingStarted, as notifyExpandingStarted resets the closing state.
            setClosing(true);
            if (delayed) {
                mNextCollapseSpeedUpFactor = speedUpFactor;
                this.mView.postDelayed(mFlingCollapseRunnable, 120);
            } else {
                fling(0, false /* expand */, speedUpFactor, false /* expandBecauseOfFalsing */);
            }
        }
    }

    @VisibleForTesting
    void setQsExpandImmediate(boolean expandImmediate) {
        if (expandImmediate != mQsExpandImmediate) {
            mQsExpandImmediate = expandImmediate;
            mShadeExpansionStateManager.notifyExpandImmediateChange(expandImmediate);
        }
    }

    @VisibleForTesting
    boolean isQsExpandImmediate() {
        return mQsExpandImmediate;
    }

    private void setShowShelfOnly(boolean shelfOnly) {
        mNotificationStackScrollLayoutController.setShouldShowShelfOnly(
                shelfOnly && !mSplitShadeEnabled);
    }

    public void closeQs() {
        cancelQsAnimation();
        setQsExpansionHeight(mQsMinExpansionHeight);
        // qsExpandImmediate is a safety latch in case we're calling closeQS while we're in the
        // middle of animation - we need to make sure that value is always false when shade if
        // fully collapsed or expanded
        setQsExpandImmediate(false);
    }

    @VisibleForTesting
    void cancelHeightAnimator() {
        if (mHeightAnimator != null) {
            if (mHeightAnimator.isRunning()) {
                mPanelUpdateWhenAnimatorEnds = false;
            }
            mHeightAnimator.cancel();
        }
        endClosing();
    }

    public void cancelAnimation() {
        mView.animate().cancel();
    }

    /**
     * Animate QS closing by flinging it.
     * If QS is expanded, it will collapse into QQS and stop.
     * If in split shade, it will collapse the whole shade.
     *
     * @param animateAway Do not stop when QS becomes QQS. Fling until QS isn't visible anymore.
     */
    public void animateCloseQs(boolean animateAway) {
        if (mSplitShadeEnabled) {
            collapsePanel(
                    /* animate= */true, /* delayed= */false, /* speedUpFactor= */1.0f);
            return;
        }

        if (mQsExpansionAnimator != null) {
            if (!mQsAnimatorExpand) {
                return;
            }
            float height = mQsExpansionHeight;
            mQsExpansionAnimator.cancel();
            setQsExpansionHeight(height);
        }
        flingSettings(0 /* vel */, animateAway ? FLING_HIDE : FLING_COLLAPSE);
    }

    private boolean isQsExpansionEnabled() {
        return mQsExpansionEnabledPolicy && mQsExpansionEnabledAmbient
                && !mRemoteInputManager.isRemoteInputActive();
    }

    public void expandWithQs() {
        if (isQsExpansionEnabled()) {
            setQsExpandImmediate(true);
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
                    /* expandedView= */null, /* needsQSAnimation= */true);
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

    private void fling(float vel) {
        if (mGestureRecorder != null) {
            mGestureRecorder.tag("fling " + ((vel > 0) ? "open" : "closed"),
                    "notifications,v=" + vel);
        }
        fling(vel, true, 1.0f /* collapseSpeedUpFactor */, false);
    }

    @VisibleForTesting
    void flingToHeight(float vel, boolean expand, float target,
            float collapseSpeedUpFactor, boolean expandBecauseOfFalsing) {
        mHeadsUpTouchHelper.notifyFling(!expand);
        mKeyguardStateController.notifyPanelFlingStart(!expand /* flingingToDismiss */);
        setClosingWithAlphaFadeout(!expand && !isOnKeyguard() && getFadeoutAlpha() == 1.0f);
        mNotificationStackScrollLayoutController.setPanelFlinging(true);
        if (target == mExpandedHeight && mOverExpansion == 0.0f) {
            // We're at the target and didn't fling and there's no overshoot
            onFlingEnd(false /* cancelled */);
            return;
        }
        mIsFlinging = true;
        // we want to perform an overshoot animation when flinging open
        final boolean addOverscroll =
                expand
                        && !mSplitShadeEnabled // Split shade has its own overscroll logic
                        && mStatusBarStateController.getState() != KEYGUARD
                        && mOverExpansion == 0.0f
                        && vel >= 0;
        final boolean shouldSpringBack = addOverscroll || (mOverExpansion != 0.0f && expand);
        float overshootAmount = 0.0f;
        if (addOverscroll) {
            // Let's overshoot depending on the amount of velocity
            overshootAmount = MathUtils.lerp(
                    0.2f,
                    1.0f,
                    MathUtils.saturate(vel
                            / (this.mFlingAnimationUtils.getHighVelocityPxPerSecond()
                            * FACTOR_OF_HIGH_VELOCITY_FOR_MAX_OVERSHOOT)));
            overshootAmount += mOverExpansion / mPanelFlingOvershootAmount;
        }
        ValueAnimator animator = createHeightAnimator(target, overshootAmount);
        if (expand) {
            if (expandBecauseOfFalsing && vel < 0) {
                vel = 0;
            }
            this.mFlingAnimationUtils.apply(animator, mExpandedHeight,
                    target + overshootAmount * mPanelFlingOvershootAmount, vel,
                    this.mView.getHeight());
            if (vel == 0) {
                animator.setDuration(SHADE_OPEN_SPRING_OUT_DURATION);
            }
        } else {
            if (shouldUseDismissingAnimation()) {
                if (vel == 0) {
                    animator.setInterpolator(Interpolators.PANEL_CLOSE_ACCELERATED);
                    long duration = (long) (200 + mExpandedHeight / this.mView.getHeight() * 100);
                    animator.setDuration(duration);
                } else {
                    mFlingAnimationUtilsDismissing.apply(animator, mExpandedHeight, target, vel,
                            this.mView.getHeight());
                }
            } else {
                mFlingAnimationUtilsClosing.apply(
                        animator, mExpandedHeight, target, vel, this.mView.getHeight());
            }

            // Make it shorter if we run a canned animation
            if (vel == 0) {
                animator.setDuration((long) (animator.getDuration() / collapseSpeedUpFactor));
            }
            if (mFixedDuration != NO_FIXED_DURATION) {
                animator.setDuration(mFixedDuration);
            }
        }
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationStart(Animator animation) {
                if (!mStatusBarStateController.isDozing()) {
                    beginJankMonitoring();
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (shouldSpringBack && !mCancelled) {
                    // After the shade is flung open to an overscrolled state, spring back
                    // the shade by reducing section padding to 0.
                    springBack();
                } else {
                    onFlingEnd(mCancelled);
                }
            }
        });
        setAnimator(animator);
        animator.start();
    }

    @VisibleForTesting
    void onFlingEnd(boolean cancelled) {
        mIsFlinging = false;
        // No overshoot when the animation ends
        setOverExpansionInternal(0, false /* isFromGesture */);
        setAnimator(null);
        mKeyguardStateController.notifyPanelFlingEnd();
        if (!cancelled) {
            endJankMonitoring();
            notifyExpandingFinished();
        } else {
            cancelJankMonitoring();
        }
        updatePanelExpansionAndVisibility();
        mNotificationStackScrollLayoutController.setPanelFlinging(false);
    }

    private boolean onQsIntercept(MotionEvent event) {
        debugLog("onQsIntercept");
        int pointerIndex = event.findPointerIndex(mQsTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mQsTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mInitialTouchY = y;
                mInitialTouchX = x;
                initVelocityTracker();
                trackMovement(event);
                float qsExpansionFraction = computeQsExpansionFraction();
                // Intercept the touch if QS is between fully collapsed and fully expanded state
                if (!mSplitShadeEnabled
                        && qsExpansionFraction > 0.0 && qsExpansionFraction < 1.0) {
                    mShadeLog.logMotionEvent(event,
                            "onQsIntercept: down action, QS partially expanded/collapsed");
                    return true;
                }
                if (mKeyguardShowing
                        && shouldQuickSettingsIntercept(mInitialTouchX, mInitialTouchY, 0)) {
                    // Dragging down on the lockscreen statusbar should prohibit other interactions
                    // immediately, otherwise we'll wait on the touchslop. This is to allow
                    // dragging down to expanded quick settings directly on the lockscreen.
                    mView.getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (mQsExpansionAnimator != null) {
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mShadeLog.logMotionEvent(event,
                            "onQsIntercept: down action, QS tracking enabled");
                    mQsTracking = true;
                    traceQsJank(true /* startTracing */, false /* wasCancelled */);
                    mNotificationStackScrollLayoutController.cancelLongPress();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mQsTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    mQsTrackingPointer = event.getPointerId(newIndex);
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
                    setQsExpansionHeight(h + mInitialHeightOnTouch);
                    trackMovement(event);
                    return true;
                } else {
                    mShadeLog.logMotionEvent(event,
                            "onQsIntercept: move ignored because qs tracking disabled");
                }
                float touchSlop = getTouchSlop(event);
                if ((h > touchSlop || (h < -touchSlop && mQsExpanded))
                        && Math.abs(h) > Math.abs(x - mInitialTouchX)
                        && shouldQuickSettingsIntercept(mInitialTouchX, mInitialTouchY, h)) {
                    mView.getParent().requestDisallowInterceptTouchEvent(true);
                    mShadeLog.onQsInterceptMoveQsTrackingEnabled(h);
                    mQsTracking = true;
                    traceQsJank(true /* startTracing */, false /* wasCancelled */);
                    onQsExpansionStarted();
                    notifyExpandingFinished();
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mNotificationStackScrollLayoutController.cancelLongPress();
                    return true;
                } else {
                    mShadeLog.logQsTrackingNotStarted(mInitialTouchY, y, h, touchSlop, mQsExpanded,
                            mCollapsedOnDown, mKeyguardShowing, isQsExpansionEnabled());
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                trackMovement(event);
                mShadeLog.logMotionEvent(event, "onQsIntercept: up action, QS tracking disabled");
                mQsTracking = false;
                break;
        }
        return false;
    }

    @VisibleForTesting
    boolean isQsTracking() {
        return mQsTracking;
    }

    private boolean isInContentBounds(float x, float y) {
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
            mQsTouchAboveFalsingThreshold = mQsFullyExpanded;
            mDozingOnDown = mDozing;
            mDownX = event.getX();
            mDownY = event.getY();
            mCollapsedOnDown = isFullyCollapsed();
            mIsPanelCollapseOnQQS = canPanelCollapseOnQQS(mDownX, mDownY);
            mListenForHeadsUp = mCollapsedOnDown && mHeadsUpManager.hasPinnedHeadsUp();
            mAllowExpandForSmallExpansion = mExpectingSynthesizedDown;
            mTouchSlopExceededBeforeDown = mExpectingSynthesizedDown;
            // When false, down but not synthesized motion event.
            mLastEventSynthesizedDown = mExpectingSynthesizedDown;
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
            if (mFalsingManager.isUnlockingDisabled() || isFalseTouch()) {
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

    private boolean isFalseTouch() {
        if (mFalsingManager.isClassifierEnabled()) {
            return mFalsingManager.isFalseTouch(Classifier.QUICK_SETTINGS);
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

    private boolean shouldExpandWhenNotFlinging() {
        if (getExpandedFraction() > 0.5f) {
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

    private float getOpeningHeight() {
        return mNotificationStackScrollLayoutController.getOpeningHeight();
    }


    private boolean handleQsTouch(MotionEvent event) {
        if (isSplitShadeAndTouchXOutsideQs(event.getX())) {
            return false;
        }
        final int action = event.getActionMasked();
        boolean collapsedQs = !mQsExpanded && !mSplitShadeEnabled;
        boolean expandedShadeCollapsedQs = getExpandedFraction() == 1f && mBarState != KEYGUARD
                && collapsedQs && isQsExpansionEnabled();
        if (action == MotionEvent.ACTION_DOWN && expandedShadeCollapsedQs) {
            // Down in the empty area while fully expanded - go to QS.
            mShadeLog.logMotionEvent(event, "handleQsTouch: down action, QS tracking enabled");
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
        // defer touches on QQS to shade while shade is collapsing. Added margin for error
        // as sometimes the qsExpansionFraction can be a tiny value instead of 0 when in QQS.
        if (!mSplitShadeEnabled
                && computeQsExpansionFraction() <= 0.01 && getExpandedFraction() < 1.0) {
            mShadeLog.logMotionEvent(event,
                    "handleQsTouch: QQS touched while shade collapsing, QS tracking disabled");
            mQsTracking = false;
        }
        if (!mQsExpandImmediate && mQsTracking) {
            onQsTouch(event);
            if (!mConflictingQsExpansionGesture && !mSplitShadeEnabled) {
                mShadeLog.logMotionEvent(event,
                        "handleQsTouch: not immediate expand or conflicting gesture");
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
            setQsExpandImmediate(true);
            setShowShelfOnly(true);
            updateExpandedHeightToMaxHeight();

            // Normally, we start listening when the panel is expanded, but here we need to start
            // earlier so the state is already up to date when dragging down.
            setListening(true);
        }
        return false;
    }

    /** Returns whether split shade is enabled and an x coordinate is outside of the QS frame. */
    private boolean isSplitShadeAndTouchXOutsideQs(float touchX) {
        return mSplitShadeEnabled && (touchX < mQsFrame.getX()
                || touchX > mQsFrame.getX() + mQsFrame.getWidth());
    }

    private boolean isInQsArea(float x, float y) {
        if (isSplitShadeAndTouchXOutsideQs(x)) {
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
            debugLog("handleQsDown");
            mFalsingCollector.onQsDown();
            mShadeLog.logMotionEvent(event, "handleQsDown: down action, QS tracking enabled");
            mQsTracking = true;
            onQsExpansionStarted();
            mInitialHeightOnTouch = mQsExpansionHeight;
            mInitialTouchY = event.getY();
            mInitialTouchX = event.getX();

            // If we interrupt an expansion gesture here, make sure to update the state correctly.
            notifyExpandingFinished();
        }
    }

    /** Input focus transfer is about to happen. */
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
                // Window never will receive touch events that typically trigger haptic on open.
                maybeVibrateOnOpening(false /* openingWithTouch */);
                fling(velocity > 1f ? 1000f * velocity : 0  /* expand */);
            }
            onTrackingStopped(false);
        }
    }

    private boolean flingExpands(float vel, float vectorVel, float x, float y) {
        boolean expands = true;
        if (!this.mFalsingManager.isUnlockingDisabled()) {
            @Classifier.InteractionType int interactionType = y - mInitialExpandY > 0
                    ? QUICK_SETTINGS : (
                    mKeyguardStateController.canDismissLockScreen() ? UNLOCK : BOUNCER_UNLOCK);
            if (!isFalseTouch(x, y, interactionType)) {
                if (Math.abs(vectorVel) < this.mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
                    expands = shouldExpandWhenNotFlinging();
                } else {
                    expands = vel > 0;
                }
            }
        }

        // If we are already running a QS expansion, make sure that we keep the panel open.
        if (mQsExpansionAnimator != null) {
            expands = true;
        }
        return expands;
    }

    private boolean shouldGestureWaitForTouchSlop() {
        if (mExpectingSynthesizedDown) {
            mExpectingSynthesizedDown = false;
            return false;
        }
        return isFullyCollapsed() || mBarState != StatusBarState.SHADE;
    }

    private void onQsTouch(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(mQsTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mQsTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float y = event.getY(pointerIndex);
        final float x = event.getX(pointerIndex);
        final float h = y - mInitialTouchY;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mShadeLog.logMotionEvent(event, "onQsTouch: down action, QS tracking enabled");
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
                if (mQsTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    final float newY = event.getY(newIndex);
                    final float newX = event.getX(newIndex);
                    mQsTrackingPointer = event.getPointerId(newIndex);
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mInitialTouchY = newY;
                    mInitialTouchX = newX;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                debugLog("onQSTouch move");
                mShadeLog.logMotionEvent(event, "onQsTouch: move action, setting QS expansion");
                setQsExpansionHeight(h + mInitialHeightOnTouch);
                if (h >= getFalsingThreshold()) {
                    mQsTouchAboveFalsingThreshold = true;
                }
                trackMovement(event);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mShadeLog.logMotionEvent(event,
                        "onQsTouch: up/cancel action, QS tracking disabled");
                mQsTracking = false;
                mQsTrackingPointer = -1;
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
        cancelQsAnimation();
        cancelHeightAnimator();

        // Reset scroll position and apply that position to the expanded height.
        float height = mQsExpansionHeight;
        setQsExpansionHeight(height);
        mNotificationStackScrollLayoutController.checkSnoozeLeavebehind();

        // When expanding QS, let's authenticate the user if possible,
        // this will speed up notification actions.
        if (height == 0 && !mKeyguardStateController.canDismissLockScreen()) {
            mUpdateMonitor.requestFaceAuth(FaceAuthApiRequestReason.QS_EXPANDED);
        }
    }

    @VisibleForTesting
    void setQsExpanded(boolean expanded) {
        boolean changed = mQsExpanded != expanded;
        if (changed) {
            mQsExpanded = expanded;
            updateQsState();
            updateExpandedHeightToMaxHeight();
            setStatusAccessibilityImportance(expanded
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            updateSystemUiStateFlags();
            NavigationBarView navigationBarView =
                    mNavigationBarController.getNavigationBarView(mDisplayId);
            if (navigationBarView != null) {
                navigationBarView.onStatusBarPanelStateChanged();
            }
            mShadeExpansionStateManager.onQsExpansionChanged(expanded);
            mShadeLog.logQsExpansionChanged("QS Expansion Changed.", expanded,
                    mQsMinExpansionHeight, mQsMaxExpansionHeight, mStackScrollerOverscrolling,
                    mDozing, mQsAnimatorExpand, mAnimatingQS);
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
            if (!mIsToLockscreenTransitionRunning) {
                mKeyguardBottomArea.setAlpha(1f);
            }
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

    void setQsExpansionHeight(float height) {
        height = Math.min(Math.max(height, mQsMinExpansionHeight), mQsMaxExpansionHeight);
        mQsFullyExpanded = height == mQsMaxExpansionHeight && mQsMaxExpansionHeight != 0;
        boolean qsAnimatingAway = !mQsAnimatorExpand && mAnimatingQS;
        if (height > mQsMinExpansionHeight && !mQsExpanded && !mStackScrollerOverscrolling
                && !mDozing && !qsAnimatingAway) {
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
        if (DEBUG_DRAWABLE) {
            mView.invalidate();
        }
    }

    private void updateQsExpansion() {
        if (mQs == null) return;
        final float squishiness;
        if ((mQsExpandImmediate || mQsExpanded) && !mSplitShadeEnabled) {
            squishiness = 1;
        } else if (mTransitioningToFullShadeProgress > 0.0f) {
            squishiness = mLockscreenShadeTransitionController.getQsSquishTransitionFraction();
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

        float shadeExpandedFraction = isOnKeyguard()
                ? getLockscreenShadeDragProgress()
                : getExpandedFraction();
        mLargeScreenShadeHeaderController.setShadeExpandedFraction(shadeExpandedFraction);
        mLargeScreenShadeHeaderController.setQsExpandedFraction(qsExpansionFraction);
        mLargeScreenShadeHeaderController.setQsVisible(mQsVisible);
    }

    private float getLockscreenShadeDragProgress() {
        // mTransitioningToFullShadeProgress > 0 means we're doing regular lockscreen to shade
        // transition. If that's not the case we should follow QS expansion fraction for when
        // user is pulling from the same top to go directly to expanded QS
        return mTransitioningToFullShadeProgress > 0
                ? mLockscreenShadeTransitionController.getQSDragProgress()
                : computeQsExpansionFraction();
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
    }

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
     * Updates scrim bounds, QS clipping, notifications clipping and keyguard status view clipping
     * as well based on the bounds of the shade and QS state.
     */
    private void setQSClippingBounds() {
        float qsExpansionFraction = computeQsExpansionFraction();
        final int qsPanelBottomY = calculateQsBottomPosition(qsExpansionFraction);
        final boolean qsVisible = (qsExpansionFraction > 0 || qsPanelBottomY > 0);
        checkCorrectScrimVisibility(qsExpansionFraction);

        int top = calculateTopQsClippingBound(qsPanelBottomY);
        int bottom = calculateBottomQsClippingBound(top);
        int left = calculateLeftQsClippingBound();
        int right = calculateRightQsClippingBound();
        // top should never be lower than bottom, otherwise it will be invisible.
        top = Math.min(top, bottom);
        applyQSClippingBounds(left, top, right, bottom, qsVisible);
    }

    private void checkCorrectScrimVisibility(float expansionFraction) {
        // issues with scrims visible on keyguard occur only in split shade
        if (mSplitShadeEnabled) {
            boolean keyguardViewsVisible = mBarState == KEYGUARD && mKeyguardOnlyContentAlpha == 1;
            // expansionFraction == 1 means scrims are fully visible as their size/visibility depend
            // on QS expansion
            if (expansionFraction == 1 && keyguardViewsVisible) {
                Log.wtf(TAG,
                        "Incorrect state, scrim is visible at the same time when clock is visible");
            }
        }
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
            return mView.getBottom();
        }
    }

    private int calculateLeftQsClippingBound() {
        if (mIsFullWidth) {
            // left bounds can ignore insets, it should always reach the edge of the screen
            return 0;
        } else {
            return mNotificationStackScrollLayoutController.getLeft();
        }
    }

    private int calculateRightQsClippingBound() {
        if (mIsFullWidth) {
            return mView.getRight() + mDisplayRightInset;
        } else {
            return mNotificationStackScrollLayoutController.getRight();
        }
    }

    /**
     * Applies clipping to quick settings, notifications layout and
     * updates bounds of the notifications background (notifications scrim).
     *
     * The parameters are bounds of the notifications area rectangle, this function
     * calculates bounds for the QS clipping based on the notifications bounds.
     */
    private void applyQSClippingBounds(int left, int top, int right, int bottom,
            boolean qsVisible) {
        if (!mAnimateNextNotificationBounds || mLastQsClipBounds.isEmpty()) {
            if (mQsClippingAnimation != null) {
                // update the end position of the animator
                mQsClippingAnimationEndBounds.set(left, top, right, bottom);
            } else {
                applyQSClippingImmediately(left, top, right, bottom, qsVisible);
            }
        } else {
            mQsClippingAnimationEndBounds.set(left, top, right, bottom);
            final int startLeft = mLastQsClipBounds.left;
            final int startTop = mLastQsClipBounds.top;
            final int startRight = mLastQsClipBounds.right;
            final int startBottom = mLastQsClipBounds.bottom;
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
        int radius = mScrimCornerRadius;
        boolean clipStatusView = false;
        mLastQsClipBounds.set(left, top, right, bottom);
        if (mIsFullWidth) {
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
            mQsClipTop = mEnableQsClipping
                    ? (int) (top - currentTranslation - mQsFrame.getTop()) : 0;
            mQsClipBottom = mEnableQsClipping
                    ? (int) (bottom - currentTranslation - mQsFrame.getTop()) : 0;
            mQsVisible = qsVisible;
            mQs.setQsVisible(mQsVisible);
            mQs.setFancyClipping(
                    mQsClipTop,
                    mQsClipBottom,
                    radius,
                    qsVisible && !mSplitShadeEnabled);
        }
        // The padding on this area is large enough that we can use a cheaper clipping strategy
        mKeyguardStatusViewController.setClipBounds(clipStatusView ? mLastQsClipBounds : null);
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
        int nsslTop = getNotificationsClippingTopBounds(top);
        int nsslBottom = bottom - mNotificationStackScrollLayoutController.getTop();
        int bottomRadius = mSplitShadeEnabled ? radius : 0;
        int topRadius = mSplitShadeEnabled && mExpandingFromHeadsUp ? 0 : radius;
        mNotificationStackScrollLayoutController.setRoundedClippingBounds(
                nsslLeft, nsslTop, nsslRight, nsslBottom, topRadius, bottomRadius);
    }

    private int getNotificationsClippingTopBounds(int qsTop) {
        if (mSplitShadeEnabled && mExpandingFromHeadsUp) {
            // in split shade nssl has extra top margin so clipping at top 0 is not enough, we need
            // to set top clipping bound to negative value to allow HUN to go up to the top edge of
            // the screen without clipping.
            return -mAmbientState.getStackTopMargin();
        } else {
            return qsTop - mNotificationStackScrollLayoutController.getTop();
        }
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
            int qsBottomYFrom = (int) getHeaderTranslation() + mQs.getQsMinExpansionHeight();
            int expandedTopMargin = mUseLargeScreenShadeHeader ? mLargeScreenShadeHeaderHeight : 0;
            int qsBottomYTo = mQs.getDesiredHeight() + expandedTopMargin;
            return (int) MathUtils.lerp(qsBottomYFrom, qsBottomYTo, qsExpansionFraction);
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

    float calculateNotificationsTopPadding() {
        if (mSplitShadeEnabled) {
            return mKeyguardShowing ? getKeyguardNotificationStaticPadding() : 0;
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

    public boolean getKeyguardShowing() {
        return mKeyguardShowing;
    }

    public float getKeyguardNotificationTopPadding() {
        return mKeyguardNotificationTopPadding;
    }

    public float getKeyguardNotificationBottomPadding() {
        return mKeyguardNotificationBottomPadding;
    }

    /** Returns the topPadding of notifications when on keyguard not respecting QS expansion. */
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

    private void requestScrollerTopPaddingUpdate(boolean animate) {
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
        if (animate && mIsFullWidth) {
            animateNextNotificationBounds(StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE,
                    delay);
            mIsQsTranslationResetAnimator = mQsTranslationForFullShadeTransition > 0.0f;
        }
        float endPosition = 0;
        if (pxAmount > 0.0f) {
            if (mSplitShadeEnabled) {
                float qsHeight = MathUtils.lerp(mQsMinExpansionHeight, mQsMaxExpansionHeight,
                        mLockscreenShadeTransitionController.getQSDragProgress());
                setQsExpansionHeight(qsHeight);
            }
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

    /** Called when pulse expansion has finished and this is going to the full shade. */
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

    /** @see #flingSettings(float, int, Runnable, boolean) */
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
    private void flingSettings(float vel, int type, final Runnable onFinishRunnable,
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
        animator.addUpdateListener(
                animation -> setQsExpansionHeight((Float) animation.getAnimatedValue()));
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

    @VisibleForTesting
    boolean canCollapsePanelOnTouch() {
        if (!isInSettings() && mBarState == KEYGUARD) {
            return true;
        }

        if (mNotificationStackScrollLayoutController.isScrolledToBottom()) {
            return true;
        }

        return !mSplitShadeEnabled && (isInSettings() || mIsPanelCollapseOnQQS);
    }

    int getMaxPanelHeight() {
        int min = mStatusBarMinHeight;
        if (!(mBarState == KEYGUARD)
                && mNotificationStackScrollLayoutController.getNotGoneChildCount() == 0) {
            int minHeight = mQsMinExpansionHeight;
            min = Math.max(min, minHeight);
        }
        int maxHeight;
        if (mQsExpandImmediate || mQsExpanded || mIsExpanding && mQsExpandedWhenExpandingStarted
                || mPulsing || mSplitShadeEnabled) {
            maxHeight = calculatePanelHeightQsExpanded();
        } else {
            maxHeight = calculatePanelHeightShade();
        }
        maxHeight = Math.max(min, maxHeight);
        if (maxHeight == 0) {
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

    private void onHeightUpdated(float expandedHeight) {
        if (expandedHeight <= 0) {
            mShadeLog.logExpansionChanged("onHeightUpdated: fully collapsed.",
                    mExpandedFraction, isExpanded(), mTracking, mExpansionDragDownAmountPx);
        } else if (isFullyExpanded()) {
            mShadeLog.logExpansionChanged("onHeightUpdated: fully expanded.",
                    mExpandedFraction, isExpanded(), mTracking, mExpansionDragDownAmountPx);
        }
        if (!mQsExpanded || mQsExpandImmediate || mIsExpanding && mQsExpandedWhenExpandingStarted) {
            // Updating the clock position will set the top padding which might
            // trigger a new panel height and re-position the clock.
            // This is a circular dependency and should be avoided, otherwise we'll have
            // a stack overflow.
            if (mStackScrollerMeasuringPass > 2) {
                debugLog("Unstable notification panel height. Aborting.");
            } else {
                positionClockAndNotifications();
            }
        }
        // Below is true when QS are expanded and we swipe up from the same bottom of panel to
        // close the whole shade with one motion. Also this will be always true when closing
        // split shade as there QS are always expanded so every collapsing motion is motion from
        // expanded QS to closed panel
        boolean collapsingShadeFromExpandedQs = mQsExpanded && !mQsTracking
                && mQsExpansionAnimator == null && !mQsExpansionFromOverscroll;
        boolean goingBetweenClosedShadeAndExpandedQs =
                mQsExpandImmediate || collapsingShadeFromExpandedQs;
        // in split shade we react when HUN is visible only if shade height is over HUN start
        // height - which means user is swiping down. Otherwise shade QS will either not show at all
        // with HUN movement or it will blink when touching HUN initially
        boolean qsShouldExpandWithHeadsUp = !mSplitShadeEnabled
                || (!mHeadsUpManager.isTrackingHeadsUp() || expandedHeight > mHeadsUpStartHeight);
        if (goingBetweenClosedShadeAndExpandedQs && qsShouldExpandWithHeadsUp) {
            float qsExpansionFraction;
            if (mSplitShadeEnabled) {
                qsExpansionFraction = 1;
            } else if (mKeyguardShowing) {
                // On Keyguard, interpolate the QS expansion linearly to the panel expansion
                qsExpansionFraction = expandedHeight / (getMaxPanelHeight());
            } else {
                // In Shade, interpolate linearly such that QS is closed whenever panel height is
                // minimum QS expansion + minStackHeight
                float panelHeightQsCollapsed =
                        mNotificationStackScrollLayoutController.getIntrinsicPadding()
                                + mNotificationStackScrollLayoutController.getLayoutMinHeight();
                float panelHeightQsExpanded = calculatePanelHeightQsExpanded();
                qsExpansionFraction = (expandedHeight - panelHeightQsCollapsed)
                        / (panelHeightQsExpanded - panelHeightQsCollapsed);
            }
            float targetHeight = mQsMinExpansionHeight
                    + qsExpansionFraction * (mQsMaxExpansionHeight - mQsMinExpansionHeight);
            setQsExpansionHeight(targetHeight);
        }
        updateExpandedHeight(expandedHeight);
        updateHeader();
        updateNotificationTranslucency();
        updatePanelExpanded();
        updateGestureExclusionRect();
        if (DEBUG_DRAWABLE) {
            mView.invalidate();
        }
    }

    private void updatePanelExpanded() {
        boolean isExpanded = !isFullyCollapsed() || mExpectingSynthesizedDown;
        if (mPanelExpanded != isExpanded) {
            mPanelExpanded = isExpanded;
            mShadeExpansionStateManager.onShadeExpansionFullyChanged(isExpanded);
            if (!isExpanded && mQs != null && mQs.isCustomizing()) {
                mQs.closeCustomizer();
            }
        }
    }

    public boolean isPanelExpanded() {
        return mPanelExpanded;
    }

    private int calculatePanelHeightShade() {
        int emptyBottomMargin = mNotificationStackScrollLayoutController.getEmptyBottomMargin();
        int maxHeight = mNotificationStackScrollLayoutController.getHeight() - emptyBottomMargin;

        if (mBarState == KEYGUARD) {
            int minKeyguardPanelBottom = mClockPositionAlgorithm.getLockscreenStatusViewHeight()
                    + mNotificationStackScrollLayoutController.getIntrinsicContentHeight();
            return Math.max(maxHeight, minKeyguardPanelBottom);
        } else {
            return maxHeight;
        }
    }

    int calculatePanelHeightQsExpanded() {
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
        if (mIsToLockscreenTransitionRunning) {
            return;
        }
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

    /** Hides the header when notifications are colliding with it. */
    private void updateHeader() {
        if (mBarState == KEYGUARD) {
            mKeyguardStatusBarViewController.updateViewState();
        }
        updateQsExpansion();
    }

    private float getHeaderTranslation() {
        if (mSplitShadeEnabled) {
            // in split shade QS don't translate, just (un)squish and overshoot
            return 0;
        }
        if (mBarState == KEYGUARD && !mKeyguardBypassController.getBypassEnabled()) {
            return -mQs.getQsMinExpansionHeight();
        }
        float appearAmount = mNotificationStackScrollLayoutController
                .calculateAppearFraction(mExpandedHeight);
        float startHeight = -mQsExpansionHeight;
        if (mBarState == StatusBarState.SHADE) {
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
        if (mIsToLockscreenTransitionRunning) {
            return;
        }
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
        mKeyguardBottomAreaInteractor.setAlpha(alpha);
        mLockIconViewController.setAlpha(alpha);
    }

    private void onExpandingStarted() {
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

    private void onExpandingFinished() {
        if (!mUnocclusionTransitionFlagEnabled) {
            mScrimController.onExpandingFinished();
        }
        mNotificationStackScrollLayoutController.onExpansionStopped();
        mHeadsUpManager.onExpandingFinished();
        mConversationNotificationManager.onNotificationPanelExpandStateChanged(isFullyCollapsed());
        mIsExpanding = false;
        mMediaHierarchyManager.setCollapsingShadeFromQS(false);
        mMediaHierarchyManager.setQsExpanded(mQsExpanded);
        if (isFullyCollapsed()) {
            DejankUtils.postAfterTraversal(() -> setListening(false));

            // Workaround b/22639032: Make sure we invalidate something because else RenderThread
            // thinks we are actually drawing a frame put in reality we don't, so RT doesn't go
            // ahead with rendering and we jank.
            mView.postOnAnimation(
                    () -> mView.getParent().invalidateChild(mView, M_DUMMY_DIRTY_RECT));
        } else {
            setListening(true);
        }
        if (mBarState != SHADE) {
            // updating qsExpandImmediate is done in onPanelStateChanged for unlocked shade but
            // on keyguard panel state is always OPEN so we need to have that extra update
            setQsExpandImmediate(false);
        }
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

    public void expand(boolean animate) {
        if (isFullyCollapsed() || isCollapsing()) {
            mInstantExpanding = true;
            mAnimateAfterExpanding = animate;
            mUpdateFlingOnLayout = false;
            abortAnimations();
            if (mTracking) {
                // The panel is expanded after this call.
                onTrackingStopped(true /* expands */);
            }
            if (mExpanding) {
                notifyExpandingFinished();
            }
            updatePanelExpansionAndVisibility();
            // Wait for window manager to pickup the change, so we know the maximum height of the
            // panel then.
            this.mView.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (!mInstantExpanding) {
                                mView.getViewTreeObserver().removeOnGlobalLayoutListener(
                                        this);
                                return;
                            }
                            if (mCentralSurfaces.getNotificationShadeWindowView()
                                    .isVisibleToUser()) {
                                mView.getViewTreeObserver().removeOnGlobalLayoutListener(
                                        this);
                                if (mAnimateAfterExpanding) {
                                    notifyExpandingStarted();
                                    beginJankMonitoring();
                                    fling(0  /* expand */);
                                } else {
                                    mShadeHeightLogger.logFunctionCall("expand");
                                    setExpandedFraction(1f);
                                }
                                mInstantExpanding = false;
                            }
                        }
                    });
            // Make sure a layout really happens.
            this.mView.requestLayout();
        }

        setListening(true);
    }

    @VisibleForTesting
    void setTouchSlopExceeded(boolean isTouchSlopExceeded) {
        mTouchSlopExceeded = isTouchSlopExceeded;
    }

    public void setOverExpansion(float overExpansion) {
        if (overExpansion == mOverExpansion) {
            return;
        }
        mOverExpansion = overExpansion;
        // Translating the quick settings by half the overexpansion to center it in the background
        // frame
        updateQsFrameTranslation();
        mNotificationStackScrollLayoutController.setOverExpansion(overExpansion);
    }

    private void updateQsFrameTranslation() {
        mQsFrameTranslateController.translateQsFrame(mQsFrame, mQs,
                mNavigationBarBottomHeight + mAmbientState.getStackTopMargin());

    }

    private void falsingAdditionalTapRequired() {
        if (mStatusBarStateController.getState() == StatusBarState.SHADE_LOCKED) {
            mTapAgainViewController.show();
        } else {
            mKeyguardIndicationController.showTransientIndication(
                    R.string.notification_tap_again);
        }

        if (!mStatusBarStateController.isDozing()) {
            mVibratorHelper.vibrate(
                    Process.myUid(),
                    mView.getContext().getPackageName(),
                    ADDITIONAL_TAP_REQUIRED_VIBRATION_EFFECT,
                    "falsing-additional-tap-required",
                    TOUCH_VIBRATION_ATTRIBUTES);
        }
    }

    private void onTrackingStarted() {
        mFalsingCollector.onTrackingStarted(!mKeyguardStateController.canDismissLockScreen());
        endClosing();
        mTracking = true;
        mTrackingStartedListener.onTrackingStarted();
        notifyExpandingStarted();
        updatePanelExpansionAndVisibility();
        mScrimController.onTrackingStarted();
        if (mQsFullyExpanded) {
            setQsExpandImmediate(true);
            setShowShelfOnly(true);
        }
        mNotificationStackScrollLayoutController.onPanelTrackingStarted();
        cancelPendingPanelCollapse();
    }

    private void onTrackingStopped(boolean expand) {
        mFalsingCollector.onTrackingStopped();
        mTracking = false;
        updatePanelExpansionAndVisibility();
        if (expand) {
            mNotificationStackScrollLayoutController.setOverScrollAmount(0.0f, true /* onTop */,
                    true /* animate */);
        }
        mNotificationStackScrollLayoutController.onPanelTrackingStopped();

        // If we unlocked from a swipe, the user's finger might still be down after the
        // unlock animation ends. We need to wait until ACTION_UP to enable blurs again.
        mDepthController.setBlursDisabledForUnlock(false);
    }

    private void updateMaxHeadsUpTranslation() {
        mNotificationStackScrollLayoutController.setHeadsUpBoundaries(
                mView.getHeight(), mNavigationBarBottomHeight);
    }

    @VisibleForTesting
    void startUnlockHintAnimation() {
        if (mPowerManager.isPowerSaveMode() || mAmbientState.getDozeAmount() > 0f) {
            onUnlockHintStarted();
            onUnlockHintFinished();
            return;
        }

        // We don't need to hint the user if an animation is already running or the user is changing
        // the expansion.
        if (mHeightAnimator != null || mTracking) {
            return;
        }
        notifyExpandingStarted();
        startUnlockHintAnimationPhase1(() -> {
            notifyExpandingFinished();
            onUnlockHintFinished();
            mHintAnimationRunning = false;
        });
        onUnlockHintStarted();
        mHintAnimationRunning = true;
    }

    @VisibleForTesting
    void onUnlockHintFinished() {
        // Delay the reset a bit so the user can read the text.
        mKeyguardIndicationController.hideTransientIndicationDelayed(HINT_RESET_DELAY_MS);
        mScrimController.setExpansionAffectsAlpha(true);
        mNotificationStackScrollLayoutController.setUnlockHintRunning(false);
    }

    @VisibleForTesting
    void onUnlockHintStarted() {
        mFalsingCollector.onUnlockHintStarted();
        mKeyguardIndicationController.showActionToUnlock();
        mScrimController.setExpansionAffectsAlpha(false);
        mNotificationStackScrollLayoutController.setUnlockHintRunning(true);
    }

    private boolean shouldUseDismissingAnimation() {
        return mBarState != StatusBarState.SHADE && (mKeyguardStateController.canDismissLockScreen()
                || !isTracking());
    }

    @VisibleForTesting
    int getMaxPanelTransitionDistance() {
        // Traditionally the value is based on the number of notifications. On split-shade, we want
        // the required distance to be a specific and constant value, to make sure the expansion
        // motion has the expected speed. We also only want this on non-lockscreen for now.
        if (mSplitShadeEnabled && mBarState == SHADE) {
            boolean transitionFromHeadsUp =
                    mHeadsUpManager.isTrackingHeadsUp() || mExpandingFromHeadsUp;
            // heads-up starting height is too close to mSplitShadeFullTransitionDistance and
            // when dragging HUN transition is already 90% complete. It makes shade become
            // immediately visible when starting to drag. We want to set distance so that
            // nothing is immediately visible when dragging (important for HUN swipe up motion) -
            // 0.4 expansion fraction is a good starting point.
            if (transitionFromHeadsUp) {
                double maxDistance = Math.max(mSplitShadeFullTransitionDistance,
                        mHeadsUpStartHeight * 2.5);
                return (int) Math.min(getMaxPanelHeight(), maxDistance);
            } else {
                return mSplitShadeFullTransitionDistance;
            }
        } else {
            return getMaxPanelHeight();
        }
    }

    @VisibleForTesting
    boolean isTrackingBlocked() {
        return mConflictingQsExpansionGesture && mQsExpanded || mBlockingExpansionForCurrentTouch;
    }

    public boolean isQsExpanded() {
        return mQsExpanded;
    }

    /** Returns whether the QS customizer is currently active. */
    public boolean isQsCustomizing() {
        return mQs.isCustomizing();
    }

    /** Close the QS customizer if it is open. */
    public void closeQsCustomizer() {
        mQs.closeCustomizer();
    }

    public void setIsLaunchAnimationRunning(boolean running) {
        boolean wasRunning = mIsLaunchAnimationRunning;
        mIsLaunchAnimationRunning = running;
        if (wasRunning != mIsLaunchAnimationRunning) {
            mShadeExpansionStateManager.notifyLaunchingActivityChanged(running);
        }
    }

    @VisibleForTesting
    void setClosing(boolean isClosing) {
        if (mClosing != isClosing) {
            mClosing = isClosing;
            mShadeExpansionStateManager.notifyPanelCollapsingChanged(isClosing);
        }
        mAmbientState.setIsClosing(isClosing);
    }

    private void updateDozingVisibilities(boolean animate) {
        mKeyguardBottomAreaInteractor.setAnimateDozingTransitions(animate);
        if (!mDozing && animate) {
            mKeyguardStatusBarViewController.animateKeyguardStatusBarIn();
        }
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

    private void onMiddleClicked() {
        switch (mBarState) {
            case KEYGUARD:
                if (!mDozingOnDown) {
                    mShadeLog.v("onMiddleClicked on Keyguard, mDozingOnDown: false");
                    // Try triggering face auth, this "might" run. Check
                    // KeyguardUpdateMonitor#shouldListenForFace to see when face auth won't run.
                    boolean didFaceAuthRun = mUpdateMonitor.requestFaceAuth(
                            FaceAuthApiRequestReason.NOTIFICATION_PANEL_CLICKED);

                    if (didFaceAuthRun) {
                        mUpdateMonitor.requestActiveUnlock(
                                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.UNLOCK_INTENT,
                                "lockScreenEmptySpaceTap");
                    } else {
                        mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_HINT,
                                0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
                        mLockscreenGestureLogger
                                .log(LockscreenUiEvent.LOCKSCREEN_LOCK_SHOW_HINT);
                        startUnlockHintAnimation();
                    }
                }
                break;
            case StatusBarState.SHADE_LOCKED:
                if (!mQsExpanded) {
                    mStatusBarStateController.setState(KEYGUARD);
                }
                break;
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

    private boolean shouldPanelBeVisible() {
        boolean headsUpVisible = mHeadsUpAnimatingAway || mHeadsUpPinnedMode;
        return headsUpVisible || isExpanded() || mBouncerShowing;
    }

    public void setHeadsUpManager(HeadsUpManagerPhone headsUpManager) {
        mHeadsUpManager = headsUpManager;
        mHeadsUpManager.addListener(mOnHeadsUpChangedListener);
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

    private void onClosingFinished() {
        mOpenCloseListener.onClosingFinished();
        setClosingWithAlphaFadeout(false);
        mMediaHierarchyManager.closeGuts();
    }

    private void setClosingWithAlphaFadeout(boolean closing) {
        mClosingWithAlphaFadeOut = closing;
        mNotificationStackScrollLayoutController.forceNoOverlappingRendering(closing);
    }

    private void updateExpandedHeight(float expandedHeight) {
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

    private void updateStatusBarIcons() {
        boolean showIconsWhenExpanded =
                (isPanelVisibleBecauseOfHeadsUp() || mIsFullWidth)
                        && getExpandedHeight() < getOpeningHeight();
        if (showIconsWhenExpanded && isOnKeyguard()) {
            showIconsWhenExpanded = false;
        }
        if (showIconsWhenExpanded != mShowIconsWhenExpanded) {
            mShowIconsWhenExpanded = showIconsWhenExpanded;
            mCommandQueue.recomputeDisableFlags(mDisplayId, false);
        }
    }

    public int getBarState() {
        return mBarState;
    }

    private boolean isOnKeyguard() {
        return mBarState == KEYGUARD;
    }

    /** Called when a HUN is dragged up or down to indicate the starting height for shade motion. */
    public void setHeadsUpDraggingStartingHeight(int startHeight) {
        mHeadsUpStartHeight = startHeight;
        float scrimMinFraction;
        if (mSplitShadeEnabled) {
            boolean highHun = mHeadsUpStartHeight * 2.5 > mSplitShadeScrimTransitionDistance;
            // if HUN height is higher than 40% of predefined transition distance, it means HUN
            // is too high for regular transition. In that case we need to calculate transition
            // distance - here we take scrim transition distance as equal to shade transition
            // distance. It doesn't result in perfect motion - usually scrim transition distance
            // should be longer - but it's good enough for HUN case.
            float transitionDistance =
                    highHun ? getMaxPanelTransitionDistance() : mSplitShadeFullTransitionDistance;
            scrimMinFraction = mHeadsUpStartHeight / transitionDistance;
        } else {
            int transitionDistance = getMaxPanelHeight();
            scrimMinFraction = transitionDistance > 0f
                    ? (float) mHeadsUpStartHeight / transitionDistance : 0f;
        }
        setPanelScrimMinFraction(scrimMinFraction);
    }

    /**
     * Sets the minimum fraction for the panel expansion offset. This may be non-zero in certain
     * cases, such as if there's a heads-up notification.
     */
    private void setPanelScrimMinFraction(float minFraction) {
        mMinFraction = minFraction;
        mDepthController.setPanelPullDownMinFraction(mMinFraction);
        mScrimController.setPanelScrimMinFraction(mMinFraction);
    }

    public void clearNotificationEffects() {
        mCentralSurfaces.clearNotificationEffects();
    }

    private boolean isPanelVisibleBecauseOfHeadsUp() {
        return (mHeadsUpManager.hasPinnedHeadsUp() || mHeadsUpAnimatingAway)
                && mBarState == StatusBarState.SHADE;
    }

    public boolean hideStatusBarIconsWhenExpanded() {
        if (mIsLaunchAnimationRunning) {
            return mHideIconsDuringLaunchAnimation;
        }
        if (mHeadsUpAppearanceController != null
                && mHeadsUpAppearanceController.shouldBeVisible()) {
            return false;
        }
        return !mIsFullWidth || !mShowIconsWhenExpanded;
    }

    private void onQsPanelScrollChanged(int scrollY) {
        mLargeScreenShadeHeaderController.setQsScrollY(scrollY);
        if (scrollY > 0 && !mQsFullyExpanded) {
            debugLog("Scrolling while not expanded. Forcing expand");
            // If we are scrolling QS, we should be fully expanded.
            expandWithQs();
        }
    }

    private final class QsFragmentListener implements FragmentListener {
        @Override
        public void onFragmentViewCreated(String tag, Fragment fragment) {
            mQs = (QS) fragment;
            mQs.setPanelView(mHeightListener);
            mQs.setCollapseExpandAction(mCollapseExpandAction);
            mQs.setHeaderClickable(isQsExpansionEnabled());
            mQs.setOverscrolling(mStackScrollerOverscrolling);
            mQs.setInSplitShade(mSplitShadeEnabled);
            mQs.setIsNotificationPanelFullWidth(mIsFullWidth);

            // recompute internal state when qspanel height changes
            mQs.getView().addOnLayoutChangeListener(
                    (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                        final int height = bottom - top;
                        final int oldHeight = oldBottom - oldTop;
                        if (height != oldHeight) {
                            onQsHeightChanged();
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
            mQs.setScrollListener(mQsScrollListener);
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
    }

    private void animateNextNotificationBounds(long duration, long delay) {
        mAnimateNextNotificationBounds = true;
        mNotificationBoundsAnimationDuration = duration;
        mNotificationBoundsAnimationDelay = delay;
    }

    public void setTouchAndAnimationDisabled(boolean disabled) {
        mTouchDisabled = disabled;
        if (mTouchDisabled) {
            cancelHeightAnimator();
            if (mTracking) {
                onTrackingStopped(true /* expanded */);
            }
            notifyExpandingFinished();
        }
        mNotificationStackScrollLayoutController.setAnimationsEnabled(!disabled);
    }

    /**
     * Sets the dozing state.
     *
     * @param dozing  {@code true} when dozing.
     * @param animate if transition should be animated.
     */
    public void setDozing(boolean dozing, boolean animate) {
        if (dozing == mDozing) return;
        mView.setDozing(dozing);
        mDozing = dozing;
        mNotificationStackScrollLayoutController.setDozing(mDozing, animate);
        mKeyguardBottomAreaInteractor.setAnimateDozingTransitions(animate);
        mKeyguardStatusBarViewController.setDozing(mDozing);

        if (dozing) {
            mBottomAreaShadeAlphaAnimator.cancel();
        }

        if (mBarState == KEYGUARD || mBarState == StatusBarState.SHADE_LOCKED) {
            updateDozingVisibilities(animate);
        }

        final float dozeAmount = dozing ? 1 : 0;
        mStatusBarStateController.setAndInstrumentDozeAmount(mView, dozeAmount, animate);

        updateKeyguardStatusViewAlignment(animate);
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

        updateKeyguardStatusViewAlignment(/* animate= */ true);
    }

    public void setAmbientIndicationTop(int ambientIndicationTop, boolean ambientTextVisible) {
        int ambientIndicationBottomPadding = 0;
        if (ambientTextVisible) {
            int stackBottom = mNotificationStackScrollLayoutController.getBottom();
            ambientIndicationBottomPadding = stackBottom - ambientIndicationTop;
        }
        if (mAmbientIndicationBottomPadding != ambientIndicationBottomPadding) {
            mAmbientIndicationBottomPadding = ambientIndicationBottomPadding;
            updateMaxDisplayedNotifications(true);
        }
    }

    public void dozeTimeTick() {
        mLockIconViewController.dozeTimeTick();
        mKeyguardStatusViewController.dozeTimeTick();
        if (mInterpolatedDarkAmount > 0) {
            positionClockAndNotifications();
        }
    }

    public void setStatusAccessibilityImportance(int mode) {
        mKeyguardStatusViewController.setStatusAccessibilityImportance(mode);
    }

    //TODO(b/254875405): this should be removed.
    public KeyguardBottomAreaView getKeyguardBottomAreaView() {
        return mKeyguardBottomArea;
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

    /** Called before animating Keyguard dismissal, i.e. the animation dismissing the bouncer. */
    public void startBouncerPreHideAnimation() {
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

    /** Updates the views to the initial state for the fold to AOD animation. */
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
     * Starts fold to AOD animation.
     *
     * @param startAction  invoked when the animation starts.
     * @param endAction    invoked when the animation finishes, also if it was cancelled.
     * @param cancelAction invoked when the animation is cancelled, before endAction.
     */
    public void startFoldToAodAnimation(Runnable startAction, Runnable endAction,
            Runnable cancelAction) {
        mView.animate()
                .translationX(0)
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_FOLD_TO_AOD)
                .setInterpolator(EMPHASIZED_DECELERATE)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        startAction.run();
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        cancelAction.run();
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        endAction.run();
                    }
                }).setUpdateListener(anim -> mKeyguardStatusViewController.animateFoldToAod(
                        anim.getAnimatedFraction())).start();
    }

    /** Cancels fold to AOD transition and resets view state. */
    public void cancelFoldToAodAnimation() {
        cancelAnimation();
        resetAlpha();
        resetTranslation();
    }

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
        pw.println(TAG + ":");
        IndentingPrintWriter ipw = asIndenting(pw);
        ipw.increaseIndent();

        ipw.print("mDownTime="); ipw.println(mDownTime);
        ipw.print("mTouchSlopExceededBeforeDown="); ipw.println(mTouchSlopExceededBeforeDown);
        ipw.print("mIsLaunchAnimationRunning="); ipw.println(mIsLaunchAnimationRunning);
        ipw.print("mOverExpansion="); ipw.println(mOverExpansion);
        ipw.print("mExpandedHeight="); ipw.println(mExpandedHeight);
        ipw.print("mTracking="); ipw.println(mTracking);
        ipw.print("mHintAnimationRunning="); ipw.println(mHintAnimationRunning);
        ipw.print("mExpanding="); ipw.println(mExpanding);
        ipw.print("mSplitShadeEnabled="); ipw.println(mSplitShadeEnabled);
        ipw.print("mKeyguardNotificationBottomPadding=");
        ipw.println(mKeyguardNotificationBottomPadding);
        ipw.print("mKeyguardNotificationTopPadding="); ipw.println(mKeyguardNotificationTopPadding);
        ipw.print("mMaxAllowedKeyguardNotifications=");
        ipw.println(mMaxAllowedKeyguardNotifications);
        ipw.print("mAnimateNextPositionUpdate="); ipw.println(mAnimateNextPositionUpdate);
        ipw.print("mQuickQsHeaderHeight="); ipw.println(mQuickQsHeaderHeight);
        ipw.print("mQsTrackingPointer="); ipw.println(mQsTrackingPointer);
        ipw.print("mQsTracking="); ipw.println(mQsTracking);
        ipw.print("mConflictingQsExpansionGesture="); ipw.println(mConflictingQsExpansionGesture);
        ipw.print("mPanelExpanded="); ipw.println(mPanelExpanded);
        ipw.print("mQsExpanded="); ipw.println(mQsExpanded);
        ipw.print("mQsExpandedWhenExpandingStarted="); ipw.println(mQsExpandedWhenExpandingStarted);
        ipw.print("mQsFullyExpanded="); ipw.println(mQsFullyExpanded);
        ipw.print("mKeyguardShowing="); ipw.println(mKeyguardShowing);
        ipw.print("mKeyguardQsUserSwitchEnabled="); ipw.println(mKeyguardQsUserSwitchEnabled);
        ipw.print("mKeyguardUserSwitcherEnabled="); ipw.println(mKeyguardUserSwitcherEnabled);
        ipw.print("mDozing="); ipw.println(mDozing);
        ipw.print("mDozingOnDown="); ipw.println(mDozingOnDown);
        ipw.print("mBouncerShowing="); ipw.println(mBouncerShowing);
        ipw.print("mBarState="); ipw.println(mBarState);
        ipw.print("mInitialHeightOnTouch="); ipw.println(mInitialHeightOnTouch);
        ipw.print("mInitialTouchX="); ipw.println(mInitialTouchX);
        ipw.print("mInitialTouchY="); ipw.println(mInitialTouchY);
        ipw.print("mQsExpansionHeight="); ipw.println(mQsExpansionHeight);
        ipw.print("mQsMinExpansionHeight="); ipw.println(mQsMinExpansionHeight);
        ipw.print("mQsMaxExpansionHeight="); ipw.println(mQsMaxExpansionHeight);
        ipw.print("mQsPeekHeight="); ipw.println(mQsPeekHeight);
        ipw.print("mStackScrollerOverscrolling="); ipw.println(mStackScrollerOverscrolling);
        ipw.print("mQsExpansionFromOverscroll="); ipw.println(mQsExpansionFromOverscroll);
        ipw.print("mLastOverscroll="); ipw.println(mLastOverscroll);
        ipw.print("mQsExpansionEnabledPolicy="); ipw.println(mQsExpansionEnabledPolicy);
        ipw.print("mQsExpansionEnabledAmbient="); ipw.println(mQsExpansionEnabledAmbient);
        ipw.print("mStatusBarMinHeight="); ipw.println(mStatusBarMinHeight);
        ipw.print("mStatusBarHeaderHeightKeyguard="); ipw.println(mStatusBarHeaderHeightKeyguard);
        ipw.print("mOverStretchAmount="); ipw.println(mOverStretchAmount);
        ipw.print("mDownX="); ipw.println(mDownX);
        ipw.print("mDownY="); ipw.println(mDownY);
        ipw.print("mDisplayTopInset="); ipw.println(mDisplayTopInset);
        ipw.print("mDisplayRightInset="); ipw.println(mDisplayRightInset);
        ipw.print("mLargeScreenShadeHeaderHeight="); ipw.println(mLargeScreenShadeHeaderHeight);
        ipw.print("mSplitShadeNotificationsScrimMarginBottom=");
        ipw.println(mSplitShadeNotificationsScrimMarginBottom);
        ipw.print("mIsExpanding="); ipw.println(mIsExpanding);
        ipw.print("mQsExpandImmediate="); ipw.println(mQsExpandImmediate);
        ipw.print("mTwoFingerQsExpandPossible="); ipw.println(mTwoFingerQsExpandPossible);
        ipw.print("mHeaderDebugInfo="); ipw.println(mHeaderDebugInfo);
        ipw.print("mQsAnimatorExpand="); ipw.println(mQsAnimatorExpand);
        ipw.print("mQsScrimEnabled="); ipw.println(mQsScrimEnabled);
        ipw.print("mQsTouchAboveFalsingThreshold="); ipw.println(mQsTouchAboveFalsingThreshold);
        ipw.print("mQsFalsingThreshold="); ipw.println(mQsFalsingThreshold);
        ipw.print("mHeadsUpStartHeight="); ipw.println(mHeadsUpStartHeight);
        ipw.print("mListenForHeadsUp="); ipw.println(mListenForHeadsUp);
        ipw.print("mNavigationBarBottomHeight="); ipw.println(mNavigationBarBottomHeight);
        ipw.print("mExpandingFromHeadsUp="); ipw.println(mExpandingFromHeadsUp);
        ipw.print("mCollapsedOnDown="); ipw.println(mCollapsedOnDown);
        ipw.print("mClosingWithAlphaFadeOut="); ipw.println(mClosingWithAlphaFadeOut);
        ipw.print("mHeadsUpAnimatingAway="); ipw.println(mHeadsUpAnimatingAway);
        ipw.print("mShowIconsWhenExpanded="); ipw.println(mShowIconsWhenExpanded);
        ipw.print("mIndicationBottomPadding="); ipw.println(mIndicationBottomPadding);
        ipw.print("mAmbientIndicationBottomPadding="); ipw.println(mAmbientIndicationBottomPadding);
        ipw.print("mIsFullWidth="); ipw.println(mIsFullWidth);
        ipw.print("mBlockingExpansionForCurrentTouch=");
        ipw.println(mBlockingExpansionForCurrentTouch);
        ipw.print("mExpectingSynthesizedDown="); ipw.println(mExpectingSynthesizedDown);
        ipw.print("mLastEventSynthesizedDown="); ipw.println(mLastEventSynthesizedDown);
        ipw.print("mInterpolatedDarkAmount="); ipw.println(mInterpolatedDarkAmount);
        ipw.print("mLinearDarkAmount="); ipw.println(mLinearDarkAmount);
        ipw.print("mPulsing="); ipw.println(mPulsing);
        ipw.print("mHideIconsDuringLaunchAnimation="); ipw.println(mHideIconsDuringLaunchAnimation);
        ipw.print("mStackScrollerMeasuringPass="); ipw.println(mStackScrollerMeasuringPass);
        ipw.print("mPanelAlpha="); ipw.println(mPanelAlpha);
        ipw.print("mBottomAreaShadeAlpha="); ipw.println(mBottomAreaShadeAlpha);
        ipw.print("mHeadsUpInset="); ipw.println(mHeadsUpInset);
        ipw.print("mHeadsUpPinnedMode="); ipw.println(mHeadsUpPinnedMode);
        ipw.print("mAllowExpandForSmallExpansion="); ipw.println(mAllowExpandForSmallExpansion);
        ipw.print("mLockscreenNotificationQSPadding=");
        ipw.println(mLockscreenNotificationQSPadding);
        ipw.print("mTransitioningToFullShadeProgress=");
        ipw.println(mTransitioningToFullShadeProgress);
        ipw.print("mTransitionToFullShadeQSPosition=");
        ipw.println(mTransitionToFullShadeQSPosition);
        ipw.print("mDistanceForQSFullShadeTransition=");
        ipw.println(mDistanceForQSFullShadeTransition);
        ipw.print("mQsTranslationForFullShadeTransition=");
        ipw.println(mQsTranslationForFullShadeTransition);
        ipw.print("mMaxOverscrollAmountForPulse="); ipw.println(mMaxOverscrollAmountForPulse);
        ipw.print("mAnimateNextNotificationBounds="); ipw.println(mAnimateNextNotificationBounds);
        ipw.print("mNotificationBoundsAnimationDelay=");
        ipw.println(mNotificationBoundsAnimationDelay);
        ipw.print("mNotificationBoundsAnimationDuration=");
        ipw.println(mNotificationBoundsAnimationDuration);
        ipw.print("mIsPanelCollapseOnQQS="); ipw.println(mIsPanelCollapseOnQQS);
        ipw.print("mAnimatingQS="); ipw.println(mAnimatingQS);
        ipw.print("mIsQsTranslationResetAnimator="); ipw.println(mIsQsTranslationResetAnimator);
        ipw.print("mIsPulseExpansionResetAnimator="); ipw.println(mIsPulseExpansionResetAnimator);
        ipw.print("mKeyguardOnlyContentAlpha="); ipw.println(mKeyguardOnlyContentAlpha);
        ipw.print("mKeyguardOnlyTransitionTranslationY=");
        ipw.println(mKeyguardOnlyTransitionTranslationY);
        ipw.print("mUdfpsMaxYBurnInOffset="); ipw.println(mUdfpsMaxYBurnInOffset);
        ipw.print("mIsGestureNavigation="); ipw.println(mIsGestureNavigation);
        ipw.print("mOldLayoutDirection="); ipw.println(mOldLayoutDirection);
        ipw.print("mScrimCornerRadius="); ipw.println(mScrimCornerRadius);
        ipw.print("mScreenCornerRadius="); ipw.println(mScreenCornerRadius);
        ipw.print("mQSAnimatingHiddenFromCollapsed="); ipw.println(mQSAnimatingHiddenFromCollapsed);
        ipw.print("mUseLargeScreenShadeHeader="); ipw.println(mUseLargeScreenShadeHeader);
        ipw.print("mEnableQsClipping="); ipw.println(mEnableQsClipping);
        ipw.print("mQsClipTop="); ipw.println(mQsClipTop);
        ipw.print("mQsClipBottom="); ipw.println(mQsClipBottom);
        ipw.print("mQsVisible="); ipw.println(mQsVisible);
        ipw.print("mMinFraction="); ipw.println(mMinFraction);
        ipw.print("mStatusViewCentered="); ipw.println(mStatusViewCentered);
        ipw.print("mSplitShadeFullTransitionDistance=");
        ipw.println(mSplitShadeFullTransitionDistance);
        ipw.print("mSplitShadeScrimTransitionDistance=");
        ipw.println(mSplitShadeScrimTransitionDistance);
        ipw.print("mMinExpandHeight="); ipw.println(mMinExpandHeight);
        ipw.print("mPanelUpdateWhenAnimatorEnds="); ipw.println(mPanelUpdateWhenAnimatorEnds);
        ipw.print("mHasVibratedOnOpen="); ipw.println(mHasVibratedOnOpen);
        ipw.print("mFixedDuration="); ipw.println(mFixedDuration);
        ipw.print("mPanelFlingOvershootAmount="); ipw.println(mPanelFlingOvershootAmount);
        ipw.print("mLastGesturedOverExpansion="); ipw.println(mLastGesturedOverExpansion);
        ipw.print("mIsSpringBackAnimation="); ipw.println(mIsSpringBackAnimation);
        ipw.print("mSplitShadeEnabled="); ipw.println(mSplitShadeEnabled);
        ipw.print("mHintDistance="); ipw.println(mHintDistance);
        ipw.print("mInitialOffsetOnTouch="); ipw.println(mInitialOffsetOnTouch);
        ipw.print("mCollapsedAndHeadsUpOnDown="); ipw.println(mCollapsedAndHeadsUpOnDown);
        ipw.print("mExpandedFraction="); ipw.println(mExpandedFraction);
        ipw.print("mExpansionDragDownAmountPx="); ipw.println(mExpansionDragDownAmountPx);
        ipw.print("mPanelClosedOnDown="); ipw.println(mPanelClosedOnDown);
        ipw.print("mHasLayoutedSinceDown="); ipw.println(mHasLayoutedSinceDown);
        ipw.print("mUpdateFlingVelocity="); ipw.println(mUpdateFlingVelocity);
        ipw.print("mUpdateFlingOnLayout="); ipw.println(mUpdateFlingOnLayout);
        ipw.print("mClosing="); ipw.println(mClosing);
        ipw.print("mTouchSlopExceeded="); ipw.println(mTouchSlopExceeded);
        ipw.print("mTrackingPointer="); ipw.println(mTrackingPointer);
        ipw.print("mTouchSlop="); ipw.println(mTouchSlop);
        ipw.print("mSlopMultiplier="); ipw.println(mSlopMultiplier);
        ipw.print("mTouchAboveFalsingThreshold="); ipw.println(mTouchAboveFalsingThreshold);
        ipw.print("mTouchStartedInEmptyArea="); ipw.println(mTouchStartedInEmptyArea);
        ipw.print("mMotionAborted="); ipw.println(mMotionAborted);
        ipw.print("mUpwardsWhenThresholdReached="); ipw.println(mUpwardsWhenThresholdReached);
        ipw.print("mAnimatingOnDown="); ipw.println(mAnimatingOnDown);
        ipw.print("mHandlingPointerUp="); ipw.println(mHandlingPointerUp);
        ipw.print("mInstantExpanding="); ipw.println(mInstantExpanding);
        ipw.print("mAnimateAfterExpanding="); ipw.println(mAnimateAfterExpanding);
        ipw.print("mIsFlinging="); ipw.println(mIsFlinging);
        ipw.print("mViewName="); ipw.println(mViewName);
        ipw.print("mInitialExpandY="); ipw.println(mInitialExpandY);
        ipw.print("mInitialExpandX="); ipw.println(mInitialExpandX);
        ipw.print("mTouchDisabled="); ipw.println(mTouchDisabled);
        ipw.print("mInitialTouchFromKeyguard="); ipw.println(mInitialTouchFromKeyguard);
        ipw.print("mNextCollapseSpeedUpFactor="); ipw.println(mNextCollapseSpeedUpFactor);
        ipw.print("mGestureWaitForTouchSlop="); ipw.println(mGestureWaitForTouchSlop);
        ipw.print("mIgnoreXTouchSlop="); ipw.println(mIgnoreXTouchSlop);
        ipw.print("mExpandLatencyTracking="); ipw.println(mExpandLatencyTracking);
        ipw.print("mExpandLatencyTracking="); ipw.println(mExpandLatencyTracking);
        ipw.println("gestureExclusionRect:" + calculateGestureExclusionRect());
        new DumpsysTableLogger(
                TAG,
                NPVCDownEventState.TABLE_HEADERS,
                mLastDownEvents.toList()
        ).printTableData(ipw);
    }


    public RemoteInputController.Delegate createRemoteInputDelegate() {
        return mNotificationStackScrollLayoutController.createDelegate();
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

    /**
     * Initialize objects instead of injecting to avoid circular dependencies.
     *
     * @param hideExpandedRunnable a runnable to run when we need to hide the expanded panel.
     */
    public void initDependencies(
            CentralSurfaces centralSurfaces,
            GestureRecorder recorder,
            Runnable hideExpandedRunnable,
            NotificationShelfController notificationShelfController) {
        // TODO(b/254859580): this can be injected.
        mCentralSurfaces = centralSurfaces;

        mGestureRecorder = recorder;
        mHideExpandedRunnable = hideExpandedRunnable;
        mNotificationStackScrollLayoutController.setShelfController(notificationShelfController);
        mNotificationShelfController = notificationShelfController;
        mLockscreenShadeTransitionController.bindController(notificationShelfController);
        updateMaxDisplayedNotifications(true);
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

    void addOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener listener) {
        mView.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    void removeOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener listener) {
        mView.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
    }

    public void setHeaderDebugInfo(String text) {
        if (DEBUG_DRAWABLE) mHeaderDebugInfo = text;
    }

    public String getHeaderDebugInfo() {
        return mHeaderDebugInfo;
    }

    public void onThemeChanged() {
        mConfigurationListener.onThemeChanged();
    }

    @VisibleForTesting
    TouchHandler createTouchHandler() {
        return new TouchHandler();
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

    /** Updates notification panel-specific flags on {@link SysUiState}. */
    public void updateSystemUiStateFlags() {
        if (SysUiState.DEBUG) {
            Log.d(TAG, "Updating panel sysui state flags: fullyExpanded="
                    + isFullyExpanded() + " inQs=" + isInSettings());
        }
        mSysUiState.setFlag(SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED,
                        isFullyExpanded() && !isInSettings())
                .setFlag(SYSUI_STATE_QUICK_SETTINGS_EXPANDED, isFullyExpanded() && isInSettings())
                .commitUpdate(mDisplayId);
    }

    private void debugLog(String fmt, Object... args) {
        if (DEBUG_LOGCAT) {
            Log.d(TAG, (mViewName != null ? (mViewName + ": ") : "") + String.format(fmt, args));
        }
    }

    @VisibleForTesting
    void notifyExpandingStarted() {
        if (!mExpanding) {
            mExpanding = true;
            onExpandingStarted();
        }
    }

    @VisibleForTesting
    void notifyExpandingFinished() {
        endClosing();
        if (mExpanding) {
            mExpanding = false;
            onExpandingFinished();
        }
    }

    private float getTouchSlop(MotionEvent event) {
        // Adjust the touch slop if another gesture may be being performed.
        return event.getClassification() == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE
                ? mTouchSlop * mSlopMultiplier
                : mTouchSlop;
    }

    private void addMovement(MotionEvent event) {
        // Add movement to velocity tracker using raw screen X and Y coordinates instead
        // of window coordinates because the window frame may be moving at the same time.
        float deltaX = event.getRawX() - event.getX();
        float deltaY = event.getRawY() - event.getY();
        event.offsetLocation(deltaX, deltaY);
        mVelocityTracker.addMovement(event);
        event.offsetLocation(-deltaX, -deltaY);
    }

    /** If the latency tracker is enabled, begins tracking expand latency. */
    public void startExpandLatencyTracking() {
        if (mLatencyTracker.isEnabled()) {
            mLatencyTracker.onActionStart(LatencyTracker.ACTION_EXPAND_PANEL);
            mExpandLatencyTracking = true;
        }
    }

    private void startOpening(MotionEvent event) {
        updatePanelExpansionAndVisibility();
        //TODO: keyguard opens QS a different way; log that too?

        // Log the position of the swipe that opened the panel
        float width = mCentralSurfaces.getDisplayWidth();
        float height = mCentralSurfaces.getDisplayHeight();
        int rot = mCentralSurfaces.getRotation();

        mLockscreenGestureLogger.writeAtFractionalPosition(MetricsEvent.ACTION_PANEL_VIEW_EXPAND,
                (int) (event.getX() / width * 100), (int) (event.getY() / height * 100), rot);
        mLockscreenGestureLogger
                .log(LockscreenUiEvent.LOCKSCREEN_UNLOCKED_NOTIFICATION_PANEL_EXPAND);
    }

    /**
     * Maybe vibrate as panel is opened.
     *
     * @param openingWithTouch Whether the panel is being opened with touch. If the panel is
     *                         instead being opened programmatically (such as by the open panel
     *                         gesture), we always play haptic.
     */
    private void maybeVibrateOnOpening(boolean openingWithTouch) {
        if (mVibrateOnOpening) {
            if (!openingWithTouch || !mHasVibratedOnOpen) {
                mVibratorHelper.vibrate(VibrationEffect.EFFECT_TICK);
                mHasVibratedOnOpen = true;
                mShadeLog.v("Vibrating on opening, mHasVibratedOnOpen=true");
            }
        }
    }

    /**
     * @return whether the swiping direction is upwards and above a 45 degree angle compared to the
     * horizontal direction
     */
    private boolean isDirectionUpwards(float x, float y) {
        float xDiff = x - mInitialExpandX;
        float yDiff = y - mInitialExpandY;
        if (yDiff >= 0) {
            return false;
        }
        return Math.abs(yDiff) >= Math.abs(xDiff);
    }

    /** Called when a MotionEvent is about to trigger Shade expansion. */
    public void startExpandMotion(float newX, float newY, boolean startTracking,
            float expandedHeight) {
        if (!mHandlingPointerUp && !mStatusBarStateController.isDozing()) {
            beginJankMonitoring();
        }
        mInitialOffsetOnTouch = expandedHeight;
        mInitialExpandY = newY;
        mInitialExpandX = newX;
        mInitialTouchFromKeyguard = mKeyguardStateController.isShowing();
        if (startTracking) {
            mTouchSlopExceeded = true;
            mShadeHeightLogger.logFunctionCall("startExpandMotion");
            setExpandedHeight(mInitialOffsetOnTouch);
            onTrackingStarted();
        }
    }

    private void endMotionEvent(MotionEvent event, float x, float y, boolean forceCancel) {
        mTrackingPointer = -1;
        mAmbientState.setSwipingUp(false);
        if ((mTracking && mTouchSlopExceeded) || Math.abs(x - mInitialExpandX) > mTouchSlop
                || Math.abs(y - mInitialExpandY) > mTouchSlop
                || (!isFullyExpanded() && !isFullyCollapsed())
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL || forceCancel) {
            mVelocityTracker.computeCurrentVelocity(1000);
            float vel = mVelocityTracker.getYVelocity();
            float vectorVel = (float) Math.hypot(
                    mVelocityTracker.getXVelocity(), mVelocityTracker.getYVelocity());

            final boolean onKeyguard = mKeyguardStateController.isShowing();
            final boolean expand;
            if (mKeyguardStateController.isKeyguardFadingAway()
                    || (mInitialTouchFromKeyguard && !onKeyguard)) {
                // Don't expand for any touches that started from the keyguard and ended after the
                // keyguard is gone.
                expand = false;
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || forceCancel) {
                if (onKeyguard) {
                    expand = true;
                } else if (mCentralSurfaces.isBouncerShowingOverDream()) {
                    expand = false;
                } else {
                    // If we get a cancel, put the shade back to the state it was in when the
                    // gesture started
                    expand = !mPanelClosedOnDown;
                }
            } else {
                expand = flingExpands(vel, vectorVel, x, y);
            }

            mDozeLog.traceFling(expand, mTouchAboveFalsingThreshold,
                    mCentralSurfaces.isWakeUpComingFromTouch());
            // Log collapse gesture if on lock screen.
            if (!expand && onKeyguard) {
                float displayDensity = mCentralSurfaces.getDisplayDensity();
                int heightDp = (int) Math.abs((y - mInitialExpandY) / displayDensity);
                int velocityDp = (int) Math.abs(vel / displayDensity);
                mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_UNLOCK, heightDp, velocityDp);
                mLockscreenGestureLogger.log(LockscreenUiEvent.LOCKSCREEN_UNLOCK);
            }
            @Classifier.InteractionType int interactionType = vel == 0 ? GENERIC
                    : y - mInitialExpandY > 0 ? QUICK_SETTINGS
                            : (mKeyguardStateController.canDismissLockScreen()
                                    ? UNLOCK : BOUNCER_UNLOCK);

            fling(vel, expand, isFalseTouch(x, y, interactionType));
            onTrackingStopped(expand);
            mUpdateFlingOnLayout = expand && mPanelClosedOnDown && !mHasLayoutedSinceDown;
            if (mUpdateFlingOnLayout) {
                mUpdateFlingVelocity = vel;
            }
        } else if (!mCentralSurfaces.isBouncerShowing()
                && !mStatusBarKeyguardViewManager.isShowingAlternateBouncer()
                && !mKeyguardStateController.isKeyguardGoingAway()) {
            onEmptySpaceClick();
            onTrackingStopped(true);
        }
        mVelocityTracker.clear();
    }

    private float getCurrentExpandVelocity() {
        mVelocityTracker.computeCurrentVelocity(1000);
        return mVelocityTracker.getYVelocity();
    }

    private void endClosing() {
        if (mClosing) {
            setClosing(false);
            onClosingFinished();
        }
    }

    /**
     * @param x the final x-coordinate when the finger was lifted
     * @param y the final y-coordinate when the finger was lifted
     * @return whether this motion should be regarded as a false touch
     */
    private boolean isFalseTouch(float x, float y,
            @Classifier.InteractionType int interactionType) {
        if (mFalsingManager.isClassifierEnabled()) {
            return mFalsingManager.isFalseTouch(interactionType);
        }
        if (!mTouchAboveFalsingThreshold) {
            return true;
        }
        if (mUpwardsWhenThresholdReached) {
            return false;
        }
        return !isDirectionUpwards(x, y);
    }

    private void fling(float vel, boolean expand, boolean expandBecauseOfFalsing) {
        fling(vel, expand, 1.0f /* collapseSpeedUpFactor */, expandBecauseOfFalsing);
    }

    private void fling(float vel, boolean expand, float collapseSpeedUpFactor,
            boolean expandBecauseOfFalsing) {
        float target = expand ? getMaxPanelHeight() : 0;
        if (!expand) {
            setClosing(true);
        }
        flingToHeight(vel, expand, target, collapseSpeedUpFactor, expandBecauseOfFalsing);
    }

    private void springBack() {
        if (mOverExpansion == 0) {
            onFlingEnd(false /* cancelled */);
            return;
        }
        mIsSpringBackAnimation = true;
        ValueAnimator animator = ValueAnimator.ofFloat(mOverExpansion, 0);
        animator.addUpdateListener(
                animation -> setOverExpansionInternal((float) animation.getAnimatedValue(),
                        false /* isFromGesture */));
        animator.setDuration(SHADE_OPEN_SPRING_BACK_DURATION);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mIsSpringBackAnimation = false;
                onFlingEnd(mCancelled);
            }
        });
        setAnimator(animator);
        animator.start();
    }

    @VisibleForTesting
    void setExpandedHeight(float height) {
        debugLog("setExpandedHeight(%.1f)", height);
        mShadeHeightLogger.logFunctionCall("setExpandedHeight");
        setExpandedHeightInternal(height);
    }

    private void updateExpandedHeightToMaxHeight() {
        float currentMaxPanelHeight = getMaxPanelHeight();

        if (isFullyCollapsed()) {
            return;
        }

        if (currentMaxPanelHeight == mExpandedHeight) {
            return;
        }

        if (mTracking && !isTrackingBlocked()) {
            return;
        }

        if (mHeightAnimator != null && !mIsSpringBackAnimation) {
            mPanelUpdateWhenAnimatorEnds = true;
            return;
        }

        mShadeHeightLogger.logFunctionCall("updateExpandedHeightToMaxHeight");
        setExpandedHeight(currentMaxPanelHeight);
    }

    private void setExpandedHeightInternal(float h) {
        mShadeHeightLogger.logSetExpandedHeightInternal(h, mSystemClock.currentTimeMillis());

        if (isNaN(h)) {
            Log.wtf(TAG, "ExpandedHeight set to NaN");
        }
        mNotificationShadeWindowController.batchApplyWindowLayoutParams(() -> {
            if (mExpandLatencyTracking && h != 0f) {
                DejankUtils.postAfterTraversal(
                        () -> mLatencyTracker.onActionEnd(LatencyTracker.ACTION_EXPAND_PANEL));
                mExpandLatencyTracking = false;
            }
            float maxPanelHeight = getMaxPanelTransitionDistance();
            if (mHeightAnimator == null) {
                // Split shade has its own overscroll logic
                if (mTracking && !mSplitShadeEnabled) {
                    float overExpansionPixels = Math.max(0, h - maxPanelHeight);
                    setOverExpansionInternal(overExpansionPixels, true /* isFromGesture */);
                }
            }
            mExpandedHeight = Math.min(h, maxPanelHeight);
            // If we are closing the panel and we are almost there due to a slow decelerating
            // interpolator, abort the animation.
            if (mExpandedHeight < 1f && mExpandedHeight != 0f && mClosing) {
                mExpandedHeight = 0f;
                if (mHeightAnimator != null) {
                    mHeightAnimator.end();
                }
            }
            mExpansionDragDownAmountPx = h;
            mExpandedFraction = Math.min(1f,
                    maxPanelHeight == 0 ? 0 : mExpandedHeight / maxPanelHeight);
            mAmbientState.setExpansionFraction(mExpandedFraction);
            onHeightUpdated(mExpandedHeight);
            updatePanelExpansionAndVisibility();
        });
    }

    /**
     * Set the current overexpansion
     *
     * @param overExpansion the amount of overexpansion to apply
     * @param isFromGesture is this amount from a gesture and needs to be rubberBanded?
     */
    private void setOverExpansionInternal(float overExpansion, boolean isFromGesture) {
        if (!isFromGesture) {
            mLastGesturedOverExpansion = -1;
            setOverExpansion(overExpansion);
        } else if (mLastGesturedOverExpansion != overExpansion) {
            mLastGesturedOverExpansion = overExpansion;
            final float heightForFullOvershoot = mView.getHeight() / 3.0f;
            float newExpansion = MathUtils.saturate(overExpansion / heightForFullOvershoot);
            newExpansion = Interpolators.getOvershootInterpolation(newExpansion);
            setOverExpansion(newExpansion * mPanelFlingOvershootAmount * 2.0f);
        }
    }

    /** Sets the expanded height relative to a number from 0 to 1. */
    public void setExpandedFraction(float frac) {
        final int maxDist = getMaxPanelTransitionDistance();
        mShadeHeightLogger.logFunctionCall("setExpandedFraction");
        setExpandedHeight(maxDist * frac);
    }

    float getExpandedHeight() {
        return mExpandedHeight;
    }

    private float getExpandedFraction() {
        return mExpandedFraction;
    }

    /**
     * This method should not be used anymore, you should probably use {@link #isShadeFullyOpen()}
     * instead. It was overused as indicating if shade is open or we're on keyguard/AOD.
     * Moving forward we should be explicit about the what state we're checking.
     * @return if panel is covering the screen, which means we're in expanded shade or keyguard/AOD
     *
     * @deprecated depends on the state you check, use {@link #isShadeFullyOpen()},
     * {@link #isOnAod()}, {@link #isOnKeyguard()} instead.
     */
    @Deprecated
    public boolean isFullyExpanded() {
        return mExpandedHeight >= getMaxPanelTransitionDistance();
    }

    /**
     * Returns true if shade is fully opened, that is we're actually in the notification shade
     * with QQS or QS. It's different from {@link #isFullyExpanded()} that it will not report
     * shade as always expanded if we're on keyguard/AOD. It will return true only when user goes
     * from keyguard to shade.
     */
    public boolean isShadeFullyOpen() {
        if (mBarState == SHADE) {
            return isFullyExpanded();
        } else if (mBarState == SHADE_LOCKED) {
            return true;
        } else {
            // case of two finger swipe from the top of keyguard
            return computeQsExpansionFraction() == 1;
        }
    }

    public boolean isFullyCollapsed() {
        return mExpandedFraction <= 0.0f;
    }

    public boolean isCollapsing() {
        return mClosing || mIsLaunchAnimationRunning;
    }

    public boolean isTracking() {
        return mTracking;
    }

    /** Returns whether the shade can be collapsed. */
    public boolean canPanelBeCollapsed() {
        return !isFullyCollapsed() && !mTracking && !mClosing;
    }

    /** Collapses the shade instantly without animation. */
    public void instantCollapse() {
        abortAnimations();
        mShadeHeightLogger.logFunctionCall("instantCollapse");
        setExpandedFraction(0f);
        if (mExpanding) {
            notifyExpandingFinished();
        }
        if (mInstantExpanding) {
            mInstantExpanding = false;
            updatePanelExpansionAndVisibility();
        }
    }

    private void abortAnimations() {
        cancelHeightAnimator();
        mView.removeCallbacks(mFlingCollapseRunnable);
    }

    public boolean isUnlockHintRunning() {
        return mHintAnimationRunning;
    }

    /**
     * Phase 1: Move everything upwards.
     */
    private void startUnlockHintAnimationPhase1(final Runnable onAnimationFinished) {
        float target = Math.max(0, getMaxPanelHeight() - mHintDistance);
        ValueAnimator animator = createHeightAnimator(target);
        animator.setDuration(250);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCancelled) {
                    setAnimator(null);
                    onAnimationFinished.run();
                } else {
                    startUnlockHintAnimationPhase2(onAnimationFinished);
                }
            }
        });
        animator.start();
        setAnimator(animator);

        final List<ViewPropertyAnimator> indicationAnimators =
                mKeyguardBottomArea.getIndicationAreaAnimators();
        for (final ViewPropertyAnimator indicationAreaAnimator : indicationAnimators) {
            indicationAreaAnimator
                    .translationY(-mHintDistance)
                    .setDuration(250)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .withEndAction(() -> indicationAreaAnimator
                            .translationY(0)
                            .setDuration(450)
                            .setInterpolator(mBounceInterpolator)
                            .start())
                    .start();
        }
    }

    private void setAnimator(ValueAnimator animator) {
        mHeightAnimator = animator;
        if (animator == null && mPanelUpdateWhenAnimatorEnds) {
            mPanelUpdateWhenAnimatorEnds = false;
            updateExpandedHeightToMaxHeight();
        }
    }

    /**
     * Phase 2: Bounce down.
     */
    private void startUnlockHintAnimationPhase2(final Runnable onAnimationFinished) {
        ValueAnimator animator = createHeightAnimator(getMaxPanelHeight());
        animator.setDuration(450);
        animator.setInterpolator(mBounceInterpolator);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setAnimator(null);
                onAnimationFinished.run();
                updatePanelExpansionAndVisibility();
            }
        });
        animator.start();
        setAnimator(animator);
    }

    private ValueAnimator createHeightAnimator(float targetHeight) {
        return createHeightAnimator(targetHeight, 0.0f /* performOvershoot */);
    }

    /**
     * Create an animator that can also overshoot
     *
     * @param targetHeight    the target height
     * @param overshootAmount the amount of overshoot desired
     */
    private ValueAnimator createHeightAnimator(float targetHeight, float overshootAmount) {
        float startExpansion = mOverExpansion;
        ValueAnimator animator = ValueAnimator.ofFloat(mExpandedHeight, targetHeight);
        animator.addUpdateListener(
                animation -> {
                    if (overshootAmount > 0.0f
                            // Also remove the overExpansion when collapsing
                            || (targetHeight == 0.0f && startExpansion != 0)) {
                        final float expansion = MathUtils.lerp(
                                startExpansion,
                                mPanelFlingOvershootAmount * overshootAmount,
                                Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                                        animator.getAnimatedFraction()));
                        setOverExpansionInternal(expansion, false /* isFromGesture */);
                    }
                    mShadeHeightLogger.logFunctionCall("height animator update");
                    setExpandedHeightInternal((float) animation.getAnimatedValue());
                });
        return animator;
    }

    /** Update the visibility of {@link NotificationPanelView} if necessary. */
    private void updateVisibility() {
        mView.setVisibility(shouldPanelBeVisible() ? VISIBLE : INVISIBLE);
    }

    /**
     * Updates the panel expansion and {@link NotificationPanelView} visibility if necessary.
     *
     * TODO(b/200063118): Could public calls to this method be replaced with calls to
     *   {@link #updateVisibility()}? That would allow us to make this method private.
     */
    public void updatePanelExpansionAndVisibility() {
        mShadeExpansionStateManager.onPanelExpansionChanged(
                mExpandedFraction, isExpanded(), mTracking, mExpansionDragDownAmountPx);
        updateVisibility();
    }

    public boolean isExpanded() {
        return mExpandedFraction > 0f
                || mInstantExpanding
                || isPanelVisibleBecauseOfHeadsUp()
                || mTracking
                || mHeightAnimator != null
                && !mIsSpringBackAnimation;
    }

    /** Called when the user performs a click anywhere in the empty area of the panel. */
    private void onEmptySpaceClick() {
        if (!mHintAnimationRunning)  {
            onMiddleClicked();
        }
    }

    @VisibleForTesting
    boolean isClosing() {
        return mClosing;
    }

    /** Collapses the shade with an animation duration in milliseconds. */
    public void collapseWithDuration(int animationDuration) {
        mFixedDuration = animationDuration;
        collapse(false /* delayed */, 1.0f /* speedUpFactor */);
        mFixedDuration = NO_FIXED_DURATION;
    }

    /** Returns the NotificationPanelView. */
    public ViewGroup getView() {
        // TODO(b/254878364): remove this method, or at least reduce references to it.
        return mView;
    }

    /** */
    public boolean postToView(Runnable action) {
        return mView.post(action);
    }

    /** */
    public boolean sendInterceptTouchEventToView(MotionEvent event) {
        return mView.onInterceptTouchEvent(event);
    }

    /** */
    public boolean sendTouchEventToView(MotionEvent event) {
        return mView.dispatchTouchEvent(event);
    }

    /** */
    public void requestLayoutOnView() {
        mView.requestLayout();
    }

    /** */
    public void resetViewAlphas() {
        ViewGroupFadeHelper.reset(mView);
    }

    /** */
    public boolean isViewEnabled() {
        return mView.isEnabled();
    }

    private void beginJankMonitoring() {
        if (mInteractionJankMonitor == null) {
            return;
        }
        InteractionJankMonitor.Configuration.Builder builder =
                InteractionJankMonitor.Configuration.Builder.withView(
                                InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE,
                                mView)
                        .setTag(isFullyCollapsed() ? "Expand" : "Collapse");
        mInteractionJankMonitor.begin(builder);
    }

    private void endJankMonitoring() {
        if (mInteractionJankMonitor == null) {
            return;
        }
        InteractionJankMonitor.getInstance().end(
                InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
    }

    private void cancelJankMonitoring() {
        if (mInteractionJankMonitor == null) {
            return;
        }
        InteractionJankMonitor.getInstance().cancel(
                InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
    }

    private ShadeExpansionStateManager getShadeExpansionStateManager() {
        return mShadeExpansionStateManager;
    }

    private final class NsslHeightChangedListener implements
            ExpandableView.OnHeightChangedListener {
        @Override
        public void onHeightChanged(ExpandableView view, boolean needsAnimation) {
            // Block update if we are in QS and just the top padding changed (i.e. view == null).
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
            if (mKeyguardShowing) {
                updateMaxDisplayedNotifications(true);
            }
            updateExpandedHeightToMaxHeight();
        }

        @Override
        public void onReset(ExpandableView view) {}
    }

    private void collapseOrExpand() {
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

    private final class NsslOverscrollTopChangedListener implements
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
            setQsExpansionHeight(mQsMinExpansionHeight + rounded);
        }

        @Override
        public void flingTopOverscroll(float velocity, boolean open) {
            // in split shade mode we want to expand/collapse QS only when touch happens within QS
            if (isSplitShadeAndTouchXOutsideQs(mInitialTouchX)) {
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
            setQsExpansionHeight(mQsExpansionHeight);
            boolean canExpand = isQsExpansionEnabled();
            flingSettings(!canExpand && open ? 0f : velocity,
                    open && canExpand ? FLING_EXPAND : FLING_COLLAPSE, () -> {
                        setOverScrolling(false);
                        updateQsState();
                    }, false /* isClick */);
        }
    }

    private void onDynamicPrivacyChanged() {
        // Do not request animation when pulsing or waking up, otherwise the clock will be out
        // of sync with the notification panel.
        if (mLinearDarkAmount != 0) {
            return;
        }
        mAnimateNextPositionUpdate = true;
    }

    private final class ShadeHeadsUpChangedListener implements OnHeadsUpChangedListener {
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

    private void onQsHeightChanged() {
        mQsMaxExpansionHeight = mQs != null ? mQs.getDesiredHeight() : 0;
        if (mQsExpanded && mQsFullyExpanded) {
            mQsExpansionHeight = mQsMaxExpansionHeight;
            requestScrollerTopPaddingUpdate(false /* animate */);
            updateExpandedHeightToMaxHeight();
        }
        if (mAccessibilityManager.isEnabled()) {
            mView.setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
        }
        mNotificationStackScrollLayoutController.setMaxTopPadding(mQsMaxExpansionHeight);
    }

    private final class ConfigurationListener implements
            ConfigurationController.ConfigurationListener {
        @Override
        public void onThemeChanged() {
            debugLog("onThemeChanged");
            reInflateViews();
        }

        @Override
        public void onSmallestScreenWidthChanged() {
            Trace.beginSection("onSmallestScreenWidthChanged");
            debugLog("onSmallestScreenWidthChanged");

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
            debugLog("onDensityOrFontScaleChanged");
            reInflateViews();
        }
    }

    private final class SettingsChangeObserver extends ContentObserver {
        SettingsChangeObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            debugLog("onSettingsChanged");

            // Can affect multi-user switcher visibility
            reInflateViews();
        }
    }

    private final class StatusBarStateListener implements StateListener {
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

            boolean fromShadeToKeyguard = statusBarState == KEYGUARD
                    && (oldState == SHADE || oldState == SHADE_LOCKED);
            if (mSplitShadeEnabled && fromShadeToKeyguard) {
                // user can go to keyguard from different shade states and closing animation
                // may not fully run - we always want to make sure we close QS when that happens
                // as we never need QS open in fresh keyguard state
                closeQs();
            }

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
            } else {
                // this else branch means we are doing one of:
                //  - from KEYGUARD to SHADE (but not fully expanded as when swiping from the top)
                //  - from SHADE to KEYGUARD
                //  - from SHADE_LOCKED to SHADE
                //  - getting notified again about the current SHADE or KEYGUARD state
                final boolean animatingUnlockedShadeToKeyguard = oldState == SHADE
                        && statusBarState == KEYGUARD
                        && mScreenOffAnimationController.isKeyguardShowDelayed();
                if (!animatingUnlockedShadeToKeyguard) {
                    // Only make the status bar visible if we're not animating the screen off, since
                    // we only want to be showing the clock/notifications during the animation.
                    if (keyguardShowing) {
                        mShadeLog.v("Updating keyguard status bar state to visible");
                    } else {
                        mShadeLog.v("Updating keyguard status bar state to invisible");
                    }
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
                    return NotificationPanelViewController.this.getLockscreenShadeDragProgress();
                }
            };

    /**
     * Reconfigures the shade to show the AOD UI (clock, smartspace, etc). This is called by the
     * screen off animation controller in order to animate in AOD without "actually" fully switching
     * to the KEYGUARD state, which is a heavy transition that causes jank as 10+ files react to the
     * change.
     */
    public void showAodUi() {
        setDozing(true /* dozing */, false /* animate */);
        mStatusBarStateController.setUpcomingState(KEYGUARD);
        mStatusBarStateListener.onStateChanged(KEYGUARD);
        mStatusBarStateListener.onDozeAmountChanged(1f, 1f);
        mShadeHeightLogger.logFunctionCall("showAodUi");
        setExpandedFraction(1f);
    }

    /** Sets the overstretch amount in raw pixels when dragging down. */
    public void setOverStretchAmount(float amount) {
        float progress = amount / mView.getHeight();
        float overStretch = Interpolators.getOvershootInterpolation(progress);
        mOverStretchAmount = overStretch * mMaxOverscrollAmountForPulse;
        positionClockAndNotifications(true /* forceUpdate */);
    }

    private final class ShadeAttachStateChangeListener implements View.OnAttachStateChangeListener {
        @Override
        public void onViewAttachedToWindow(View v) {
            mFragmentService.getFragmentHostManager(mView)
                    .addTagListener(QS.TAG, mQsFragmentListener);
            mStatusBarStateController.addCallback(mStatusBarStateListener);
            mStatusBarStateListener.onStateChanged(mStatusBarStateController.getState());
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
            mContentResolver.unregisterContentObserver(mSettingsChangeObserver);
            mFragmentService.getFragmentHostManager(mView)
                    .removeTagListener(QS.TAG, mQsFragmentListener);
            mStatusBarStateController.removeCallback(mStatusBarStateListener);
            mConfigurationController.removeCallback(mConfigurationListener);
            mFalsingManager.removeTapListener(mFalsingTapListener);
        }
    }

    private final class ShadeLayoutChangeListener implements View.OnLayoutChangeListener {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                int oldTop, int oldRight, int oldBottom) {
            DejankUtils.startDetectingBlockingIpcs("NVP#onLayout");
            updateExpandedHeightToMaxHeight();
            mHasLayoutedSinceDown = true;
            if (mUpdateFlingOnLayout) {
                abortAnimations();
                fling(mUpdateFlingVelocity);
                mUpdateFlingOnLayout = false;
            }
            updateMaxDisplayedNotifications(!shouldAvoidChangingNotificationsCount());
            setIsFullWidth(mNotificationStackScrollLayoutController.getWidth() == mView.getWidth());

            // Update Clock Pivot (used by anti-burnin transformations)
            mKeyguardStatusViewController.updatePivot(mView.getWidth(), mView.getHeight());

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
                updateExpandedHeightToMaxHeight();

                // Size has changed, start an animation.
                if (mQsMaxExpansionHeight != oldMaxHeight) {
                    startQsSizeChangeAnimation(oldMaxHeight, mQsMaxExpansionHeight);
                }
            } else if (!mQsExpanded && mQsExpansionAnimator == null) {
                setQsExpansionHeight(mQsMinExpansionHeight + mLastOverscroll);
            } else {
                mShadeLog.v("onLayoutChange: qs expansion not set");
            }
            updateExpandedHeight(getExpandedHeight());
            updateHeader();

            // If we are running a size change animation, the animation takes care of the height
            // of the container. However, if we are not animating, we always need to make the QS
            // container the desired height so when closing the QS detail, it stays smaller after
            // the size change animation is finished but the detail view is still being animated
            // away (this animation takes longer than the size change animation).
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

    @NonNull
    private WindowInsets onApplyShadeWindowInsets(WindowInsets insets) {
        // the same types of insets that are handled in NotificationShadeWindowView
        int insetTypes = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
        Insets combinedInsets = insets.getInsetsIgnoringVisibility(insetTypes);
        mDisplayTopInset = combinedInsets.top;
        mDisplayRightInset = combinedInsets.right;

        mNavigationBarBottomHeight = insets.getStableInsetBottom();
        updateMaxHeadsUpTranslation();
        return insets;
    }

    /** Removes any pending runnables that would collapse the panel. */
    public void cancelPendingPanelCollapse() {
        mView.removeCallbacks(mMaybeHideExpandedRunnable);
    }

    private void onPanelStateChanged(@PanelState int state) {
        updateQSExpansionEnabledAmbient();

        if (state == STATE_OPEN && mCurrentPanelState != state) {
            setQsExpandImmediate(false);
            mView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
        if (state == STATE_OPENING) {
            // we need to ignore it on keyguard as this is a false alarm - transition from unlocked
            // to locked will trigger this event and we're not actually in the process of opening
            // the shade, lockscreen is just always expanded
            if (mSplitShadeEnabled && !isOnKeyguard()) {
                setQsExpandImmediate(true);
            }
            mOpenCloseListener.onOpenStarted();
        }
        if (state == STATE_CLOSED) {
            setQsExpandImmediate(false);
            // Close the status bar in the next frame so we can show the end of the
            // animation.
            mView.post(mMaybeHideExpandedRunnable);
        }
        mCurrentPanelState = state;
    }

    private Consumer<Float> toLockscreenTransitionAlpha(
            NotificationStackScrollLayoutController stackScroller) {
        return (Float alpha) -> {
            mKeyguardStatusViewController.setAlpha(alpha);
            stackScroller.setAlpha(alpha);

            mKeyguardBottomAreaInteractor.setAlpha(alpha);
            mLockIconViewController.setAlpha(alpha);

            if (mKeyguardQsUserSwitchController != null) {
                mKeyguardQsUserSwitchController.setAlpha(alpha);
            }
            if (mKeyguardUserSwitcherController != null) {
                mKeyguardUserSwitcherController.setAlpha(alpha);
            }
        };
    }

    private Consumer<Float> toLockscreenTransitionY(
                NotificationStackScrollLayoutController stackScroller) {
        return (Float translationY) -> {
            mKeyguardStatusViewController.setTranslationY(translationY,  /* excludeMedia= */false);
            stackScroller.setTranslationY(translationY);
        };
    }

    @VisibleForTesting
    StatusBarStateController getStatusBarStateController() {
        return mStatusBarStateController;
    }

    @VisibleForTesting
    StateListener getStatusBarStateListener() {
        return mStatusBarStateListener;
    }

    @VisibleForTesting
    boolean isHintAnimationRunning() {
        return mHintAnimationRunning;
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

    /** Handles MotionEvents for the Shade. */
    public final class TouchHandler implements View.OnTouchListener {
        private long mLastTouchDownTime = -1L;

        /** @see ViewGroup#onInterceptTouchEvent(MotionEvent) */
        public boolean onInterceptTouchEvent(MotionEvent event) {
            mShadeLog.logMotionEvent(event, "NPVC onInterceptTouchEvent");
            if (mQs.disallowPanelTouches()) {
                mShadeLog.logMotionEvent(event,
                        "NPVC not intercepting touch, panel touches disallowed");
                return false;
            }
            initDownStates(event);
            // Do not let touches go to shade or QS if the bouncer is visible,
            // but still let user swipe down to expand the panel, dismissing the bouncer.
            if (mCentralSurfaces.isBouncerShowing()) {
                mShadeLog.v("NotificationPanelViewController MotionEvent intercepted: "
                        + "bouncer is showing");
                return true;
            }
            if (mCommandQueue.panelsEnabled()
                    && !mNotificationStackScrollLayoutController.isLongPressInProgress()
                    && mHeadsUpTouchHelper.onInterceptTouchEvent(event)) {
                mMetricsLogger.count(COUNTER_PANEL_OPEN, 1);
                mMetricsLogger.count(COUNTER_PANEL_OPEN_PEEK, 1);
                mShadeLog.v("NotificationPanelViewController MotionEvent intercepted: "
                        + "HeadsUpTouchHelper");
                return true;
            }
            if (!shouldQuickSettingsIntercept(mDownX, mDownY, 0)
                    && mPulseExpansionHandler.onInterceptTouchEvent(event)) {
                mShadeLog.v("NotificationPanelViewController MotionEvent intercepted: "
                        + "PulseExpansionHandler");
                return true;
            }

            if (!isFullyCollapsed() && onQsIntercept(event)) {
                debugLog("onQsIntercept true");
                mShadeLog.v("NotificationPanelViewController MotionEvent intercepted: "
                        + "QsIntercept");
                return true;
            }

            if (mInstantExpanding || !mNotificationsDragEnabled || mTouchDisabled) {
                mShadeLog.logNotInterceptingTouchInstantExpanding(mInstantExpanding,
                        !mNotificationsDragEnabled, mTouchDisabled);
                return false;
            }
            if (mMotionAborted && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                mShadeLog.logMotionEventStatusBarState(event, mStatusBarStateController.getState(),
                        "NPVC MotionEvent not intercepted: non-down action, motion was aborted");
                return false;
            }

            /* If the user drags anywhere inside the panel we intercept it if the movement is
             upwards. This allows closing the shade from anywhere inside the panel.
             We only do this if the current content is scrolled to the bottom, i.e.
             canCollapsePanelOnTouch() is true and therefore there is no conflicting scrolling
             gesture possible. */
            int pointerIndex = event.findPointerIndex(mTrackingPointer);
            if (pointerIndex < 0) {
                pointerIndex = 0;
                mTrackingPointer = event.getPointerId(pointerIndex);
            }
            final float x = event.getX(pointerIndex);
            final float y = event.getY(pointerIndex);
            boolean canCollapsePanel = canCollapsePanelOnTouch();

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mCentralSurfaces.userActivity();
                    mAnimatingOnDown = mHeightAnimator != null && !mIsSpringBackAnimation;
                    mMinExpandHeight = 0.0f;
                    mDownTime = mSystemClock.uptimeMillis();
                    if (mAnimatingOnDown && mClosing && !mHintAnimationRunning) {
                        cancelHeightAnimator();
                        mTouchSlopExceeded = true;
                        mShadeLog.v("NotificationPanelViewController MotionEvent intercepted:"
                                + " mAnimatingOnDown: true, mClosing: true, mHintAnimationRunning:"
                                + " false");
                        return true;
                    }
                    mInitialExpandY = y;
                    mInitialExpandX = x;
                    mTouchStartedInEmptyArea = !isInContentBounds(x, y);
                    mTouchSlopExceeded = mTouchSlopExceededBeforeDown;
                    mMotionAborted = false;
                    mPanelClosedOnDown = isFullyCollapsed();
                    mCollapsedAndHeadsUpOnDown = false;
                    mHasLayoutedSinceDown = false;
                    mUpdateFlingOnLayout = false;
                    mTouchAboveFalsingThreshold = false;
                    addMovement(event);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    final int upPointer = event.getPointerId(event.getActionIndex());
                    if (mTrackingPointer == upPointer) {
                        // gesture is ongoing, find a new pointer to track
                        final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                        mTrackingPointer = event.getPointerId(newIndex);
                        mInitialExpandX = event.getX(newIndex);
                        mInitialExpandY = event.getY(newIndex);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    mShadeLog.logMotionEventStatusBarState(event,
                            mStatusBarStateController.getState(),
                            "onInterceptTouchEvent: pointer down action");
                    if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                        mMotionAborted = true;
                        mVelocityTracker.clear();
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    final float h = y - mInitialExpandY;
                    addMovement(event);
                    final boolean openShadeWithoutHun =
                            mPanelClosedOnDown && !mCollapsedAndHeadsUpOnDown;
                    if (canCollapsePanel || mTouchStartedInEmptyArea || mAnimatingOnDown
                            || openShadeWithoutHun) {
                        float hAbs = Math.abs(h);
                        float touchSlop = getTouchSlop(event);
                        if ((h < -touchSlop
                                || ((openShadeWithoutHun || mAnimatingOnDown) && hAbs > touchSlop))
                                && hAbs > Math.abs(x - mInitialExpandX)) {
                            cancelHeightAnimator();
                            startExpandMotion(x, y, true /* startTracking */, mExpandedHeight);
                            mShadeLog.v("NotificationPanelViewController MotionEvent"
                                    + " intercepted: startExpandMotion");
                            return true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    mVelocityTracker.clear();
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (event.getDownTime() == mLastTouchDownTime) {
                    // An issue can occur when swiping down after unlock, where multiple down
                    // events are received in this handler with identical downTimes. Until the
                    // source of the issue can be located, detect this case and ignore.
                    // see b/193350347
                    mShadeLog.logMotionEvent(event,
                            "onTouch: duplicate down event detected... ignoring");
                    return true;
                }
                mLastTouchDownTime = event.getDownTime();
            }


            if (mQsFullyExpanded && mQs != null && mQs.disallowPanelTouches()) {
                mShadeLog.logMotionEvent(event,
                        "onTouch: ignore touch, panel touches disallowed and qs fully expanded");
                return false;
            }

            // Do not allow panel expansion if bouncer is scrimmed or showing over a dream,
            // otherwise user would be able to pull down QS or expand the shade.
            if (mCentralSurfaces.isBouncerShowingScrimmed()
                    || mCentralSurfaces.isBouncerShowingOverDream()) {
                mShadeLog.logMotionEvent(event,
                        "onTouch: ignore touch, bouncer scrimmed or showing over dream");
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
                mShadeLog.logMotionEvent(event, "onTouch: PulseExpansionHandler handled event");
                return true;
            }
            if (mPulsing) {
                mShadeLog.logMotionEvent(event, "onTouch: eat touch, device pulsing");
                return true;
            }
            if (mListenForHeadsUp && !mHeadsUpTouchHelper.isTrackingHeadsUp()
                    && !mNotificationStackScrollLayoutController.isLongPressInProgress()
                    && mHeadsUpTouchHelper.onInterceptTouchEvent(event)) {
                mMetricsLogger.count(COUNTER_PANEL_OPEN_PEEK, 1);
            }
            boolean handled = mHeadsUpTouchHelper.onTouchEvent(event);

            if (!mHeadsUpTouchHelper.isTrackingHeadsUp() && handleQsTouch(event)) {
                mShadeLog.logMotionEvent(event, "onTouch: handleQsTouch handled event");
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isFullyCollapsed()) {
                mMetricsLogger.count(COUNTER_PANEL_OPEN, 1);
                handled = true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isFullyExpanded()
                    && mKeyguardStateController.isShowing()) {
                mStatusBarKeyguardViewManager.updateKeyguardPosition(event.getX());
            }

            handled |= handleTouch(event);
            return !mDozing || handled;
        }

        private boolean handleTouch(MotionEvent event) {
            if (mInstantExpanding) {
                mShadeLog.logMotionEvent(event,
                        "handleTouch: touch ignored due to instant expanding");
                return false;
            }
            if (mTouchDisabled && event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                mShadeLog.logMotionEvent(event, "handleTouch: non-cancel action, touch disabled");
                return false;
            }
            if (mMotionAborted && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                mShadeLog.logMotionEventStatusBarState(event, mStatusBarStateController.getState(),
                        "handleTouch: non-down action, motion was aborted");
                return false;
            }

            // If dragging should not expand the notifications shade, then return false.
            if (!mNotificationsDragEnabled) {
                if (mTracking) {
                    // Turn off tracking if it's on or the shade can get stuck in the down position.
                    onTrackingStopped(true /* expand */);
                }
                mShadeLog.logMotionEvent(event, "handleTouch: drag not enabled");
                return false;
            }

            // On expanding, single mouse click expands the panel instead of dragging.
            if (isFullyCollapsed() && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    expand(true);
                }
                return true;
            }

            /*
             * We capture touch events here and update the expand height here in case according to
             * the users fingers. This also handles multi-touch.
             *
             * Flinging is also enabled in order to open or close the shade.
             */
            int pointerIndex = event.findPointerIndex(mTrackingPointer);
            if (pointerIndex < 0) {
                pointerIndex = 0;
                mTrackingPointer = event.getPointerId(pointerIndex);
            }
            final float x = event.getX(pointerIndex);
            final float y = event.getY(pointerIndex);

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mGestureWaitForTouchSlop = shouldGestureWaitForTouchSlop();
                mIgnoreXTouchSlop = true;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mShadeLog.logMotionEvent(event, "onTouch: down action");
                    startExpandMotion(x, y, false /* startTracking */, mExpandedHeight);
                    mMinExpandHeight = 0.0f;
                    mPanelClosedOnDown = isFullyCollapsed();
                    mHasLayoutedSinceDown = false;
                    mUpdateFlingOnLayout = false;
                    mMotionAborted = false;
                    mDownTime = mSystemClock.uptimeMillis();
                    mTouchAboveFalsingThreshold = false;
                    mCollapsedAndHeadsUpOnDown =
                            isFullyCollapsed() && mHeadsUpManager.hasPinnedHeadsUp();
                    addMovement(event);
                    boolean regularHeightAnimationRunning = mHeightAnimator != null
                            && !mHintAnimationRunning && !mIsSpringBackAnimation;
                    if (!mGestureWaitForTouchSlop || regularHeightAnimationRunning) {
                        mTouchSlopExceeded = regularHeightAnimationRunning
                                || mTouchSlopExceededBeforeDown;
                        cancelHeightAnimator();
                        onTrackingStarted();
                    }
                    if (isFullyCollapsed() && !mHeadsUpManager.hasPinnedHeadsUp()
                            && !mCentralSurfaces.isBouncerShowing()) {
                        startOpening(event);
                    }
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    final int upPointer = event.getPointerId(event.getActionIndex());
                    if (mTrackingPointer == upPointer) {
                        // gesture is ongoing, find a new pointer to track
                        final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                        final float newY = event.getY(newIndex);
                        final float newX = event.getX(newIndex);
                        mTrackingPointer = event.getPointerId(newIndex);
                        mHandlingPointerUp = true;
                        startExpandMotion(newX, newY, true /* startTracking */, mExpandedHeight);
                        mHandlingPointerUp = false;
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    mShadeLog.logMotionEventStatusBarState(event,
                            mStatusBarStateController.getState(),
                            "handleTouch: pointer down action");
                    if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                        mMotionAborted = true;
                        endMotionEvent(event, x, y, true /* forceCancel */);
                        return false;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isFullyCollapsed()) {
                        // If panel is fully collapsed, reset haptic effect before adding movement.
                        mHasVibratedOnOpen = false;
                        mShadeLog.logHasVibrated(mHasVibratedOnOpen, mExpandedFraction);
                    }
                    addMovement(event);
                    if (!isFullyCollapsed()) {
                        maybeVibrateOnOpening(true /* openingWithTouch */);
                    }
                    float h = y - mInitialExpandY;

                    // If the panel was collapsed when touching, we only need to check for the
                    // y-component of the gesture, as we have no conflicting horizontal gesture.
                    if (Math.abs(h) > getTouchSlop(event)
                            && (Math.abs(h) > Math.abs(x - mInitialExpandX)
                            || mIgnoreXTouchSlop)) {
                        mTouchSlopExceeded = true;
                        if (mGestureWaitForTouchSlop && !mTracking && !mCollapsedAndHeadsUpOnDown) {
                            if (mInitialOffsetOnTouch != 0f) {
                                startExpandMotion(x, y, false /* startTracking */, mExpandedHeight);
                                h = 0;
                            }
                            cancelHeightAnimator();
                            onTrackingStarted();
                        }
                    }
                    float newHeight = Math.max(0, h + mInitialOffsetOnTouch);
                    newHeight = Math.max(newHeight, mMinExpandHeight);
                    if (-h >= getFalsingThreshold()) {
                        mTouchAboveFalsingThreshold = true;
                        mUpwardsWhenThresholdReached = isDirectionUpwards(x, y);
                    }
                    if ((!mGestureWaitForTouchSlop || mTracking) && !isTrackingBlocked()) {
                        // Count h==0 as part of swipe-up,
                        // otherwise {@link NotificationStackScrollLayout}
                        // wrongly enables stack height updates at the start of lockscreen swipe-up
                        mAmbientState.setSwipingUp(h <= 0);
                        mShadeHeightLogger.logFunctionCall("ACTION_MOVE");
                        setExpandedHeightInternal(newHeight);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mShadeLog.logMotionEvent(event, "onTouch: up/cancel action");
                    addMovement(event);
                    endMotionEvent(event, x, y, false /* forceCancel */);
                    // mHeightAnimator is null, there is no remaining frame, ends instrumenting.
                    if (mHeightAnimator == null) {
                        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            endJankMonitoring();
                        } else {
                            cancelJankMonitoring();
                        }
                    }
                    break;
            }
            return !mGestureWaitForTouchSlop || mTracking;
        }
    }

    static class SplitShadeTransitionAdapter extends Transition {
        private static final String PROP_BOUNDS = "splitShadeTransitionAdapter:bounds";
        private static final String[] TRANSITION_PROPERTIES = { PROP_BOUNDS };

        private final KeyguardStatusViewController mController;

        SplitShadeTransitionAdapter(KeyguardStatusViewController controller) {
            mController = controller;
        }

        private void captureValues(TransitionValues transitionValues) {
            Rect boundsRect = new Rect();
            boundsRect.left = transitionValues.view.getLeft();
            boundsRect.top = transitionValues.view.getTop();
            boundsRect.right = transitionValues.view.getRight();
            boundsRect.bottom = transitionValues.view.getBottom();
            transitionValues.values.put(PROP_BOUNDS, boundsRect);
        }

        @Override
        public void captureEndValues(TransitionValues transitionValues) {
            captureValues(transitionValues);
        }

        @Override
        public void captureStartValues(TransitionValues transitionValues) {
            captureValues(transitionValues);
        }

        @Override
        public Animator createAnimator(ViewGroup sceneRoot, TransitionValues startValues,
                TransitionValues endValues) {
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);

            Rect from = (Rect) startValues.values.get(PROP_BOUNDS);
            Rect to = (Rect) endValues.values.get(PROP_BOUNDS);

            anim.addUpdateListener(
                    animation -> mController.getClockAnimations().onPositionUpdated(
                            from, to, animation.getAnimatedFraction()));

            return anim;
        }

        @Override
        public String[] getTransitionProperties() {
            return TRANSITION_PROPERTIES;
        }
    }

    private final class ShadeAccessibilityDelegate extends AccessibilityDelegate {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host,
                AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action
                    == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId()
                    || action
                    == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()) {
                mStatusBarKeyguardViewManager.showPrimaryBouncer(true);
                return true;
            }
            return super.performAccessibilityAction(host, action, args);
        }
    }

    /** Listens for when touch tracking begins. */
    interface TrackingStartedListener {
        void onTrackingStarted();
    }

    /** Listens for when shade begins opening of finishes closing. */
    interface OpenCloseListener {
        /** Called when the shade finishes closing. */
        void onClosingFinished();
        /** Called when the shade starts opening. */
        void onOpenStarted();
    }
}
