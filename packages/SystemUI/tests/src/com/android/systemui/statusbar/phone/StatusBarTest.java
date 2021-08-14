/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IWallpaperManager;
import android.app.Notification;
import android.app.WallpaperManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.IntentFilter;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.fingerprint.FingerprintManager;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.dreams.IDreamManager;
import android.support.test.metricshelper.MetricsAsserts;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.InitController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.ScreenPinningRequest;
import com.android.systemui.settings.brightness.BrightnessSlider;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SuperStatusBarViewFactory;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.legacy.VisualStabilityManager;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.interruption.BypassHeadsUpNotifier;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.logging.NotificationPanelLoggerFake;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.unfold.UnfoldLightRevealOverlayAnimation;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.volume.VolumeComponent;
import com.android.systemui.wmshell.BubblesManager;
import com.android.unfold.config.UnfoldTransitionConfig;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.startingsurface.StartingSurface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.Optional;

import javax.inject.Provider;

import dagger.Lazy;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
public class StatusBarTest extends SysuiTestCase {

    private StatusBar mStatusBar;
    private FakeMetricsLogger mMetricsLogger;
    private PowerManager mPowerManager;
    private TestableNotificationInterruptStateProviderImpl mNotificationInterruptStateProvider;

    @Mock private NotificationsController mNotificationsController;
    @Mock private LightBarController mLightBarController;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private KeyguardIndicationController mKeyguardIndicationController;
    @Mock private NotificationStackScrollLayout mStackScroller;
    @Mock private NotificationStackScrollLayoutController mStackScrollerController;
    @Mock private NotificationListContainer mNotificationListContainer;
    @Mock private HeadsUpManagerPhone mHeadsUpManager;
    @Mock private NotificationPanelViewController mNotificationPanelViewController;
    @Mock private NotificationPanelView mNotificationPanelView;
    @Mock private IStatusBarService mBarService;
    @Mock private IDreamManager mDreamManager;
    @Mock private ScrimController mScrimController;
    @Mock private DozeScrimController mDozeScrimController;
    @Mock private Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    @Mock private BiometricUnlockController mBiometricUnlockController;
    @Mock private VisualStabilityManager mVisualStabilityManager;
    @Mock private NotificationListener mNotificationListener;
    @Mock private KeyguardViewMediator mKeyguardViewMediator;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private StatusBarStateControllerImpl mStatusBarStateController;
    @Mock private BatteryController mBatteryController;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private StatusBarNotificationPresenter mNotificationPresenter;
    @Mock private NotificationFilter mNotificationFilter;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    @Mock private NotificationLogger.ExpansionStateLogger mExpansionStateLogger;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private NotificationShadeWindowView mNotificationShadeWindowView;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private AssistManager mAssistManager;
    @Mock private NotificationEntryManager mNotificationEntryManager;
    @Mock private NotificationGutsManager mNotificationGutsManager;
    @Mock private NotificationMediaManager mNotificationMediaManager;
    @Mock private NavigationBarController mNavigationBarController;
    @Mock private BypassHeadsUpNotifier mBypassHeadsUpNotifier;
    @Mock private SysuiColorExtractor mColorExtractor;
    @Mock private ColorExtractor.GradientColors mGradientColors;
    @Mock private PulseExpansionHandler mPulseExpansionHandler;
    @Mock private NotificationWakeUpCoordinator mNotificationWakeUpCoordinator;
    @Mock private KeyguardBypassController mKeyguardBypassController;
    @Mock private DynamicPrivacyController mDynamicPrivacyController;
    @Mock private AutoHideController mAutoHideController;
    @Mock private NotificationViewHierarchyManager mNotificationViewHierarchyManager;
    @Mock private UserSwitcherController mUserSwitcherController;
    @Mock private NetworkController mNetworkController;
    @Mock private BubblesManager mBubblesManager;
    @Mock private Bubbles mBubbles;
    @Mock private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock private NotificationIconAreaController mNotificationIconAreaController;
    @Mock private NotificationShadeWindowViewController mNotificationShadeWindowViewController;
    @Mock private DozeParameters mDozeParameters;
    @Mock private Lazy<LockscreenWallpaper> mLockscreenWallpaperLazy;
    @Mock private LockscreenGestureLogger mLockscreenGestureLogger;
    @Mock private LockscreenWallpaper mLockscreenWallpaper;
    @Mock private DozeServiceHost mDozeServiceHost;
    @Mock private ViewMediatorCallback mKeyguardVieMediatorCallback;
    @Mock private VolumeComponent mVolumeComponent;
    @Mock private CommandQueue mCommandQueue;
    @Mock private Provider<StatusBarComponent.Builder> mStatusBarComponentBuilderProvider;
    @Mock private StatusBarComponent.Builder mStatusBarComponentBuilder;
    @Mock private StatusBarComponent mStatusBarComponent;
    @Mock private PluginManager mPluginManager;
    @Mock private LegacySplitScreen mLegacySplitScreen;
    @Mock private SuperStatusBarViewFactory mSuperStatusBarViewFactory;
    @Mock private LightsOutNotifController mLightsOutNotifController;
    @Mock private ViewMediatorCallback mViewMediatorCallback;
    @Mock private StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    @Mock private ScreenPinningRequest mScreenPinningRequest;
    @Mock private StatusBarNotificationActivityStarter.Builder
            mStatusBarNotificationActivityStarterBuilder;
    @Mock private PluginDependencyProvider mPluginDependencyProvider;
    @Mock private KeyguardDismissUtil mKeyguardDismissUtil;
    @Mock private ExtensionController mExtensionController;
    @Mock private UserInfoControllerImpl mUserInfoControllerImpl;
    @Mock private PhoneStatusBarPolicy mPhoneStatusBarPolicy;
    @Mock private DemoModeController mDemoModeController;
    @Mock private Lazy<NotificationShadeDepthController> mNotificationShadeDepthControllerLazy;
    @Mock private BrightnessSlider.Factory mBrightnessSliderFactory;
    @Mock private UnfoldTransitionConfig mUnfoldTransitionConfig;
    @Mock private Lazy<UnfoldLightRevealOverlayAnimation> mUnfoldLightRevealOverlayAnimationLazy;
    @Mock private OngoingCallController mOngoingCallController;
    @Mock private SystemStatusAnimationScheduler mAnimationScheduler;
    @Mock private StatusBarLocationPublisher mLocationPublisher;
    @Mock private StatusBarIconController mIconController;
    @Mock private LockscreenShadeTransitionController mLockscreenTransitionController;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private WallpaperManager mWallpaperManager;
    @Mock private IWallpaperManager mIWallpaperManager;
    @Mock private KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    @Mock private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    @Mock private TunerService mTunerService;
    @Mock private StartingSurface mStartingSurface;
    private ShadeController mShadeController;
    private FakeExecutor mUiBgExecutor = new FakeExecutor(new FakeSystemClock());
    private InitController mInitController = new InitController();

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(NotificationFilter.class, mNotificationFilter);

