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

import static android.view.View.GONE;

import static com.android.systemui.statusbar.notification.ActivityLaunchAnimator.ExpandAnimationParameters;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_ALL;

import static java.lang.Float.isNaN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.Fragment;
import android.app.StatusBarManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.MathUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardClockSwitch;
import com.android.keyguard.KeyguardStatusView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.DejankUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.ActivityLaunchAnimator;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.ConversationNotificationManager;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.ViewGroupFadeHelper;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger.LockscreenUiEvent;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcher;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.InjectionInflationController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.inject.Inject;

@StatusBarComponent.StatusBarScope
public class NotificationPanelViewController extends PanelViewController {

    private static final boolean DEBUG = false;

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
    private final DozeParameters mDozeParameters;
    private final OnHeightChangedListener mOnHeightChangedListener = new OnHeightChangedListener();
    private final OnClickListener mOnClickListener = new OnClickListener();
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
    private final ZenModeControllerCallback
            mZenModeControllerCallback =
            new ZenModeControllerCallback();
    private final ConfigurationListener mConfigurationListener = new ConfigurationListener();
    private final StatusBarStateListener mStatusBarStateListener = new StatusBarStateListener();
    private final ExpansionCallback mExpansionCallback = new ExpansionCallback();
    private final BiometricUnlockController mBiometricUnlockController;
    private final NotificationPanelView mView;
    private final MetricsLogger mMetricsLogger;
    private final ActivityManager mActivityManager;
    private final ZenModeController mZenModeController;
    private final ConfigurationController mConfigurationController;
    private final FlingAnimationUtils.Builder mFlingAnimationUtilsBuilder;

    // Cap and total height of Roboto font. Needs to be adjusted when font for the big clock is
    // changed.
    private static final int CAP_HEIGHT = 1456;
    private static final int FONT_HEIGHT = 2163;

    /**
     * Maximum time before which we will expand the panel even for slow motions when getting a
     * touch passed over from launcher.
     */
    private static final int MAX_TIME_TO_OPEN_WHEN_FLINGING_FROM_LAUNCHER = 300;

    private static final String COUNTER_PANEL_OPEN = "panel_open";
    private static final String COUNTER_PANEL_OPEN_QS = "panel_open_qs";
    private static final String COUNTER_PANEL_OPEN_PEEK = "panel_open_peek";

    private static final Rect M_DUMMY_DIRTY_RECT = new Rect(0, 0, 1, 1);
    private static final Rect EMPTY_RECT = new Rect();

    private static final AnimationProperties
            CLOCK_ANIMATION_PROPERTIES =
            new AnimationProperties().setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
    private final AnimatableProperty KEYGUARD_HEADS_UP_SHOWING_AMOUNT = AnimatableProperty.from(
            "KEYGUARD_HEADS_UP_SHOWING_AMOUNT",
            (notificationPanelView, aFloat) -> setKeyguardHeadsUpShowingAmount(aFloat),
            (Function<NotificationPanelView, Float>) notificationPanelView ->
                    getKeyguardHeadsUpShowingAmount(),
            R.id.keyguard_hun_animator_tag, R.id.keyguard_hun_animator_end_tag,
            R.id.keyguard_hun_animator_start_tag);
    private static final AnimationProperties
            KEYGUARD_HUN_PROPERTIES =
            new AnimationProperties().setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
    @VisibleForTesting
    final KeyguardUpdateMonitorCallback
            mKeyguardUpdateCallback =
            new KeyguardUpdateMonitorCallback() {

                @Override
                public void onBiometricAuthenticated(int userId,
                        BiometricSourceType biometricSourceType,
                        boolean isStrongBiometric) {
                    if (mFirstBypassAttempt
                            && mUpdateMonitor.isUnlockingWithBiometricAllowed(isStrongBiometric)) {
                        mDelayShowingKeyguardStatusBar = true;
                    }
                }

                @Override
                public void onBiometricRunningStateChanged(boolean running,
                        BiometricSourceType biometricSourceType) {
                    boolean
                            keyguardOrShadeLocked =
                            mBarState == StatusBarState.KEYGUARD
                                    || mBarState == StatusBarState.SHADE_LOCKED;
                    if (!running && mFirstBypassAttempt && keyguardOrShadeLocked && !mDozing
                            && !mDelayShowingKeyguardStatusBar
                            && !mBiometricUnlockController.isBiometricUnlock()) {
                        mFirstBypassAttempt = false;
                        animateKeyguardStatusBarIn(StackStateAnimator.ANIMATION_DURATION_STANDARD);
                    }
                }

                @Override
                public void onFinishedGoingToSleep(int why) {
                    mFirstBypassAttempt = mKeyguardBypassController.getBypassEnabled();
                    mDelayShowingKeyguardStatusBar = false;
                }
            };

    private final InjectionInflationController mInjectionInflationController;
    private final PowerManager mPowerManager;
    private final AccessibilityManager mAccessibilityManager;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;
    private final PulseExpansionHandler mPulseExpansionHandler;
    private final KeyguardBypassController mKeyguardBypassController;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final ConversationNotificationManager mConversationNotificationManager;
    private final MediaHierarchyManager mMediaHierarchyManager;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    private KeyguardAffordanceHelper mAffordanceHelper;
    private KeyguardUserSwitcher mKeyguardUserSwitcher;
    private KeyguardStatusBarView mKeyguardStatusBar;
    private ViewGroup mBigClockContainer;
    private QS mQs;
    private FrameLayout mQsFrame;
    private KeyguardStatusView mKeyguardStatusView;
    private View mQsNavbarScrim;
    private NotificationsQuickSettingsContainer mNotificationContainerParent;
    private NotificationStackScrollLayout mNotificationStackScroller;
    private boolean mAnimateNextPositionUpdate;

    private int mTrackingPointer;
    private VelocityTracker mQsVelocityTracker;
    private boolean mQsTracking;

    /**
     * If set, the ongoing touch gesture might both trigger the expansion in {@link PanelView} and
     * the expansion for quick settings.
     */
    private boolean mConflictingQsExpansionGesture;

    private boolean mPanelExpanded;
    private boolean mQsExpanded;
    private boolean mQsExpandedWhenExpandingStarted;
    private boolean mQsFullyExpanded;
    private boolean mKeyguardShowing;
    private boolean mDozing;
    private boolean mDozingOnDown;
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
    private boolean mQsExpansionEnabled = true;
    private ValueAnimator mQsExpansionAnimator;
    private FlingAnimationUtils mFlingAnimationUtils;
    private int mStatusBarMinHeight;
    private int mNotificationsHeaderCollideDistance;
    private float mEmptyDragAmount;
    private float mDownX;
    private float mDownY;

    private final KeyguardClockPositionAlgorithm
            mClockPositionAlgorithm =
            new KeyguardClockPositionAlgorithm();
    private final KeyguardClockPositionAlgorithm.Result
            mClockPositionResult =
            new KeyguardClockPositionAlgorithm.Result();
    private boolean mIsExpanding;

    private boolean mBlockTouches;
    // Used for two finger gesture as well as accessibility shortcut to QS.
    private boolean mQsExpandImmediate;
    private boolean mTwoFingerQsExpandPossible;

    /**
     * If we are in a panel collapsing motion, we reset scrollY of our scroll view but still
     * need to take this into account in our panel height calculation.
     */
    private boolean mQsAnimatorExpand;
    private boolean mIsLaunchTransitionFinished;
    private boolean mIsLaunchTransitionRunning;
    private Runnable mLaunchAnimationEndRunnable;
    private boolean mOnlyAffordanceInThisMotion;
    private boolean mKeyguardStatusViewAnimating;
    private ValueAnimator mQsSizeChangeAnimator;

    private boolean mShowEmptyShadeView;

    private boolean mQsScrimEnabled = true;
    private boolean mQsTouchAboveFalsingThreshold;
    private int mQsFalsingThreshold;

    private float mKeyguardStatusBarAnimateAlpha = 1f;
    private HeadsUpTouchHelper mHeadsUpTouchHelper;
    private boolean mListenForHeadsUp;
    private int mNavigationBarBottomHeight;
    private boolean mExpandingFromHeadsUp;
    private boolean mCollapsedOnDown;
    private int mPositionMinSideMargin;
    private int mLastOrientation = -1;
    private boolean mClosingWithAlphaFadeOut;
    private boolean mHeadsUpAnimatingAway;
    private boolean mLaunchingAffordance;
    private boolean mAffordanceHasPreview;
    private FalsingManager mFalsingManager;
    private String mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_AFFORDANCE;

    private Runnable mHeadsUpExistenceChangedRunnable = () -> {
        setHeadsUpAnimatingAway(false);
        notifyBarPanelExpansionChanged();
    };
    private NotificationGroupManager mGroupManager;
    private boolean mShowIconsWhenExpanded;
    private int mIndicationBottomPadding;
    private int mAmbientIndicationBottomPadding;
    private boolean mIsFullWidth;
    private boolean mBlockingExpansionForCurrentTouch;

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
    private LockscreenGestureLogger mLockscreenGestureLogger = new LockscreenGestureLogger();
    private boolean mUserSetupComplete;
    private int mQsNotificationTopPadding;
    private float mExpandOffset;
    private boolean mHideIconsDuringNotificationLaunch = true;
    private int mStackScrollerMeasuringPass;
    private ArrayList<Consumer<ExpandableNotificationRow>>
            mTrackingHeadsUpListeners =
            new ArrayList<>();
    private ArrayList<Runnable> mVerticalTranslationListener = new ArrayList<>();
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
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final ShadeController mShadeController;
    private int mDisplayId;

    /**
     * Cache the resource id of the theme to avoid unnecessary work in onThemeChanged.
     *
     * onThemeChanged is forced when the theme might not have changed. So, to avoid unncessary
     * work, check the current id with the cached id.
     */
    private int mThemeResId;
    private KeyguardIndicationController mKeyguardIndicationController;
    private Consumer<Boolean> mAffordanceLaunchListener;
    private int mShelfHeight;
    private Runnable mOnReinflationListener;
    private int mDarkIconSize;
    private int mHeadsUpInset;
    private boolean mHeadsUpPinnedMode;
    private float mKeyguardHeadsUpShowingAmount = 0.0f;
    private boolean mShowingKeyguardHeadsUp;
    private boolean mAllowExpandForSmallExpansion;
    private Runnable mExpandAfterLayoutRunnable;

    /**
     * Is this a collapse that started on the panel where we should allow the panel to intercept
     */
    private boolean mIsPanelCollapseOnQQS;

    /**
     * If face auth with bypass is running for the first time after you turn on the screen.
     * (From aod or screen off)
     */
    private boolean mFirstBypassAttempt;
    /**
     * If auth happens successfully during {@code mFirstBypassAttempt}, and we should wait until
     * the keyguard is dismissed to show the status bar.
     */
    private boolean mDelayShowingKeyguardStatusBar;

    private boolean mAnimatingQS;
    private int mOldLayoutDirection;

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

