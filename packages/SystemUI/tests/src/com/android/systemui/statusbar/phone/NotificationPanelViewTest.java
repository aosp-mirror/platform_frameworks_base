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

package com.android.systemui.statusbar.phone;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.keyguard.KeyguardClockSwitch.LARGE;
import static com.android.keyguard.KeyguardClockSwitch.SMALL;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;
import static com.android.systemui.statusbar.notification.ViewGroupFadeHelper.reset;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.IdRes;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.biometrics.BiometricSourceType;
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
import android.view.ViewPropertyAnimator;
import android.view.ViewStub;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.LatencyTracker;
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
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.communal.CommunalHostView;
import com.android.systemui.communal.CommunalHostViewController;
import com.android.systemui.communal.CommunalSource;
import com.android.systemui.communal.CommunalSourceMonitor;
import com.android.systemui.communal.dagger.CommunalViewComponent;
import com.android.systemui.controls.dagger.ControlsComponent;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.idle.IdleHostViewController;
import com.android.systemui.idle.dagger.IdleViewComponent;
import com.android.systemui.media.KeyguardMediaController;
import com.android.systemui.media.MediaDataManager;
import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.QSDetailDisplayer;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardAffordanceView;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.events.PrivacyDotViewController;
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController;
import com.android.systemui.statusbar.notification.ConversationNotificationManager;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationRoundnessManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.wallet.controller.QuickAccessWalletController;
import com.android.wm.shell.animation.FlingAnimationUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.lang.ref.WeakReference;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationPanelViewTest extends SysuiTestCase {

    private static final int NOTIFICATION_SCRIM_TOP_PADDING_IN_SPLIT_SHADE = 50;

    @Mock
    private StatusBar mStatusBar;
    @Mock
    private NotificationStackScrollLayout mNotificationStackScrollLayout;
    @Mock
    private KeyguardBottomAreaView mKeyguardBottomArea;
    @Mock
    private KeyguardBottomAreaView mQsFrame;
    private KeyguardStatusView mKeyguardStatusView;
    @Mock
    private ViewGroup mBigClockContainer;
    @Mock
    private NotificationIconAreaController mNotificationAreaController;
    @Mock
    private HeadsUpManagerPhone mHeadsUpManager;
    @Mock
    private NotificationShelfController mNotificationShelfController;
    @Mock
    private NotificationGroupManagerLegacy mGroupManager;
    @Mock
    private KeyguardStatusBarView mKeyguardStatusBar;
    @Mock
    private View mUserSwitcherView;
    @Mock
    private ViewStub mUserSwitcherStubView;
    @Mock
    private HeadsUpTouchHelper.Callback mHeadsUpCallback;
    @Mock
    private PanelBar mPanelBar;
    @Mock
    private KeyguardUpdateMonitor mUpdateMonitor;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    @Mock
    private NotificationPanelView mView;
    @Mock
    private LayoutInflater mLayoutInflater;
    @Mock
    private DynamicPrivacyController mDynamicPrivacyController;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private DozeLog mDozeLog;
    @Mock
    private CommandQueue mCommandQueue;
    @Mock
    private VibratorHelper mVibratorHelper;
    @Mock
    private LatencyTracker mLatencyTracker;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private ActivityManager mActivityManager;
    @Mock
    private Resources mResources;
    @Mock
    private Configuration mConfiguration;
    private DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    @Mock
    private KeyguardClockSwitch mKeyguardClockSwitch;
    private PanelViewController.TouchHandler mTouchHandler;
    private ConfigurationController mConfigurationController;
    @Mock
    private MediaHierarchyManager mMediaHiearchyManager;
    @Mock
    private ConversationNotificationManager mConversationNotificationManager;
    @Mock
    private BiometricUnlockController mBiometricUnlockController;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private KeyguardStatusViewComponent.Factory mKeyguardStatusViewComponentFactory;
    @Mock
    private KeyguardQsUserSwitchComponent.Factory mKeyguardQsUserSwitchComponentFactory;
    @Mock
    private KeyguardUserSwitcherComponent.Factory mKeyguardUserSwitcherComponentFactory;
    @Mock
    private IdleViewComponent.Factory mIdleViewComponentFactory;
    @Mock
    private IdleViewComponent mIdleViewComponent;
    @Mock
    private IdleHostViewController mIdleHostViewController;
    @Mock
    private QSDetailDisplayer mQSDetailDisplayer;
    @Mock
    private KeyguardStatusViewComponent mKeyguardStatusViewComponent;
    @Mock
    private KeyguardStatusBarViewComponent.Factory mKeyguardStatusBarViewComponentFactory;
    @Mock
    private KeyguardStatusBarViewComponent mKeyguardStatusBarViewComponent;
    @Mock
    private CommunalViewComponent.Factory mCommunalViewComponentFactory;
    @Mock
    private CommunalViewComponent mCommunalViewComponent;
    @Mock
    private CommunalHostViewController mCommunalHostViewController;
    @Mock
    private CommunalSourceMonitor mCommunalSourceMonitor;
    @Mock
    private CommunalSource mCommunalSource;
    @Mock
    private CommunalHostView mCommunalHostView;
    @Mock
    private KeyguardClockSwitchController mKeyguardClockSwitchController;
    @Mock
    private KeyguardStatusViewController mKeyguardStatusViewController;
    @Mock
    private KeyguardStatusBarViewController mKeyguardStatusBarViewController;
    @Mock
    private NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    @Mock
    private NotificationShadeDepthController mNotificationShadeDepthController;
    @Mock
    private LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock
    private AuthController mAuthController;
    @Mock
    private ScrimController mScrimController;
    @Mock
    private MediaDataManager mMediaDataManager;
    @Mock
    private AmbientState mAmbientState;
    @Mock
    private UserManager mUserManager;
    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private LockIconViewController mLockIconViewController;
    @Mock
    private KeyguardMediaController mKeyguardMediaController;
    @Mock
    private PrivacyDotViewController mPrivacyDotViewController;
    @Mock
    private NavigationModeController mNavigationModeController;
    @Mock
    private SecureSettings mSecureSettings;
    @Mock
    private SplitShadeHeaderController mSplitShadeHeaderController;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private TapAgainViewController mTapAgainViewController;
    @Mock
    private KeyguardIndicationController mKeyguardIndicationController;
    @Mock
    private FragmentService mFragmentService;
    @Mock
    private FragmentHostManager mFragmentHostManager;
    @Mock
    private QuickAccessWalletController mQuickAccessWalletController;
    @Mock
    private NotificationRemoteInputManager mNotificationRemoteInputManager;
    @Mock
    private RecordingController mRecordingController;
    @Mock
    private ControlsComponent mControlsComponent;
    @Mock
    private LockscreenSmartspaceController mLockscreenSmartspaceController;
    @Mock
    private FrameLayout mSplitShadeSmartspaceContainer;

    private SysuiStatusBarStateController mStatusBarStateController;
    private NotificationPanelViewController mNotificationPanelViewController;
    private View.AccessibilityDelegate mAccessibiltyDelegate;
    private NotificationsQuickSettingsContainer mNotificationContainerParent;
    private List<View.OnAttachStateChangeListener> mOnAttachStateChangeListeners;
    private FalsingManagerFake mFalsingManager = new FalsingManagerFake();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mStatusBarStateController = new StatusBarStateControllerImpl(mUiEventLogger);

        mKeyguardStatusView = new KeyguardStatusView(mContext);
        mKeyguardStatusView.setId(R.id.keyguard_status_view);

        when(mAuthController.isUdfpsEnrolled(anyInt())).thenReturn(false);
        when(mHeadsUpCallback.getContext()).thenReturn(mContext);
        when(mView.getResources()).thenReturn(mResources);
        when(mResources.getConfiguration()).thenReturn(mConfiguration);
        mConfiguration.orientation = ORIENTATION_PORTRAIT;
        when(mResources.getDisplayMetrics()).thenReturn(mDisplayMetrics);
        mDisplayMetrics.density = 100;
        when(mResources.getBoolean(R.bool.config_enableNotificationShadeDrag)).thenReturn(true);
        when(mResources.getDimensionPixelSize(R.dimen.notifications_top_padding_split_shade))
                .thenReturn(NOTIFICATION_SCRIM_TOP_PADDING_IN_SPLIT_SHADE);
        when(mResources.getDimensionPixelSize(R.dimen.qs_panel_width)).thenReturn(400);
        when(mResources.getDimensionPixelSize(R.dimen.notification_panel_width)).thenReturn(400);
        when(mView.getContext()).thenReturn(getContext());
        when(mView.findViewById(R.id.keyguard_header)).thenReturn(mKeyguardStatusBar);
        when(mView.findViewById(R.id.keyguard_user_switcher_view)).thenReturn(mUserSwitcherView);
        when(mView.findViewById(R.id.keyguard_user_switcher_stub)).thenReturn(
                mUserSwitcherStubView);
        when(mView.findViewById(R.id.keyguard_clock_container)).thenReturn(mKeyguardClockSwitch);
        when(mView.findViewById(R.id.notification_stack_scroller))
                .thenReturn(mNotificationStackScrollLayout);
        when(mView.findViewById(R.id.communal_host)).thenReturn(mCommunalHostView);
        when(mNotificationStackScrollLayout.getController())
                .thenReturn(mNotificationStackScrollLayoutController);
        when(mNotificationStackScrollLayoutController.getHeight()).thenReturn(1000);
        when(mNotificationStackScrollLayoutController.getHeadsUpCallback())
                .thenReturn(mHeadsUpCallback);
        when(mView.findViewById(R.id.keyguard_bottom_area)).thenReturn(mKeyguardBottomArea);
        when(mKeyguardBottomArea.getLeftView()).thenReturn(mock(KeyguardAffordanceView.class));
        when(mKeyguardBottomArea.getRightView()).thenReturn(mock(KeyguardAffordanceView.class));
        when(mKeyguardBottomArea.animate()).thenReturn(mock(ViewPropertyAnimator.class));
        when(mView.findViewById(R.id.big_clock_container)).thenReturn(mBigClockContainer);
        when(mView.findViewById(R.id.qs_frame)).thenReturn(mQsFrame);
        when(mView.findViewById(R.id.keyguard_status_view))
                .thenReturn(mock(KeyguardStatusView.class));
        when(mView.findViewById(R.id.split_shade_smartspace_container))
                .thenReturn(mSplitShadeSmartspaceContainer);
        mNotificationContainerParent = new NotificationsQuickSettingsContainer(getContext(), null);
        mNotificationContainerParent.addView(newViewWithId(R.id.qs_frame));
        mNotificationContainerParent.addView(newViewWithId(R.id.notification_stack_scroller));
        mNotificationContainerParent.addView(mKeyguardStatusView);
        mNotificationContainerParent.onFinishInflate();
        when(mView.findViewById(R.id.notification_container_parent))
                .thenReturn(mNotificationContainerParent);
        when(mFragmentService.getFragmentHostManager(mView)).thenReturn(mFragmentHostManager);
        FlingAnimationUtils.Builder flingAnimationUtilsBuilder = new FlingAnimationUtils.Builder(
                mDisplayMetrics);

        doAnswer((Answer<Void>) invocation -> {
            mTouchHandler = invocation.getArgument(0);
            return null;
        }).when(mView).setOnTouchListener(any(PanelViewController.TouchHandler.class));

        NotificationWakeUpCoordinator coordinator =
                new NotificationWakeUpCoordinator(
                        mock(HeadsUpManagerPhone.class),
                        new StatusBarStateControllerImpl(new UiEventLoggerFake()),
                        mKeyguardBypassController,
                        mDozeParameters,
                        mUnlockedScreenOffAnimationController);
        mConfigurationController = new ConfigurationControllerImpl(mContext);
        PulseExpansionHandler expansionHandler = new PulseExpansionHandler(
                mContext,
                coordinator,
                mKeyguardBypassController, mHeadsUpManager,
                mock(NotificationRoundnessManager.class),
                mConfigurationController,
                mStatusBarStateController,
                mFalsingManager,
                mLockscreenShadeTransitionController,
                new FalsingCollectorFake());
        when(mKeyguardStatusViewComponentFactory.build(any()))
                .thenReturn(mKeyguardStatusViewComponent);
        when(mKeyguardStatusViewComponent.getKeyguardClockSwitchController())
                .thenReturn(mKeyguardClockSwitchController);
        when(mKeyguardStatusViewComponent.getKeyguardStatusViewController())
                .thenReturn(mKeyguardStatusViewController);
        when(mKeyguardStatusBarViewComponentFactory.build(any()))
                .thenReturn(mKeyguardStatusBarViewComponent);
        when(mKeyguardStatusBarViewComponent.getKeyguardStatusBarViewController())
                .thenReturn(mKeyguardStatusBarViewController);
        when(mCommunalViewComponentFactory.build(any()))
                .thenReturn(mCommunalViewComponent);
        when(mCommunalViewComponent.getCommunalHostViewController())
                .thenReturn(mCommunalHostViewController);
        when(mIdleViewComponentFactory.build(any()))
                .thenReturn(mIdleViewComponent);
        when(mIdleViewComponent.getIdleHostViewController())
                .thenReturn(mIdleHostViewController);
        when(mLayoutInflater.inflate(eq(R.layout.keyguard_status_view), any(), anyBoolean()))
                .thenReturn(mKeyguardStatusView);
        when(mLayoutInflater.inflate(eq(R.layout.keyguard_bottom_area), any(), anyBoolean()))
                .thenReturn(mKeyguardBottomArea);
        when(mNotificationRemoteInputManager.isRemoteInputActive()).thenReturn(false);

        reset(mView);

        mNotificationPanelViewController = new NotificationPanelViewController(mView,
                mResources,
                new Handler(Looper.getMainLooper()),
                mLayoutInflater,
                coordinator, expansionHandler, mDynamicPrivacyController, mKeyguardBypassController,
                mFalsingManager, new FalsingCollectorFake(),
                mNotificationLockscreenUserManager, mNotificationEntryManager,
                mKeyguardStateController, mStatusBarStateController, mDozeLog,
                mDozeParameters, mCommandQueue, mVibratorHelper,
                mLatencyTracker, mPowerManager, mAccessibilityManager, 0, mUpdateMonitor,
                mCommunalSourceMonitor, mMetricsLogger, mActivityManager, mConfigurationController,
                () -> flingAnimationUtilsBuilder, mStatusBarTouchableRegionManager,
                mConversationNotificationManager, mMediaHiearchyManager,
                mBiometricUnlockController, mStatusBarKeyguardViewManager,
                mNotificationStackScrollLayoutController,
                mKeyguardStatusViewComponentFactory,
                mKeyguardQsUserSwitchComponentFactory,
                mKeyguardUserSwitcherComponentFactory,
                mKeyguardStatusBarViewComponentFactory,
                mCommunalViewComponentFactory,
                mIdleViewComponentFactory,
                mLockscreenShadeTransitionController,
                mQSDetailDisplayer,
                mGroupManager,
                mNotificationAreaController,
                mAuthController,
                mScrimController,
                mUserManager,
                mMediaDataManager,
                mNotificationShadeDepthController,
                mAmbientState,
                mLockIconViewController,
                mKeyguardMediaController,
                mPrivacyDotViewController,
                mTapAgainViewController,
                mNavigationModeController,
                mFragmentService,
                mContentResolver,
                mQuickAccessWalletController,
                mRecordingController,
                new FakeExecutor(new FakeSystemClock()),
                mSecureSettings,
                mSplitShadeHeaderController,
                mLockscreenSmartspaceController,
                mUnlockedScreenOffAnimationController,
                mNotificationRemoteInputManager,
                mControlsComponent);
        mNotificationPanelViewController.initDependencies(
                mStatusBar,
                mNotificationShelfController);
        mNotificationPanelViewController.setHeadsUpManager(mHeadsUpManager);
        mNotificationPanelViewController.setBar(mPanelBar);
        mNotificationPanelViewController.setKeyguardIndicationController(
                mKeyguardIndicationController);
        ArgumentCaptor<View.OnAttachStateChangeListener> onAttachStateChangeListenerArgumentCaptor =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);
        verify(mView, atLeast(1)).addOnAttachStateChangeListener(
                onAttachStateChangeListenerArgumentCaptor.capture());
        mOnAttachStateChangeListeners = onAttachStateChangeListenerArgumentCaptor.getAllValues();

        ArgumentCaptor<View.AccessibilityDelegate> accessibilityDelegateArgumentCaptor =
                ArgumentCaptor.forClass(View.AccessibilityDelegate.class);
        verify(mView).setAccessibilityDelegate(accessibilityDelegateArgumentCaptor.capture());
        mAccessibiltyDelegate = accessibilityDelegateArgumentCaptor.getValue();
        mNotificationPanelViewController.mStatusBarStateController
                .addCallback(mNotificationPanelViewController.mStatusBarStateListener);
        mNotificationPanelViewController
                .setHeadsUpAppearanceController(mock(HeadsUpAppearanceController.class));
    }

    @Test
    public void testSetDozing_notifiesNsslAndStateController() {
        mNotificationPanelViewController.setDozing(true /* dozing */, false /* animate */,
                null /* touch */);
        verify(mNotificationStackScrollLayoutController)
                .setDozing(eq(true), eq(false), eq(null));
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
    public void testKeyguardStatusBarVisibility_hiddenForBypass() {
        when(mUpdateMonitor.shouldListenForFace()).thenReturn(true);
        mNotificationPanelViewController.mKeyguardUpdateCallback.onBiometricRunningStateChanged(
                true, BiometricSourceType.FACE);
        verify(mKeyguardStatusBar, never()).setVisibility(View.VISIBLE);

        when(mKeyguardBypassController.getBypassEnabled()).thenReturn(true);
        mNotificationPanelViewController.mKeyguardUpdateCallback.onFinishedGoingToSleep(0);
        mNotificationPanelViewController.mKeyguardUpdateCallback.onBiometricRunningStateChanged(
                true, BiometricSourceType.FACE);
        verify(mKeyguardStatusBar, never()).setVisibility(View.VISIBLE);
    }

    @Test
    public void testA11y_initializeNode() {
        AccessibilityNodeInfo nodeInfo = new AccessibilityNodeInfo();
        mAccessibiltyDelegate.onInitializeAccessibilityNodeInfo(mView, nodeInfo);

        List<AccessibilityNodeInfo.AccessibilityAction> actionList = nodeInfo.getActionList();
        assertThat(actionList).containsAtLeastElementsIn(
                new AccessibilityNodeInfo.AccessibilityAction[] {
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD,
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP}
        );
    }

    @Test
    public void testA11y_scrollForward() {
        mAccessibiltyDelegate.performAccessibilityAction(
                mView,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId(),
                null);

        verify(mStatusBarKeyguardViewManager).showBouncer(true);
    }

    @Test
    public void testA11y_scrollUp() {
        mAccessibiltyDelegate.performAccessibilityAction(
                mView,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId(),
                null);

        verify(mStatusBarKeyguardViewManager).showBouncer(true);
    }

    @Test
    public void testAllChildrenOfNotificationContainer_haveIds() {
        enableSplitShade(/* enabled= */ true);
        mNotificationContainerParent.removeAllViews();
        mNotificationContainerParent.addView(newViewWithId(1));
        mNotificationContainerParent.addView(newViewWithId(View.NO_ID));

        mNotificationPanelViewController.updateResources();

        assertThat(mNotificationContainerParent.getChildAt(0).getId()).isEqualTo(1);
        assertThat(mNotificationContainerParent.getChildAt(1).getId()).isNotEqualTo(View.NO_ID);
    }

    @Test
    public void testSinglePaneShadeLayout_isAlignedToParent() {
        enableSplitShade(/* enabled= */ false);

        mNotificationPanelViewController.updateResources();

        assertThat(getConstraintSetLayout(R.id.qs_frame).endToEnd)
                .isEqualTo(ConstraintSet.PARENT_ID);
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).startToStart)
                .isEqualTo(ConstraintSet.PARENT_ID);
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
        when(mUserManager.isUserSwitcherEnabled()).thenReturn(true);

        updateSmallestScreenWidth(800);

        verify(mUserSwitcherStubView).inflate();
    }

    @Test
    public void testSplitShadeLayout_isAlignedToGuideline() {
        enableSplitShade(/* enabled= */ true);

        mNotificationPanelViewController.updateResources();

        assertThat(getConstraintSetLayout(R.id.qs_frame).endToEnd)
                .isEqualTo(R.id.qs_edge_guideline);
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).startToStart)
                .isEqualTo(R.id.qs_edge_guideline);
    }

    @Test
    public void testSinglePaneShadeLayout_childrenHaveConstantWidth() {
        enableSplitShade(/* enabled= */ false);

        mNotificationPanelViewController.updateResources();

        assertThat(getConstraintSetLayout(R.id.qs_frame).mWidth)
                .isEqualTo(mResources.getDimensionPixelSize(R.dimen.qs_panel_width));
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).mWidth)
                .isEqualTo(mResources.getDimensionPixelSize(R.dimen.notification_panel_width));
    }

    @Test
    public void testSplitShadeLayout_childrenHaveZeroWidth() {
        enableSplitShade(/* enabled= */ true);

        mNotificationPanelViewController.updateResources();

        assertThat(getConstraintSetLayout(R.id.qs_frame).mWidth).isEqualTo(0);
        assertThat(getConstraintSetLayout(R.id.notification_stack_scroller).mWidth).isEqualTo(0);
    }

    @Test
    public void testOnDragDownEvent_horizontalTranslationIsZeroForSplitShade() {
        when(mNotificationStackScrollLayoutController.getWidth()).thenReturn(350f);
        when(mView.getWidth()).thenReturn(800);
        enableSplitShade(/* enabled= */ true);

        onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN,
                200f /* x position */, 0f, 0));

        verify(mQsFrame).setTranslationX(0);
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
    public void testDoubleTapRequired_Keyguard() {
        FalsingManager.FalsingTapListener listener = getFalsingTapListener();
        mStatusBarStateController.setState(KEYGUARD);

        listener.onDoubleTapRequired();

        verify(mKeyguardIndicationController).showTransientIndication(anyInt());
    }

    @Test
    public void testDoubleTapRequired_ShadeLocked() {
        FalsingManager.FalsingTapListener listener = getFalsingTapListener();
        mStatusBarStateController.setState(SHADE_LOCKED);

        listener.onDoubleTapRequired();

        verify(mTapAgainViewController).show();
    }

    @Test
    public void testSwitchesToCorrectClockInSinglePaneShade() {
        mStatusBarStateController.setState(KEYGUARD);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController).displayClock(LARGE);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        mNotificationPanelViewController.closeQs();
        verify(mKeyguardStatusViewController).displayClock(SMALL);
    }

    @Test
    public void testSwitchesToCorrectClockInSplitShade() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController).displayClock(LARGE);

        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController, times(2)).displayClock(LARGE);
        verify(mKeyguardStatusViewController, never()).displayClock(SMALL);
    }

    @Test
    public void testDisplaysSmallClockOnLockscreenInSplitShadeWhenMediaIsPlaying() {
        mStatusBarStateController.setState(KEYGUARD);
        enableSplitShade(/* enabled= */ true);
        when(mMediaDataManager.hasActiveMedia()).thenReturn(true);

        // one notification + media player visible
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController).displayClock(SMALL);

        // only media player visible
        when(mNotificationStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        triggerPositionClockAndNotifications();
        verify(mKeyguardStatusViewController, times(2)).displayClock(SMALL);
        verify(mKeyguardStatusViewController, never()).displayClock(LARGE);
    }

    @Test
    public void testCommunalhostViewControllerInit() {
        clearInvocations(mCommunalHostViewController);
        givenViewAttached();
        verify(mCommunalHostViewController).init();
    }

    @Test
    public void testCommunalSourceListening() {
        final ArgumentCaptor<CommunalSourceMonitor.Callback> monitorCallback =
                ArgumentCaptor.forClass(CommunalSourceMonitor.Callback.class);

        givenViewAttached();
        verify(mCommunalSourceMonitor).addCallback(monitorCallback.capture());

        final ArgumentCaptor<WeakReference<CommunalSource>> sourceCapture =
                ArgumentCaptor.forClass(WeakReference.class);

        monitorCallback.getValue().onSourceAvailable(new WeakReference<>(mCommunalSource));
        verify(mCommunalHostViewController).show(sourceCapture.capture());
        assertThat(sourceCapture.getValue().get()).isEqualTo(mCommunalSource);

        clearInvocations(mCommunalHostViewController);
        givenViewDetached();
        verify(mCommunalSourceMonitor).removeCallback(any());
        verify(mCommunalHostViewController).show(sourceCapture.capture());
        assertThat(sourceCapture.getValue()).isEqualTo(null);
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

    private void givenViewDetached() {
        for (View.OnAttachStateChangeListener listener : mOnAttachStateChangeListeners) {
            listener.onViewDetachedFromWindow(mView);
        }
    }


    private View newViewWithId(int id) {
        View view = new View(mContext);
        view.setId(id);
        ConstraintLayout.LayoutParams layoutParams = new ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // required as cloning ConstraintSet fails if view doesn't have layout params
        view.setLayoutParams(layoutParams);
        return view;
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
        when(mUserManager.isUserSwitcherEnabled()).thenReturn(enabled);
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
}