        IPowerManager powerManagerService = mock(IPowerManager.class);
        IThermalService thermalService = mock(IThermalService.class);
        mPowerManager = new PowerManager(mContext, powerManagerService, thermalService,
                Handler.createAsync(Looper.myLooper()));

        mNotificationInterruptStateProvider =
                new TestableNotificationInterruptStateProviderImpl(mContext.getContentResolver(),
                        mPowerManager,
                        mDreamManager, mAmbientDisplayConfiguration, mNotificationFilter,
                        mStatusBarStateController, mBatteryController, mHeadsUpManager,
                        new Handler(TestableLooper.get(this).getLooper()));

        mContext.addMockSystemService(TrustManager.class, mock(TrustManager.class));
        mContext.addMockSystemService(FingerprintManager.class, mock(FingerprintManager.class));

        mMetricsLogger = new FakeMetricsLogger();
        NotificationLogger notificationLogger = new NotificationLogger(mNotificationListener,
                mUiBgExecutor, mock(NotificationEntryManager.class), mStatusBarStateController,
                mExpansionStateLogger, new NotificationPanelLoggerFake());
        notificationLogger.setVisibilityReporter(mock(Runnable.class));

        when(mCommandQueue.asBinder()).thenReturn(new Binder());

        mContext.setTheme(R.style.Theme_SystemUI_LightWallpaper);

        when(mStackScroller.getController()).thenReturn(mStackScrollerController);
        when(mStackScrollerController.getView()).thenReturn(mStackScroller);
        when(mStackScrollerController.getNotificationListContainer()).thenReturn(
                mNotificationListContainer);
        when(mStackScroller.generateLayoutParams(any())).thenReturn(new LayoutParams(0, 0));
        when(mNotificationPanelViewController.getView()).thenReturn(mNotificationPanelView);
        when(mNotificationPanelView.getLayoutParams()).thenReturn(new LayoutParams(0, 0));
        when(powerManagerService.isInteractive()).thenReturn(true);
        when(mStackScroller.getActivatedChild()).thenReturn(null);