    @Inject
    public NotificationPanelViewController(NotificationPanelView view,
            InjectionInflationController injectionInflationController,
            NotificationWakeUpCoordinator coordinator, PulseExpansionHandler pulseExpansionHandler,
            DynamicPrivacyController dynamicPrivacyController,
            KeyguardBypassController bypassController, FalsingManager falsingManager,
            ShadeController shadeController,
            NotificationLockscreenUserManager notificationLockscreenUserManager,
            NotificationEntryManager notificationEntryManager,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController, DozeLog dozeLog,
            DozeParameters dozeParameters, CommandQueue commandQueue, VibratorHelper vibratorHelper,
            LatencyTracker latencyTracker, PowerManager powerManager,
            AccessibilityManager accessibilityManager, @DisplayId int displayId,
            KeyguardUpdateMonitor keyguardUpdateMonitor, MetricsLogger metricsLogger,
            ActivityManager activityManager, ZenModeController zenModeController,
            ConfigurationController configurationController,
            FlingAnimationUtils.Builder flingAnimationUtilsBuilder,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager,
            ConversationNotificationManager conversationNotificationManager,
            MediaHierarchyManager mediaHierarchyManager,
            BiometricUnlockController biometricUnlockController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        super(view, falsingManager, dozeLog, keyguardStateController,
                (SysuiStatusBarStateController) statusBarStateController, vibratorHelper,
                latencyTracker, flingAnimationUtilsBuilder, statusBarTouchableRegionManager);
        mView = view;
        mMetricsLogger = metricsLogger;
        mActivityManager = activityManager;
        mZenModeController = zenModeController;
        mConfigurationController = configurationController;
        mFlingAnimationUtilsBuilder = flingAnimationUtilsBuilder;
        mMediaHierarchyManager = mediaHierarchyManager;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mView.setWillNotDraw(!DEBUG);
        mInjectionInflationController = injectionInflationController;
        mFalsingManager = falsingManager;
        mPowerManager = powerManager;
        mWakeUpCoordinator = coordinator;
        mAccessibilityManager = accessibilityManager;
        mView.setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
        setPanelAlpha(255, false /* animate */);
        mCommandQueue = commandQueue;
        mDisplayId = displayId;
        mPulseExpansionHandler = pulseExpansionHandler;
        mDozeParameters = dozeParameters;
        mBiometricUnlockController = biometricUnlockController;
        pulseExpansionHandler.setPulseExpandAbortListener(() -> {
            if (mQs != null) {
                mQs.animateHeaderSlidingOut();
            }
        });
        mThemeResId = mView.getContext().getThemeResId();
        mKeyguardBypassController = bypassController;
        mUpdateMonitor = keyguardUpdateMonitor;
        mFirstBypassAttempt = mKeyguardBypassController.getBypassEnabled();
        KeyguardStateController.Callback
                keyguardMonitorCallback =
                new KeyguardStateController.Callback() {
                    @Override
                    public void onKeyguardFadingAwayChanged() {
                        if (!mKeyguardStateController.isKeyguardFadingAway()) {
                            mFirstBypassAttempt = false;
                            mDelayShowingKeyguardStatusBar = false;
                        }
                    }
                };
        mKeyguardStateController.addCallback(keyguardMonitorCallback);
        DynamicPrivacyControlListener
                dynamicPrivacyControlListener =
                new DynamicPrivacyControlListener();
        dynamicPrivacyController.addListener(dynamicPrivacyControlListener);

        mBottomAreaShadeAlphaAnimator = ValueAnimator.ofFloat(1f, 0);
        mBottomAreaShadeAlphaAnimator.addUpdateListener(animation -> {
            mBottomAreaShadeAlpha = (float) animation.getAnimatedValue();
            updateKeyguardBottomAreaAlpha();
        });
        mBottomAreaShadeAlphaAnimator.setDuration(160);
        mBottomAreaShadeAlphaAnimator.setInterpolator(Interpolators.ALPHA_OUT);
        mShadeController = shadeController;
        mLockscreenUserManager = notificationLockscreenUserManager;
        mEntryManager = notificationEntryManager;
        mConversationNotificationManager = conversationNotificationManager;

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

        onFinishInflate();
    }

