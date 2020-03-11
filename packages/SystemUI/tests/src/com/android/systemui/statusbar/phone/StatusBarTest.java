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
import static android.view.Display.DEFAULT_DISPLAY;

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

import android.app.Notification;
import android.app.StatusBarManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.Context;
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
import android.widget.LinearLayout;

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
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.ScreenPinningRequest;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SuperStatusBarViewFactory;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.BypassHeadsUpNotifier;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationAlertingManager;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.logging.NotificationPanelLoggerFake;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.volume.VolumeComponent;

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
    private TestableNotificationInterruptionStateProvider mNotificationInterruptionStateProvider;

    @Mock private NotificationsController mNotificationsController;
    @Mock private LightBarController mLightBarController;
    @Mock private StatusBarIconController mStatusBarIconController;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private KeyguardIndicationController mKeyguardIndicationController;
    @Mock private NotificationStackScrollLayout mStackScroller;
    @Mock private HeadsUpManagerPhone mHeadsUpManager;
    @Mock private NotificationPanelViewController mNotificationPanelViewController;
    @Mock private NotificationPanelView mNotificationPanelView;
    @Mock private IStatusBarService mBarService;
    @Mock private IDreamManager mDreamManager;
    @Mock private ScrimController mScrimController;
    @Mock private DozeScrimController mDozeScrimController;
    @Mock private Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    @Mock private BiometricUnlockController mBiometricUnlockController;
    @Mock private NotificationInterruptionStateProvider.HeadsUpSuppressor mHeadsUpSuppressor;
    @Mock private VisualStabilityManager mVisualStabilityManager;
    @Mock private NotificationListener mNotificationListener;
    @Mock private KeyguardViewMediator mKeyguardViewMediator;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private RemoteInputController mRemoteInputController;
    @Mock private StatusBarStateControllerImpl mStatusBarStateController;
    @Mock private BatteryController mBatteryController;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private StatusBarNotificationPresenter mNotificationPresenter;
    @Mock private NotificationEntryListener mEntryListener;
    @Mock private NotificationFilter mNotificationFilter;
    @Mock private NotificationAlertingManager mNotificationAlertingManager;
    @Mock private NotificationLogger.ExpansionStateLogger mExpansionStateLogger;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    @Mock private NotificationShadeWindowView mNotificationShadeWindowView;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private AssistManager mAssistManager;
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
    @Mock private VibratorHelper mVibratorHelper;
    @Mock private BubbleController mBubbleController;
    @Mock private NotificationGroupManager mGroupManager;
    @Mock private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock private NotificationIconAreaController mNotificationIconAreaController;
    @Mock private NotificationShadeWindowViewController mNotificationShadeWindowViewController;
    @Mock private DozeParameters mDozeParameters;
    @Mock private Lazy<LockscreenWallpaper> mLockscreenWallpaperLazy;
    @Mock private LockscreenWallpaper mLockscreenWallpaper;
    @Mock private DozeServiceHost mDozeServiceHost;
    @Mock private LinearLayout mLockIconContainer;
    @Mock private ViewMediatorCallback mKeyguardVieMediatorCallback;
    @Mock private KeyguardLiftController mKeyguardLiftController;
    @Mock private VolumeComponent mVolumeComponent;
    @Mock private CommandQueue mCommandQueue;
    @Mock private Recents mRecents;
    @Mock private Provider<StatusBarComponent.Builder> mStatusBarComponentBuilderProvider;
    @Mock private StatusBarComponent.Builder mStatusBarComponentBuilder;
    @Mock private StatusBarComponent mStatusBarComponent;
    @Mock private PluginManager mPluginManager;
    @Mock private Divider mDivider;
    @Mock private SuperStatusBarViewFactory mSuperStatusBarViewFactory;
    @Mock private LightsOutNotifController mLightsOutNotifController;
    @Mock private ViewMediatorCallback mViewMediatorCallback;
    @Mock private DismissCallbackRegistry mDismissCallbackRegistry;
    @Mock private StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    @Mock private ScreenPinningRequest mScreenPinningRequest;
    @Mock private LockscreenLockIconController mLockscreenLockIconController;
    @Mock private StatusBarNotificationActivityStarter.Builder
            mStatusBarNotificationActivityStarterBuilder;
    @Mock private DarkIconDispatcher mDarkIconDispatcher;
    @Mock private PluginDependencyProvider mPluginDependencyProvider;
    @Mock private KeyguardDismissUtil mKeyguardDismissUtil;
    @Mock private ExtensionController mExtensionController;
    @Mock private UserInfoControllerImpl mUserInfoControllerImpl;
    @Mock private PhoneStatusBarPolicy mPhoneStatusBarPolicy;
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

        mNotificationInterruptionStateProvider =
                new TestableNotificationInterruptionStateProvider(mContext, mPowerManager,
                        mDreamManager, mAmbientDisplayConfiguration, mNotificationFilter,
                        mStatusBarStateController, mBatteryController);

        mContext.addMockSystemService(TrustManager.class, mock(TrustManager.class));
        mContext.addMockSystemService(FingerprintManager.class, mock(FingerprintManager.class));

        mMetricsLogger = new FakeMetricsLogger();
        NotificationLogger notificationLogger = new NotificationLogger(mNotificationListener,
                mUiBgExecutor, mock(NotificationEntryManager.class), mStatusBarStateController,
                mExpansionStateLogger, new NotificationPanelLoggerFake());
        notificationLogger.setVisibilityReporter(mock(Runnable.class));

        when(mCommandQueue.asBinder()).thenReturn(new Binder());

        mContext.setTheme(R.style.Theme_SystemUI_Light);

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

        mNotificationInterruptionStateProvider.setUpWithPresenter(mNotificationPresenter,
                mHeadsUpManager, mHeadsUpSuppressor);

        when(mRemoteInputManager.getController()).thenReturn(mRemoteInputController);

        WakefulnessLifecycle wakefulnessLifecycle = new WakefulnessLifecycle();
        wakefulnessLifecycle.dispatchStartedWakingUp();
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
                () -> mStatusBar, () -> mAssistManager, () -> mBubbleController);

        mStatusBar = new StatusBar(
                mContext,
                mNotificationsController,
                mLightBarController,
                mAutoHideController,
                mKeyguardUpdateMonitor,
                mStatusBarIconController,
                mPulseExpansionHandler,
                mNotificationWakeUpCoordinator,
                mKeyguardBypassController,
                mKeyguardStateController,
                mHeadsUpManager,
                mDynamicPrivacyController,
                mBypassHeadsUpNotifier,
                new FalsingManagerFake(),
                mBroadcastDispatcher,
                new RemoteInputQuickSettingsDisabler(
                        mContext,
                        configurationController,
                        mCommandQueue
                ),
                mNotificationGutsManager,
                notificationLogger,
                mNotificationInterruptionStateProvider,
                mNotificationViewHierarchyManager,
                mKeyguardViewMediator,
                mNotificationAlertingManager,
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
                mVibratorHelper,
                mBubbleController,
                mGroupManager,
                mVisualStabilityManager,
                mDeviceProvisionedController,
                mNavigationBarController,
                () -> mAssistManager,
                configurationController,
                mNotificationShadeWindowController,
                mLockscreenLockIconController,
                mDozeParameters,
                mScrimController,
                mKeyguardLiftController,
                mLockscreenWallpaperLazy,
                mBiometricUnlockControllerLazy,
                mDozeServiceHost,
                mPowerManager, mScreenPinningRequest,
                mDozeScrimController,
                mVolumeComponent,
                mCommandQueue,
                Optional.of(mRecents),
                mStatusBarComponentBuilderProvider,
                mPluginManager,
                Optional.of(mDivider),
                mLightsOutNotifController,
                mStatusBarNotificationActivityStarterBuilder,
                mShadeController,
                mSuperStatusBarViewFactory,
                mStatusBarKeyguardViewManager,
                mViewMediatorCallback,
                mInitController,
                mDarkIconDispatcher,
                new Handler(TestableLooper.get(this).getLooper()),
                mPluginDependencyProvider,
                mKeyguardDismissUtil,
                mExtensionController,
                mUserInfoControllerImpl,
                mPhoneStatusBarPolicy,
                mKeyguardIndicationController,
                mDismissCallbackRegistry,
                mStatusBarTouchableRegionManager);

        when(mNotificationShadeWindowView.findViewById(R.id.lock_icon_container)).thenReturn(
                mLockIconContainer);

        when(mKeyguardViewMediator.registerStatusBar(any(StatusBar.class), any(ViewGroup.class),
                any(NotificationPanelViewController.class), any(BiometricUnlockController.class),
                any(ViewGroup.class), any(ViewGroup.class), any(KeyguardBypassController.class)))
                .thenReturn(mStatusBarKeyguardViewManager);

        when(mKeyguardViewMediator.getViewMediatorCallback()).thenReturn(
                mKeyguardVieMediatorCallback);

        // TODO: we should be able to call mStatusBar.start() and have all the below values
        // initialized automatically.
        mStatusBar.mNotificationShadeWindowView = mNotificationShadeWindowView;
        mStatusBar.mNotificationPanelViewController = mNotificationPanelViewController;
        mStatusBar.mDozeScrimController = mDozeScrimController;
        mStatusBar.mNotificationIconAreaController = mNotificationIconAreaController;
        mStatusBar.mPresenter = mNotificationPresenter;
        mStatusBar.mKeyguardIndicationController = mKeyguardIndicationController;
        mStatusBar.mBarService = mBarService;
        mStatusBar.mStackScroller = mStackScroller;
        mStatusBar.startKeyguard();
        mInitController.executePostInitTasks();
        notificationLogger.setUpWithContainer(mStackScroller);
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
        when(mHeadsUpSuppressor.canHeadsUp(any(), any())).thenReturn(true);

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

        assertTrue(mNotificationInterruptionStateProvider.shouldHeadsUp(entry));
    }

    @Test
    public void testShouldHeadsUp_suppressedGroupSummary() throws Exception {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mHeadsUpSuppressor.canHeadsUp(any(), any())).thenReturn(true);

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

        assertFalse(mNotificationInterruptionStateProvider.shouldHeadsUp(entry));
    }

    @Test
    public void testShouldHeadsUp_suppressedHeadsUp() throws Exception {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mHeadsUpSuppressor.canHeadsUp(any(), any())).thenReturn(true);

        Notification n = new Notification.Builder(getContext(), "a").build();

        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .setImportance(IMPORTANCE_HIGH)
                .setSuppressedVisualEffects(SUPPRESSED_EFFECT_PEEK)
                .build();

        assertFalse(mNotificationInterruptionStateProvider.shouldHeadsUp(entry));
    }

    @Test
    public void testShouldHeadsUp_noSuppressedHeadsUp() throws Exception {
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpManager.isSnoozed(anyString())).thenReturn(false);
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mHeadsUpSuppressor.canHeadsUp(any(), any())).thenReturn(true);

        Notification n = new Notification.Builder(getContext(), "a").build();

        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .setImportance(IMPORTANCE_HIGH)
                .build();

        assertTrue(mNotificationInterruptionStateProvider.shouldHeadsUp(entry));
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
    public void testDisableExpandStatusBar() {
        mStatusBar.setBarStateForTest(StatusBarState.SHADE);
        mStatusBar.setUserSetupForTest(true);
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);

        when(mCommandQueue.panelsEnabled()).thenReturn(false);
        mStatusBar.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NONE,
                StatusBarManager.DISABLE2_NOTIFICATION_SHADE, false);
        verify(mNotificationPanelViewController).setQsExpansionEnabled(false);
        mStatusBar.animateExpandNotificationsPanel();
        verify(mNotificationPanelViewController, never()).expand(anyBoolean());
        mStatusBar.animateExpandSettingsPanel(null);
        verify(mNotificationPanelViewController, never()).expand(anyBoolean());

        when(mCommandQueue.panelsEnabled()).thenReturn(true);
        mStatusBar.disable(DEFAULT_DISPLAY, StatusBarManager.DISABLE_NONE,
                StatusBarManager.DISABLE2_NONE, false);
        verify(mNotificationPanelViewController).setQsExpansionEnabled(true);
        mStatusBar.animateExpandNotificationsPanel();
        verify(mNotificationPanelViewController).expandWithoutQs();
        mStatusBar.animateExpandSettingsPanel(null);
        verify(mNotificationPanelViewController).expandWithQs();
    }

    @Test
    public void testDump_DoesNotCrash() {
        mStatusBar.dump(null, new PrintWriter(new ByteArrayOutputStream()), null);
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
        verify(mStatusBarStateController).setState(eq(StatusBarState.KEYGUARD));

        // If useFullscreenUserSwitcher is true, state is set to FULLSCREEN_USER_SWITCHER.
        when(mUserSwitcherController.useFullscreenUserSwitcher()).thenReturn(true);
        mStatusBar.showKeyguardImpl();
        verify(mStatusBarStateController).setState(eq(StatusBarState.FULLSCREEN_USER_SWITCHER));
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
    public void testSuppressAmbientDisplay_suppress() {
        mStatusBar.suppressAmbientDisplay(true);
        verify(mDozeServiceHost).setDozeSuppressed(true);
    }

    @Test
    public void testSuppressAmbientDisplay_unsuppress() {
        mStatusBar.suppressAmbientDisplay(false);
        verify(mDozeServiceHost).setDozeSuppressed(false);
    }

    public static class TestableNotificationInterruptionStateProvider extends
            NotificationInterruptionStateProvider {

        TestableNotificationInterruptionStateProvider(
                Context context,
                PowerManager powerManager,
                IDreamManager dreamManager,
                AmbientDisplayConfiguration ambientDisplayConfiguration,
                NotificationFilter filter,
                StatusBarStateController controller,
                BatteryController batteryController) {
            super(context, powerManager, dreamManager, ambientDisplayConfiguration, filter,
                    batteryController, controller);
            mUseHeadsUp = true;
        }
    }
}