        doAnswer(invocation -> {
            OnDismissAction onDismissAction = (OnDismissAction) invocation.getArguments()[0];
            onDismissAction.onDismiss();
            return null;
        }).when(mStatusBarKeyguardViewManager).dismissWithAction(any(), any(), anyBoolean());

        doAnswer(invocation -> {
            Runnable runnable = (Runnable) invocation.getArguments()[0];
            runnable.run();
            return null;
        }).when(mStatusBarKeyguardViewManager).addAfterKeyguardGoneRunnable(any());

        WakefulnessLifecycle wakefulnessLifecycle =
                new WakefulnessLifecycle(mContext, mIWallpaperManager);
        wakefulnessLifecycle.dispatchStartedWakingUp(PowerManager.WAKE_REASON_UNKNOWN);
        wakefulnessLifecycle.dispatchFinishedWakingUp();

        when(mGradientColors.supportsDarkText()).thenReturn(true);
        when(mColorExtractor.getNeutralColors()).thenReturn(mGradientColors);
        ConfigurationController configurationController = new ConfigurationControllerImpl(mContext);

        when(mLockscreenWallpaperLazy.get()).thenReturn(mLockscreenWallpaper);
        when(mBiometricUnlockControllerLazy.get()).thenReturn(mBiometricUnlockController);

        when(mStatusBarComponentBuilderProvider.get()).thenReturn(mStatusBarComponentBuilder);
        when(mStatusBarComponentBuilder.build()).thenReturn(mStatusBarComponent);
        when(mStatusBarComponent.getNotificationShadeWindowViewController()).thenReturn(
                mNotificationShadeWindowViewController);

        mShadeController = new ShadeControllerImpl(mCommandQueue,
                mStatusBarStateController, mNotificationShadeWindowController,
                mStatusBarKeyguardViewManager, mContext.getSystemService(WindowManager.class),
                () -> Optional.of(mStatusBar), () -> mAssistManager, Optional.of(mBubbles));

        mStatusBar = new StatusBar(
                mContext,
                mNotificationsController,
                mLightBarController,
                mAutoHideController,
                mKeyguardUpdateMonitor,
                mPulseExpansionHandler,
                mNotificationWakeUpCoordinator,
                mKeyguardBypassController,
                mKeyguardStateController,
                mHeadsUpManager,
                mDynamicPrivacyController,
                mBypassHeadsUpNotifier,
                new FalsingManagerFake(),
                new FalsingCollectorFake(),
                mBroadcastDispatcher,
                mNotificationEntryManager,
                mNotificationGutsManager,
                notificationLogger,
                mNotificationInterruptStateProvider,
                mNotificationViewHierarchyManager,
                mKeyguardViewMediator,
                new DisplayMetrics(),
                mMetricsLogger,
                mUiBgExecutor,
                mNotificationMediaManager,
                mLockscreenUserManager,
                mRemoteInputManager,
                mUserSwitcherController,
                mNetworkController,
                mBatteryController,
                mColorExtractor,
                new ScreenLifecycle(),
                wakefulnessLifecycle,
                mStatusBarStateController,
                Optional.of(mBubblesManager),
                Optional.of(mBubbles),
                mVisualStabilityManager,
                mDeviceProvisionedController,
                mNavigationBarController,
                () -> mAssistManager,
                configurationController,
                mNotificationShadeWindowController,
                mDozeParameters,
                mScrimController,
                mLockscreenWallpaperLazy,
                mLockscreenGestureLogger,
                mBiometricUnlockControllerLazy,
                mDozeServiceHost,
                mPowerManager, mScreenPinningRequest,
                mDozeScrimController,
                mVolumeComponent,
                mCommandQueue,
                mStatusBarComponentBuilderProvider,
                mPluginManager,
                Optional.of(mLegacySplitScreen),
                mLightsOutNotifController,
                mStatusBarNotificationActivityStarterBuilder,
                mShadeController,
                mSuperStatusBarViewFactory,
                mStatusBarKeyguardViewManager,
                mViewMediatorCallback,
                mInitController,
                new Handler(TestableLooper.get(this).getLooper()),
                mPluginDependencyProvider,
                mKeyguardDismissUtil,
                mExtensionController,
                mUserInfoControllerImpl,
                mPhoneStatusBarPolicy,
                mKeyguardIndicationController,
                mDemoModeController,
                mNotificationShadeDepthControllerLazy,
                mStatusBarTouchableRegionManager,
                mNotificationIconAreaController,
                mBrightnessSliderFactory,
                mUnfoldTransitionConfig,
                mUnfoldLightRevealOverlayAnimationLazy,
                mOngoingCallController,
                mAnimationScheduler,
                mLocationPublisher,
                mIconController,
                mLockscreenTransitionController,
                mFeatureFlags,
                mKeyguardUnlockAnimationController,
                mWallpaperManager,
                mUnlockedScreenOffAnimationController,
                Optional.of(mStartingSurface),
                mTunerService);
        when(mKeyguardViewMediator.registerStatusBar(any(StatusBar.class), any(ViewGroup.class),
                any(NotificationPanelViewController.class), any(BiometricUnlockController.class),
                any(ViewGroup.class), any(KeyguardBypassController.class)))
                .thenReturn(mStatusBarKeyguardViewManager);