    private void onFinishInflate() {
        loadDimens();
        mKeyguardStatusBar = mView.findViewById(R.id.keyguard_header);
        mKeyguardStatusView = mView.findViewById(R.id.keyguard_status_view);

        KeyguardClockSwitch keyguardClockSwitch = mView.findViewById(R.id.keyguard_clock_container);
        mBigClockContainer = mView.findViewById(R.id.big_clock_container);
        keyguardClockSwitch.setBigClockContainer(mBigClockContainer);

        mNotificationContainerParent = mView.findViewById(R.id.notification_container_parent);
        mNotificationStackScroller = mView.findViewById(R.id.notification_stack_scroller);
        mNotificationStackScroller.setOnHeightChangedListener(mOnHeightChangedListener);
        mNotificationStackScroller.setOverscrollTopChangedListener(mOnOverscrollTopChangedListener);
        mNotificationStackScroller.setOnEmptySpaceClickListener(mOnEmptySpaceClickListener);
        addTrackingHeadsUpListener(mNotificationStackScroller::setTrackingHeadsUp);
        mKeyguardBottomArea = mView.findViewById(R.id.keyguard_bottom_area);
        mQsNavbarScrim = mView.findViewById(R.id.qs_navbar_scrim);
        mLastOrientation = mResources.getConfiguration().orientation;

        initBottomArea();

        mWakeUpCoordinator.setStackScroller(mNotificationStackScroller);
        mQsFrame = mView.findViewById(R.id.qs_frame);
        mPulseExpansionHandler.setUp(
                mNotificationStackScroller, mExpansionCallback, mShadeController);
        mWakeUpCoordinator.addListener(new NotificationWakeUpCoordinator.WakeUpListener() {
            @Override
            public void onFullyHiddenChanged(boolean isFullyHidden) {
                updateKeyguardStatusBarForHeadsUp();
            }

            @Override
            public void onPulseExpansionChanged(boolean expandingChanged) {
                if (mKeyguardBypassController.getBypassEnabled()) {
                    // Position the notifications while dragging down while pulsing
                    requestScrollerTopPaddingUpdate(false /* animate */);
                    updateQSPulseExpansion();
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
    }

    @Override
    protected void loadDimens() {
        super.loadDimens();
        mFlingAnimationUtils = mFlingAnimationUtilsBuilder.reset()
                .setMaxLengthSeconds(0.4f).build();
        mStatusBarMinHeight = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mQsPeekHeight = mResources.getDimensionPixelSize(R.dimen.qs_peek_height);
        mNotificationsHeaderCollideDistance = mResources.getDimensionPixelSize(
                R.dimen.header_notifications_collide_distance);
        mClockPositionAlgorithm.loadDimens(mResources);
        mQsFalsingThreshold = mResources.getDimensionPixelSize(R.dimen.qs_falsing_threshold);
        mPositionMinSideMargin = mResources.getDimensionPixelSize(
                R.dimen.notification_panel_min_side_margin);
        mIndicationBottomPadding = mResources.getDimensionPixelSize(
                R.dimen.keyguard_indication_bottom_padding);
        mQsNotificationTopPadding = mResources.getDimensionPixelSize(
                R.dimen.qs_notification_padding);
        mShelfHeight = mResources.getDimensionPixelSize(R.dimen.notification_shelf_height);
        mDarkIconSize = mResources.getDimensionPixelSize(R.dimen.status_bar_icon_drawing_size_dark);
        int statusbarHeight = mResources.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mHeadsUpInset = statusbarHeight + mResources.getDimensionPixelSize(
                R.dimen.heads_up_status_bar_padding);
    }

    /**
     * Returns if there's a custom clock being presented.
     */
    public boolean hasCustomClock() {
        return mKeyguardStatusView.hasCustomClock();
    }

    private void setStatusBar(StatusBar bar) {
        // TODO: this can be injected.
        mStatusBar = bar;
        mKeyguardBottomArea.setStatusBar(mStatusBar);
    }
    /**
     * @see #launchCamera(boolean, int)
     * @see #setLaunchingAffordance(boolean)
     */
    public void setLaunchAffordanceListener(Consumer<Boolean> listener) {
        mAffordanceLaunchListener = listener;
    }

    public void updateResources() {
        int qsWidth = mResources.getDimensionPixelSize(R.dimen.qs_panel_width);
        int panelGravity = mResources.getInteger(R.integer.notification_panel_layout_gravity);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mQsFrame.getLayoutParams();
        if (lp.width != qsWidth || lp.gravity != panelGravity) {
            lp.width = qsWidth;
            lp.gravity = panelGravity;
            mQsFrame.setLayoutParams(lp);
        }

        int panelWidth = mResources.getDimensionPixelSize(R.dimen.notification_panel_width);
        lp = (FrameLayout.LayoutParams) mNotificationStackScroller.getLayoutParams();
        if (lp.width != panelWidth || lp.gravity != panelGravity) {
            lp.width = panelWidth;
            lp.gravity = panelGravity;
            mNotificationStackScroller.setLayoutParams(lp);
        }
    }

    private void reInflateViews() {
        updateShowEmptyShadeView();

        // Re-inflate the status view group.
        int index = mView.indexOfChild(mKeyguardStatusView);
        mView.removeView(mKeyguardStatusView);
        mKeyguardStatusView = (KeyguardStatusView) mInjectionInflationController.injectable(
                LayoutInflater.from(mView.getContext())).inflate(
                R.layout.keyguard_status_view, mView, false);
        mView.addView(mKeyguardStatusView, index);

        // Re-associate the clock container with the keyguard clock switch.
        mBigClockContainer.removeAllViews();
        KeyguardClockSwitch keyguardClockSwitch = mView.findViewById(R.id.keyguard_clock_container);
        keyguardClockSwitch.setBigClockContainer(mBigClockContainer);

        // Update keyguard bottom area
        index = mView.indexOfChild(mKeyguardBottomArea);
        mView.removeView(mKeyguardBottomArea);
        KeyguardBottomAreaView oldBottomArea = mKeyguardBottomArea;
        mKeyguardBottomArea = (KeyguardBottomAreaView) mInjectionInflationController.injectable(
                LayoutInflater.from(mView.getContext())).inflate(
                R.layout.keyguard_bottom_area, mView, false);
        mKeyguardBottomArea.initFrom(oldBottomArea);
        mView.addView(mKeyguardBottomArea, index);
        initBottomArea();
        mKeyguardIndicationController.setIndicationArea(mKeyguardBottomArea);
        mStatusBarStateListener.onDozeAmountChanged(mStatusBarStateController.getDozeAmount(),
                mStatusBarStateController.getInterpolatedDozeAmount());

        if (mKeyguardStatusBar != null) {
            mKeyguardStatusBar.onThemeChanged();
        }

        setKeyguardStatusViewVisibility(mBarState, false, false);
        setKeyguardBottomAreaVisibility(mBarState, false);
        if (mOnReinflationListener != null) {
            mOnReinflationListener.run();
        }
    }

    private void initBottomArea() {
        mAffordanceHelper = new KeyguardAffordanceHelper(
                mKeyguardAffordanceHelperCallback, mView.getContext(), mFalsingManager);
        mKeyguardBottomArea.setAffordanceHelper(mAffordanceHelper);
        mKeyguardBottomArea.setStatusBar(mStatusBar);
        mKeyguardBottomArea.setUserSetupComplete(mUserSetupComplete);
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
        mNotificationStackScroller.setIsFullWidth(isFullWidth);
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
        boolean animate = mNotificationStackScroller.isAddOrRemoveAnimationPending();
        boolean animateClock = animate || mAnimateNextPositionUpdate;
        int stackScrollerPadding;
        if (mBarState != StatusBarState.KEYGUARD) {
            stackScrollerPadding = getUnlockedStackScrollerPadding();
        } else {
            int totalHeight = mView.getHeight();
            int bottomPadding = Math.max(mIndicationBottomPadding, mAmbientIndicationBottomPadding);
            int clockPreferredY = mKeyguardStatusView.getClockPreferredY(totalHeight);
            boolean bypassEnabled = mKeyguardBypassController.getBypassEnabled();
            final boolean
                    hasVisibleNotifications =
                    !bypassEnabled && mNotificationStackScroller.getVisibleNotificationCount() != 0;
            mKeyguardStatusView.setHasVisibleNotifications(hasVisibleNotifications);
            mClockPositionAlgorithm.setup(mStatusBarMinHeight, totalHeight - bottomPadding,
                    mNotificationStackScroller.getIntrinsicContentHeight(), getExpandedFraction(),
                    totalHeight, (int) (mKeyguardStatusView.getHeight() - mShelfHeight / 2.0f
                            - mDarkIconSize / 2.0f), clockPreferredY, hasCustomClock(),
                    hasVisibleNotifications, mInterpolatedDarkAmount, mEmptyDragAmount,
                    bypassEnabled, getUnlockedStackScrollerPadding());
            mClockPositionAlgorithm.run(mClockPositionResult);
            PropertyAnimator.setProperty(mKeyguardStatusView, AnimatableProperty.X,
                    mClockPositionResult.clockX, CLOCK_ANIMATION_PROPERTIES, animateClock);
            PropertyAnimator.setProperty(mKeyguardStatusView, AnimatableProperty.Y,
                    mClockPositionResult.clockY, CLOCK_ANIMATION_PROPERTIES, animateClock);
            updateNotificationTranslucency();
            updateClock();
            stackScrollerPadding = mClockPositionResult.stackScrollerPaddingExpanded;
        }
        mNotificationStackScroller.setIntrinsicPadding(stackScrollerPadding);
        mKeyguardBottomArea.setAntiBurnInOffsetX(mClockPositionResult.clockX);

        mStackScrollerMeasuringPass++;
        requestScrollerTopPaddingUpdate(animate);
        mStackScrollerMeasuringPass = 0;
        mAnimateNextPositionUpdate = false;
    }

    /**
     * @return the padding of the stackscroller when unlocked
     */
    private int getUnlockedStackScrollerPadding() {
        return (mQs != null ? mQs.getHeader().getHeight() : 0) + mQsPeekHeight
                + mQsNotificationTopPadding;
    }

    /**
     * @param maximum the maximum to return at most
     * @return the maximum keyguard notifications that can fit on the screen
     */
    public int computeMaxKeyguardNotifications(int maximum) {
        float minPadding = mClockPositionAlgorithm.getMinStackScrollerPadding();
        int notificationPadding = Math.max(
                1, mResources.getDimensionPixelSize(R.dimen.notification_divider_height));
        NotificationShelf shelf = mNotificationStackScroller.getNotificationShelf();
        float
                shelfSize =
                shelf.getVisibility() == View.GONE ? 0
                        : shelf.getIntrinsicHeight() + notificationPadding;
        float
                availableSpace =
                mNotificationStackScroller.getHeight() - minPadding - shelfSize - Math.max(
                        mIndicationBottomPadding, mAmbientIndicationBottomPadding)
                        - mKeyguardStatusView.getLogoutButtonHeight();
        int count = 0;
        ExpandableView previousView = null;
        for (int i = 0; i < mNotificationStackScroller.getChildCount(); i++) {
            ExpandableView child = (ExpandableView) mNotificationStackScroller.getChildAt(i);
            if (!canShowViewOnLockscreen(child)) {
                continue;
            }
            availableSpace -= child.getMinHeight(true /* ignoreTemporaryStates */);
            availableSpace -= count == 0 ? 0 : notificationPadding;
            availableSpace -= mNotificationStackScroller.calculateGapHeight(previousView, child,
                    count);
            previousView = child;
            if (availableSpace >= 0 && count < maximum) {
                count++;
            } else if (availableSpace > -shelfSize) {
                // if we are exactly the last view, then we can show us still!
                for (int j = i + 1; j < mNotificationStackScroller.getChildCount(); j++) {
                    ExpandableView view = (ExpandableView) mNotificationStackScroller.getChildAt(j);
                    if (view instanceof ExpandableNotificationRow &&
                            canShowViewOnLockscreen(view)) {
                        return count;
                    }
                }
                count++;
                return count;
            } else {
                return count;
            }
        }
        return count;
    }

    /**
     * Can a view be shown on the lockscreen when calculating the number of allowed notifications
     * to show?
     *
     * @param child the view in question
     * @return true if it can be shown
     */
    private boolean canShowViewOnLockscreen(ExpandableView child) {
        if (child.hasNoContentHeight()) {
            return false;
        }
        if (child instanceof ExpandableNotificationRow &&
                !canShowRowOnLockscreen((ExpandableNotificationRow) child)) {
            return false;
        } else if (child.getVisibility() == GONE) {
            // ENRs can be gone and count because their visibility is only set after
            // this calculation, but all other views should be up to date
            return false;
        }
        return true;
    }

    /**
     * Can a row be shown on the lockscreen when calculating the number of allowed notifications
     * to show?
     *
     * @param row the row in question
     * @return true if it can be shown
     */
    private boolean canShowRowOnLockscreen(ExpandableNotificationRow row) {
        boolean suppressedSummary =
                mGroupManager != null && mGroupManager.isSummaryOfSuppressedGroup(
                        row.getEntry().getSbn());
        if (suppressedSummary) {
            return false;
        }
        if (!mLockscreenUserManager.shouldShowOnKeyguard(row.getEntry())) {
            return false;
        }
        if (row.isRemoved()) {
            return false;
        }
        return true;
    }

    private void updateClock() {
        if (!mKeyguardStatusViewAnimating) {
            mKeyguardStatusView.setAlpha(mClockPositionResult.clockAlpha);
        }
    }

    public void animateToFullShade(long delay) {
        mNotificationStackScroller.goToFullShade(delay);
        mView.requestLayout();
        mAnimateNextPositionUpdate = true;
    }

    public void setQsExpansionEnabled(boolean qsExpansionEnabled) {
        mQsExpansionEnabled = qsExpansionEnabled;
        if (mQs == null) return;
        mQs.setHeaderClickable(qsExpansionEnabled);
    }

    @Override
    public void resetViews(boolean animate) {
        mIsLaunchTransitionFinished = false;
        mBlockTouches = false;
        if (!mLaunchingAffordance) {
            mAffordanceHelper.reset(false);
            mLastCameraLaunchSource = KeyguardBottomAreaView.CAMERA_LAUNCH_SOURCE_AFFORDANCE;
        }
        mStatusBar.getGutsManager().closeAndSaveGuts(true /* leavebehind */, true /* force */,
                true /* controls */, -1 /* x */, -1 /* y */, true /* resetMenu */);
        if (animate) {
            animateCloseQs(true /* animateAway */);
        } else {
            closeQs();
        }
        mNotificationStackScroller.setOverScrollAmount(0f, true /* onTop */, animate,
                !animate /* cancelAnimators */);
        mNotificationStackScroller.resetScrollPosition();
    }

    @Override
    public void collapse(boolean delayed, float speedUpFactor) {
        if (!canPanelBeCollapsed()) {
            return;
        }

        if (mQsExpanded) {
            mQsExpandImmediate = true;
            mNotificationStackScroller.setShouldShowShelfOnly(true);
        }
        super.collapse(delayed, speedUpFactor);
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

    public void expandWithQs() {
        if (mQsExpansionEnabled) {
            mQsExpandImmediate = true;
            mNotificationStackScroller.setShouldShowShelfOnly(true);
        }
        if (isFullyCollapsed()) {
            expand(true /* animate */);
        } else {
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
        GestureRecorder gr = ((PhoneStatusBarView) mBar).mBar.getGestureRecorder();
        if (gr != null) {
            gr.tag("fling " + ((vel > 0) ? "open" : "closed"), "notifications,v=" + vel);
        }
        super.fling(vel, expand);
    }

    @Override
    protected void flingToHeight(float vel, boolean expand, float target,
            float collapseSpeedUpFactor, boolean expandBecauseOfFalsing) {
        mHeadsUpTouchHelper.notifyFling(!expand);
        setClosingWithAlphaFadeout(!expand && !isOnKeyguard() && getFadeoutAlpha() == 1.0f);
        super.flingToHeight(vel, expand, target, collapseSpeedUpFactor, expandBecauseOfFalsing);
    }


    private boolean onQsIntercept(MotionEvent event) {
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
                    onQsExpansionStarted();
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mQsTracking = true;
                    mNotificationStackScroller.cancelLongPress();
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
                    mView.getParent().requestDisallowInterceptTouchEvent(true);
                    mQsTracking = true;
                    onQsExpansionStarted();
                    notifyExpandingFinished();
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mNotificationStackScroller.cancelLongPress();
                    return true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                trackMovement(event);
                if (mQsTracking) {
                    flingQsWithCurrentVelocity(y,
                            event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                    mQsTracking = false;
                }
                break;
        }
        return false;
    }

    @Override
    protected boolean isInContentBounds(float x, float y) {
        float stackScrollerX = mNotificationStackScroller.getX();
        return !mNotificationStackScroller.isBelowLastNotification(x - stackScrollerX, y)
                && stackScrollerX < x && x < stackScrollerX + mNotificationStackScroller.getWidth();
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
        final boolean expandsQs = flingExpandsQs(vel);
        if (expandsQs) {
            logQsSwipeDown(y);
        }
        flingSettings(vel, expandsQs && !isCancelMotionEvent ? FLING_EXPAND : FLING_COLLAPSE);
    }

    private void logQsSwipeDown(float y) {
        float vel = getCurrentQSVelocity();
        final int
                gesture =
                mBarState == StatusBarState.KEYGUARD ? MetricsEvent.ACTION_LS_QS
                        : MetricsEvent.ACTION_SHADE_QS_PULL;
        mLockscreenGestureLogger.write(gesture,
                (int) ((y - mInitialTouchY) / mStatusBar.getDisplayDensity()),
                (int) (vel / mStatusBar.getDisplayDensity()));
    }

    private boolean flingExpandsQs(float vel) {
        if (mFalsingManager.isUnlockingDisabled() || isFalseTouch()) {
            return false;
        }
        if (Math.abs(vel) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            return getQsExpansionFraction() > 0.5f;
        } else {
            return vel > 0;
        }
    }

    private boolean isFalseTouch() {
        if (!mKeyguardAffordanceHelperCallback.needsAntiFalsing()) {
            return false;
        }
        if (mFalsingManager.isClassifierEnabled()) {
            return mFalsingManager.isFalseTouch();
        }
        return !mQsTouchAboveFalsingThreshold;
    }

    private float getQsExpansionFraction() {
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
            long timeSinceDown = SystemClock.uptimeMillis() - mDownTime;
            return timeSinceDown <= MAX_TIME_TO_OPEN_WHEN_FLINGING_FROM_LAUNCHER;
        }
        return false;
    }

    @Override
    protected float getOpeningHeight() {
        return mNotificationStackScroller.getOpeningHeight();
    }


    private boolean handleQsTouch(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && getExpandedFraction() == 1f
                && mBarState != StatusBarState.KEYGUARD && !mQsExpanded && mQsExpansionEnabled) {

            // Down in the empty area while fully expanded - go to QS.
            mQsTracking = true;
            mConflictingQsExpansionGesture = true;
            onQsExpansionStarted();
            mInitialHeightOnTouch = mQsExpansionHeight;
            mInitialTouchY = event.getX();
            mInitialTouchX = event.getY();
        }
        if (!isFullyCollapsed()) {
            handleQsDown(event);
        }
        if (!mQsExpandImmediate && mQsTracking) {
            onQsTouch(event);
            if (!mConflictingQsExpansionGesture) {
                return true;
            }
        }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            mConflictingQsExpansionGesture = false;
        }
        if (action == MotionEvent.ACTION_DOWN && isFullyCollapsed() && mQsExpansionEnabled) {
            mTwoFingerQsExpandPossible = true;
        }
        if (mTwoFingerQsExpandPossible && isOpenQsEvent(event) && event.getY(event.getActionIndex())
                < mStatusBarMinHeight) {
            mMetricsLogger.count(COUNTER_PANEL_OPEN_QS, 1);
            mQsExpandImmediate = true;
            mNotificationStackScroller.setShouldShowShelfOnly(true);
            requestPanelHeightUpdate();

            // Normally, we start listening when the panel is expanded, but here we need to start
            // earlier so the state is already up to date when dragging down.
            setListening(true);
        }
        return false;
    }

    private boolean isInQsArea(float x, float y) {
        return (x >= mQsFrame.getX() && x <= mQsFrame.getX() + mQsFrame.getWidth()) && (
                y <= mNotificationStackScroller.getBottomMostNotificationBottom()
                        || y <= mQs.getView().getY() + mQs.getView().getHeight());
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
            mFalsingManager.onQsDown();
            mQsTracking = true;
            onQsExpansionStarted();
            mInitialHeightOnTouch = mQsExpansionHeight;
            mInitialTouchY = event.getX();
            mInitialTouchX = event.getY();

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
                float fraction = getQsExpansionFraction();
                if (fraction != 0f || y >= mInitialTouchY) {
                    flingQsWithCurrentVelocity(y,
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
        float factor = mStatusBar.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
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
        mNotificationStackScroller.checkSnoozeLeavebehind();

        // When expanding QS, let's authenticate the user if possible,
        // this will speed up notification actions.
        if (height == 0) {
            mStatusBar.requestFaceAuth();
        }
    }

    private void setQsExpanded(boolean expanded) {
        boolean changed = mQsExpanded != expanded;
        if (changed) {
            mQsExpanded = expanded;
            updateQsState();
            requestPanelHeightUpdate();
            mFalsingManager.setQsExpanded(expanded);
            mStatusBar.setQsExpanded(expanded);
            mNotificationContainerParent.setQsExpanded(expanded);
            mPulseExpansionHandler.setQsExpanded(expanded);
            mKeyguardBypassController.setQSExpanded(expanded);
        }
    }

    private void maybeAnimateBottomAreaAlpha() {
        mBottomAreaShadeAlphaAnimator.cancel();
        if (mBarState == StatusBarState.SHADE_LOCKED) {
            mBottomAreaShadeAlphaAnimator.start();
        } else {
            mBottomAreaShadeAlpha = 1f;
        }
    }

    private final Runnable mAnimateKeyguardStatusViewInvisibleEndRunnable = new Runnable() {
        @Override
        public void run() {
            mKeyguardStatusViewAnimating = false;
            mKeyguardStatusView.setVisibility(View.INVISIBLE);
        }
    };

    private final Runnable mAnimateKeyguardStatusViewGoneEndRunnable = new Runnable() {
        @Override
        public void run() {
            mKeyguardStatusViewAnimating = false;
            mKeyguardStatusView.setVisibility(View.GONE);
        }
    };

    private final Runnable mAnimateKeyguardStatusViewVisibleEndRunnable = new Runnable() {
        @Override
        public void run() {
            mKeyguardStatusViewAnimating = false;
        }
    };

    private final Runnable mAnimateKeyguardStatusBarInvisibleEndRunnable = new Runnable() {
        @Override
        public void run() {
            mKeyguardStatusBar.setVisibility(View.INVISIBLE);
            mKeyguardStatusBar.setAlpha(1f);
            mKeyguardStatusBarAnimateAlpha = 1f;
        }
    };

    private void animateKeyguardStatusBarOut() {
        ValueAnimator anim = ValueAnimator.ofFloat(mKeyguardStatusBar.getAlpha(), 0f);
        anim.addUpdateListener(mStatusBarAnimateAlphaListener);
        anim.setStartDelay(mKeyguardStateController.isKeyguardFadingAway()
                ? mKeyguardStateController.getKeyguardFadingAwayDelay() : 0);

        long duration;
        if (mKeyguardStateController.isKeyguardFadingAway()) {
            duration = mKeyguardStateController.getShortenedFadingAwayDuration();
        } else {
            duration = StackStateAnimator.ANIMATION_DURATION_STANDARD;
        }
        anim.setDuration(duration);

        anim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimateKeyguardStatusBarInvisibleEndRunnable.run();
            }
        });
        anim.start();
    }

    private final ValueAnimator.AnimatorUpdateListener
            mStatusBarAnimateAlphaListener =
            new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mKeyguardStatusBarAnimateAlpha = (float) animation.getAnimatedValue();
                    updateHeaderKeyguardAlpha();
                }
            };

