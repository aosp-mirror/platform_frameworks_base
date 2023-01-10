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
 * limitations under the License.
 */

package com.android.systemui.shade;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.keyguard.FaceAuthApiRequestReason.NOTIFICATION_PANEL_CLICKED;
import static com.android.keyguard.KeyguardClockSwitch.LARGE;
import static com.android.keyguard.KeyguardClockSwitch.SMALL;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_CLOSED;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_OPEN;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_OPENING;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.IdRes;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewPropertyAnimator;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.constraintlayout.widget.ConstraintSet;
import androidx.test.filters.SmallTest;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.FaceAuthApiRequestReason;
import com.android.keyguard.KeyguardClockSwitch;
import com.android.keyguard.KeyguardClockSwitchController;
import com.android.keyguard.KeyguardStatusView;
import com.android.keyguard.KeyguardStatusViewController;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.LockIconViewController;
import com.android.keyguard.dagger.KeyguardQsUserSwitchComponent;
import com.android.keyguard.dagger.KeyguardStatusBarViewComponent;
import com.android.keyguard.dagger.KeyguardStatusViewComponent;
import com.android.keyguard.dagger.KeyguardUserSwitcherComponent;
import com.android.systemui.DejankUtils;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.domain.interactor.KeyguardBottomAreaInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBottomAreaViewModel;
import com.android.systemui.keyguard.ui.viewmodel.OccludedToLockscreenTransitionViewModel;
import com.android.systemui.media.controls.pipeline.MediaDataManager;
import com.android.systemui.media.controls.ui.KeyguardMediaController;
import com.android.systemui.media.controls.ui.MediaHierarchyManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.shade.transition.ShadeTransitionController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.QsFrameTranslateController;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.ConversationNotificationManager;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinatorLogger;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.ExpandableView.OnHeightChangedListener;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationRoundnessManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.HeadsUpTouchHelper;
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView;
import com.android.systemui.statusbar.phone.KeyguardBottomAreaViewController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.KeyguardStatusBarViewController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarTouchableRegionManager;
import com.android.systemui.statusbar.phone.TapAgainViewController;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardQsUserSwitchController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcherController;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcherView;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.unfold.SysUIUnfoldComponent;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.time.SystemClock;
import com.android.wm.shell.animation.FlingAnimationUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.Optional;