        when(mKeyguardViewMediator.getViewMediatorCallback()).thenReturn(
                mKeyguardVieMediatorCallback);

        // TODO: we should be able to call mStatusBar.start() and have all the below values
        // initialized automatically.
        mStatusBar.mNotificationShadeWindowView = mNotificationShadeWindowView;
        mStatusBar.mNotificationPanelViewController = mNotificationPanelViewController;
        mStatusBar.mDozeScrimController = mDozeScrimController;
        mStatusBar.mPresenter = mNotificationPresenter;
        mStatusBar.mKeyguardIndicationController = mKeyguardIndicationController;
        mStatusBar.mBarService = mBarService;
        mStatusBar.mStackScroller = mStackScroller;
        mStatusBar.startKeyguard();
        mInitController.executePostInitTasks();
        notificationLogger.setUpWithContainer(mNotificationListContainer);
    }

    @Test
    public void testSetBouncerShowing_noCrash() {
        mStatusBar.setBouncerShowing(true);
    }

    @Test
    public void executeRunnableDismissingKeyguard_nullRunnable_showingAndOccluded() {
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(true);

        mStatusBar.executeRunnableDismissingKeyguard(null, null, false, false, false);
    }

    @Test
    public void executeRunnableDismissingKeyguard_nullRunnable_showing() {
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);

        mStatusBar.executeRunnableDismissingKeyguard(null, null, false, false, false);
    }

    @Test
    public void executeRunnableDismissingKeyguard_nullRunnable_notShowing() {
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);

        mStatusBar.executeRunnableDismissingKeyguard(null, null, false, false, false);
    }

    @Test
    public void lockscreenStateMetrics_notShowing() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardStateController.isMethodSecure()).thenReturn(false);
        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing hidden insecure lockscreen log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_CLOSE)
                        .setSubtype(0));
    }

    @Test
    public void lockscreenStateMetrics_notShowing_secure() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardStateController.isMethodSecure()).thenReturn(true);

        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing hidden secure lockscreen log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_CLOSE)
                        .setSubtype(1));
    }

    @Test
    public void lockscreenStateMetrics_isShowing() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardStateController.isMethodSecure()).thenReturn(false);

        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing insecure lockscreen showing",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(0));
    }

    @Test
    public void lockscreenStateMetrics_isShowing_secure() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardStateController.isMethodSecure()).thenReturn(true);

        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing secure lockscreen showing log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(1));
    }

    @Test
    public void lockscreenStateMetrics_isShowingBouncer() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        // interesting state
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);
        when(mKeyguardStateController.isMethodSecure()).thenReturn(true);

        mStatusBar.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing bouncer log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.BOUNCER)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(1));
    }

    @Test
    public void testShouldHeadsUp_nonSuppressedGroupSummary() throws Exception {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);

        Notification n = new Notification.Builder(getContext(), "a")
                .setGroup("a")
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .build();

        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .setImportance(IMPORTANCE_HIGH)
                .build();

        assertTrue(mNotificationInterruptStateProvider.shouldHeadsUp(entry));
    }

    @Test
    public void testShouldHeadsUp_suppressedGroupSummary() throws Exception {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);

        Notification n = new Notification.Builder(getContext(), "a")
                .setGroup("a")
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .build();

        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .setImportance(IMPORTANCE_HIGH)
                .build();

        assertFalse(mNotificationInterruptStateProvider.shouldHeadsUp(entry));
    }

    @Test
    public void testShouldHeadsUp_suppressedHeadsUp() throws Exception {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);

        Notification n = new Notification.Builder(getContext(), "a").build();

        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .setImportance(IMPORTANCE_HIGH)
                .setSuppressedVisualEffects(SUPPRESSED_EFFECT_PEEK)
                .build();

        assertFalse(mNotificationInterruptStateProvider.shouldHeadsUp(entry));
    }

    @Test
    public void testShouldHeadsUp_noSuppressedHeadsUp() throws Exception {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);

        Notification n = new Notification.Builder(getContext(), "a").build();

        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .setImportance(IMPORTANCE_HIGH)
                .build();

        assertTrue(mNotificationInterruptStateProvider.shouldHeadsUp(entry));
    }

    @Test
    public void testLogHidden() {
        try {
            mStatusBar.handleVisibleToUserChanged(false);
            mUiBgExecutor.runAllReady();
            verify(mBarService, times(1)).onPanelHidden();
            verify(mBarService, never()).onPanelRevealed(anyBoolean(), anyInt());
        } catch (RemoteException e) {
            fail();
        }
    }

    @Test
    public void testPanelOpenForHeadsUp() {
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(true);
        when(mNotificationsController.getActiveNotificationsCount()).thenReturn(5);
        when(mNotificationPresenter.isPresenterFullyCollapsed()).thenReturn(true);
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);

        try {
            mStatusBar.handleVisibleToUserChanged(true);
            mUiBgExecutor.runAllReady();
            verify(mBarService, never()).onPanelHidden();
            verify(mBarService, times(1)).onPanelRevealed(false, 1);
        } catch (RemoteException e) {
            fail();
        }
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testPanelOpenAndClear() {
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(false);
        when(mNotificationsController.getActiveNotificationsCount()).thenReturn(5);

        when(mNotificationPresenter.isPresenterFullyCollapsed()).thenReturn(false);
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);

        try {
            mStatusBar.handleVisibleToUserChanged(true);
            mUiBgExecutor.runAllReady();
            verify(mBarService, never()).onPanelHidden();
            verify(mBarService, times(1)).onPanelRevealed(true, 5);
        } catch (RemoteException e) {
            fail();
        }
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testPanelOpenAndNoClear() {
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(false);
        when(mNotificationsController.getActiveNotificationsCount()).thenReturn(5);
        when(mNotificationPresenter.isPresenterFullyCollapsed()).thenReturn(false);
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);

        try {
            mStatusBar.handleVisibleToUserChanged(true);
            mUiBgExecutor.runAllReady();
            verify(mBarService, never()).onPanelHidden();
            verify(mBarService, times(1)).onPanelRevealed(false, 5);
        } catch (RemoteException e) {
            fail();
        }
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testDump_DoesNotCrash() {
        mStatusBar.dump(null, new PrintWriter(new ByteArrayOutputStream()), null);
    }

    @Test
    public void testDumpBarTransitions_DoesNotCrash() {
        StatusBar.dumpBarTransitions(
                new PrintWriter(new ByteArrayOutputStream()), "var", /* transitions= */ null);
    }

    @Test
    @RunWithLooper(setAsMainLooper = true)
    public void testUpdateKeyguardState_DoesNotCrash() {
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);
        when(mLockscreenUserManager.getCurrentProfiles()).thenReturn(
                new SparseArray<>());
        mStatusBar.onStateChanged(StatusBarState.SHADE);
    }

    @Test
    public void testFingerprintNotification_UpdatesScrims() {
        mStatusBar.notifyBiometricAuthModeChanged();
        verify(mScrimController).transitionTo(any(), any());
    }

    @Test
    public void testFingerprintUnlock_UpdatesScrims() {
        // Simulate unlocking from AoD with fingerprint.
        when(mBiometricUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_WAKE_AND_UNLOCK);
        mStatusBar.updateScrimController();
        verify(mScrimController).transitionTo(eq(ScrimState.UNLOCKED), any());
    }

    @Test
    public void testSetOccluded_propagatesToScrimController() {
        mStatusBar.setOccluded(true);
        verify(mScrimController).setKeyguardOccluded(eq(true));

        reset(mScrimController);
        mStatusBar.setOccluded(false);
        verify(mScrimController).setKeyguardOccluded(eq(false));
    }

    @Test
    public void testPulseWhileDozing_updatesScrimController() {
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);
        mStatusBar.showKeyguardImpl();

        // Starting a pulse should change the scrim controller to the pulsing state
        when(mDozeServiceHost.isPulsing()).thenReturn(true);
        mStatusBar.updateScrimController();
        verify(mScrimController).transitionTo(eq(ScrimState.PULSING), any());

        // Ending a pulse should take it back to keyguard state
        when(mDozeServiceHost.isPulsing()).thenReturn(false);
        mStatusBar.updateScrimController();
        verify(mScrimController).transitionTo(eq(ScrimState.KEYGUARD));
    }

    @Test
    public void testSetState_changesIsFullScreenUserSwitcherState() {
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);
        assertFalse(mStatusBar.isFullScreenUserSwitcherState());

        mStatusBar.setBarStateForTest(StatusBarState.FULLSCREEN_USER_SWITCHER);
        assertTrue(mStatusBar.isFullScreenUserSwitcherState());
    }

    @Test
    public void testShowKeyguardImplementation_setsState() {
        when(mLockscreenUserManager.getCurrentProfiles()).thenReturn(new SparseArray<>());

        mStatusBar.setBarStateForTest(StatusBarState.SHADE);

        // By default, showKeyguardImpl sets state to KEYGUARD.
        mStatusBar.showKeyguardImpl();
        verify(mStatusBarStateController).setState(
                eq(StatusBarState.KEYGUARD), eq(false) /* force */);

        // If useFullscreenUserSwitcher is true, state is set to FULLSCREEN_USER_SWITCHER.
        when(mUserSwitcherController.useFullscreenUserSwitcher()).thenReturn(true);
        mStatusBar.showKeyguardImpl();
        verify(mStatusBarStateController).setState(
                eq(StatusBarState.FULLSCREEN_USER_SWITCHER), eq(false) /* force */);
    }

    @Test
    public void testOnStartedWakingUp_isNotDozing() {
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);
        when(mStatusBarStateController.isKeyguardRequested()).thenReturn(true);
        when(mDozeServiceHost.getDozingRequested()).thenReturn(true);
        mStatusBar.updateIsKeyguard();
        // TODO: mNotificationPanelView.expand(false) gets called twice. Should be once.
        verify(mNotificationPanelViewController, times(2)).expand(eq(false));
        clearInvocations(mNotificationPanelViewController);

        mStatusBar.mWakefulnessObserver.onStartedWakingUp();
        verify(mDozeServiceHost).stopDozing();
        verify(mNotificationPanelViewController).expand(eq(false));
    }

    @Test
    public void testOnStartedWakingUp_doesNotDismissBouncer_whenPulsing() {
        mStatusBar.setBarStateForTest(StatusBarState.KEYGUARD);
        when(mStatusBarStateController.isKeyguardRequested()).thenReturn(true);
        when(mDozeServiceHost.getDozingRequested()).thenReturn(true);
        mStatusBar.updateIsKeyguard();
        clearInvocations(mNotificationPanelViewController);

        mStatusBar.setBouncerShowing(true);
        mStatusBar.mWakefulnessObserver.onStartedWakingUp();
        verify(mNotificationPanelViewController, never()).expand(anyBoolean());
    }

    @Test
    public void testRegisterBroadcastsonDispatcher() {
        mStatusBar.registerBroadcastReceiver();
        verify(mBroadcastDispatcher).registerReceiver(
                any(BroadcastReceiver.class),
                any(IntentFilter.class),
                eq(null),
                any(UserHandle.class));
    }

    @Test
    public void testUpdateResources_updatesBouncer() {
        mStatusBar.updateResources();

        verify(mStatusBarKeyguardViewManager).updateResources();
    }

    public static class TestableNotificationInterruptStateProviderImpl extends
            NotificationInterruptStateProviderImpl {

        TestableNotificationInterruptStateProviderImpl(
                ContentResolver contentResolver,
                PowerManager powerManager,
                IDreamManager dreamManager,
                AmbientDisplayConfiguration ambientDisplayConfiguration,
                NotificationFilter filter,
                StatusBarStateController controller,
                BatteryController batteryController,
                HeadsUpManager headsUpManager,
                Handler mainHandler) {
            super(contentResolver, powerManager, dreamManager, ambientDisplayConfiguration, filter,
                    batteryController, controller, headsUpManager, mainHandler);
            mUseHeadsUp = true;
        }
    }
}