    private void animateKeyguardStatusBarIn(long duration) {
        mKeyguardStatusBar.setVisibility(View.VISIBLE);
        mKeyguardStatusBar.setAlpha(0f);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.addUpdateListener(mStatusBarAnimateAlphaListener);
        anim.setDuration(duration);
        anim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        anim.start();
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
        } else if (statusBarState == StatusBarState.KEYGUARD
                || statusBarState == StatusBarState.SHADE_LOCKED) {
            mKeyguardBottomArea.setVisibility(View.VISIBLE);
            mKeyguardBottomArea.setAlpha(1f);
        } else {
            mKeyguardBottomArea.setVisibility(View.GONE);
        }
    }

    private void setKeyguardStatusViewVisibility(int statusBarState, boolean keyguardFadingAway,
            boolean goingToFullShade) {
        mKeyguardStatusView.animate().cancel();
        mKeyguardStatusViewAnimating = false;
        if ((!keyguardFadingAway && mBarState == StatusBarState.KEYGUARD
                && statusBarState != StatusBarState.KEYGUARD) || goingToFullShade) {
            mKeyguardStatusViewAnimating = true;
            mKeyguardStatusView.animate().alpha(0f).setStartDelay(0).setDuration(
                    160).setInterpolator(Interpolators.ALPHA_OUT).withEndAction(
                    mAnimateKeyguardStatusViewGoneEndRunnable);
            if (keyguardFadingAway) {
                mKeyguardStatusView.animate().setStartDelay(
                        mKeyguardStateController.getKeyguardFadingAwayDelay()).setDuration(
                        mKeyguardStateController.getShortenedFadingAwayDuration()).start();
            }
        } else if (mBarState == StatusBarState.SHADE_LOCKED
                && statusBarState == StatusBarState.KEYGUARD) {
            mKeyguardStatusView.setVisibility(View.VISIBLE);
            mKeyguardStatusViewAnimating = true;
            mKeyguardStatusView.setAlpha(0f);
            mKeyguardStatusView.animate().alpha(1f).setStartDelay(0).setDuration(
                    320).setInterpolator(Interpolators.ALPHA_IN).withEndAction(
                    mAnimateKeyguardStatusViewVisibleEndRunnable);
        } else if (statusBarState == StatusBarState.KEYGUARD) {
            if (keyguardFadingAway) {
                mKeyguardStatusViewAnimating = true;
                mKeyguardStatusView.animate().alpha(0).translationYBy(
                        -getHeight() * 0.05f).setInterpolator(
                        Interpolators.FAST_OUT_LINEAR_IN).setDuration(125).setStartDelay(
                        0).withEndAction(mAnimateKeyguardStatusViewInvisibleEndRunnable).start();
            } else {
                mKeyguardStatusView.setVisibility(View.VISIBLE);
                mKeyguardStatusView.setAlpha(1f);
            }
        } else {
            mKeyguardStatusView.setVisibility(View.GONE);
            mKeyguardStatusView.setAlpha(1f);
        }
    }

    private void updateQsState() {
        mNotificationStackScroller.setQsExpanded(mQsExpanded);
        mNotificationStackScroller.setScrollingEnabled(
                mBarState != StatusBarState.KEYGUARD && (!mQsExpanded
                        || mQsExpansionFromOverscroll));
        updateEmptyShadeView();

        mQsNavbarScrim.setVisibility(
                mBarState == StatusBarState.SHADE && mQsExpanded && !mStackScrollerOverscrolling
                        && mQsScrimEnabled ? View.VISIBLE : View.INVISIBLE);
        if (mKeyguardUserSwitcher != null && mQsExpanded && !mStackScrollerOverscrolling) {
            mKeyguardUserSwitcher.hideIfNotSimple(true /* animate */);
        }
        if (mQs == null) return;
        mQs.setExpanded(mQsExpanded);
    }

    private void setQsExpansion(float height) {
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
        updateHeaderKeyguardAlpha();
        if (mBarState == StatusBarState.SHADE_LOCKED || mBarState == StatusBarState.KEYGUARD) {
            updateKeyguardBottomAreaAlpha();
            updateBigClockAlpha();
        }
        if (mBarState == StatusBarState.SHADE && mQsExpanded && !mStackScrollerOverscrolling
                && mQsScrimEnabled) {
            mQsNavbarScrim.setAlpha(getQsExpansionFraction());
        }

        if (mAccessibilityManager.isEnabled()) {
            mView.setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
        }

        if (!mFalsingManager.isUnlockingDisabled() && mQsFullyExpanded
                && mFalsingManager.shouldEnforceBouncer()) {
            mStatusBar.executeRunnableDismissingKeyguard(null, null /* cancelAction */,
                    false /* dismissShade */, true /* afterKeyguardGone */, false /* deferred */);
        }
        for (int i = 0; i < mExpansionListeners.size(); i++) {
            mExpansionListeners.get(i).onQsExpansionChanged(
                    mQsMaxExpansionHeight != 0 ? mQsExpansionHeight / mQsMaxExpansionHeight : 0);
        }
        if (DEBUG) {
            mView.invalidate();
        }
    }

    protected void updateQsExpansion() {
        if (mQs == null) return;
        float qsExpansionFraction = getQsExpansionFraction();
        mQs.setQsExpansion(qsExpansionFraction, getHeaderTranslation());
        mMediaHierarchyManager.setQsExpansion(qsExpansionFraction);
        mNotificationStackScroller.setQsExpansionFraction(qsExpansionFraction);
    }

    private String determineAccessibilityPaneTitle() {
        if (mQs != null && mQs.isCustomizing()) {
            return mResources.getString(R.string.accessibility_desc_quick_settings_edit);
        } else if (mQsExpansionHeight != 0.0f && mQsFullyExpanded) {
            // Upon initialisation when we are not layouted yet we don't want to announce that we
            // are fully expanded, hence the != 0.0f check.
            return mResources.getString(R.string.accessibility_desc_quick_settings);
        } else if (mBarState == StatusBarState.KEYGUARD) {
            return mResources.getString(R.string.accessibility_desc_lock_screen);
        } else {
            return mResources.getString(R.string.accessibility_desc_notification_shade);
        }
    }

    private float calculateQsTopPadding() {
        if (mKeyguardShowing && (mQsExpandImmediate
                || mIsExpanding && mQsExpandedWhenExpandingStarted)) {

            // Either QS pushes the notifications down when fully expanded, or QS is fully above the
            // notifications (mostly on tablets). maxNotificationPadding denotes the normal top
            // padding on Keyguard, maxQsPadding denotes the top padding from the quick settings
            // panel. We need to take the maximum and linearly interpolate with the panel expansion
            // for a nice motion.
            int maxNotificationPadding = getKeyguardNotificationStaticPadding();
            int maxQsPadding = mQsMaxExpansionHeight + mQsNotificationTopPadding;
            int max = mBarState == StatusBarState.KEYGUARD ? Math.max(
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
                    (float) (mQsMaxExpansionHeight + mQsNotificationTopPadding),
                    getQsExpansionFraction());
        } else {
            return mQsExpansionHeight + mQsNotificationTopPadding;
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
        if (!mNotificationStackScroller.isPulseExpanding()) {
            return collapsedPosition;
        } else {
            int expandedPosition = mClockPositionResult.stackScrollerPadding;
            return (int) MathUtils.lerp(collapsedPosition, expandedPosition,
                    mNotificationStackScroller.calculateAppearFractionBypass());
        }
    }


    protected void requestScrollerTopPaddingUpdate(boolean animate) {
        mNotificationStackScroller.updateTopPadding(calculateQsTopPadding(), animate);
        if (mKeyguardShowing && mKeyguardBypassController.getBypassEnabled()) {
            // update the position of the header
            updateQsExpansion();
        }
    }


    private void updateQSPulseExpansion() {
        if (mQs != null) {
            mQs.setShowCollapsedOnKeyguard(
                    mKeyguardShowing && mKeyguardBypassController.getBypassEnabled()
                            && mNotificationStackScroller.isPulseExpanding());
        }
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
        flingSettings(vel, type, null, false /* isClick */);
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
                target = 0;
        }
        if (target == mQsExpansionHeight) {
            if (onFinishRunnable != null) {
                onFinishRunnable.run();
            }
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
            @Override
            public void onAnimationStart(Animator animation) {
                notifyExpandingStarted();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimatingQS = false;
                notifyExpandingFinished();
                mNotificationStackScroller.resetCheckSnoozeLeavebehind();
                mQsExpansionAnimator = null;
                if (onFinishRunnable != null) {
                    onFinishRunnable.run();
                }
            }
        });
        // Let's note that we're animating QS. Moving the animator here will cancel it immediately,
        // so we need a separate flag.
        mAnimatingQS = true;
        animator.start();
        mQsExpansionAnimator = animator;
        mQsAnimatorExpand = expanding;
    }

    /**
     * @return Whether we should intercept a gesture to open Quick Settings.
     */
    private boolean shouldQuickSettingsIntercept(float x, float y, float yDiff) {
        if (!mQsExpansionEnabled || mCollapsedOnDown || (mKeyguardShowing
                && mKeyguardBypassController.getBypassEnabled())) {
            return false;
        }
        View header = mKeyguardShowing || mQs == null ? mKeyguardStatusBar : mQs.getHeader();
        final boolean
                onHeader =
                x >= mQsFrame.getX() && x <= mQsFrame.getX() + mQsFrame.getWidth()
                        && y >= header.getTop() && y <= header.getBottom();
        if (mQsExpanded) {
            return onHeader || (yDiff < 0 && isInQsArea(x, y));
        } else {
            return onHeader;
        }
    }

    @Override
    protected boolean canCollapsePanelOnTouch() {
        if (!isInSettings()) {
            return mBarState == StatusBarState.KEYGUARD
                    || mNotificationStackScroller.isScrolledToBottom()
                    || mIsPanelCollapseOnQQS;
        } else {
            return true;
        }
    }

    @Override
    protected int getMaxPanelHeight() {
        if (mKeyguardBypassController.getBypassEnabled() && mBarState == StatusBarState.KEYGUARD) {
            return getMaxPanelHeightBypass();
        } else {
            return getMaxPanelHeightNonBypass();
        }
    }

    private int getMaxPanelHeightNonBypass() {
        int min = mStatusBarMinHeight;
        if (!(mBarState == StatusBarState.KEYGUARD)
                && mNotificationStackScroller.getNotGoneChildCount() == 0) {
            int minHeight = (int) (mQsMinExpansionHeight + getOverExpansionAmount());
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
        if (maxHeight == 0) {
            Log.wtf(TAG, "maxPanelHeight is 0. getOverExpansionAmount(): "
                    + getOverExpansionAmount() + ", calculatePanelHeightQsExpanded: "
                    + calculatePanelHeightQsExpanded() + ", calculatePanelHeightShade: "
                    + calculatePanelHeightShade() + ", mStatusBarMinHeight = "
                    + mStatusBarMinHeight + ", mQsMinExpansionHeight = " + mQsMinExpansionHeight);
        }
        return maxHeight;
    }

    private int getMaxPanelHeightBypass() {
        int position =
                mClockPositionAlgorithm.getExpandedClockPosition()
                        + mKeyguardStatusView.getHeight();
        if (mNotificationStackScroller.getVisibleNotificationCount() != 0) {
            position += mShelfHeight / 2.0f + mDarkIconSize / 2.0f;
        }
        return position;
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
                        mNotificationStackScroller.getIntrinsicPadding()
                                + mNotificationStackScroller.getLayoutMinHeight();
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
            mStatusBar.setPanelExpanded(isExpanded);
            mPanelExpanded = isExpanded;
        }
    }

    private int calculatePanelHeightShade() {
        int emptyBottomMargin = mNotificationStackScroller.getEmptyBottomMargin();
        int maxHeight = mNotificationStackScroller.getHeight() - emptyBottomMargin;
        maxHeight += mNotificationStackScroller.getTopPaddingOverflow();

        if (mBarState == StatusBarState.KEYGUARD) {
            int
                    minKeyguardPanelBottom =
                    mClockPositionAlgorithm.getExpandedClockPosition()
                            + mKeyguardStatusView.getHeight()
                            + mNotificationStackScroller.getIntrinsicContentHeight();
            return Math.max(maxHeight, minKeyguardPanelBottom);
        } else {
            return maxHeight;
        }
    }

    private int calculatePanelHeightQsExpanded() {
        float
                notificationHeight =
                mNotificationStackScroller.getHeight()
                        - mNotificationStackScroller.getEmptyBottomMargin()
                        - mNotificationStackScroller.getTopPadding();

        // When only empty shade view is visible in QS collapsed state, simulate that we would have
        // it in expanded QS state as well so we don't run into troubles when fading the view in/out
        // and expanding/collapsing the whole panel from/to quick settings.
        if (mNotificationStackScroller.getNotGoneChildCount() == 0 && mShowEmptyShadeView) {
            notificationHeight = mNotificationStackScroller.getEmptyShadeViewHeight();
        }
        int maxQsHeight = mQsMaxExpansionHeight;

        if (mKeyguardShowing) {
            maxQsHeight += mQsNotificationTopPadding;
        }

        // If an animation is changing the size of the QS panel, take the animated value.
        if (mQsSizeChangeAnimator != null) {
            maxQsHeight = (int) mQsSizeChangeAnimator.getAnimatedValue();
        }
        float totalHeight = Math.max(maxQsHeight,
                mBarState == StatusBarState.KEYGUARD ? mClockPositionResult.stackScrollerPadding
                        : 0) + notificationHeight
                + mNotificationStackScroller.getTopPaddingOverflow();
        if (totalHeight > mNotificationStackScroller.getHeight()) {
            float
                    fullyCollapsedHeight =
                    maxQsHeight + mNotificationStackScroller.getLayoutMinHeight();
            totalHeight = Math.max(fullyCollapsedHeight, mNotificationStackScroller.getHeight());
        }
        return (int) totalHeight;
    }

    private void updateNotificationTranslucency() {
        float alpha = 1f;
        if (mClosingWithAlphaFadeOut && !mExpandingFromHeadsUp
                && !mHeadsUpManager.hasPinnedHeadsUp()) {
            alpha = getFadeoutAlpha();
        }
        if (mBarState == StatusBarState.KEYGUARD && !mHintAnimationRunning
                && !mKeyguardBypassController.getBypassEnabled()) {
            alpha *= mClockPositionResult.clockAlpha;
        }
        mNotificationStackScroller.setAlpha(alpha);
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

    @Override
    protected float getOverExpansionAmount() {
        float result = mNotificationStackScroller.getCurrentOverScrollAmount(true /* top */);
        if (isNaN(result)) {
            Log.wtf(TAG, "OverExpansionAmount is NaN!");
        }

        return result;
    }

    @Override
    protected float getOverExpansionPixels() {
        return mNotificationStackScroller.getCurrentOverScrolledPixels(true /* top */);
    }

    /**
     * Hides the header when notifications are colliding with it.
     */
    private void updateHeader() {
        if (mBarState == StatusBarState.KEYGUARD) {
            updateHeaderKeyguardAlpha();
        }
        updateQsExpansion();
    }

    protected float getHeaderTranslation() {
        if (mBarState == StatusBarState.KEYGUARD && !mKeyguardBypassController.getBypassEnabled()) {
            return -mQs.getQsMinExpansionHeight();
        }
        float appearAmount = mNotificationStackScroller.calculateAppearFraction(mExpandedHeight);
        float startHeight = -mQsExpansionHeight;
        if (mKeyguardBypassController.getBypassEnabled() && isOnKeyguard()
                && mNotificationStackScroller.isPulseExpanding()) {
            if (!mPulseExpansionHandler.isExpanding()
                    && !mPulseExpansionHandler.getLeavingLockscreen()) {
                // If we aborted the expansion we need to make sure the header doesn't reappear
                // again after the header has animated away
                appearAmount = 0;
            } else {
                appearAmount = mNotificationStackScroller.calculateAppearFractionBypass();
            }
            startHeight = -mQs.getQsMinExpansionHeight();
        }
        float translation = MathUtils.lerp(startHeight, 0, Math.min(1.0f, appearAmount))
                + mExpandOffset;
        return Math.min(0, translation);
    }

    /**
     * @return the alpha to be used to fade out the contents on Keyguard (status bar, bottom area)
     * during swiping up
     */
    private float getKeyguardContentsAlpha() {
        float alpha;
        if (mBarState == StatusBarState.KEYGUARD) {

            // When on Keyguard, we hide the header as soon as we expanded close enough to the
            // header
            alpha =
                    getExpandedHeight() / (mKeyguardStatusBar.getHeight()
                            + mNotificationsHeaderCollideDistance);
        } else {

            // In SHADE_LOCKED, the top card is already really close to the header. Hide it as
            // soon as we start translating the stack.
            alpha = getExpandedHeight() / mKeyguardStatusBar.getHeight();
        }
        alpha = MathUtils.saturate(alpha);
        alpha = (float) Math.pow(alpha, 0.75);
        return alpha;
    }

    private void updateHeaderKeyguardAlpha() {
        if (!mKeyguardShowing) {
            return;
        }
        float alphaQsExpansion = 1 - Math.min(1, getQsExpansionFraction() * 2);
        float newAlpha = Math.min(getKeyguardContentsAlpha(), alphaQsExpansion)
                * mKeyguardStatusBarAnimateAlpha;
        newAlpha *= 1.0f - mKeyguardHeadsUpShowingAmount;
        mKeyguardStatusBar.setAlpha(newAlpha);
        boolean
                hideForBypass =
                mFirstBypassAttempt && mUpdateMonitor.shouldListenForFace()
                        || mDelayShowingKeyguardStatusBar;
        mKeyguardStatusBar.setVisibility(
                newAlpha != 0f && !mDozing && !hideForBypass ? View.VISIBLE : View.INVISIBLE);
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
        float alpha = Math.min(expansionAlpha, 1 - getQsExpansionFraction());
        alpha *= mBottomAreaShadeAlpha;
        mKeyguardBottomArea.setAffordanceAlpha(alpha);
        mKeyguardBottomArea.setImportantForAccessibility(
                alpha == 0f ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        View ambientIndicationContainer = mStatusBar.getAmbientIndicationContainer();
        if (ambientIndicationContainer != null) {
            ambientIndicationContainer.setAlpha(alpha);
        }
    }

    /**
     * Custom clock fades away when user drags up to unlock or pulls down quick settings.
     *
     * Updates alpha of custom clock to match the alpha of the KeyguardBottomArea. See
     * {@link #updateKeyguardBottomAreaAlpha}.
     */
    private void updateBigClockAlpha() {
        float expansionAlpha = MathUtils.map(
                isUnlockHintRunning() ? 0 : KeyguardBouncer.ALPHA_EXPANSION_THRESHOLD, 1f, 0f, 1f,
                getExpandedFraction());
        float alpha = Math.min(expansionAlpha, 1 - getQsExpansionFraction());
        mBigClockContainer.setAlpha(alpha);
    }

    @Override
    protected void onExpandingStarted() {
        super.onExpandingStarted();
        mNotificationStackScroller.onExpansionStarted();
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
        super.onExpandingFinished();
        mNotificationStackScroller.onExpansionStopped();
        mHeadsUpManager.onExpandingFinished();
        mConversationNotificationManager.onNotificationPanelExpandStateChanged(isFullyCollapsed());
        mIsExpanding = false;
        mMediaHierarchyManager.setCollapsingShadeFromQS(false);
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
        mNotificationStackScroller.setShouldShowShelfOnly(false);
        mTwoFingerQsExpandPossible = false;
        notifyListenersTrackingHeadsUp(null);
        mExpandingFromHeadsUp = false;
        setPanelScrimMinFraction(0.0f);
    }

    private void notifyListenersTrackingHeadsUp(ExpandableNotificationRow pickedChild) {
        for (int i = 0; i < mTrackingHeadsUpListeners.size(); i++) {
            Consumer<ExpandableNotificationRow> listener = mTrackingHeadsUpListeners.get(i);
            listener.accept(pickedChild);
        }
    }

    private void setListening(boolean listening) {
        mKeyguardStatusBar.setListening(listening);
        if (mQs == null) return;
        mQs.setListening(listening);
    }

    @Override
    public void expand(boolean animate) {
        super.expand(animate);
        setListening(true);
    }

    @Override
    protected void setOverExpansion(float overExpansion, boolean isPixels) {
        if (mConflictingQsExpansionGesture || mQsExpandImmediate) {
            return;
        }
        if (mBarState != StatusBarState.KEYGUARD) {
            mNotificationStackScroller.setOnHeightChangedListener(null);
            if (isPixels) {
                mNotificationStackScroller.setOverScrolledPixels(overExpansion, true /* onTop */,
                        false /* animate */);
            } else {
                mNotificationStackScroller.setOverScrollAmount(overExpansion, true /* onTop */,
                        false /* animate */);
            }
            mNotificationStackScroller.setOnHeightChangedListener(mOnHeightChangedListener);
        }
    }

    @Override
    protected void onTrackingStarted() {
        mFalsingManager.onTrackingStarted(!mKeyguardStateController.canDismissLockScreen());
        super.onTrackingStarted();
        if (mQsFullyExpanded) {
            mQsExpandImmediate = true;
            mNotificationStackScroller.setShouldShowShelfOnly(true);
        }
        if (mBarState == StatusBarState.KEYGUARD || mBarState == StatusBarState.SHADE_LOCKED) {
            mAffordanceHelper.animateHideLeftRightIcon();
        }
        mNotificationStackScroller.onPanelTrackingStarted();
    }

    @Override
    protected void onTrackingStopped(boolean expand) {
        mFalsingManager.onTrackingStopped();
        super.onTrackingStopped(expand);
        if (expand) {
            mNotificationStackScroller.setOverScrolledPixels(0.0f, true /* onTop */,
                    true /* animate */);
        }
        mNotificationStackScroller.onPanelTrackingStopped();
        if (expand && (mBarState == StatusBarState.KEYGUARD
                || mBarState == StatusBarState.SHADE_LOCKED)) {
            if (!mHintAnimationRunning) {
                mAffordanceHelper.reset(true);
            }
        }
    }

    private void updateMaxHeadsUpTranslation() {
        mNotificationStackScroller.setHeadsUpBoundaries(getHeight(), mNavigationBarBottomHeight);
    }

    @Override
    protected void startUnlockHintAnimation() {
        if (mPowerManager.isPowerSaveMode()) {
            onUnlockHintStarted();
            onUnlockHintFinished();
            return;
        }
        super.startUnlockHintAnimation();
    }

    @Override
    protected void onUnlockHintFinished() {
        super.onUnlockHintFinished();
        mNotificationStackScroller.setUnlockHintRunning(false);
    }

    @Override
    protected void onUnlockHintStarted() {
        super.onUnlockHintStarted();
        mNotificationStackScroller.setUnlockHintRunning(true);
    }

    @Override
    protected float getPeekHeight() {
        if (mNotificationStackScroller.getNotGoneChildCount() > 0) {
            return mNotificationStackScroller.getPeekHeight();
        } else {
            return mQsMinExpansionHeight;
        }
    }

    @Override
    protected boolean shouldExpandToTopOfClearAll(float targetHeight) {
        boolean perform = super.shouldExpandToTopOfClearAll(targetHeight);
        if (!perform) {
            return false;
        }
        // Let's make sure we're not appearing but the animation will end below the appear.
        // Otherwise quick settings would jump at the end of the animation.
        float fraction = mNotificationStackScroller.calculateAppearFraction(targetHeight);
        return fraction >= 1.0f;
    }

    @Override
    protected boolean shouldUseDismissingAnimation() {
        return mBarState != StatusBarState.SHADE && (mKeyguardStateController.canDismissLockScreen()
                || !isTracking());
    }

    @Override
    protected boolean fullyExpandedClearAllVisible() {
        return mNotificationStackScroller.isFooterViewNotGone()
                && mNotificationStackScroller.isScrolledToBottom() && !mQsExpandImmediate;
    }

    @Override
    protected boolean isClearAllVisible() {
        return mNotificationStackScroller.isFooterViewContentVisible();
    }

    @Override
    protected int getClearAllHeightWithPadding() {
        return mNotificationStackScroller.getFooterViewHeightWithPadding();
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

    public void closeQsDetail() {
        mQs.closeDetail();
    }

    public boolean isLaunchTransitionFinished() {
        return mIsLaunchTransitionFinished;
    }

    public boolean isLaunchTransitionRunning() {
        return mIsLaunchTransitionRunning;
    }

    public void setLaunchTransitionEndRunnable(Runnable r) {
        mLaunchAnimationEndRunnable = r;
    }

    private void updateDozingVisibilities(boolean animate) {
        mKeyguardBottomArea.setDozing(mDozing, animate);
        if (!mDozing && animate) {
            animateKeyguardStatusBarIn(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        }
    }

    @Override
    public boolean isDozing() {
        return mDozing;
    }

    public void showEmptyShadeView(boolean emptyShadeViewVisible) {
        mShowEmptyShadeView = emptyShadeViewVisible;
        updateEmptyShadeView();
    }

    private void updateEmptyShadeView() {
        // Hide "No notifications" in QS.
        mNotificationStackScroller.updateEmptyShadeView(mShowEmptyShadeView && !mQsExpanded);
    }

    public void setQsScrimEnabled(boolean qsScrimEnabled) {
        boolean changed = mQsScrimEnabled != qsScrimEnabled;
        mQsScrimEnabled = qsScrimEnabled;
        if (changed) {
            updateQsState();
        }
    }

    public void setKeyguardUserSwitcher(KeyguardUserSwitcher keyguardUserSwitcher) {
        mKeyguardUserSwitcher = keyguardUserSwitcher;
    }

    public void onScreenTurningOn() {
        mKeyguardStatusView.dozeTimeTick();
    }

    @Override
    protected boolean onMiddleClicked() {
        switch (mBarState) {
            case StatusBarState.KEYGUARD:
                if (!mDozingOnDown) {
                    if (mKeyguardBypassController.getBypassEnabled()) {
                        mUpdateMonitor.requestFaceAuth();
                    } else {
                        mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_HINT,
                                0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
                        mLockscreenGestureLogger
                            .log(LockscreenUiEvent.LOCKSCREEN_LOCK_SHOW_HINT);
                        startUnlockHintAnimation();
                    }
                }
                return true;
            case StatusBarState.SHADE_LOCKED:
                if (!mQsExpanded) {
                    mStatusBarStateController.setState(StatusBarState.KEYGUARD);
                }
                return true;
            case StatusBarState.SHADE:

                // This gets called in the middle of the touch handling, where the state is still
                // that we are tracking the panel. Collapse the panel after this is done.
                mView.post(mPostCollapseRunnable);
                return false;
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

    private void updateKeyguardStatusBarForHeadsUp() {
        boolean
                showingKeyguardHeadsUp =
                mKeyguardShowing && mHeadsUpAppearanceController.shouldBeVisible();
        if (mShowingKeyguardHeadsUp != showingKeyguardHeadsUp) {
            mShowingKeyguardHeadsUp = showingKeyguardHeadsUp;
            if (mKeyguardShowing) {
                PropertyAnimator.setProperty(mView, KEYGUARD_HEADS_UP_SHOWING_AMOUNT,
                        showingKeyguardHeadsUp ? 1.0f : 0.0f, KEYGUARD_HUN_PROPERTIES,
                        true /* animate */);
            } else {
                PropertyAnimator.applyImmediately(mView, KEYGUARD_HEADS_UP_SHOWING_AMOUNT, 0.0f);
            }
        }
    }

    private void setKeyguardHeadsUpShowingAmount(float amount) {
        mKeyguardHeadsUpShowingAmount = amount;
        updateHeaderKeyguardAlpha();
    }

    private float getKeyguardHeadsUpShowingAmount() {
        return mKeyguardHeadsUpShowingAmount;
    }

    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mHeadsUpAnimatingAway = headsUpAnimatingAway;
        mNotificationStackScroller.setHeadsUpAnimatingAway(headsUpAnimatingAway);
        updateHeadsUpVisibility();
    }

    private void updateHeadsUpVisibility() {
        ((PhoneStatusBarView) mBar).setHeadsUpVisible(mHeadsUpAnimatingAway || mHeadsUpPinnedMode);
    }

    @Override
    public void setHeadsUpManager(HeadsUpManagerPhone headsUpManager) {
        super.setHeadsUpManager(headsUpManager);
        mHeadsUpTouchHelper = new HeadsUpTouchHelper(headsUpManager,
                mNotificationStackScroller.getHeadsUpCallback(),
                NotificationPanelViewController.this);
    }

    public void setTrackedHeadsUp(ExpandableNotificationRow pickedChild) {
        if (pickedChild != null) {
            notifyListenersTrackingHeadsUp(pickedChild);
            mExpandingFromHeadsUp = true;
        }
        // otherwise we update the state when the expansion is finished
    }

    @Override
    protected void onClosingFinished() {
        super.onClosingFinished();
        resetHorizontalPanelPosition();
        setClosingWithAlphaFadeout(false);
        mMediaHierarchyManager.closeGuts();
    }

    private void setClosingWithAlphaFadeout(boolean closing) {
        mClosingWithAlphaFadeOut = closing;
        mNotificationStackScroller.forceNoOverlappingRendering(closing);
    }

    /**
     * Updates the vertical position of the panel so it is positioned closer to the touch
     * responsible for opening the panel.
     *
     * @param x the x-coordinate the touch event
     */
    protected void updateVerticalPanelPosition(float x) {
        if (mNotificationStackScroller.getWidth() * 1.75f > mView.getWidth()) {
            resetHorizontalPanelPosition();
            return;
        }
        float leftMost = mPositionMinSideMargin + mNotificationStackScroller.getWidth() / 2;
        float
                rightMost =
                mView.getWidth() - mPositionMinSideMargin
                        - mNotificationStackScroller.getWidth() / 2;
        if (Math.abs(x - mView.getWidth() / 2) < mNotificationStackScroller.getWidth() / 4) {
            x = mView.getWidth() / 2;
        }
        x = Math.min(rightMost, Math.max(leftMost, x));
        float
                center =
                mNotificationStackScroller.getLeft() + mNotificationStackScroller.getWidth() / 2;
        setHorizontalPanelTranslation(x - center);
    }

    private void resetHorizontalPanelPosition() {
        setHorizontalPanelTranslation(0f);
    }

    protected void setHorizontalPanelTranslation(float translation) {
        mNotificationStackScroller.setTranslationX(translation);
        mQsFrame.setTranslationX(translation);
        int size = mVerticalTranslationListener.size();
        for (int i = 0; i < size; i++) {
            mVerticalTranslationListener.get(i).run();
        }
    }

    protected void updateExpandedHeight(float expandedHeight) {
        if (mTracking) {
            mNotificationStackScroller.setExpandingVelocity(getCurrentExpandVelocity());
        }
        if (mKeyguardBypassController.getBypassEnabled() && isOnKeyguard()) {
            // The expandedHeight is always the full panel Height when bypassing
            expandedHeight = getMaxPanelHeightNonBypass();
        }
        mNotificationStackScroller.setExpandedHeight(expandedHeight);
        updateKeyguardBottomAreaAlpha();
        updateBigClockAlpha();
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
        return mBarState == StatusBarState.KEYGUARD;
    }

    public void setPanelScrimMinFraction(float minFraction) {
        mBar.panelScrimMinFractionChanged(minFraction);
    }

    public void clearNotificationEffects() {
        mStatusBar.clearNotificationEffects();
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
        if (mAffordanceLaunchListener != null) {
            mAffordanceLaunchListener.accept(launchingAffordance);
        }
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
        if (!mStatusBar.isCameraAllowedByAdmin()) {
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

    private void setGroupManager(NotificationGroupManager groupManager) {
        mGroupManager = groupManager;
    }

    public boolean hideStatusBarIconsWhenExpanded() {
        if (mLaunchingNotification) {
            return mHideIconsDuringNotificationLaunch;
        }
        if (mHeadsUpAppearanceController != null
                && mHeadsUpAppearanceController.shouldBeVisible()) {
            return false;
        }
        return !isFullWidth() || !mShowIconsWhenExpanded;
    }

    private final FragmentListener mFragmentListener = new FragmentListener() {
        @Override
        public void onFragmentViewCreated(String tag, Fragment fragment) {
            mQs = (QS) fragment;
            mQs.setPanelView(mHeightListener);
            mQs.setExpandClickListener(mOnClickListener);
            mQs.setHeaderClickable(mQsExpansionEnabled);
            updateQSPulseExpansion();
            mQs.setOverscrolling(mStackScrollerOverscrolling);

            // recompute internal state when qspanel height changes
            mQs.getView().addOnLayoutChangeListener(
                    (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                        final int height = bottom - top;
                        final int oldHeight = oldBottom - oldTop;
                        if (height != oldHeight) {
                            mHeightListener.onQsHeightChanged();
                        }
                    });
            mNotificationStackScroller.setQsContainer((ViewGroup) mQs.getView());
            if (mQs instanceof QSFragment) {
                mKeyguardStatusBar.setQSPanel(((QSFragment) mQs).getQsPanel());
            }
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

    @Override
    public void setTouchAndAnimationDisabled(boolean disabled) {
        super.setTouchAndAnimationDisabled(disabled);
        if (disabled && mAffordanceHelper.isSwipingInProgress() && !mIsLaunchTransitionRunning) {
            mAffordanceHelper.reset(false /* animate */);
        }
        mNotificationStackScroller.setAnimationsEnabled(!disabled);
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
        mNotificationStackScroller.setDozing(mDozing, animate, wakeUpTouchLocation);
        mKeyguardBottomArea.setDozing(mDozing, animate);

        if (dozing) {
            mBottomAreaShadeAlphaAnimator.cancel();
        }

        if (mBarState == StatusBarState.KEYGUARD || mBarState == StatusBarState.SHADE_LOCKED) {
            updateDozingVisibilities(animate);
        }

        final float dozeAmount = dozing ? 1 : 0;
        mStatusBarStateController.setDozeAmount(dozeAmount, animate);
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
        mNotificationStackScroller.setPulsing(pulsing, animatePulse);
        mKeyguardStatusView.setPulsing(pulsing);
    }

    public void setAmbientIndicationBottomPadding(int ambientIndicationBottomPadding) {
        if (mAmbientIndicationBottomPadding != ambientIndicationBottomPadding) {
            mAmbientIndicationBottomPadding = ambientIndicationBottomPadding;
            mStatusBar.updateKeyguardMaxNotifications();
        }
    }

    public void dozeTimeTick() {
        mKeyguardBottomArea.dozeTimeTick();
        mKeyguardStatusView.dozeTimeTick();
        if (mInterpolatedDarkAmount > 0) {
            positionClockAndNotifications();
        }
    }

    public void setStatusAccessibilityImportance(int mode) {
        mKeyguardStatusView.setImportantForAccessibility(mode);
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

    public void applyExpandAnimationParams(ExpandAnimationParameters params) {
        mExpandOffset = params != null ? params.getTopChange() : 0;
        updateQsExpansion();
        if (params != null) {
            boolean hideIcons = params.getProgress(
                    ActivityLaunchAnimator.ANIMATION_DELAY_ICON_FADE_IN, 100) == 0.0f;
            if (hideIcons != mHideIconsDuringNotificationLaunch) {
                mHideIconsDuringNotificationLaunch = hideIcons;
                if (!hideIcons) {
                    mCommandQueue.recomputeDisableFlags(mDisplayId, true /* animate */);
                }
            }
        }
    }

    public void addTrackingHeadsUpListener(Consumer<ExpandableNotificationRow> listener) {
        mTrackingHeadsUpListeners.add(listener);
    }

    public void removeTrackingHeadsUpListener(Consumer<ExpandableNotificationRow> listener) {
        mTrackingHeadsUpListeners.remove(listener);
    }

    public void addVerticalTranslationListener(Runnable verticalTranslationListener) {
        mVerticalTranslationListener.add(verticalTranslationListener);
    }

    public void removeVerticalTranslationListener(Runnable verticalTranslationListener) {
        mVerticalTranslationListener.remove(verticalTranslationListener);
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
        setKeyguardStatusViewVisibility(mBarState, true /* keyguardFadingAway */,
                false /* goingToFullShade */);
    }

    /**
     * Do not let the user drag the shade up and down for the current touch session.
     * This is necessary to avoid shade expansion while/after the bouncer is dismissed.
     */
    public void blockExpansionForCurrentTouch() {
        mBlockingExpansionForCurrentTouch = mTracking;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println("    gestureExclusionRect: " + calculateGestureExclusionRect());
        if (mKeyguardStatusBar != null) {
            mKeyguardStatusBar.dump(fd, pw, args);
        }
        if (mKeyguardStatusView != null) {
            mKeyguardStatusView.dump(fd, pw, args);
        }
    }

    public boolean hasActiveClearableNotifications() {
        return mNotificationStackScroller.hasActiveClearableNotifications(ROWS_ALL);
    }

    private void updateShowEmptyShadeView() {
        boolean
                showEmptyShadeView =
                mBarState != StatusBarState.KEYGUARD && !mEntryManager.hasVisibleNotifications();
        showEmptyShadeView(showEmptyShadeView);
    }

    public RemoteInputController.Delegate createRemoteInputDelegate() {
        return mNotificationStackScroller.createDelegate();
    }

    void updateNotificationViews(String reason) {
        mNotificationStackScroller.updateSectionBoundaries(reason);
        mNotificationStackScroller.updateSpeedBumpIndex();
        mNotificationStackScroller.updateFooter();
        updateShowEmptyShadeView();
        mNotificationStackScroller.updateIconAreaViews();
    }

    public void onUpdateRowStates() {
        mNotificationStackScroller.onUpdateRowStates();
    }

    public boolean hasPulsingNotifications() {
        return mNotificationStackScroller.hasPulsingNotifications();
    }

    public ActivatableNotificationView getActivatedChild() {
        return mNotificationStackScroller.getActivatedChild();
    }

    public void setActivatedChild(ActivatableNotificationView o) {
        mNotificationStackScroller.setActivatedChild(o);
    }

    public void runAfterAnimationFinished(Runnable r) {
        mNotificationStackScroller.runAfterAnimationFinished(r);
    }

    public void setScrollingEnabled(boolean b) {
        mNotificationStackScroller.setScrollingEnabled(b);
    }

    public void initDependencies(StatusBar statusBar, NotificationGroupManager groupManager,
            NotificationShelf notificationShelf,
            NotificationIconAreaController notificationIconAreaController,
            ScrimController scrimController) {
        setStatusBar(statusBar);
        setGroupManager(mGroupManager);
        mNotificationStackScroller.setNotificationPanelController(this);
        mNotificationStackScroller.setIconAreaController(notificationIconAreaController);
        mNotificationStackScroller.setStatusBar(statusBar);
        mNotificationStackScroller.setGroupManager(groupManager);
        mNotificationStackScroller.setShelf(notificationShelf);
        mNotificationStackScroller.setScrimController(scrimController);
        updateShowEmptyShadeView();
    }

    public void showTransientIndication(int id) {
        mKeyguardIndicationController.showTransientIndication(id);
    }

    public void setOnReinflationListener(Runnable onReinflationListener) {
        mOnReinflationListener = onReinflationListener;
    }

    public void setAlpha(float alpha) {
        mView.setAlpha(alpha);
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

    public TextView getHeaderDebugInfo() {
        return mView.findViewById(R.id.header_debug_info);
    }

    public void onThemeChanged() {
        mConfigurationListener.onThemeChanged();
    }

    @Override
    public OnLayoutChangeListener createLayoutChangeListener() {
        return new OnLayoutChangeListener();
    }

    public void setEmptyDragAmount(float amount) {
        mExpansionCallback.setEmptyDragAmount(amount);
    }

    @Override
    protected TouchHandler createTouchHandler() {
        return new TouchHandler() {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
                if (mBlockTouches || mQsFullyExpanded && mQs.disallowPanelTouches()) {
                    return false;
                }
                initDownStates(event);
                // Do not let touches go to shade or QS if the bouncer is visible,
                // but still let user swipe down to expand the panel, dismissing the bouncer.
                if (mStatusBar.isBouncerShowing()) {
                    return true;
                }
                if (mBar.panelEnabled() && mHeadsUpTouchHelper.onInterceptTouchEvent(event)) {
                    mMetricsLogger.count(COUNTER_PANEL_OPEN, 1);
                    mMetricsLogger.count(COUNTER_PANEL_OPEN_PEEK, 1);
                    return true;
                }
                if (!shouldQuickSettingsIntercept(mDownX, mDownY, 0)
                        && mPulseExpansionHandler.onInterceptTouchEvent(event)) {
                    return true;
                }

                if (!isFullyCollapsed() && onQsIntercept(event)) {
                    return true;
                }
                return super.onInterceptTouchEvent(event);
            }

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (mBlockTouches || (mQsFullyExpanded && mQs != null
                        && mQs.disallowPanelTouches())) {
                    return false;
                }

                // Do not allow panel expansion if bouncer is scrimmed, otherwise user would be able
                // to pull down QS or expand the shade.
                if (mStatusBar.isBouncerShowingScrimmed()) {
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
                if (!mIsExpanding && !shouldQuickSettingsIntercept(mDownX, mDownY, 0)
                        && mPulseExpansionHandler.onTouchEvent(event)) {
                    // We're expanding all the other ones shouldn't get this anymore
                    return true;
                }
                if (mListenForHeadsUp && !mHeadsUpTouchHelper.isTrackingHeadsUp()
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
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isFullyCollapsed()) {
                    mMetricsLogger.count(COUNTER_PANEL_OPEN, 1);
                    updateVerticalPanelPosition(event.getX());
                    handled = true;
                }
                handled |= super.onTouch(v, event);
                return !mDozing || mPulsing || handled;
            }
        };
    }

    @Override
    protected PanelViewController.OnConfigurationChangedListener
            createOnConfigurationChangedListener() {
        return new OnConfigurationChangedListener();
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
            ExpandableView firstChildNotGone = mNotificationStackScroller.getFirstChildNotGone();
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

    private class OnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            onQsExpansionStarted();
            if (mQsExpanded) {
                flingSettings(0 /* vel */, FLING_COLLAPSE, null /* onFinishRunnable */,
                        true /* isClick */);
            } else if (mQsExpansionEnabled) {
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
            cancelQsAnimation();
            if (!mQsExpansionEnabled) {
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
            mLastOverscroll = 0f;
            mQsExpansionFromOverscroll = false;
            setQsExpansion(mQsExpansionHeight);
            flingSettings(!mQsExpansionEnabled && open ? 0f : velocity,
                    open && mQsExpansionEnabled ? FLING_EXPAND : FLING_COLLAPSE, () -> {
                        mStackScrollerOverscrolling = false;
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
            float displayDensity = mStatusBar.getDisplayDensity();
            int lengthDp = Math.abs((int) (translation / displayDensity));
            int velocityDp = Math.abs((int) (vel / displayDensity));
            if (start) {
                mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_DIALER, lengthDp, velocityDp);
                mLockscreenGestureLogger.log(LockscreenUiEvent.LOCKSCREEN_DIALER);
                mFalsingManager.onLeftAffordanceOn();
                if (mFalsingManager.shouldEnforceBouncer()) {
                    mStatusBar.executeRunnableDismissingKeyguard(
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
                mFalsingManager.onCameraOn();
                if (mFalsingManager.shouldEnforceBouncer()) {
                    mStatusBar.executeRunnableDismissingKeyguard(
                            () -> mKeyguardBottomArea.launchCamera(mLastCameraLaunchSource), null,
                            true /* dismissShade */, false /* afterKeyguardGone */,
                            true /* deferred */);
                } else {
                    mKeyguardBottomArea.launchCamera(mLastCameraLaunchSource);
                }
            }
            mStatusBar.startLaunchTransitionTimeout();
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
            mStatusBar.readyForKeyguardDone();
        }

        @Override
        public float getMaxTranslationDistance() {
            return (float) Math.hypot(mView.getWidth(), getHeight());
        }

        @Override
        public void onSwipingStarted(boolean rightIcon) {
            mFalsingManager.onAffordanceSwipingStarted(rightIcon);
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
            mFalsingManager.onAffordanceSwipingAborted();
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
                mStatusBar.onHintFinished();
            });
            rightIcon =
                    mView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? !rightIcon
                            : rightIcon;
            if (rightIcon) {
                mStatusBar.onCameraHintStarted();
            } else {
                if (mKeyguardBottomArea.isLeftVoiceAssist()) {
                    mStatusBar.onVoiceAssistHintStarted();
                } else {
                    mStatusBar.onPhoneHintStarted();
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
            return mStatusBar.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
        }

        @Override
        public boolean needsAntiFalsing() {
            return mBarState == StatusBarState.KEYGUARD;
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
            mNotificationStackScroller.setInHeadsUpPinnedMode(inPinnedMode);
            if (inPinnedMode) {
                mHeadsUpExistenceChangedRunnable.run();
                updateNotificationTranslucency();
            } else {
                setHeadsUpAnimatingAway(true);
                mNotificationStackScroller.runAfterAnimationFinished(
                        mHeadsUpExistenceChangedRunnable);
            }
            updateGestureExclusionRect();
            mHeadsUpPinnedMode = inPinnedMode;
            updateHeadsUpVisibility();
            updateKeyguardStatusBarForHeadsUp();
        }

        @Override
        public void onHeadsUpPinned(NotificationEntry entry) {
            if (!isOnKeyguard()) {
                mNotificationStackScroller.generateHeadsUpAnimation(entry.getHeadsUpAnimationView(),
                        true);
            }
        }

        @Override
        public void onHeadsUpUnPinned(NotificationEntry entry) {

            // When we're unpinning the notification via active edge they remain heads-upped,
            // we need to make sure that an animation happens in this case, otherwise the
            // notification
            // will stick to the top without any interaction.
            if (isFullyCollapsed() && entry.isRowHeadsUp() && !isOnKeyguard()) {
                mNotificationStackScroller.generateHeadsUpAnimation(
                        entry.getHeadsUpAnimationView(), false);
                entry.setHeadsUpIsVisible();
            }
        }

        @Override
        public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
            mNotificationStackScroller.generateHeadsUpAnimation(entry, isHeadsUp);
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
            mNotificationStackScroller.setMaxTopPadding(
                    mQsMaxExpansionHeight + mQsNotificationTopPadding);
        }
    }

    private class ZenModeControllerCallback implements ZenModeController.Callback {
        @Override
        public void onZenChanged(int zen) {
            updateShowEmptyShadeView();
        }
    }

    private class ConfigurationListener implements ConfigurationController.ConfigurationListener {
        @Override
        public void onDensityOrFontScaleChanged() {
            updateShowEmptyShadeView();
        }

        @Override
        public void onThemeChanged() {
            final int themeResId = mView.getContext().getThemeResId();
            if (mThemeResId == themeResId) {
                return;
            }
            mThemeResId = themeResId;

            reInflateViews();
        }

        @Override
        public void onOverlayChanged() {
            reInflateViews();
        }

        @Override
        public void onUiModeChanged() {}
    }

    private class StatusBarStateListener implements StateListener {
        @Override
        public void onStateChanged(int statusBarState) {
            boolean goingToFullShade = mStatusBarStateController.goingToFullShade();
            boolean keyguardFadingAway = mKeyguardStateController.isKeyguardFadingAway();
            int oldState = mBarState;
            boolean keyguardShowing = statusBarState == StatusBarState.KEYGUARD;
            setKeyguardStatusViewVisibility(statusBarState, keyguardFadingAway, goingToFullShade);
            setKeyguardBottomAreaVisibility(statusBarState, goingToFullShade);

            mBarState = statusBarState;
            mKeyguardShowing = keyguardShowing;

            if (oldState == StatusBarState.KEYGUARD && (goingToFullShade
                    || statusBarState == StatusBarState.SHADE_LOCKED)) {
                animateKeyguardStatusBarOut();
                long
                        delay =
                        mBarState == StatusBarState.SHADE_LOCKED ? 0
                                : mKeyguardStateController.calculateGoingToFullShadeDelay();
                mQs.animateHeaderSlidingIn(delay);
            } else if (oldState == StatusBarState.SHADE_LOCKED
                    && statusBarState == StatusBarState.KEYGUARD) {
                animateKeyguardStatusBarIn(StackStateAnimator.ANIMATION_DURATION_STANDARD);
                mNotificationStackScroller.resetScrollPosition();
                // Only animate header if the header is visible. If not, it will partially
                // animate out
                // the top of QS
                if (!mQsExpanded) {
                    mQs.animateHeaderSlidingOut();
                }
            } else {
                mKeyguardStatusBar.setAlpha(1f);
                mKeyguardStatusBar.setVisibility(keyguardShowing ? View.VISIBLE : View.INVISIBLE);
                if (keyguardShowing && oldState != mBarState) {
                    if (mQs != null) {
                        mQs.hideImmediately();
                    }
                }
            }
            updateKeyguardStatusBarForHeadsUp();
            if (keyguardShowing) {
                updateDozingVisibilities(false /* animate */);
            }
            // THe update needs to happen after the headerSlide in above, otherwise the translation
            // would reset
            updateQSPulseExpansion();
            maybeAnimateBottomAreaAlpha();
            resetHorizontalPanelPosition();
            updateQsState();
        }

        @Override
        public void onDozeAmountChanged(float linearAmount, float amount) {
            mInterpolatedDarkAmount = amount;
            mLinearDarkAmount = linearAmount;
            mKeyguardStatusView.setDarkAmount(mInterpolatedDarkAmount);
            mKeyguardBottomArea.setDarkAmount(mInterpolatedDarkAmount);
            positionClockAndNotifications();
        }
    }

    private class ExpansionCallback implements PulseExpansionHandler.ExpansionCallback {
        public void setEmptyDragAmount(float amount) {
            mEmptyDragAmount = amount * 0.2f;
            positionClockAndNotifications();
        }
    }

    private class OnAttachStateChangeListener implements View.OnAttachStateChangeListener {
        @Override
        public void onViewAttachedToWindow(View v) {
            FragmentHostManager.get(mView).addTagListener(QS.TAG, mFragmentListener);
            mStatusBarStateController.addCallback(mStatusBarStateListener);
            mZenModeController.addCallback(mZenModeControllerCallback);
            mConfigurationController.addCallback(mConfigurationListener);
            mUpdateMonitor.registerCallback(mKeyguardUpdateCallback);
            // Theme might have changed between inflating this view and attaching it to the
            // window, so
            // force a call to onThemeChanged
            mConfigurationListener.onThemeChanged();
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            FragmentHostManager.get(mView).removeTagListener(QS.TAG, mFragmentListener);
            mStatusBarStateController.removeCallback(mStatusBarStateListener);
            mZenModeController.removeCallback(mZenModeControllerCallback);
            mConfigurationController.removeCallback(mConfigurationListener);
            mUpdateMonitor.removeCallback(mKeyguardUpdateCallback);
        }
    }

    private class OnLayoutChangeListener extends PanelViewController.OnLayoutChangeListener {

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                int oldTop, int oldRight, int oldBottom) {
            DejankUtils.startDetectingBlockingIpcs("NVP#onLayout");
            super.onLayoutChange(v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom);
            setIsFullWidth(mNotificationStackScroller.getWidth() == mView.getWidth());

            // Update Clock Pivot
            mKeyguardStatusView.setPivotX(mView.getWidth() / 2);
            mKeyguardStatusView.setPivotY(
                    (FONT_HEIGHT - CAP_HEIGHT) / 2048f * mKeyguardStatusView.getClockTextSize());

            // Calculate quick setting heights.
            int oldMaxHeight = mQsMaxExpansionHeight;
            if (mQs != null) {
                float previousMin = mQsMinExpansionHeight;
                mQsMinExpansionHeight = mKeyguardShowing ? 0 : mQs.getQsMinExpansionHeight();
                if (mQsExpansionHeight == previousMin) {
                    mQsExpansionHeight = mQsMinExpansionHeight;
                }
                mQsMaxExpansionHeight = mQs.getDesiredHeight();
                mNotificationStackScroller.setMaxTopPadding(
                        mQsMaxExpansionHeight + mQsNotificationTopPadding);
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
            } else if (!mQsExpanded) {
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

    private class DebugDrawable extends Drawable {

        @Override
        public void draw(Canvas canvas) {
            Paint p = new Paint();
            p.setColor(Color.RED);
            p.setStrokeWidth(2);
            p.setStyle(Paint.Style.STROKE);
            canvas.drawLine(0, getMaxPanelHeight(), mView.getWidth(), getMaxPanelHeight(), p);
            p.setColor(Color.BLUE);
            canvas.drawLine(0, getExpandedHeight(), mView.getWidth(), getExpandedHeight(), p);
            p.setColor(Color.GREEN);
            canvas.drawLine(0, calculatePanelHeightQsExpanded(), mView.getWidth(),
                    calculatePanelHeightQsExpanded(), p);
            p.setColor(Color.YELLOW);
            canvas.drawLine(0, calculatePanelHeightShade(), mView.getWidth(),
                    calculatePanelHeightShade(), p);
            p.setColor(Color.MAGENTA);
            canvas.drawLine(
                    0, calculateQsTopPadding(), mView.getWidth(), calculateQsTopPadding(), p);
            p.setColor(Color.CYAN);
            canvas.drawLine(0, mClockPositionResult.stackScrollerPadding, mView.getWidth(),
                    mNotificationStackScroller.getTopPadding(), p);
            p.setColor(Color.GRAY);
            canvas.drawLine(0, mClockPositionResult.clockY, mView.getWidth(),
                    mClockPositionResult.clockY, p);
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
            if (newConfig.orientation != mLastOrientation) {
                resetHorizontalPanelPosition();
            }
            mLastOrientation = newConfig.orientation;
        }
    }

    private class OnApplyWindowInsetsListener implements View.OnApplyWindowInsetsListener {
        public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            mNavigationBarBottomHeight = insets.getStableInsetBottom();
            updateMaxHeadsUpTranslation();
            return insets;
        }
    }
}