import kotlinx.coroutines.CoroutineDispatcher;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationPanelViewControllerTest extends SysuiTestCase {

    private static final int SPLIT_SHADE_FULL_TRANSITION_DISTANCE = 400;
    private static final int NOTIFICATION_SCRIM_TOP_PADDING_IN_SPLIT_SHADE = 50;
    private static final int PANEL_WIDTH = 500; // Random value just for the test.

    @Mock private CentralSurfaces mCentralSurfaces;
    @Mock private NotificationStackScrollLayout mNotificationStackScrollLayout;
    @Mock private KeyguardBottomAreaView mKeyguardBottomArea;
    @Mock private KeyguardBottomAreaViewController mKeyguardBottomAreaViewController;
    @Mock private KeyguardBottomAreaView mQsFrame;
    @Mock private HeadsUpManagerPhone mHeadsUpManager;
    @Mock private NotificationShelfController mNotificationShelfController;
    @Mock private NotificationGutsManager mGutsManager;
    @Mock private KeyguardStatusBarView mKeyguardStatusBar;
    @Mock private KeyguardUserSwitcherView mUserSwitcherView;
    @Mock private ViewStub mUserSwitcherStubView;
    @Mock private HeadsUpTouchHelper.Callback mHeadsUpCallback;
    @Mock private KeyguardUpdateMonitor mUpdateMonitor;
    @Mock private KeyguardBypassController mKeyguardBypassController;
    @Mock private DozeParameters mDozeParameters;
    @Mock private ScreenOffAnimationController mScreenOffAnimationController;
    @Mock private NotificationPanelView mView;
    @Mock private LayoutInflater mLayoutInflater;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private DynamicPrivacyController mDynamicPrivacyController;
    @Mock private StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private DozeLog mDozeLog;
    @Mock private ShadeLogger mShadeLog;
    @Mock private ShadeHeightLogger mShadeHeightLogger;
    @Mock private CommandQueue mCommandQueue;
    @Mock private VibratorHelper mVibratorHelper;
    @Mock private LatencyTracker mLatencyTracker;
    @Mock private PowerManager mPowerManager;
    @Mock private AccessibilityManager mAccessibilityManager;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private Resources mResources;
    @Mock private Configuration mConfiguration;
    @Mock private KeyguardClockSwitch mKeyguardClockSwitch;
    @Mock private MediaHierarchyManager mMediaHierarchyManager;
    @Mock private ConversationNotificationManager mConversationNotificationManager;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private KeyguardStatusViewComponent.Factory mKeyguardStatusViewComponentFactory;
    @Mock private KeyguardQsUserSwitchComponent.Factory mKeyguardQsUserSwitchComponentFactory;
    @Mock private KeyguardQsUserSwitchComponent mKeyguardQsUserSwitchComponent;
    @Mock private KeyguardQsUserSwitchController mKeyguardQsUserSwitchController;
    @Mock private KeyguardUserSwitcherComponent.Factory mKeyguardUserSwitcherComponentFactory;
    @Mock private KeyguardUserSwitcherComponent mKeyguardUserSwitcherComponent;
    @Mock private KeyguardUserSwitcherController mKeyguardUserSwitcherController;
    @Mock private KeyguardStatusViewComponent mKeyguardStatusViewComponent;
    @Mock private KeyguardStatusBarViewComponent.Factory mKeyguardStatusBarViewComponentFactory;
    @Mock private KeyguardStatusBarViewComponent mKeyguardStatusBarViewComponent;
    @Mock private KeyguardClockSwitchController mKeyguardClockSwitchController;
    @Mock private KeyguardStatusViewController mKeyguardStatusViewController;
    @Mock private KeyguardStatusBarViewController mKeyguardStatusBarViewController;
    @Mock private NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    @Mock private NotificationShadeDepthController mNotificationShadeDepthController;
    @Mock private LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock private AuthController mAuthController;
    @Mock private ScrimController mScrimController;
    @Mock private MediaDataManager mMediaDataManager;
    @Mock private AmbientState mAmbientState;
    @Mock private UserManager mUserManager;
    @Mock private UiEventLogger mUiEventLogger;
    @Mock private LockIconViewController mLockIconViewController;
    @Mock private KeyguardMediaController mKeyguardMediaController;
    @Mock private NavigationModeController mNavigationModeController;
    @Mock private NavigationBarController mNavigationBarController;
    @Mock private LargeScreenShadeHeaderController mLargeScreenShadeHeaderController;
    @Mock private ContentResolver mContentResolver;
    @Mock private TapAgainViewController mTapAgainViewController;
    @Mock private KeyguardIndicationController mKeyguardIndicationController;
    @Mock private FragmentService mFragmentService;
    @Mock private FragmentHostManager mFragmentHostManager;
    @Mock private NotificationRemoteInputManager mNotificationRemoteInputManager;
    @Mock private RecordingController mRecordingController;
    @Mock private LockscreenGestureLogger mLockscreenGestureLogger;
    @Mock private DumpManager mDumpManager;
    @Mock private InteractionJankMonitor mInteractionJankMonitor;
    @Mock private NotificationsQSContainerController mNotificationsQSContainerController;
    @Mock private QsFrameTranslateController mQsFrameTranslateController;
    @Mock private StatusBarWindowStateController mStatusBarWindowStateController;
    @Mock private KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    @Mock private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock private SysUiState mSysUiState;
    @Mock private NotificationListContainer mNotificationListContainer;
    @Mock private NotificationStackSizeCalculator mNotificationStackSizeCalculator;
    @Mock private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    @Mock private ShadeTransitionController mShadeTransitionController;
    @Mock private QS mQs;
    @Mock private QSFragment mQSFragment;
    @Mock private ViewGroup mQsHeader;
    @Mock private ViewParent mViewParent;
    @Mock private ViewTreeObserver mViewTreeObserver;
    @Mock private KeyguardBottomAreaViewModel mKeyguardBottomAreaViewModel;
    @Mock private KeyguardBottomAreaInteractor mKeyguardBottomAreaInteractor;
    @Mock private DreamingToLockscreenTransitionViewModel mDreamingToLockscreenTransitionViewModel;
    @Mock private OccludedToLockscreenTransitionViewModel mOccludedToLockscreenTransitionViewModel;
    @Mock private KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    @Mock private CoroutineDispatcher mMainDispatcher;
    @Mock private MotionEvent mDownMotionEvent;
    @Captor
    private ArgumentCaptor<NotificationStackScrollLayout.OnEmptySpaceClickListener>
            mEmptySpaceClickListenerCaptor;

    private NotificationPanelViewController.TouchHandler mTouchHandler;
    private ConfigurationController mConfigurationController;
    private SysuiStatusBarStateController mStatusBarStateController;
    private NotificationPanelViewController mNotificationPanelViewController;
    private View.AccessibilityDelegate mAccessibilityDelegate;
    private NotificationsQuickSettingsContainer mNotificationContainerParent;
    private List<View.OnAttachStateChangeListener> mOnAttachStateChangeListeners;
    private Handler mMainHandler;
    private View.OnLayoutChangeListener mLayoutChangeListener;

    private final FalsingManagerFake mFalsingManager = new FalsingManagerFake();
    private final Optional<SysUIUnfoldComponent> mSysUIUnfoldComponent = Optional.empty();
    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    private final ShadeExpansionStateManager mShadeExpansionStateManager =
            new ShadeExpansionStateManager();
    private FragmentHostManager.FragmentListener mFragmentListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        SystemClock systemClock = new FakeSystemClock();
        mStatusBarStateController = new StatusBarStateControllerImpl(mUiEventLogger, mDumpManager,
                mInteractionJankMonitor, mShadeExpansionStateManager);

        KeyguardStatusView keyguardStatusView = new KeyguardStatusView(mContext);
        keyguardStatusView.setId(R.id.keyguard_status_view);
        DejankUtils.setImmediate(true);

        when(mAuthController.isUdfpsEnrolled(anyInt())).thenReturn(false);
        when(mHeadsUpCallback.getContext()).thenReturn(mContext);
        when(mView.getResources()).thenReturn(mResources);
        when(mView.getWidth()).thenReturn(PANEL_WIDTH);
        when(mResources.getConfiguration()).thenReturn(mConfiguration);
        mConfiguration.orientation = ORIENTATION_PORTRAIT;
        when(mResources.getDisplayMetrics()).thenReturn(mDisplayMetrics);
        mDisplayMetrics.density = 100;
        when(mResources.getBoolean(R.bool.config_enableNotificationShadeDrag)).thenReturn(true);
        when(mResources.getDimensionPixelSize(R.dimen.notifications_top_padding_split_shade))
                .thenReturn(NOTIFICATION_SCRIM_TOP_PADDING_IN_SPLIT_SHADE);
        when(mResources.getDimensionPixelSize(R.dimen.notification_panel_margin_horizontal))
                .thenReturn(10);
        when(mResources.getDimensionPixelSize(R.dimen.split_shade_full_transition_distance))
                .thenReturn(SPLIT_SHADE_FULL_TRANSITION_DISTANCE);
        when(mView.getContext()).thenReturn(getContext());
        when(mView.findViewById(R.id.keyguard_header)).thenReturn(mKeyguardStatusBar);
        when(mView.findViewById(R.id.keyguard_user_switcher_view)).thenReturn(mUserSwitcherView);
        when(mView.findViewById(R.id.keyguard_user_switcher_stub)).thenReturn(
                mUserSwitcherStubView);
        when(mView.findViewById(R.id.keyguard_clock_container)).thenReturn(mKeyguardClockSwitch);
        when(mView.findViewById(R.id.notification_stack_scroller))
                .thenReturn(mNotificationStackScrollLayout);
        when(mNotificationStackScrollLayoutController.getHeight()).thenReturn(1000);
        when(mNotificationStackScrollLayoutController.getHeadsUpCallback())
                .thenReturn(mHeadsUpCallback);
        when(mKeyguardBottomAreaViewController.getView()).thenReturn(mKeyguardBottomArea);
        when(mView.findViewById(R.id.keyguard_bottom_area)).thenReturn(mKeyguardBottomArea);
        when(mKeyguardBottomArea.animate()).thenReturn(mock(ViewPropertyAnimator.class));
        when(mView.findViewById(R.id.qs_frame)).thenReturn(mQsFrame);
        when(mView.findViewById(R.id.keyguard_status_view))
                .thenReturn(mock(KeyguardStatusView.class));
        mNotificationContainerParent = new NotificationsQuickSettingsContainer(getContext(), null);
        mNotificationContainerParent.addView(keyguardStatusView);
        mNotificationContainerParent.onFinishInflate();
        when(mView.findViewById(R.id.notification_container_parent))
                .thenReturn(mNotificationContainerParent);
        when(mFragmentService.getFragmentHostManager(mView)).thenReturn(mFragmentHostManager);
        FlingAnimationUtils.Builder flingAnimationUtilsBuilder = new FlingAnimationUtils.Builder(
                mDisplayMetrics);
        when(mKeyguardQsUserSwitchComponentFactory.build(any()))
                .thenReturn(mKeyguardQsUserSwitchComponent);
        when(mKeyguardQsUserSwitchComponent.getKeyguardQsUserSwitchController())
                .thenReturn(mKeyguardQsUserSwitchController);
        when(mKeyguardUserSwitcherComponentFactory.build(any()))
                .thenReturn(mKeyguardUserSwitcherComponent);
        when(mKeyguardUserSwitcherComponent.getKeyguardUserSwitcherController())
                .thenReturn(mKeyguardUserSwitcherController);
        when(mScreenOffAnimationController.shouldAnimateClockChange()).thenReturn(true);
        when(mQs.getView()).thenReturn(mView);
        when(mQSFragment.getView()).thenReturn(mView);
        doAnswer(invocation -> {
            mFragmentListener = invocation.getArgument(1);
            return null;
        }).when(mFragmentHostManager).addTagListener(eq(QS.TAG), any());
        doAnswer((Answer<Void>) invocation -> {
            mTouchHandler = invocation.getArgument(0);
            return null;
        }).when(mView).setOnTouchListener(any(NotificationPanelViewController.TouchHandler.class));

        NotificationWakeUpCoordinator coordinator =
                new NotificationWakeUpCoordinator(
                        mDumpManager,
                        mock(HeadsUpManagerPhone.class),
                        new StatusBarStateControllerImpl(new UiEventLoggerFake(), mDumpManager,
                                mInteractionJankMonitor, mShadeExpansionStateManager),
                        mKeyguardBypassController,
                        mDozeParameters,
                        mScreenOffAnimationController,
                        mock(NotificationWakeUpCoordinatorLogger.class));
        mConfigurationController = new ConfigurationControllerImpl(mContext);
        PulseExpansionHandler expansionHandler = new PulseExpansionHandler(
                mContext,
                coordinator,
                mKeyguardBypassController, mHeadsUpManager,
                mock(NotificationRoundnessManager.class),
                mConfigurationController,
                mStatusBarStateController,
                mFalsingManager,
                mShadeExpansionStateManager,
                mLockscreenShadeTransitionController,
                new FalsingCollectorFake(),
                mDumpManager);
        when(mKeyguardStatusViewComponentFactory.build(any()))
                .thenReturn(mKeyguardStatusViewComponent);
        when(mKeyguardStatusViewComponent.getKeyguardClockSwitchController())
                .thenReturn(mKeyguardClockSwitchController);
        when(mKeyguardStatusViewComponent.getKeyguardStatusViewController())
                .thenReturn(mKeyguardStatusViewController);
        when(mKeyguardStatusBarViewComponentFactory.build(any(), any()))
                .thenReturn(mKeyguardStatusBarViewComponent);
        when(mKeyguardStatusBarViewComponent.getKeyguardStatusBarViewController())
                .thenReturn(mKeyguardStatusBarViewController);
        when(mLayoutInflater.inflate(eq(R.layout.keyguard_status_view), any(), anyBoolean()))
                .thenReturn(keyguardStatusView);
        when(mLayoutInflater.inflate(eq(R.layout.keyguard_user_switcher), any(), anyBoolean()))
                .thenReturn(mUserSwitcherView);
        when(mLayoutInflater.inflate(eq(R.layout.keyguard_bottom_area), any(), anyBoolean()))
                .thenReturn(mKeyguardBottomArea);
        when(mNotificationRemoteInputManager.isRemoteInputActive())
                .thenReturn(false);
        when(mInteractionJankMonitor.begin(any(), anyInt()))
                .thenReturn(true);
        when(mInteractionJankMonitor.end(anyInt()))
                .thenReturn(true);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mNotificationShadeWindowController).batchApplyWindowLayoutParams(any());
        doAnswer(invocation -> {
            mLayoutChangeListener = invocation.getArgument(0);
            return null;
        }).when(mView).addOnLayoutChangeListener(any());

        when(mView.getViewTreeObserver()).thenReturn(mViewTreeObserver);
        when(mView.getParent()).thenReturn(mViewParent);
        when(mQs.getHeader()).thenReturn(mQsHeader);
        when(mDownMotionEvent.getAction()).thenReturn(MotionEvent.ACTION_DOWN);
        when(mSysUiState.setFlag(anyInt(), anyBoolean())).thenReturn(mSysUiState);

        mMainHandler = new Handler(Looper.getMainLooper());

        mNotificationPanelViewController = new NotificationPanelViewController(
                mView,
                mMainHandler,
                mLayoutInflater,
                mFeatureFlags,
                coordinator, expansionHandler, mDynamicPrivacyController, mKeyguardBypassController,
                mFalsingManager, new FalsingCollectorFake(),
                mKeyguardStateController,
                mStatusBarStateController,
                mStatusBarWindowStateController,
                mNotificationShadeWindowController,
                mDozeLog, mDozeParameters, mCommandQueue, mVibratorHelper,
                mLatencyTracker, mPowerManager, mAccessibilityManager, 0, mUpdateMonitor,
                mMetricsLogger,
                mShadeLog,
                mShadeHeightLogger,
                mConfigurationController,
                () -> flingAnimationUtilsBuilder, mStatusBarTouchableRegionManager,
                mConversationNotificationManager, mMediaHierarchyManager,
                mStatusBarKeyguardViewManager,
                mGutsManager,
                mNotificationsQSContainerController,
                mNotificationStackScrollLayoutController,
                mKeyguardStatusViewComponentFactory,
                mKeyguardQsUserSwitchComponentFactory,
                mKeyguardUserSwitcherComponentFactory,
                mKeyguardStatusBarViewComponentFactory,
                mLockscreenShadeTransitionController,
                mAuthController,
                mScrimController,
                mUserManager,
                mMediaDataManager,
                mNotificationShadeDepthController,
                mAmbientState,
                mLockIconViewController,
                mKeyguardMediaController,
                mTapAgainViewController,
                mNavigationModeController,
                mNavigationBarController,
                mFragmentService,
                mContentResolver,
                mRecordingController,
                mLargeScreenShadeHeaderController,
                mScreenOffAnimationController,
                mLockscreenGestureLogger,
                mShadeExpansionStateManager,
                mNotificationRemoteInputManager,
                mSysUIUnfoldComponent,
                mInteractionJankMonitor,
                mQsFrameTranslateController,
                mSysUiState,
                () -> mKeyguardBottomAreaViewController,
                mKeyguardUnlockAnimationController,
                mKeyguardIndicationController,
                mNotificationListContainer,
                mNotificationStackSizeCalculator,
                mUnlockedScreenOffAnimationController,
                mShadeTransitionController,
                systemClock,
                mKeyguardBottomAreaViewModel,
                mKeyguardBottomAreaInteractor,
                mDreamingToLockscreenTransitionViewModel,
                mOccludedToLockscreenTransitionViewModel,
                mMainDispatcher,
                mKeyguardTransitionInteractor,
                mDumpManager);
        mNotificationPanelViewController.initDependencies(
                mCentralSurfaces,
                null,
                () -> {},
                mNotificationShelfController);
        mNotificationPanelViewController.setTrackingStartedListener(() -> {});
        mNotificationPanelViewController.setOpenCloseListener(
                new NotificationPanelViewController.OpenCloseListener() {
                    @Override
                    public void onClosingFinished() {}

                    @Override
                    public void onOpenStarted() {}
                });
        mNotificationPanelViewController.setHeadsUpManager(mHeadsUpManager);
        ArgumentCaptor<View.OnAttachStateChangeListener> onAttachStateChangeListenerArgumentCaptor =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);
        verify(mView, atLeast(1)).addOnAttachStateChangeListener(
                onAttachStateChangeListenerArgumentCaptor.capture());
        mOnAttachStateChangeListeners = onAttachStateChangeListenerArgumentCaptor.getAllValues();

        ArgumentCaptor<View.AccessibilityDelegate> accessibilityDelegateArgumentCaptor =
                ArgumentCaptor.forClass(View.AccessibilityDelegate.class);
        verify(mView).setAccessibilityDelegate(accessibilityDelegateArgumentCaptor.capture());
        mAccessibilityDelegate = accessibilityDelegateArgumentCaptor.getValue();
        mNotificationPanelViewController.getStatusBarStateController()
                .addCallback(mNotificationPanelViewController.getStatusBarStateListener());
        mNotificationPanelViewController
                .setHeadsUpAppearanceController(mock(HeadsUpAppearanceController.class));
        verify(mNotificationStackScrollLayoutController)
                .setOnEmptySpaceClickListener(mEmptySpaceClickListenerCaptor.capture());
        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ true);
        reset(mKeyguardStatusViewController);
    }

    @After
    public void tearDown() {
        mNotificationPanelViewController.cancelHeightAnimator();
        mMainHandler.removeCallbacksAndMessages(null);
    }

    @Test
    public void onNotificationHeightChangeWhileOnKeyguardWillComputeMaxKeyguardNotifications() {
        mStatusBarStateController.setState(KEYGUARD);
        ArgumentCaptor<OnHeightChangedListener> captor =
                ArgumentCaptor.forClass(OnHeightChangedListener.class);
        verify(mNotificationStackScrollLayoutController)
                .setOnHeightChangedListener(captor.capture());
        OnHeightChangedListener listener = captor.getValue();

        clearInvocations(mNotificationStackSizeCalculator);
        listener.onHeightChanged(mock(ExpandableView.class), false);

        verify(mNotificationStackSizeCalculator)
                .computeMaxKeyguardNotifications(any(), anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    public void onNotificationHeightChangeWhileInShadeWillNotComputeMaxKeyguardNotifications() {
        mStatusBarStateController.setState(SHADE);
        ArgumentCaptor<OnHeightChangedListener> captor =
                ArgumentCaptor.forClass(OnHeightChangedListener.class);
        verify(mNotificationStackScrollLayoutController)
                .setOnHeightChangedListener(captor.capture());
        OnHeightChangedListener listener = captor.getValue();

        clearInvocations(mNotificationStackSizeCalculator);
        listener.onHeightChanged(mock(ExpandableView.class), false);

        verify(mNotificationStackSizeCalculator, never())
                .computeMaxKeyguardNotifications(any(), anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    public void computeMaxKeyguardNotifications_lockscreenToShade_returnsExistingMax() {
        when(mAmbientState.getFractionToShade()).thenReturn(0.5f);
        mNotificationPanelViewController.setMaxDisplayedNotifications(-1);

        // computeMaxKeyguardNotifications sets maxAllowed to 0 at minimum if it updates the value
        assertThat(mNotificationPanelViewController.computeMaxKeyguardNotifications())
                .isEqualTo(-1);
    }

    @Test
    public void computeMaxKeyguardNotifications_noTransition_updatesMax() {
        when(mAmbientState.getFractionToShade()).thenReturn(0f);
        mNotificationPanelViewController.setMaxDisplayedNotifications(-1);

        // computeMaxKeyguardNotifications sets maxAllowed to 0 at minimum if it updates the value
        assertThat(mNotificationPanelViewController.computeMaxKeyguardNotifications())
                .isNotEqualTo(-1);
    }

    private void setBottomPadding(int stackBottom, int lockIconPadding, int indicationPadding,
            int ambientPadding) {

        when(mNotificationStackScrollLayoutController.getTop()).thenReturn(0);
        when(mNotificationStackScrollLayoutController.getHeight()).thenReturn(stackBottom);
        when(mNotificationStackScrollLayoutController.getBottom()).thenReturn(stackBottom);
        when(mLockIconViewController.getTop()).thenReturn((float) (stackBottom - lockIconPadding));

        when(mResources.getDimensionPixelSize(R.dimen.keyguard_indication_bottom_padding))
                .thenReturn(indicationPadding);
        mNotificationPanelViewController.loadDimens();

        mNotificationPanelViewController.setAmbientIndicationTop(
                /* ambientIndicationTop= */ stackBottom - ambientPadding,
                /* ambientTextVisible= */ true);
    }

    @Test
    @Ignore("b/261472011 - Test appears inconsistent across environments")
    public void getVerticalSpaceForLockscreenNotifications_useLockIconBottomPadding_returnsSpaceAvailable() {
        setBottomPadding(/* stackScrollLayoutBottom= */ 180,
                /* lockIconPadding= */ 20,
                /* indicationPadding= */ 0,
                /* ambientPadding= */ 0);

        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenNotifications())
                .isEqualTo(80);
    }

    @Test
    @Ignore("b/261472011 - Test appears inconsistent across environments")
    public void getVerticalSpaceForLockscreenNotifications_useIndicationBottomPadding_returnsSpaceAvailable() {
        setBottomPadding(/* stackScrollLayoutBottom= */ 180,
                /* lockIconPadding= */ 0,
                /* indicationPadding= */ 30,
                /* ambientPadding= */ 0);

        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenNotifications())
                .isEqualTo(70);
    }

    @Test
    @Ignore("b/261472011 - Test appears inconsistent across environments")
    public void getVerticalSpaceForLockscreenNotifications_useAmbientBottomPadding_returnsSpaceAvailable() {
        setBottomPadding(/* stackScrollLayoutBottom= */ 180,
                /* lockIconPadding= */ 0,
                /* indicationPadding= */ 0,
                /* ambientPadding= */ 40);

        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenNotifications())
                .isEqualTo(60);
    }

    @Test
    public void getVerticalSpaceForLockscreenShelf_useLockIconBottomPadding_returnsShelfHeight() {
        setBottomPadding(/* stackScrollLayoutBottom= */ 100,
                /* lockIconPadding= */ 20,
                /* indicationPadding= */ 0,
                /* ambientPadding= */ 0);

        when(mNotificationShelfController.getIntrinsicHeight()).thenReturn(5);
        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(5);
    }

    @Test
    public void getVerticalSpaceForLockscreenShelf_useIndicationBottomPadding_returnsZero() {
        setBottomPadding(/* stackScrollLayoutBottom= */ 100,
                /* lockIconPadding= */ 0,
                /* indicationPadding= */ 30,
                /* ambientPadding= */ 0);

        when(mNotificationShelfController.getIntrinsicHeight()).thenReturn(5);
        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(0);
    }

    @Test
    public void getVerticalSpaceForLockscreenShelf_useAmbientBottomPadding_returnsZero() {
        setBottomPadding(/* stackScrollLayoutBottom= */ 100,
                /* lockIconPadding= */ 0,
                /* indicationPadding= */ 0,
                /* ambientPadding= */ 40);

        when(mNotificationShelfController.getIntrinsicHeight()).thenReturn(5);
        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(0);
    }

    @Test
    public void getVerticalSpaceForLockscreenShelf_useLockIconPadding_returnsLessThanShelfHeight() {
        setBottomPadding(/* stackScrollLayoutBottom= */ 100,
                /* lockIconPadding= */ 10,
                /* indicationPadding= */ 8,
                /* ambientPadding= */ 0);

        when(mNotificationShelfController.getIntrinsicHeight()).thenReturn(5);
        assertThat(mNotificationPanelViewController.getVerticalSpaceForLockscreenShelf())
                .isEqualTo(2);
    }

    @Test
    public void testSetPanelScrimMinFractionWhenHeadsUpIsDragged() {
        mNotificationPanelViewController.setHeadsUpDraggingStartingHeight(
                mNotificationPanelViewController.getMaxPanelHeight() / 2);
        verify(mNotificationShadeDepthController).setPanelPullDownMinFraction(eq(0.5f));
    }

    @Test
    public void testSetDozing_notifiesNsslAndStateController() {
        mNotificationPanelViewController.setDozing(true /* dozing */, false /* animate */);
        verify(mNotificationStackScrollLayoutController).setDozing(eq(true), eq(false));
        assertThat(mStatusBarStateController.getDozeAmount()).isEqualTo(1f);
    }

    @Test
    public void testSetExpandedHeight() {
        mNotificationPanelViewController.setExpandedHeight(200);
        assertThat((int) mNotificationPanelViewController.getExpandedHeight()).isEqualTo(200);
    }

    @Test
    public void testOnTouchEvent_expansionCanBeBlocked() {
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */));
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 200f /* y */,
                0 /* metaState */));
        assertThat((int) mNotificationPanelViewController.getExpandedHeight()).isEqualTo(200);
        assertThat(mNotificationPanelViewController.isTrackingBlocked()).isFalse();

        mNotificationPanelViewController.blockExpansionForCurrentTouch();
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 300f /* y */,
                0 /* metaState */));
        // Expansion should not have changed because it was blocked
        assertThat((int) mNotificationPanelViewController.getExpandedHeight()).isEqualTo(200);
        assertThat(mNotificationPanelViewController.isTrackingBlocked()).isTrue();

        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_UP, 0f /* x */, 300f /* y */,
                0 /* metaState */));
        assertThat(mNotificationPanelViewController.isTrackingBlocked()).isFalse();
    }

    @Test
    public void test_pulsing_onTouchEvent_noTracking() {
        // GIVEN device is pulsing
        mNotificationPanelViewController.setPulsing(true);

        // WHEN touch DOWN & MOVE events received
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */));
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 200f /* y */,
                0 /* metaState */));

        // THEN touch is NOT tracked (since the device is pulsing)
        assertThat(mNotificationPanelViewController.isTracking()).isFalse();
    }

    @Test
    public void test_onTouchEvent_startTracking() {
        // GIVEN device is NOT pulsing
        mNotificationPanelViewController.setPulsing(false);

        // WHEN touch DOWN & MOVE events received
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */));
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 200f /* y */,
                0 /* metaState */));

        // THEN touch is tracked
        assertThat(mNotificationPanelViewController.isTracking()).isTrue();
    }

    @Test
    public void testOnTouchEvent_expansionResumesAfterBriefTouch() {
        mFalsingManager.setIsClassifierEnabled(true);
        mFalsingManager.setIsFalseTouch(false);
        // Start shade collapse with swipe up
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 0f /* y */,
                0 /* metaState */));
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_MOVE, 0f /* x */, 300f /* y */,
                0 /* metaState */));
        onTouchEvent(MotionEvent.obtain(0L /* downTime */,
                0L /* eventTime */, MotionEvent.ACTION_UP, 0f /* x */, 300f /* y */,
                0 /* metaState */));

        assertThat(mNotificationPanelViewController.isClosing()).isTrue();
        assertThat(mNotificationPanelViewController.isFlinging()).isTrue();

        // simulate touch that does not exceed touch slop
        onTouchEvent(MotionEvent.obtain(2L /* downTime */,
                2L /* eventTime */, MotionEvent.ACTION_DOWN, 0f /* x */, 300f /* y */,
                0 /* metaState */));

        mNotificationPanelViewController.setTouchSlopExceeded(false);

        onTouchEvent(MotionEvent.obtain(2L /* downTime */,
                2L /* eventTime */, MotionEvent.ACTION_UP, 0f /* x */, 300f /* y */,
                0 /* metaState */));

        // fling should still be called after a touch that does not exceed touch slop
        assertThat(mNotificationPanelViewController.isClosing()).isTrue();
        assertThat(mNotificationPanelViewController.isFlinging()).isTrue();
    }

    @Test
    public void testA11y_initializeNode() {
        AccessibilityNodeInfo nodeInfo = new AccessibilityNodeInfo();
        mAccessibilityDelegate.onInitializeAccessibilityNodeInfo(mView, nodeInfo);

        List<AccessibilityNodeInfo.AccessibilityAction> actionList = nodeInfo.getActionList();
        assertThat(actionList).containsAtLeastElementsIn(
                new AccessibilityNodeInfo.AccessibilityAction[] {
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD,
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP}
        );
    }

    @Test
    public void testA11y_scrollForward() {
        mAccessibilityDelegate.performAccessibilityAction(
                mView,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId(),
                null);

        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(true);
    }

    @Test
    public void testA11y_scrollUp() {
        mAccessibilityDelegate.performAccessibilityAction(
                mView,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId(),
                null);

        verify(mStatusBarKeyguardViewManager).showPrimaryBouncer(true);
    }

    @Test
    public void testKeyguardStatusViewInSplitShade_changesConstraintsDependingOnNotifications() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        mNotificationPanelViewController.updateResources();
        assertThat(getConstraintSetLayout(R.id.keyguard_status_view).endToEnd)
                .isEqualTo(R.id.qs_edge_guideline);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        mNotificationPanelViewController.updateResources();
        assertThat(getConstraintSetLayout(R.id.keyguard_status_view).endToEnd)
                .isEqualTo(ConstraintSet.PARENT_ID);
    }

    @Test
    public void keyguardStatusView_splitShade_dozing_alwaysDozingOn_isCentered() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        setDozing(/* dozing= */ true, /* dozingAlwaysOn= */ true);

        assertKeyguardStatusViewCentered();
    }

    @Test
    public void keyguardStatusView_splitShade_dozing_alwaysDozingOff_isNotCentered() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        setDozing(/* dozing= */ true, /* dozingAlwaysOn= */ false);

        assertKeyguardStatusViewNotCentered();
    }

    @Test
    public void keyguardStatusView_splitShade_notDozing_alwaysDozingOn_isNotCentered() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        setDozing(/* dozing= */ false, /* dozingAlwaysOn= */ true);

        assertKeyguardStatusViewNotCentered();
    }

    @Test
    public void keyguardStatusView_splitShade_pulsing_isNotCentered() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mNotificationListContainer.hasPulsingNotifications()).thenReturn(true);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        setDozing(/* dozing= */ false, /* dozingAlwaysOn= */ false);

        assertKeyguardStatusViewNotCentered();
    }

    @Test
    public void keyguardStatusView_splitShade_notPulsing_isNotCentered() {
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mNotificationListContainer.hasPulsingNotifications()).thenReturn(false);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        setDozing(/* dozing= */ false, /* dozingAlwaysOn= */ false);

        assertKeyguardStatusViewNotCentered();
    }

    @Test
    public void keyguardStatusView_singleShade_isCentered() {
        enableSplitShade(/* enabled= */ false);
        // The conditions below would make the clock NOT be centered on split shade.
        // On single shade it should always be centered though.
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        when(mNotificationListContainer.hasPulsingNotifications()).thenReturn(false);
        mStatusBarStateController.setState(KEYGUARD);
        setDozing(/* dozing= */ false, /* dozingAlwaysOn= */ false);

        assertKeyguardStatusViewCentered();
    }

    @Test
    public void testDisableUserSwitcherAfterEnabling_returnsViewStubToTheViewHierarchy() {
        givenViewAttached();
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_keyguardUserSwitcher)).thenReturn(true);
        updateMultiUserSetting(true);
        clearInvocations(mView);

        updateMultiUserSetting(false);

        ArgumentCaptor<View> captor = ArgumentCaptor.forClass(View.class);
        verify(mView, atLeastOnce()).addView(captor.capture(), anyInt());
        final View userSwitcherStub = CollectionUtils.find(captor.getAllValues(),
                view -> view.getId() == R.id.keyguard_user_switcher_stub);
        assertThat(userSwitcherStub).isNotNull();
        assertThat(userSwitcherStub).isInstanceOf(ViewStub.class);
    }

    @Test
    public void testChangeSmallestScreenWidthAndUserSwitchEnabled_inflatesUserSwitchView() {
        givenViewAttached();
        when(mView.findViewById(R.id.keyguard_user_switcher_view)).thenReturn(null);
        updateSmallestScreenWidth(300);
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_keyguardUserSwitcher)).thenReturn(true);
        when(mResources.getBoolean(R.bool.qs_show_user_switcher_for_single_user)).thenReturn(false);
        when(mUserManager.isUserSwitcherEnabled(false)).thenReturn(true);

        updateSmallestScreenWidth(800);

        verify(mUserSwitcherStubView).inflate();
    }

    @Test
    public void testFinishInflate_userSwitcherDisabled_doNotInflateUserSwitchView_initClock() {
        givenViewAttached();
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_keyguardUserSwitcher)).thenReturn(true);
        when(mResources.getBoolean(R.bool.qs_show_user_switcher_for_single_user)).thenReturn(false);
        when(mUserManager.isUserSwitcherEnabled(false /* showEvenIfNotActionable */))
                .thenReturn(false);

        mNotificationPanelViewController.onFinishInflate();

        verify(mUserSwitcherStubView, never()).inflate();
        verify(mKeyguardStatusViewController, times(3)).displayClock(LARGE, /* animate */ true);
    }

    @Test
    public void testReInflateViews_userSwitcherDisabled_doNotInflateUserSwitchView() {
        givenViewAttached();
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_keyguardUserSwitcher)).thenReturn(true);
        when(mResources.getBoolean(R.bool.qs_show_user_switcher_for_single_user)).thenReturn(false);
        when(mUserManager.isUserSwitcherEnabled(false /* showEvenIfNotActionable */))
                .thenReturn(false);

        mNotificationPanelViewController.reInflateViews();

        verify(mUserSwitcherStubView, never()).inflate();
    }

    @Test
    public void testCanCollapsePanelOnTouch_trueForKeyGuard() {
        mStatusBarStateController.setState(KEYGUARD);

        assertThat(mNotificationPanelViewController.canCollapsePanelOnTouch()).isTrue();
    }

    @Test
    public void testCanCollapsePanelOnTouch_trueWhenScrolledToBottom() {
        mStatusBarStateController.setState(SHADE);
        when(mNotificationStackScrollLayoutController.isScrolledToBottom()).thenReturn(true);

        assertThat(mNotificationPanelViewController.canCollapsePanelOnTouch()).isTrue();
    }

    @Test
    public void testCanCollapsePanelOnTouch_trueWhenInSettings() {
        mStatusBarStateController.setState(SHADE);
        mNotificationPanelViewController.setQsExpanded(true);

        assertThat(mNotificationPanelViewController.canCollapsePanelOnTouch()).isTrue();
    }

    @Test
    public void testCanCollapsePanelOnTouch_falseInDualPaneShade() {
        mStatusBarStateController.setState(SHADE);
        enableSplitShade(/* enabled= */ true);
        mNotificationPanelViewController.setQsExpanded(true);

        assertThat(mNotificationPanelViewController.canCollapsePanelOnTouch()).isFalse();
    }

    @Test
    public void testSwipeWhileLocked_notifiesKeyguardState() {
        mStatusBarStateController.setState(KEYGUARD);

        // Fling expanded (cancelling the keyguard exit swipe). We should notify keyguard state that
        // the fling occurred and did not dismiss the keyguard.
        mNotificationPanelViewController.flingToHeight(
                0f, true /* expand */, 1000f, 1f, false);
        verify(mKeyguardStateController).notifyPanelFlingStart(false /* dismissKeyguard */);

        // Fling un-expanded, which is a keyguard exit fling when we're in KEYGUARD state.
        mNotificationPanelViewController.flingToHeight(
                0f, false /* expand */, 1000f, 1f, false);
        verify(mKeyguardStateController).notifyPanelFlingStart(true /* dismissKeyguard */);
    }

    @Test
    public void testCancelSwipeWhileLocked_notifiesKeyguardState() {
        mStatusBarStateController.setState(KEYGUARD);

        // Fling expanded (cancelling the keyguard exit swipe). We should notify keyguard state that
        // the fling occurred and did not dismiss the keyguard.
        mNotificationPanelViewController.flingToHeight(
                0f, true /* expand */, 1000f, 1f, false);
        mNotificationPanelViewController.cancelHeightAnimator();
        verify(mKeyguardStateController).notifyPanelFlingEnd();
    }

    @Test
    public void testSwipe_exactlyToTarget_notifiesNssl() {
        // No over-expansion
        mNotificationPanelViewController.setOverExpansion(0f);
        // Fling to a target that is equal to the current position (i.e. a no-op fling).
        mNotificationPanelViewController.flingToHeight(
                0f,
                true,
                mNotificationPanelViewController.getExpandedHeight(),
                1f,
                false);
        // Verify that the NSSL is notified that the panel is *not* flinging.
        verify(mNotificationStackScrollLayoutController).setPanelFlinging(false);
    }

    @Test
    public void testDoubleTapRequired_Keyguard() {
        FalsingManager.FalsingTapListener listener = getFalsingTapListener();
        mStatusBarStateController.setState(KEYGUARD);

        listener.onAdditionalTapRequired();

        verify(mKeyguardIndicationController).showTransientIndication(anyInt());
    }

    @Test
    public void testDoubleTapRequired_ShadeLocked() {
        FalsingManager.FalsingTapListener listener = getFalsingTapListener();
        mStatusBarStateController.setState(SHADE_LOCKED);

        listener.onAdditionalTapRequired();

        verify(mTapAgainViewController).show();
    }

    @Test
    public void testRotatingToSplitShadeWithQsExpanded_transitionsToShadeLocked() {
        mStatusBarStateController.setState(KEYGUARD);
        mNotificationPanelViewController.setQsExpanded(true);

        enableSplitShade(true);

        assertThat(mStatusBarStateController.getState()).isEqualTo(SHADE_LOCKED);
    }

    @Test
    public void testUnlockedSplitShadeTransitioningToKeyguard_closesQS() {
        enableSplitShade(true);
        mStatusBarStateController.setState(SHADE);
        mNotificationPanelViewController.setQsExpanded(true);

        mStatusBarStateController.setState(KEYGUARD);

        assertThat(mNotificationPanelViewController.isQsExpanded()).isEqualTo(false);
        assertThat(mNotificationPanelViewController.isQsExpandImmediate()).isEqualTo(false);
    }

    @Test
    public void testLockedSplitShadeTransitioningToKeyguard_closesQS() {
        enableSplitShade(true);
        mStatusBarStateController.setState(SHADE_LOCKED);
        mNotificationPanelViewController.setQsExpanded(true);

        mStatusBarStateController.setState(KEYGUARD);

        assertThat(mNotificationPanelViewController.isQsExpanded()).isEqualTo(false);
        assertThat(mNotificationPanelViewController.isQsExpandImmediate()).isEqualTo(false);
    }

    @Test
    public void testSwitchesToCorrectClockInSinglePaneShade() {
        mStatusBarStateController.setState(KEYGUARD);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ true);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        mNotificationPanelViewController.closeQs();
        verify(mKeyguardStatusViewController).displayClock(SMALL, /* animate */ true);
    }

    @Test
    public void testSwitchesToCorrectClockInSplitShade() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        clearInvocations(mKeyguardStatusViewController);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ true);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController, times(2))
                .displayClock(LARGE, /* animate */ true);
        verify(mKeyguardStatusViewController, never())
                .displayClock(SMALL, /* animate */ true);
    }

    @Test
    public void testHasNotifications_switchesToLargeClockWhenEnteringSplitShade() {
        mStatusBarStateController.setState(KEYGUARD);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);

        enableSplitShade(/* enabled= */ true);

        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ true);
    }

    @Test
    public void testNoNotifications_switchesToLargeClockWhenEnteringSplitShade() {
        mStatusBarStateController.setState(KEYGUARD);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);

        enableSplitShade(/* enabled= */ true);

        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ true);
    }

    @Test
    public void testHasNotifications_switchesToSmallClockWhenExitingSplitShade() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        clearInvocations(mKeyguardStatusViewController);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);

        enableSplitShade(/* enabled= */ false);

        verify(mKeyguardStatusViewController).displayClock(SMALL, /* animate */ true);
    }

    @Test
    public void testNoNotifications_switchesToLargeClockWhenExitingSplitShade() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        clearInvocations(mKeyguardStatusViewController);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);

        enableSplitShade(/* enabled= */ false);

        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ true);
    }

    @Test
    public void clockSize_mediaShowing_inSplitShade_onAod_isLarge() {
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        when(mMediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(true);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        clearInvocations(mKeyguardStatusViewController);

        mNotificationPanelViewController.setDozing(/* dozing= */ true, /* animate= */ false);

        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate= */ true);
    }

    @Test
    public void clockSize_mediaShowing_inSplitShade_screenOff_notAod_isSmall() {
        when(mDozeParameters.getAlwaysOn()).thenReturn(false);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        when(mMediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(true);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);
        clearInvocations(mKeyguardStatusViewController);

        mNotificationPanelViewController.setDozing(/* dozing= */ true, /* animate= */ false);

        verify(mKeyguardStatusViewController).displayClock(SMALL, /* animate= */ true);
    }

    @Test
    public void testSwitchesToBigClockInSplitShadeOnAodAnimateDisabled() {
        when(mScreenOffAnimationController.shouldAnimateClockChange()).thenReturn(false);
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        clearInvocations(mKeyguardStatusViewController);
        when(mMediaDataManager.hasActiveMedia()).thenReturn(true);
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(2);

        mNotificationPanelViewController.setDozing(true, false);

        verify(mKeyguardStatusViewController).displayClock(LARGE, /* animate */ false);
    }

    @Test
    public void testDisplaysSmallClockOnLockscreenInSplitShadeWhenMediaIsPlaying() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        clearInvocations(mKeyguardStatusViewController);
        when(mMediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(true);

        // one notification + media player visible
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController).displayClock(SMALL, /* animate */ true);

        // only media player visible
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController, times(2)).displayClock(SMALL, true);
        verify(mKeyguardStatusViewController, never()).displayClock(LARGE, /* animate */ true);
    }

    @Test
    public void testLargeScreenHeaderMadeActiveForLargeScreen() {
        mStatusBarStateController.setState(SHADE);
        when(mResources.getBoolean(R.bool.config_use_large_screen_shade_header)).thenReturn(true);
        mNotificationPanelViewController.updateResources();
        verify(mLargeScreenShadeHeaderController).setLargeScreenActive(true);

        when(mResources.getBoolean(R.bool.config_use_large_screen_shade_header)).thenReturn(false);
        mNotificationPanelViewController.updateResources();
        verify(mLargeScreenShadeHeaderController).setLargeScreenActive(false);
    }

    @Test
    public void testExpandWithQsMethodIsUsingLockscreenTransitionController() {
        enableSplitShade(/* enabled= */ true);
        mStatusBarStateController.setState(KEYGUARD);

        mNotificationPanelViewController.expandWithQs();

        verify(mLockscreenShadeTransitionController).goToLockedShade(
                /* expandedView= */null, /* needsQSAnimation= */true);
    }

    @Test
    public void testUnlockAnimationDoesNotAffectScrim() {
        mNotificationPanelViewController.onUnlockHintStarted();
        verify(mScrimController).setExpansionAffectsAlpha(false);
        mNotificationPanelViewController.onUnlockHintFinished();
        verify(mScrimController).setExpansionAffectsAlpha(true);
    }

    @Test
    public void testUnlockHintAnimation_runs_whenNotInPowerSaveMode_andDozeAmountIsZero() {
        when(mPowerManager.isPowerSaveMode()).thenReturn(false);
        when(mAmbientState.getDozeAmount()).thenReturn(0f);
        mNotificationPanelViewController.startUnlockHintAnimation();
        assertThat(mNotificationPanelViewController.isHintAnimationRunning()).isTrue();
    }

    @Test
    public void testUnlockHintAnimation_doesNotRun_inPowerSaveMode() {
        when(mPowerManager.isPowerSaveMode()).thenReturn(true);
        mNotificationPanelViewController.startUnlockHintAnimation();
        assertThat(mNotificationPanelViewController.isHintAnimationRunning()).isFalse();
    }

    @Test
    public void testUnlockHintAnimation_doesNotRun_whenDozeAmountNotZero() {
        when(mPowerManager.isPowerSaveMode()).thenReturn(false);
        when(mAmbientState.getDozeAmount()).thenReturn(0.5f);
        mNotificationPanelViewController.startUnlockHintAnimation();
        assertThat(mNotificationPanelViewController.isHintAnimationRunning()).isFalse();
    }

    @Test
    public void setKeyguardStatusBarAlpha_setsAlphaOnKeyguardStatusBarController() {
        float statusBarAlpha = 0.5f;

        mNotificationPanelViewController.setKeyguardStatusBarAlpha(statusBarAlpha);

        verify(mKeyguardStatusBarViewController).setAlpha(statusBarAlpha);
    }

    @Test
    public void testQsToBeImmediatelyExpandedWhenOpeningPanelInSplitShade() {
        enableSplitShade(/* enabled= */ true);
        mShadeExpansionStateManager.updateState(STATE_CLOSED);
        assertThat(mNotificationPanelViewController.isQsExpandImmediate()).isFalse();

        mShadeExpansionStateManager.updateState(STATE_OPENING);

        assertThat(mNotificationPanelViewController.isQsExpandImmediate()).isTrue();
    }

    @Test
    public void testQsNotToBeImmediatelyExpandedWhenGoingFromUnlockedToLocked() {
        enableSplitShade(/* enabled= */ true);
        mShadeExpansionStateManager.updateState(STATE_CLOSED);

        mStatusBarStateController.setState(KEYGUARD);
        // going to lockscreen would trigger STATE_OPENING
        mShadeExpansionStateManager.updateState(STATE_OPENING);

        assertThat(mNotificationPanelViewController.isQsExpandImmediate()).isFalse();
    }

    @Test
    public void testQsImmediateResetsWhenPanelOpensOrCloses() {
        mNotificationPanelViewController.setQsExpandImmediate(true);
        mShadeExpansionStateManager.updateState(STATE_OPEN);
        assertThat(mNotificationPanelViewController.isQsExpandImmediate()).isFalse();

        mNotificationPanelViewController.setQsExpandImmediate(true);
        mShadeExpansionStateManager.updateState(STATE_CLOSED);
        assertThat(mNotificationPanelViewController.isQsExpandImmediate()).isFalse();
    }

    @Test
    public void testQsExpansionChangedToDefaultWhenRotatingFromOrToSplitShade() {
        // to make sure shade is in expanded state
        mNotificationPanelViewController.startWaitingForOpenPanelGesture();
        assertThat(mNotificationPanelViewController.isQsExpanded()).isFalse();

        // switch to split shade from portrait (default state)
        enableSplitShade(/* enabled= */ true);
        assertThat(mNotificationPanelViewController.isQsExpanded()).isTrue();

        // switch to portrait from split shade
        enableSplitShade(/* enabled= */ false);
        assertThat(mNotificationPanelViewController.isQsExpanded()).isFalse();
    }

    @Test
    public void testPanelClosedWhenClosingQsInSplitShade() {
        mShadeExpansionStateManager.onPanelExpansionChanged(/* fraction= */ 1,
                /* expanded= */ true, /* tracking= */ false, /* dragDownPxAmount= */ 0);
        enableSplitShade(/* enabled= */ true);
        mNotificationPanelViewController.setExpandedFraction(1f);

        assertThat(mNotificationPanelViewController.isClosing()).isFalse();
        mNotificationPanelViewController.animateCloseQs(false);
        assertThat(mNotificationPanelViewController.isClosing()).isTrue();
    }

    @Test
    public void testPanelStaysOpenWhenClosingQs() {
        mShadeExpansionStateManager.onPanelExpansionChanged(/* fraction= */ 1,
                /* expanded= */ true, /* tracking= */ false, /* dragDownPxAmount= */ 0);
        mNotificationPanelViewController.setExpandedFraction(1f);

        assertThat(mNotificationPanelViewController.isClosing()).isFalse();
        mNotificationPanelViewController.animateCloseQs(false);
        assertThat(mNotificationPanelViewController.isClosing()).isFalse();
    }

    @Test
    public void interceptTouchEvent_withinQs_shadeExpanded_startsQsTracking() {
        mNotificationPanelViewController.setQs(mQs);
        when(mQsFrame.getX()).thenReturn(0f);
        when(mQsFrame.getWidth()).thenReturn(1000);
        when(mQsHeader.getTop()).thenReturn(0);
        when(mQsHeader.getBottom()).thenReturn(1000);
        NotificationPanelViewController.TouchHandler touchHandler =
                mNotificationPanelViewController.createTouchHandler();

        mNotificationPanelViewController.setExpandedFraction(1f);
        touchHandler.onInterceptTouchEvent(
                createMotionEvent(/* x= */ 0, /* y= */ 0, MotionEvent.ACTION_DOWN));
        touchHandler.onInterceptTouchEvent(
                createMotionEvent(/* x= */ 0, /* y= */ 500, MotionEvent.ACTION_MOVE));

        assertThat(mNotificationPanelViewController.isQsTracking()).isTrue();
    }

    @Test
    public void interceptTouchEvent_withinQs_shadeExpanded_inSplitShade_doesNotStartQsTracking() {
        enableSplitShade(true);
        mNotificationPanelViewController.setQs(mQs);
        when(mQsFrame.getX()).thenReturn(0f);
        when(mQsFrame.getWidth()).thenReturn(1000);
        when(mQsHeader.getTop()).thenReturn(0);
        when(mQsHeader.getBottom()).thenReturn(1000);
        NotificationPanelViewController.TouchHandler touchHandler =
                mNotificationPanelViewController.createTouchHandler();

        mNotificationPanelViewController.setExpandedFraction(1f);
        touchHandler.onInterceptTouchEvent(
                createMotionEvent(/* x= */ 0, /* y= */ 0, MotionEvent.ACTION_DOWN));
        touchHandler.onInterceptTouchEvent(
                createMotionEvent(/* x= */ 0, /* y= */ 500, MotionEvent.ACTION_MOVE));

        assertThat(mNotificationPanelViewController.isQsTracking()).isFalse();
    }

    @Test
    public void testOnAttachRefreshStatusBarState() {
        mStatusBarStateController.setState(KEYGUARD);
        when(mKeyguardStateController.isKeyguardFadingAway()).thenReturn(false);
        for (View.OnAttachStateChangeListener listener : mOnAttachStateChangeListeners) {
            listener.onViewAttachedToWindow(mView);
        }
        verify(mKeyguardStatusViewController).setKeyguardStatusViewVisibility(
                KEYGUARD/*statusBarState*/,
                false/*keyguardFadingAway*/,
                false/*goingToFullShade*/, SHADE/*oldStatusBarState*/);
    }

    @Test
    public void getMaxPanelTransitionDistance_expanding_inSplitShade_returnsSplitShadeFullTransitionDistance() {
        enableSplitShade(true);
        mNotificationPanelViewController.expandWithQs();

        int maxDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();

        assertThat(maxDistance).isEqualTo(SPLIT_SHADE_FULL_TRANSITION_DISTANCE);
    }

    @Test
    public void getMaxPanelTransitionDistance_inSplitShade_withHeadsUp_returnsBiggerValue() {
        enableSplitShade(true);
        mNotificationPanelViewController.expandWithQs();
        when(mHeadsUpManager.isTrackingHeadsUp()).thenReturn(true);
        mNotificationPanelViewController.setHeadsUpDraggingStartingHeight(
                SPLIT_SHADE_FULL_TRANSITION_DISTANCE);

        int maxDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();

        assertThat(maxDistance).isGreaterThan(SPLIT_SHADE_FULL_TRANSITION_DISTANCE);
    }

    @Test
    public void getMaxPanelTransitionDistance_expandingSplitShade_keyguard_returnsNonSplitShadeValue() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(true);
        mNotificationPanelViewController.expandWithQs();

        int maxDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();

        assertThat(maxDistance).isNotEqualTo(SPLIT_SHADE_FULL_TRANSITION_DISTANCE);
    }

    @Test
    public void getMaxPanelTransitionDistance_expanding_notSplitShade_returnsNonSplitShadeValue() {
        enableSplitShade(false);
        mNotificationPanelViewController.expandWithQs();

        int maxDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();

        assertThat(maxDistance).isNotEqualTo(SPLIT_SHADE_FULL_TRANSITION_DISTANCE);
    }

    @Test
    public void onLayoutChange_fullWidth_updatesQSWithFullWithTrue() {
        mNotificationPanelViewController.setQs(mQs);

        setIsFullWidth(true);

        verify(mQs).setIsNotificationPanelFullWidth(true);
    }

    @Test
    public void onLayoutChange_notFullWidth_updatesQSWithFullWithFalse() {
        mNotificationPanelViewController.setQs(mQs);

        setIsFullWidth(false);

        verify(mQs).setIsNotificationPanelFullWidth(false);
    }

    @Test
    public void onLayoutChange_qsNotSet_doesNotCrash() {
        mNotificationPanelViewController.setQs(null);

        triggerLayoutChange();
    }

    @Test
    public void onQsFragmentAttached_fullWidth_setsFullWidthTrueOnQS() {
        setIsFullWidth(true);
        givenViewAttached();
        mFragmentListener.onFragmentViewCreated(QS.TAG, mQSFragment);

        verify(mQSFragment).setIsNotificationPanelFullWidth(true);
    }

    @Test
    public void onQsFragmentAttached_notFullWidth_setsFullWidthFalseOnQS() {
        setIsFullWidth(false);
        givenViewAttached();
        mFragmentListener.onFragmentViewCreated(QS.TAG, mQSFragment);

        verify(mQSFragment).setIsNotificationPanelFullWidth(false);
    }

    @Test
    public void setQsExpansion_lockscreenShadeTransitionInProgress_usesLockscreenSquishiness() {
        float squishinessFraction = 0.456f;
        mNotificationPanelViewController.setQs(mQs);
        when(mLockscreenShadeTransitionController.getQsSquishTransitionFraction())
                .thenReturn(squishinessFraction);
        when(mNotificationStackScrollLayoutController.getNotificationSquishinessFraction())
                .thenReturn(0.987f);
        // Call setTransitionToFullShadeAmount to get into the full shade transition in progress
        // state.
        mNotificationPanelViewController.setTransitionToFullShadeAmount(
                /* pxAmount= */ 234,
                /* animate= */ false,
                /* delay= */ 0
        );

        mNotificationPanelViewController.setQsExpansionHeight(/* height= */ 123);

        // First for setTransitionToFullShadeAmount and then setQsExpansion
        verify(mQs, times(2)).setQsExpansion(
                /* expansion= */ anyFloat(),
                /* panelExpansionFraction= */ anyFloat(),
                /* proposedTranslation= */ anyFloat(),
                eq(squishinessFraction)
        );
    }

    @Test
    public void setQsExpansion_lockscreenShadeTransitionNotInProgress_usesStandardSquishiness() {
        float lsSquishinessFraction = 0.456f;
        float nsslSquishinessFraction = 0.987f;
        mNotificationPanelViewController.setQs(mQs);
        when(mLockscreenShadeTransitionController.getQsSquishTransitionFraction())
                .thenReturn(lsSquishinessFraction);
        when(mNotificationStackScrollLayoutController.getNotificationSquishinessFraction())
                .thenReturn(nsslSquishinessFraction);

        mNotificationPanelViewController.setQsExpansionHeight(/* height= */ 123);

        verify(mQs).setQsExpansion(
                /* expansion= */ anyFloat(),
                /* panelExpansionFraction= */ anyFloat(),
                /* proposedTranslation= */ anyFloat(),
                eq(nsslSquishinessFraction)
        );
    }

    @Test
    public void onEmptySpaceClicked_notDozingAndOnKeyguard_requestsFaceAuth() {
        StatusBarStateController.StateListener statusBarStateListener =
                mNotificationPanelViewController.getStatusBarStateListener();
        statusBarStateListener.onStateChanged(KEYGUARD);
        mNotificationPanelViewController.setDozing(false, false);

        // This sets the dozing state that is read when onMiddleClicked is eventually invoked.
        mTouchHandler.onTouch(mock(View.class), mDownMotionEvent);
        mEmptySpaceClickListenerCaptor.getValue().onEmptySpaceClicked(0, 0);

        verify(mUpdateMonitor).requestFaceAuth(
                FaceAuthApiRequestReason.NOTIFICATION_PANEL_CLICKED);
    }

    @Test
    public void onEmptySpaceClicked_notDozingAndFaceDetectionIsNotRunning_startsUnlockAnimation() {
        StatusBarStateController.StateListener statusBarStateListener =
                mNotificationPanelViewController.getStatusBarStateListener();
        statusBarStateListener.onStateChanged(KEYGUARD);
        mNotificationPanelViewController.setDozing(false, false);
        when(mUpdateMonitor.requestFaceAuth(NOTIFICATION_PANEL_CLICKED)).thenReturn(false);

        // This sets the dozing state that is read when onMiddleClicked is eventually invoked.
        mTouchHandler.onTouch(mock(View.class), mDownMotionEvent);
        mEmptySpaceClickListenerCaptor.getValue().onEmptySpaceClicked(0, 0);

        verify(mNotificationStackScrollLayoutController).setUnlockHintRunning(true);
    }

    @Test
    public void onEmptySpaceClicked_notDozingAndFaceDetectionIsRunning_doesNotStartUnlockHint() {
        StatusBarStateController.StateListener statusBarStateListener =
                mNotificationPanelViewController.getStatusBarStateListener();
        statusBarStateListener.onStateChanged(KEYGUARD);
        mNotificationPanelViewController.setDozing(false, false);
        when(mUpdateMonitor.requestFaceAuth(NOTIFICATION_PANEL_CLICKED)).thenReturn(true);

        // This sets the dozing state that is read when onMiddleClicked is eventually invoked.
        mTouchHandler.onTouch(mock(View.class), mDownMotionEvent);
        mEmptySpaceClickListenerCaptor.getValue().onEmptySpaceClicked(0, 0);

        verify(mNotificationStackScrollLayoutController, never()).setUnlockHintRunning(true);
    }

    @Test
    public void onEmptySpaceClicked_whenDozingAndOnKeyguard_doesNotRequestFaceAuth() {
        StatusBarStateController.StateListener statusBarStateListener =
                mNotificationPanelViewController.getStatusBarStateListener();
        statusBarStateListener.onStateChanged(KEYGUARD);
        mNotificationPanelViewController.setDozing(true, false);

        // This sets the dozing state that is read when onMiddleClicked is eventually invoked.
        mTouchHandler.onTouch(mock(View.class), mDownMotionEvent);
        mEmptySpaceClickListenerCaptor.getValue().onEmptySpaceClicked(0, 0);

        verify(mUpdateMonitor, never()).requestFaceAuth(anyString());
    }

    @Test
    public void onEmptySpaceClicked_whenStatusBarShadeLocked_doesNotRequestFaceAuth() {
        StatusBarStateController.StateListener statusBarStateListener =
                mNotificationPanelViewController.getStatusBarStateListener();
        statusBarStateListener.onStateChanged(SHADE_LOCKED);

        mEmptySpaceClickListenerCaptor.getValue().onEmptySpaceClicked(0, 0);

        verify(mUpdateMonitor, never()).requestFaceAuth(anyString());

    }

    /**
     * When shade is flinging to close and this fling is not intercepted,
     * {@link AmbientState#setIsClosing(boolean)} should be called before
     * {@link NotificationStackScrollLayoutController#onExpansionStopped()}
     * to ensure scrollY can be correctly set to be 0
     */
    @Test
    public void onShadeFlingClosingEnd_mAmbientStateSetClose_thenOnExpansionStopped() {
        // Given: Shade is expanded
        mNotificationPanelViewController.notifyExpandingFinished();
        mNotificationPanelViewController.setClosing(false);

        // When: Shade flings to close not canceled
        mNotificationPanelViewController.notifyExpandingStarted();
        mNotificationPanelViewController.setClosing(true);
        mNotificationPanelViewController.onFlingEnd(false);

        // Then: AmbientState's mIsClosing should be set to false
        // before mNotificationStackScrollLayoutController.onExpansionStopped() is called
        // to ensure NotificationStackScrollLayout.resetScrollPosition() -> resetScrollPosition
        // -> setOwnScrollY(0) can set scrollY to 0 when shade is closed
        InOrder inOrder = inOrder(mAmbientState, mNotificationStackScrollLayoutController);
        inOrder.verify(mAmbientState).setIsClosing(false);
        inOrder.verify(mNotificationStackScrollLayoutController).onExpansionStopped();
    }

    @Test
    public void inUnlockedSplitShade_transitioningMaxTransitionDistance_makesShadeFullyExpanded() {
        mStatusBarStateController.setState(SHADE);
        enableSplitShade(true);
        int transitionDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();
        mNotificationPanelViewController.setExpandedHeight(transitionDistance);
        assertThat(mNotificationPanelViewController.isFullyExpanded()).isTrue();
    }

    @Test
    public void shadeExpanded_inShadeState() {
        mStatusBarStateController.setState(SHADE);

        mNotificationPanelViewController.setExpandedHeight(0);
        assertThat(mNotificationPanelViewController.isShadeFullyOpen()).isFalse();

        int transitionDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();
        mNotificationPanelViewController.setExpandedHeight(transitionDistance);
        assertThat(mNotificationPanelViewController.isShadeFullyOpen()).isTrue();
    }

    @Test
    public void shadeExpanded_onKeyguard() {
        mStatusBarStateController.setState(KEYGUARD);

        int transitionDistance = mNotificationPanelViewController.getMaxPanelTransitionDistance();
        mNotificationPanelViewController.setExpandedHeight(transitionDistance);
        assertThat(mNotificationPanelViewController.isShadeFullyOpen()).isFalse();

        // set maxQsExpansion in NPVC
        int maxQsExpansion = 123;
        mNotificationPanelViewController.setQs(mQs);
        when(mQs.getDesiredHeight()).thenReturn(maxQsExpansion);
        triggerLayoutChange();

        mNotificationPanelViewController.setQsExpansionHeight(maxQsExpansion);
        assertThat(mNotificationPanelViewController.isShadeFullyOpen()).isTrue();
    }

    @Test
    public void shadeExpanded_onShadeLocked() {
        mStatusBarStateController.setState(SHADE_LOCKED);
        assertThat(mNotificationPanelViewController.isShadeFullyOpen()).isTrue();
    }

    private static MotionEvent createMotionEvent(int x, int y, int action) {
        return MotionEvent.obtain(
                /* downTime= */ 0, /* eventTime= */ 0, action, x, y, /* metaState= */ 0);
    }

    private void triggerPositionClockAndNotifications() {
        mNotificationPanelViewController.closeQs();
    }

    private FalsingManager.FalsingTapListener getFalsingTapListener() {
        for (View.OnAttachStateChangeListener listener : mOnAttachStateChangeListeners) {
            listener.onViewAttachedToWindow(mView);
        }
        assertThat(mFalsingManager.getTapListeners().size()).isEqualTo(1);
        return mFalsingManager.getTapListeners().get(0);
    }

    private void givenViewAttached() {
        for (View.OnAttachStateChangeListener listener : mOnAttachStateChangeListeners) {
            listener.onViewAttachedToWindow(mView);
        }
    }

    private ConstraintSet.Layout getConstraintSetLayout(@IdRes int id) {
        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.clone(mNotificationContainerParent);
        return constraintSet.getConstraint(id).layout;
    }

    private void enableSplitShade(boolean enabled) {
        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(enabled);
        mNotificationPanelViewController.updateResources();
    }

    private void updateMultiUserSetting(boolean enabled) {
        when(mResources.getBoolean(R.bool.qs_show_user_switcher_for_single_user)).thenReturn(false);
        when(mUserManager.isUserSwitcherEnabled(false)).thenReturn(enabled);
        final ArgumentCaptor<ContentObserver> observerCaptor =
                ArgumentCaptor.forClass(ContentObserver.class);
        verify(mContentResolver)
                .registerContentObserver(any(), anyBoolean(), observerCaptor.capture());
        observerCaptor.getValue().onChange(/* selfChange */ false);
    }

    private void updateSmallestScreenWidth(int smallestScreenWidthDp) {
        Configuration configuration = new Configuration();
        configuration.smallestScreenWidthDp = smallestScreenWidthDp;
        mConfigurationController.onConfigurationChanged(configuration);
    }

    private void onTouchEvent(MotionEvent ev) {
        mTouchHandler.onTouch(mView, ev);
    }

    private void setDozing(boolean dozing, boolean dozingAlwaysOn) {
        when(mDozeParameters.getAlwaysOn()).thenReturn(dozingAlwaysOn);
        mNotificationPanelViewController.setDozing(
                /* dozing= */ dozing,
                /* animate= */ false
        );
    }

    private void assertKeyguardStatusViewCentered() {
        mNotificationPanelViewController.updateResources();
        assertThat(getConstraintSetLayout(R.id.keyguard_status_view).endToEnd).isAnyOf(
                ConstraintSet.PARENT_ID, ConstraintSet.UNSET);
    }

    private void assertKeyguardStatusViewNotCentered() {
        mNotificationPanelViewController.updateResources();
        assertThat(getConstraintSetLayout(R.id.keyguard_status_view).endToEnd).isEqualTo(
                R.id.qs_edge_guideline);
    }

    private void setIsFullWidth(boolean fullWidth) {
        float nsslWidth = fullWidth ? PANEL_WIDTH : PANEL_WIDTH / 2f;
        when(mNotificationStackScrollLayoutController.getWidth()).thenReturn(nsslWidth);
        triggerLayoutChange();
    }

    private void triggerLayoutChange() {
        mLayoutChangeListener.onLayoutChange(
                mView,
                /* left= */ 0,
                /* top= */ 0,
                /* right= */ 0,
                /* bottom= */ 0,
                /* oldLeft= */ 0,
                /* oldTop= */ 0,
                /* oldRight= */ 0,
                /* oldBottom= */ 0
        );
    }
}
