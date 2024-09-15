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

import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.provider.Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED;
import static android.provider.Settings.Global.HEADS_UP_ON;

import static com.android.systemui.Flags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR;
import static com.android.systemui.Flags.FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE;
import static com.android.systemui.Flags.FLAG_LIGHT_REVEAL_MIGRATION;
import static com.android.systemui.flags.Flags.SHORTCUT_LIST_SEARCH_LAYOUT;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.phone.CentralSurfaces.MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU;

import static com.google.common.truth.Truth.assertThat;

import static kotlinx.coroutines.flow.FlowKt.flowOf;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import static java.util.Collections.emptySet;

import android.app.ActivityManager;
import android.app.IWallpaperManager;
import android.app.WallpaperManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.fingerprint.FingerprintManager;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.service.dreams.IDreamManager;
import android.support.test.metricshelper.MetricsAsserts;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.compose.animation.scene.ObservableTransitionState;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.InitController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.floatingmenu.AccessibilityFloatingMenuController;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.back.domain.interactor.BackActionInteractor;
import com.android.systemui.biometrics.AuthRippleController;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.charging.WiredChargingRippleController;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.communal.shared.model.CommunalScenes;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.emergency.EmergencyGestureModule.EmergencyGestureIntentFactory;
import com.android.systemui.flags.DisableSceneContainer;
import com.android.systemui.flags.EnableSceneContainer;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.ui.viewmodel.LightRevealScrimViewModel;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.notetask.NoteTaskController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.res.R;
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor;
import com.android.systemui.scene.domain.startable.ScrimStartable;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.settings.brightness.BrightnessSliderController;
import com.android.systemui.settings.brightness.domain.interactor.BrightnessMirrorShowingInteractor;
import com.android.systemui.shade.CameraLauncher;
import com.android.systemui.shade.GlanceableHubContainerController;
import com.android.systemui.shade.NotificationPanelView;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shade.NotificationShadeWindowViewController;
import com.android.systemui.shade.QuickSettingsController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeControllerImpl;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.shade.ShadeLogger;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyboardShortcutListSearch;
import com.android.systemui.statusbar.KeyboardShortcuts;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LightRevealScrim;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.OperatorNameViewController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.core.StatusBarInitializer;
import com.android.systemui.statusbar.data.repository.FakeStatusBarModeRepository;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationLaunchAnimatorControllerProvider;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.interruption.AvalancheProvider;
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptLogger;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionLogger;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderTestUtil;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.util.FakeEventLog;
import com.android.systemui.util.WallpaperController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.concurrency.MessageRouterImpl;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.settings.FakeGlobalSettings;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SystemSettings;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.volume.VolumeComponent;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.startingsurface.StartingSurface;

import dagger.Lazy;

import kotlinx.coroutines.test.TestScope;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.Optional;

import javax.inject.Provider;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper(setAsMainLooper = true)
@EnableFlags(FLAG_LIGHT_REVEAL_MIGRATION)
public class CentralSurfacesImplTest extends SysuiTestCase {

    private static final int FOLD_STATE_FOLDED = 0;
    private static final int FOLD_STATE_UNFOLDED = 1;

    private final KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);

    private CentralSurfacesImpl mCentralSurfaces;
    private FakeMetricsLogger mMetricsLogger;
    private PowerManager mPowerManager;
    private VisualInterruptionDecisionProvider mVisualInterruptionDecisionProvider;


    private final TestScope mTestScope = mKosmos.getTestScope();
    @Mock private NotificationsController mNotificationsController;
    @Mock private LightBarController mLightBarController;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private KeyguardIndicationController mKeyguardIndicationController;
    @Mock private NotificationStackScrollLayout mStackScroller;
    @Mock private NotificationStackScrollLayoutController mStackScrollerController;
    @Mock private HeadsUpManager mHeadsUpManager;
    @Mock private NotificationPanelViewController mNotificationPanelViewController;
    @Mock private ShadeLogger mShadeLogger;
    @Mock private NotificationPanelView mNotificationPanelView;
    @Mock private QuickSettingsController mQuickSettingsController;
    @Mock private IStatusBarService mBarService;
    @Mock private IDreamManager mDreamManager;
    @Mock private LightRevealScrimViewModel mLightRevealScrimViewModel;
    @Mock private LightRevealScrim mLightRevealScrim;
    @Mock private DozeScrimController mDozeScrimController;
    @Mock private Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    @Mock private BiometricUnlockController mBiometricUnlockController;
    @Mock private AuthRippleController mAuthRippleController;
    @Mock private KeyguardViewMediator mKeyguardViewMediator;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private StatusBarStateControllerImpl mStatusBarStateController;
    @Mock private BatteryController mBatteryController;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private NotificationLaunchAnimatorControllerProvider
            mNotifTransitionAnimControllerProvider;
    @Mock private StatusBarNotificationPresenter mNotificationPresenter;
    @Mock private NotificationActivityStarter mNotificationActivityStarter;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private StatusBarSignalPolicy mStatusBarSignalPolicy;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private AssistManager mAssistManager;
    @Mock private NotificationGutsManager mNotificationGutsManager;
    @Mock private NotificationMediaManager mNotificationMediaManager;
    @Mock private NavigationBarController mNavigationBarController;
    @Mock private AccessibilityFloatingMenuController mAccessibilityFloatingMenuController;
    @Mock private SysuiColorExtractor mColorExtractor;
    private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock private PowerInteractor mPowerInteractor;
    @Mock private ColorExtractor.GradientColors mGradientColors;
    @Mock private PulseExpansionHandler mPulseExpansionHandler;
    @Mock private NotificationWakeUpCoordinator mNotificationWakeUpCoordinator;
    @Mock private KeyguardBypassController mKeyguardBypassController;
    @Mock private AutoHideController mAutoHideController;
    @Mock private StatusBarWindowController mStatusBarWindowController;
    @Mock private Provider<CollapsedStatusBarFragment> mCollapsedStatusBarFragmentProvider;
    @Mock private StatusBarWindowStateController mStatusBarWindowStateController;
    @Mock private Bubbles mBubbles;
    @Mock private NoteTaskController mNoteTaskController;
    @Mock private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock private NotificationIconAreaController mNotificationIconAreaController;
    @Mock private NotificationShadeWindowViewController mNotificationShadeWindowViewController;
    @Mock private Lazy<NotificationShadeWindowViewController>
            mNotificationShadeWindowViewControllerLazy;
    @Mock private DozeParameters mDozeParameters;
    @Mock private DozeServiceHost mDozeServiceHost;
    @Mock private BackActionInteractor mBackActionInteractor;
    @Mock private ViewMediatorCallback mKeyguardVieMediatorCallback;
    @Mock private VolumeComponent mVolumeComponent;
    @Mock private CommandQueue mCommandQueue;
    @Mock private CentralSurfacesCommandQueueCallbacks mCentralSurfacesCommandQueueCallbacks;
    @Mock private PluginManager mPluginManager;
    @Mock private ViewMediatorCallback mViewMediatorCallback;
    @Mock private StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    @Mock private PluginDependencyProvider mPluginDependencyProvider;
    @Mock private ExtensionController mExtensionController;
    @Mock private UserInfoControllerImpl mUserInfoControllerImpl;
    @Mock private PhoneStatusBarPolicy mPhoneStatusBarPolicy;
    @Mock private DemoModeController mDemoModeController;
    @Mock private Lazy<NotificationShadeDepthController> mNotificationShadeDepthControllerLazy;
    @Mock private BrightnessSliderController.Factory mBrightnessSliderFactory;
    @Mock private WallpaperController mWallpaperController;
    @Mock private StatusBarHideIconsForBouncerManager mStatusBarHideIconsForBouncerManager;
    @Mock private LockscreenShadeTransitionController mLockscreenTransitionController;
    @Mock private WallpaperManager mWallpaperManager;
    @Mock private IWallpaperManager mIWallpaperManager;
    @Mock private KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    @Mock private ScreenOffAnimationController mScreenOffAnimationController;
    @Mock private StartingSurface mStartingSurface;
    @Mock private OperatorNameViewController mOperatorNameViewController;
    @Mock private OperatorNameViewController.Factory mOperatorNameViewControllerFactory;
    @Mock private ActivityTransitionAnimator mActivityTransitionAnimator;
    @Mock private DeviceStateManager mDeviceStateManager;
    @Mock private WiredChargingRippleController mWiredChargingRippleController;
    @Mock private Lazy<CameraLauncher> mCameraLauncherLazy;
    @Mock private CameraLauncher mCameraLauncher;
    @Mock private AlternateBouncerInteractor mAlternateBouncerInteractor;
    @Mock private UserTracker mUserTracker;
    @Mock private AvalancheProvider mAvalancheProvider;
    @Mock private FingerprintManager mFingerprintManager;
    @Mock IPowerManager mPowerManagerService;
    @Mock ActivityStarter mActivityStarter;
    @Mock private WindowRootViewVisibilityInteractor mWindowRootViewVisibilityInteractor;
    @Mock private KeyboardShortcuts mKeyboardShortcuts;
    @Mock private KeyboardShortcutListSearch mKeyboardShortcutListSearch;
    @Mock private PackageManager mPackageManager;
    @Mock private GlanceableHubContainerController mGlanceableHubContainerController;
    @Mock private EmergencyGestureIntentFactory mEmergencyGestureIntentFactory;

    private ShadeController mShadeController;
    private final FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private final FakeGlobalSettings mFakeGlobalSettings = new FakeGlobalSettings();
    private final SystemSettings mSystemSettings = new FakeSettings();
    private final FakeEventLog mFakeEventLog = new FakeEventLog();
    private FakeExecutor mMainExecutor = new FakeExecutor(mFakeSystemClock);
    private final FakeExecutor mUiBgExecutor = new FakeExecutor(mFakeSystemClock);
    private final FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();
    private final InitController mInitController = new InitController();
    private final DumpManager mDumpManager = new DumpManager();
    private final ScreenLifecycle mScreenLifecycle = new ScreenLifecycle(mDumpManager);
    private MessageRouterImpl mMessageRouter = new MessageRouterImpl(mMainExecutor);

    private final BrightnessMirrorShowingInteractor mBrightnessMirrorShowingInteractor =
            mKosmos.getBrightnessMirrorShowingInteractor();
    private ScrimController mScrimController;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Set default value to avoid IllegalStateException.
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, false);
        // Turn AOD on and toggle feature flag for jank fixes
        mFeatureFlags.set(Flags.ZJ_285570694_LOCKSCREEN_TRANSITION_FROM_AOD, true);
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);

        IThermalService thermalService = mock(IThermalService.class);
        mPowerManager = new PowerManager(mContext, mPowerManagerService, thermalService,
                Handler.createAsync(Looper.myLooper()));

        mFakeGlobalSettings.putInt(HEADS_UP_NOTIFICATIONS_ENABLED, HEADS_UP_ON);

        when(mBubbles.canShowBubbleNotification()).thenReturn(true);

        mVisualInterruptionDecisionProvider =
                VisualInterruptionDecisionProviderTestUtil.INSTANCE.createProviderByFlag(
                        mAmbientDisplayConfiguration,
                        mBatteryController,
                        mDeviceProvisionedController,
                        mFakeEventLog,
                        mock(NotifPipelineFlags.class),
                        mFakeGlobalSettings,
                        mHeadsUpManager,
                        mock(KeyguardNotificationVisibilityProvider.class),
                        mKeyguardStateController,
                        new Handler(TestableLooper.get(this).getLooper()),
                        mock(VisualInterruptionDecisionLogger.class),
                        mock(NotificationInterruptLogger.class),
                        mPowerManager,
                        mStatusBarStateController,
                        mFakeSystemClock,
                        mock(UiEventLogger.class),
                        mUserTracker,
                        mAvalancheProvider,
                        mSystemSettings,
                        mPackageManager,
                        Optional.of(mBubbles));
        mVisualInterruptionDecisionProvider.start();

        mContext.addMockSystemService(TrustManager.class, mock(TrustManager.class));
        mContext.addMockSystemService(FingerprintManager.class, mock(FingerprintManager.class));

        mMetricsLogger = new FakeMetricsLogger();

        when(mCommandQueue.asBinder()).thenReturn(new Binder());

        mContext.setTheme(R.style.Theme_SystemUI_LightWallpaper);

        when(mStackScrollerController.getView()).thenReturn(mStackScroller);
        when(mStackScroller.generateLayoutParams(any())).thenReturn(new LayoutParams(0, 0));
        when(mNotificationPanelView.getLayoutParams()).thenReturn(new LayoutParams(0, 0));
        when(mPowerManagerService.isInteractive()).thenReturn(true);

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

        mWakefulnessLifecycle =
                new WakefulnessLifecycle(mContext, mIWallpaperManager, mFakeSystemClock,
                        mDumpManager);
        mWakefulnessLifecycle.dispatchStartedWakingUp(PowerManager.WAKE_REASON_UNKNOWN);
        mWakefulnessLifecycle.dispatchFinishedWakingUp();

        when(mGradientColors.supportsDarkText()).thenReturn(true);
        when(mColorExtractor.getNeutralColors()).thenReturn(mGradientColors);

        when(mBiometricUnlockControllerLazy.get()).thenReturn(mBiometricUnlockController);
        when(mCameraLauncherLazy.get()).thenReturn(mCameraLauncher);
        when(mNotificationShadeWindowViewControllerLazy.get())
                .thenReturn(mNotificationShadeWindowViewController);

        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mNotificationShadeWindowController).batchApplyWindowLayoutParams(any());
        if (SceneContainerFlag.isEnabled()) {
            mShadeController = spy(mKosmos.getShadeController());
        } else {
            mShadeController = spy(new ShadeControllerImpl(
                    mCommandQueue,
                    mMainExecutor,
                    mock(WindowRootViewVisibilityInteractor.class),
                    mKeyguardStateController,
                    mStatusBarStateController,
                    mStatusBarKeyguardViewManager,
                    mStatusBarWindowController,
                    mDeviceProvisionedController,
                    mNotificationShadeWindowController,
                    0,
                    () -> mNotificationPanelViewController,
                    () -> mAssistManager,
                    () -> mNotificationGutsManager
            ));
        }
        mShadeController.setNotificationShadeWindowViewController(
                mNotificationShadeWindowViewController);
        mShadeController.setNotificationPresenter(mNotificationPresenter);

        when(mOperatorNameViewControllerFactory.create(any()))
                .thenReturn(mOperatorNameViewController);
        when(mUserTracker.getUserId()).thenReturn(ActivityManager.getCurrentUser());
        when(mUserTracker.getUserHandle()).thenReturn(
                UserHandle.of(ActivityManager.getCurrentUser()));

        mScrimController = mKosmos.getScrimController();

        createCentralSurfaces();
    }

    private void createCentralSurfaces() {
        mMainExecutor = new FakeExecutor(mFakeSystemClock);
        mMessageRouter = new MessageRouterImpl(mMainExecutor);
        mKeyboardShortcuts = mock(KeyboardShortcuts.class);
        mKeyboardShortcutListSearch = mock(KeyboardShortcutListSearch.class);
        // Test setup for legacy version
        mKeyboardShortcuts.mContext = mContext;
        mKeyboardShortcutListSearch.mContext = mContext;
        KeyboardShortcuts.sInstance = mKeyboardShortcuts;
        KeyboardShortcutListSearch.sInstance = mKeyboardShortcutListSearch;

        ConfigurationController configurationController = new ConfigurationControllerImpl(mContext);
        mCentralSurfaces = new CentralSurfacesImpl(
                mContext,
                mNotificationsController,
                mock(FragmentService.class),
                mLightBarController,
                mAutoHideController,
                new StatusBarInitializer(
                        mStatusBarWindowController,
                        mCollapsedStatusBarFragmentProvider,
                        emptySet()),
                mStatusBarWindowController,
                mStatusBarWindowStateController,
                new FakeStatusBarModeRepository(),
                mKeyguardUpdateMonitor,
                mStatusBarSignalPolicy,
                mPulseExpansionHandler,
                mNotificationWakeUpCoordinator,
                mKeyguardBypassController,
                mKeyguardStateController,
                mHeadsUpManager,
                new FalsingManagerFake(),
                new FalsingCollectorFake(),
                mBroadcastDispatcher,
                mNotificationGutsManager,
                new ShadeExpansionStateManager(),
                mKeyguardViewMediator,
                new DisplayMetrics(),
                mMetricsLogger,
                mShadeLogger,
                new JavaAdapter(mTestScope),
                mUiBgExecutor,
                mNotificationPanelViewController,
                mNotificationMediaManager,
                mLockscreenUserManager,
                mRemoteInputManager,
                mQuickSettingsController,
                mBatteryController,
                mColorExtractor,
                mScreenLifecycle,
                mWakefulnessLifecycle,
                mPowerInteractor,
                mKosmos.getCommunalInteractor(),
                mStatusBarStateController,
                Optional.of(mBubbles),
                () -> mNoteTaskController,
                mDeviceProvisionedController,
                mNavigationBarController,
                mAccessibilityFloatingMenuController,
                () -> mAssistManager,
                configurationController,
                mNotificationShadeWindowController,
                mNotificationShadeWindowViewControllerLazy,
                mStackScrollerController,
                (Lazy<NotificationPresenter>) () -> mNotificationPresenter,
                (Lazy<NotificationActivityStarter>) () -> mNotificationActivityStarter,
                mNotifTransitionAnimControllerProvider,
                mDozeParameters,
                mScrimController,
                mBiometricUnlockControllerLazy,
                mAuthRippleController,
                mDozeServiceHost,
                mBackActionInteractor,
                mPowerManager,
                mDozeScrimController,
                mVolumeComponent,
                mCommandQueue,
                () -> mCentralSurfacesCommandQueueCallbacks,
                mPluginManager,
                mShadeController,
                mWindowRootViewVisibilityInteractor,
                mStatusBarKeyguardViewManager,
                mViewMediatorCallback,
                mInitController,
                new Handler(TestableLooper.get(this).getLooper()),
                mPluginDependencyProvider,
                mExtensionController,
                mUserInfoControllerImpl,
                mPhoneStatusBarPolicy,
                mKeyguardIndicationController,
                mDemoModeController,
                mNotificationShadeDepthControllerLazy,
                mStatusBarTouchableRegionManager,
                mNotificationIconAreaController,
                mBrightnessSliderFactory,
                mScreenOffAnimationController,
                mWallpaperController,
                mStatusBarHideIconsForBouncerManager,
                mLockscreenTransitionController,
                mFeatureFlags,
                mKeyguardUnlockAnimationController,
                mMainExecutor,
                mMessageRouter,
                mWallpaperManager,
                Optional.of(mStartingSurface),
                mActivityTransitionAnimator,
                mDeviceStateManager,
                mWiredChargingRippleController,
                mDreamManager,
                mCameraLauncherLazy,
                () -> mLightRevealScrimViewModel,
                mLightRevealScrim,
                mAlternateBouncerInteractor,
                mUserTracker,
                () -> mFingerprintManager,
                mActivityStarter,
                mBrightnessMirrorShowingInteractor,
                mGlanceableHubContainerController,
                mEmergencyGestureIntentFactory
        );
        mScreenLifecycle.addObserver(mCentralSurfaces.mScreenObserver);
        mCentralSurfaces.initShadeVisibilityListener();
        when(mKeyguardViewMediator.registerCentralSurfaces(
                any(CentralSurfacesImpl.class),
                any(NotificationPanelViewController.class),
                any(ShadeExpansionStateManager.class),
                any(BiometricUnlockController.class),
                any(ViewGroup.class)))
                .thenReturn(mStatusBarKeyguardViewManager);

        when(mKeyguardViewMediator.getViewMediatorCallback()).thenReturn(
                mKeyguardVieMediatorCallback);

        // TODO(b/277764509): we should be able to call mCentralSurfaces.start() and have all the
        //  below values initialized automatically.
        mCentralSurfaces.mDozeScrimController = mDozeScrimController;
        mCentralSurfaces.mKeyguardIndicationController = mKeyguardIndicationController;
        mCentralSurfaces.mBarService = mBarService;
        mCentralSurfaces.mGestureWakeLock = mPowerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "sysui:GestureWakeLock");
        mCentralSurfaces.startKeyguard();
        mInitController.executePostInitTasks();
        mCentralSurfaces.registerCallbacks();
    }

    @Test
    public void testSetBouncerShowing_noCrash() {
        mCentralSurfaces.setBouncerShowing(true);
    }

    @Test
    public void lockscreenStateMetrics_notShowing() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mKeyguardStateController.isOccluded()).thenReturn(false);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        // interesting state
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardStateController.isMethodSecure()).thenReturn(false);
        mCentralSurfaces.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing hidden insecure lockscreen log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_CLOSE)
                        .setSubtype(0));
    }

    @Test
    public void lockscreenStateMetrics_notShowing_secure() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mKeyguardStateController.isOccluded()).thenReturn(false);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        // interesting state
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardStateController.isMethodSecure()).thenReturn(true);

        mCentralSurfaces.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing hidden secure lockscreen log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_CLOSE)
                        .setSubtype(1));
    }

    @Test
    public void lockscreenStateMetrics_isShowing() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mKeyguardStateController.isOccluded()).thenReturn(false);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        // interesting state
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardStateController.isMethodSecure()).thenReturn(false);

        mCentralSurfaces.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing insecure lockscreen showing",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(0));
    }

    @Test
    public void lockscreenStateMetrics_isShowing_secure() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mKeyguardStateController.isOccluded()).thenReturn(false);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        // interesting state
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);
        when(mKeyguardStateController.isMethodSecure()).thenReturn(true);

        mCentralSurfaces.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing secure lockscreen showing log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.LOCKSCREEN)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(1));
    }

    @Test
    public void lockscreenStateMetrics_isShowingBouncer() {
        // uninteresting state, except that fingerprint must be non-zero
        when(mKeyguardStateController.isOccluded()).thenReturn(false);
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        // interesting state
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);
        when(mKeyguardStateController.isMethodSecure()).thenReturn(true);

        mCentralSurfaces.onKeyguardViewManagerStatesUpdated();

        MetricsAsserts.assertHasLog("missing bouncer log",
                mMetricsLogger.getLogs(),
                new LogMaker(MetricsEvent.BOUNCER)
                        .setType(MetricsEvent.TYPE_OPEN)
                        .setSubtype(1));
    }

    @Test
    public void testDump_DoesNotCrash() {
        mCentralSurfaces.dump(new PrintWriter(new ByteArrayOutputStream()), null);
    }

    @Test
    public void testDumpBarTransitions_DoesNotCrash() {
        CentralSurfaces.dumpBarTransitions(
                new PrintWriter(new ByteArrayOutputStream()), "var", /* transitions= */ null);
    }

    @Test
    public void testFingerprintNotification_UpdatesScrims() {
        mCentralSurfaces.notifyBiometricAuthModeChanged();
        verify(mScrimController).legacyTransitionTo(any(), any());
    }

    @Test
    public void testFingerprintUnlock_UpdatesScrims() {
        // Simulate unlocking from AoD with fingerprint.
        when(mBiometricUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_WAKE_AND_UNLOCK);
        mCentralSurfaces.updateScrimController();
        verify(mScrimController).legacyTransitionTo(eq(ScrimState.UNLOCKED), any());
    }

    @Test
    public void testTransitionLaunch_goesToUnlocked() {
        mCentralSurfaces.setBarStateForTest(StatusBarState.KEYGUARD);
        mCentralSurfaces.showKeyguardImpl();

        // Starting a pulse should change the scrim controller to the pulsing state
        when(mCameraLauncher.isLaunchingAffordance()).thenReturn(true);
        mCentralSurfaces.updateScrimController();
        verify(mScrimController).legacyTransitionTo(eq(ScrimState.UNLOCKED), any());
    }

    @Test
    public void testSetExpansionAffectsAlpha_whenKeyguardShowingButGoingAwayForAnyReason() {
        mCentralSurfaces.updateScrimController();
        verify(mScrimController).setExpansionAffectsAlpha(eq(true));

        clearInvocations(mScrimController);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(false);
        mCentralSurfaces.updateScrimController();
        verify(mScrimController).setExpansionAffectsAlpha(eq(true));

        clearInvocations(mScrimController);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(true);
        mCentralSurfaces.updateScrimController();
        verify(mScrimController).setExpansionAffectsAlpha(eq(false));

        clearInvocations(mScrimController);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isKeyguardFadingAway()).thenReturn(true);
        mCentralSurfaces.updateScrimController();
        verify(mScrimController).setExpansionAffectsAlpha(eq(false));
    }

    @Test
    public void testTransitionLaunch_noPreview_doesntGoUnlocked() {
        mCentralSurfaces.setBarStateForTest(StatusBarState.KEYGUARD);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mCentralSurfaces.showKeyguardImpl();

        // Starting a pulse should change the scrim controller to the pulsing state
        when(mCameraLauncher.isLaunchingAffordance()).thenReturn(false);
        mCentralSurfaces.updateScrimController();
        verify(mScrimController).legacyTransitionTo(eq(ScrimState.KEYGUARD));
    }

    @Test
    public void testSetOccluded_propagatesToScrimController() {
        ArgumentCaptor<KeyguardStateController.Callback> callbackCaptor =
                ArgumentCaptor.forClass(KeyguardStateController.Callback.class);
        verify(mKeyguardStateController).addCallback(callbackCaptor.capture());

        when(mKeyguardStateController.isOccluded()).thenReturn(true);
        callbackCaptor.getValue().onKeyguardShowingChanged();
        verify(mScrimController).setKeyguardOccluded(eq(true));

        reset(mScrimController);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);
        callbackCaptor.getValue().onKeyguardShowingChanged();
        verify(mScrimController).setKeyguardOccluded(eq(false));
    }

    @Test
    public void testPulseWhileDozing_updatesScrimController() {
        mCentralSurfaces.setBarStateForTest(StatusBarState.KEYGUARD);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mCentralSurfaces.showKeyguardImpl();

        // Starting a pulse should change the scrim controller to the pulsing state
        when(mDozeServiceHost.isPulsing()).thenReturn(true);
        mCentralSurfaces.updateScrimController();
        verify(mScrimController).legacyTransitionTo(eq(ScrimState.PULSING), any());

        // Ending a pulse should take it back to keyguard state
        when(mDozeServiceHost.isPulsing()).thenReturn(false);
        mCentralSurfaces.updateScrimController();
        verify(mScrimController).legacyTransitionTo(eq(ScrimState.KEYGUARD));
    }

    @Test
    public void testSetDozingNotUnlocking_transitionToAOD_cancelKeyguardFadingAway() {
        setDozing(true);
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        when(mKeyguardStateController.isKeyguardFadingAway()).thenReturn(true);

        mCentralSurfaces.updateScrimController();

        verify(mScrimController, times(2)).legacyTransitionTo(eq(ScrimState.AOD));
        verify(mStatusBarKeyguardViewManager).onKeyguardFadedAway();
    }

    @Test
    @DisableFlags(FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
    public void testSetDozingNotUnlocking_transitionToAuthScrimmed_cancelKeyguardFadingAway() {
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);
        when(mKeyguardStateController.isKeyguardFadingAway()).thenReturn(true);

        mCentralSurfaces.updateScrimController();

        verify(mScrimController).legacyTransitionTo(eq(ScrimState.AUTH_SCRIMMED_SHADE));
        verify(mStatusBarKeyguardViewManager).onKeyguardFadedAway();
    }

    @Test
    @DisableFlags(FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
    public void testOccludingQSNotExpanded_flagOff_transitionToAuthScrimmed() {
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // GIVEN device occluded and panel is NOT expanded
        mCentralSurfaces.setBarStateForTest(SHADE); // occluding on LS has StatusBarState = SHADE
        when(mKeyguardStateController.isOccluded()).thenReturn(true);
        when(mNotificationPanelViewController.isPanelExpanded()).thenReturn(false);

        mCentralSurfaces.updateScrimController();

        verify(mScrimController).legacyTransitionTo(eq(ScrimState.AUTH_SCRIMMED));
    }

    @Test
    @EnableFlags(FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
    public void testNotOccluding_QSNotExpanded_flagOn_doesNotTransitionScrimState() {
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // GIVEN device occluded and panel is NOT expanded
        mCentralSurfaces.setBarStateForTest(SHADE);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);
        when(mNotificationPanelViewController.isPanelExpanded()).thenReturn(false);

        mCentralSurfaces.updateScrimController();

        // Tests the safeguard to reset the scrimstate
        verify(mScrimController, never()).legacyTransitionTo(any());
    }

    @Test
    @EnableFlags(FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
    public void testNotOccluding_QSExpanded_flagOn_doesTransitionScrimStateToKeyguard() {
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // GIVEN device occluded and panel is NOT expanded
        mCentralSurfaces.setBarStateForTest(SHADE);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);
        when(mNotificationPanelViewController.isPanelExpanded()).thenReturn(true);

        mCentralSurfaces.updateScrimController();

        // Tests the safeguard to reset the scrimstate
        verify(mScrimController, never()).legacyTransitionTo(eq(ScrimState.KEYGUARD));
    }

    @Test
    @DisableFlags(FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
    public void testOccludingQSExpanded_transitionToAuthScrimmedShade() {
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // GIVEN device occluded and qs IS expanded
        mCentralSurfaces.setBarStateForTest(SHADE); // occluding on LS has StatusBarState = SHADE
        when(mKeyguardStateController.isOccluded()).thenReturn(true);
        when(mNotificationPanelViewController.isPanelExpanded()).thenReturn(true);

        mCentralSurfaces.updateScrimController();

        verify(mScrimController).legacyTransitionTo(eq(ScrimState.AUTH_SCRIMMED_SHADE));
    }

    @Test
    public void testEnteringGlanceableHub_updatesScrim() {
        // Transition to the glanceable hub.
        mKosmos.getCommunalRepository()
                .setTransitionState(
                        flowOf(new ObservableTransitionState.Idle(CommunalScenes.Communal)));
        mTestScope.getTestScheduler().runCurrent();

        // ScrimState also transitions.
        verify(mScrimController).legacyTransitionTo(ScrimState.GLANCEABLE_HUB);

        // Transition away from the glanceable hub.
        mKosmos.getCommunalRepository()
                .setTransitionState(
                        flowOf(new ObservableTransitionState.Idle(CommunalScenes.Blank)));
        mTestScope.getTestScheduler().runCurrent();

        // ScrimState goes back to UNLOCKED.
        verify(mScrimController).legacyTransitionTo(eq(ScrimState.UNLOCKED), any());
    }

    @Test
    public void testEnteringGlanceableHub_whenDreaming_updatesScrim() {
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(true);
        when(mKeyguardUpdateMonitor.isDreaming()).thenReturn(true);

        // Transition to the glanceable hub.
        mKosmos.getCommunalRepository()
                .setTransitionState(
                        flowOf(new ObservableTransitionState.Idle(CommunalScenes.Communal)));
        mTestScope.getTestScheduler().runCurrent();

        // ScrimState also transitions.
        verify(mScrimController).legacyTransitionTo(ScrimState.GLANCEABLE_HUB_OVER_DREAM);

        // Transition away from the glanceable hub.
        mKosmos.getCommunalRepository()
                .setTransitionState(
                        flowOf(new ObservableTransitionState.Idle(CommunalScenes.Blank)));
        mTestScope.getTestScheduler().runCurrent();

        // ScrimState goes back to UNLOCKED.
        verify(mScrimController).legacyTransitionTo(eq(ScrimState.DREAMING));
    }

    @Test
    public void testShowKeyguardImplementation_setsState() {
        when(mLockscreenUserManager.getCurrentProfiles()).thenReturn(new SparseArray<>());

        mCentralSurfaces.setBarStateForTest(SHADE);

        // By default, showKeyguardImpl sets state to KEYGUARD.
        mCentralSurfaces.showKeyguardImpl();
        verify(mStatusBarStateController).setState(
                eq(StatusBarState.KEYGUARD), eq(false) /* force */);
    }

    @Test
    public void testOnStartedWakingUp_isNotDozing() {
        mCentralSurfaces.setBarStateForTest(StatusBarState.KEYGUARD);
        when(mStatusBarStateController.isKeyguardRequested()).thenReturn(true);
        when(mDozeServiceHost.getDozingRequested()).thenReturn(true);
        mCentralSurfaces.updateIsKeyguard();
        // TODO: mNotificationPanelView.expand(false) gets called twice. Should be once.
        verify(mNotificationPanelViewController, times(2)).expand(eq(false));
        clearInvocations(mNotificationPanelViewController);

        mCentralSurfaces.mWakefulnessObserver.onStartedWakingUp();
        verify(mDozeServiceHost, never()).stopDozing();
        verify(mNotificationPanelViewController).expand(eq(false));
        mCentralSurfaces.mWakefulnessObserver.onFinishedWakingUp();
        verify(mDozeServiceHost).stopDozing();
    }

    @Test
    public void testOnStartedWakingUp_doesNotDismissBouncer_whenPulsing() {
        mCentralSurfaces.setBarStateForTest(StatusBarState.KEYGUARD);
        when(mStatusBarStateController.isKeyguardRequested()).thenReturn(true);
        when(mDozeServiceHost.getDozingRequested()).thenReturn(true);
        mCentralSurfaces.updateIsKeyguard();
        clearInvocations(mNotificationPanelViewController);

        mCentralSurfaces.setBouncerShowing(true);
        mCentralSurfaces.mWakefulnessObserver.onStartedWakingUp();
        verify(mNotificationPanelViewController, never()).expand(anyBoolean());
        mCentralSurfaces.mWakefulnessObserver.onFinishedWakingUp();
        verify(mDozeServiceHost).stopDozing();
    }

    @Test
    public void testRegisterBroadcastsonDispatcher() {
        mCentralSurfaces.registerBroadcastReceiver();
        verify(mBroadcastDispatcher).registerReceiver(
                any(BroadcastReceiver.class),
                any(IntentFilter.class),
                eq(null),
                any(UserHandle.class));
    }

    @Test
    public void testUpdateResources_updatesBouncer() {
        mCentralSurfaces.updateResources();

        verify(mStatusBarKeyguardViewManager).updateResources();
    }

    @Test
    public void deviceStateChange_unfolded_shadeOpen_setsLeaveOpenOnKeyguardHide() {
        setFoldedStates(FOLD_STATE_FOLDED);
        setGoToSleepStates(FOLD_STATE_FOLDED);
        mCentralSurfaces.setBarStateForTest(SHADE);
        when(mNotificationPanelViewController.isShadeFullyExpanded()).thenReturn(true);

        setDeviceState(FOLD_STATE_UNFOLDED);

        verify(mStatusBarStateController).setLeaveOpenOnKeyguardHide(true);
    }

    @Test
    public void deviceStateChange_unfolded_shadeOpen_onKeyguard_doesNotSetLeaveOpenOnKeyguardHide() {
        setFoldedStates(FOLD_STATE_FOLDED);
        setGoToSleepStates(FOLD_STATE_FOLDED);
        mCentralSurfaces.setBarStateForTest(KEYGUARD);
        when(mNotificationPanelViewController.isShadeFullyExpanded()).thenReturn(true);

        setDeviceState(FOLD_STATE_UNFOLDED);

        verify(mStatusBarStateController, never()).setLeaveOpenOnKeyguardHide(true);
    }


    @Test
    public void deviceStateChange_unfolded_shadeClose_doesNotSetLeaveOpenOnKeyguardHide() {
        setFoldedStates(FOLD_STATE_FOLDED);
        setGoToSleepStates(FOLD_STATE_FOLDED);
        mCentralSurfaces.setBarStateForTest(SHADE);
        when(mNotificationPanelViewController.isShadeFullyExpanded()).thenReturn(false);

        setDeviceState(FOLD_STATE_UNFOLDED);

        verify(mStatusBarStateController, never()).setLeaveOpenOnKeyguardHide(true);
    }

    @Test
    public void deviceStateChange_unfolded_shadeExpanding_onKeyguard_closesQS() {
        setFoldedStates(FOLD_STATE_FOLDED);
        setGoToSleepStates(FOLD_STATE_FOLDED);
        mCentralSurfaces.setBarStateForTest(KEYGUARD);
        when(mNotificationPanelViewController.isExpandingOrCollapsing()).thenReturn(true);

        setDeviceState(FOLD_STATE_UNFOLDED);
        mScreenLifecycle.dispatchScreenTurnedOff();

        verify(mQuickSettingsController).closeQs();
    }

    @Test
    public void deviceStateChange_unfolded_shadeExpanded_onKeyguard_closesQS() {
        setFoldedStates(FOLD_STATE_FOLDED);
        setGoToSleepStates(FOLD_STATE_FOLDED);
        mCentralSurfaces.setBarStateForTest(KEYGUARD);
        when(mNotificationPanelViewController.isShadeFullyExpanded()).thenReturn(true);

        setDeviceState(FOLD_STATE_UNFOLDED);
        mScreenLifecycle.dispatchScreenTurnedOff();

        verify(mQuickSettingsController).closeQs();
    }

    @Test
    public void testKeyguardHideDelayedIfOcclusionAnimationRunning() {
        // Show the keyguard and verify we've done so.
        setKeyguardShowingAndOccluded(true /* showing */, false /* occluded */);
        verify(mStatusBarStateController).setState(StatusBarState.KEYGUARD);

        // Request to hide the keyguard, but while the occlude animation is playing. We should delay
        // this hide call, since we're playing the occlude animation over the keyguard and thus want
        // it to remain visible.
        when(mKeyguardViewMediator.isOccludeAnimationPlaying()).thenReturn(true);
        setKeyguardShowingAndOccluded(false /* showing */, true /* occluded */);
        verify(mStatusBarStateController, never()).setState(SHADE);

        // Once the animation ends, verify that the keyguard is actually hidden.
        when(mKeyguardViewMediator.isOccludeAnimationPlaying()).thenReturn(false);
        setKeyguardShowingAndOccluded(false /* showing */, true /* occluded */);
        verify(mStatusBarStateController).setState(SHADE);
    }

    @Test
    public void testKeyguardHideNotDelayedIfOcclusionAnimationNotRunning() {
        // Show the keyguard and verify we've done so.
        setKeyguardShowingAndOccluded(true /* showing */, false /* occluded */);
        verify(mStatusBarStateController).setState(StatusBarState.KEYGUARD);

        // Hide the keyguard while the occlusion animation is not running. Verify that we
        // immediately hide the keyguard.
        when(mKeyguardViewMediator.isOccludeAnimationPlaying()).thenReturn(false);
        setKeyguardShowingAndOccluded(false /* showing */, true /* occluded */);
        verify(mStatusBarStateController).setState(SHADE);
    }

    /** Regression test for b/298355063 */
    @Test
    public void fingerprintManagerNull_noNPE() {
        // GIVEN null fingerprint manager
        mFingerprintManager = null;
        createCentralSurfaces();

        // GIVEN should animate doze wakeup
        when(mDozeServiceHost.shouldAnimateWakeup()).thenReturn(true);
        when(mBiometricUnlockController.getMode()).thenReturn(
                BiometricUnlockController.MODE_ONLY_WAKE);
        when(mDozeServiceHost.isPulsing()).thenReturn(false);
        when(mStatusBarStateController.getDozeAmount()).thenReturn(1f);

        // WHEN waking up from the power button
        mWakefulnessLifecycle.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mCentralSurfaces.mWakefulnessObserver.onStartedWakingUp();

        // THEN no NPE when fingerprintManager is null
    }

    @Test
    public void bubbleBarVisibility() {
        createCentralSurfaces();
        mCentralSurfaces.onStatusBarWindowStateChanged(WINDOW_STATE_HIDDEN);
        verify(mBubbles).onStatusBarVisibilityChanged(false);

        mCentralSurfaces.onStatusBarWindowStateChanged(WINDOW_STATE_SHOWING);
        verify(mBubbles).onStatusBarVisibilityChanged(true);
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_TRUNCATED_STATUS_BAR_ICONS_FIX)
    public void updateResources_flagEnabled_doesNotUpdateStatusBarWindowHeight() {
        mCentralSurfaces.updateResources();

        verify(mStatusBarWindowController, never()).refreshStatusBarHeight();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_TRUNCATED_STATUS_BAR_ICONS_FIX)
    public void updateResources_flagDisabled_updatesStatusBarWindowHeight() {
        mCentralSurfaces.updateResources();

        verify(mStatusBarWindowController).refreshStatusBarHeight();
    }

    @Test
    @EnableSceneContainer
    public void brightnesShowingChanged_flagEnabled_ScrimControllerNotified() {
        mCentralSurfaces.registerCallbacks();
        final ScrimStartable scrimStartable = mKosmos.getScrimStartable();
        scrimStartable.start();

        mBrightnessMirrorShowingInteractor.setMirrorShowing(true);
        mTestScope.getTestScheduler().runCurrent();
        verify(mScrimController, atLeastOnce()).transitionTo(ScrimState.BRIGHTNESS_MIRROR);

        mBrightnessMirrorShowingInteractor.setMirrorShowing(false);
        mTestScope.getTestScheduler().runCurrent();
        ArgumentCaptor<ScrimState> captor = ArgumentCaptor.forClass(ScrimState.class);
        // The default is to call the one with the callback argument
        verify(mScrimController, atLeastOnce()).transitionTo(captor.capture());
        assertThat(captor.getValue()).isNotEqualTo(ScrimState.BRIGHTNESS_MIRROR);
    }

    @Test
    @DisableSceneContainer
    public void brightnesShowingChanged_flagDisabled_ScrimControllerNotified() {
        mCentralSurfaces.registerCallbacks();

        mBrightnessMirrorShowingInteractor.setMirrorShowing(true);
        mTestScope.getTestScheduler().runCurrent();
        verify(mScrimController, never()).legacyTransitionTo(ScrimState.BRIGHTNESS_MIRROR);
        verify(mScrimController, never())
                .legacyTransitionTo(eq(ScrimState.BRIGHTNESS_MIRROR), any());
    }

    @Test
    @EnableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE)
    public void dismissKeyboardShortcuts_largeScreen_bothFlagsEnabled_doesNotDismissAny() {
        switchToLargeScreen();
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, true);
        createCentralSurfaces();

        dismissKeyboardShortcuts();

        verifyNoMoreInteractions(mKeyboardShortcuts, mKeyboardShortcutListSearch);
    }

    @Test
    @DisableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE)
    public void dismissKeyboardShortcuts_largeScreen_newFlagsDisabled_dismissesTabletVersion() {
        switchToLargeScreen();
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, true);
        createCentralSurfaces();

        dismissKeyboardShortcuts();

        verify(mKeyboardShortcutListSearch).dismissKeyboardShortcuts();
    }

    @Test
    @DisableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE)
    public void dismissKeyboardShortcuts_largeScreen_bothFlagsDisabled_dismissesPhoneVersion() {
        switchToLargeScreen();
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, false);
        createCentralSurfaces();

        dismissKeyboardShortcuts();

        verify(mKeyboardShortcuts).dismissKeyboardShortcuts();
        verifyNoMoreInteractions(mKeyboardShortcutListSearch);
    }

    @Test
    @EnableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE)
    public void dismissKeyboardShortcuts_smallScreen_bothFlagsEnabled_doesNotDismissAny() {
        switchToSmallScreen();
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, true);
        createCentralSurfaces();

        dismissKeyboardShortcuts();

        verifyNoMoreInteractions(mKeyboardShortcuts, mKeyboardShortcutListSearch);
    }

    @Test
    @DisableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE)
    public void dismissKeyboardShortcuts_smallScreen_newFlagsDisabled_dismissesPhoneVersion() {
        switchToSmallScreen();
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, true);
        createCentralSurfaces();

        dismissKeyboardShortcuts();

        verify(mKeyboardShortcuts).dismissKeyboardShortcuts();
        verifyNoMoreInteractions(mKeyboardShortcutListSearch);
    }

    @Test
    @DisableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE)
    public void dismissKeyboardShortcuts_smallScreen_bothFlagsDisabled_dismissesPhoneVersion() {
        switchToSmallScreen();
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, false);
        createCentralSurfaces();

        dismissKeyboardShortcuts();

        verify(mKeyboardShortcuts).dismissKeyboardShortcuts();
        verifyNoMoreInteractions(mKeyboardShortcutListSearch);
    }

    @Test
    @EnableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE)
    public void toggleKeyboardShortcuts_largeScreen_bothFlagsEnabled_doesNotTogglesAny() {
        switchToLargeScreen();
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, true);
        createCentralSurfaces();

        int deviceId = 321;
        toggleKeyboardShortcuts(/* deviceId= */ deviceId);

        verifyNoMoreInteractions(mKeyboardShortcuts, mKeyboardShortcutListSearch);
    }

    @Test
    @DisableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE)
    public void toggleKeyboardShortcuts_largeScreen_newFlagsDisabled_togglesTabletVersion() {
        switchToLargeScreen();
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, true);
        createCentralSurfaces();

        int deviceId = 654;
        toggleKeyboardShortcuts(deviceId);

        verify(mKeyboardShortcutListSearch).showKeyboardShortcuts(deviceId);
        verifyNoMoreInteractions(mKeyboardShortcuts);
    }

    @Test
    @DisableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE)
    public void toggleKeyboardShortcuts_largeScreen_bothFlagsDisabled_togglesPhoneVersion() {
        switchToLargeScreen();
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, false);
        createCentralSurfaces();

        int deviceId = 987;
        toggleKeyboardShortcuts(deviceId);

        verify(mKeyboardShortcuts).showKeyboardShortcuts(deviceId);
        verifyNoMoreInteractions(mKeyboardShortcutListSearch);
    }

    @Test
    @EnableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE)
    public void toggleKeyboardShortcuts_smallScreen_bothFlagsEnabled_doesNotToggleAny() {
        switchToSmallScreen();
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, true);
        createCentralSurfaces();

        int deviceId = 789;
        toggleKeyboardShortcuts(/* deviceId= */ deviceId);

        verifyNoMoreInteractions(mKeyboardShortcuts, mKeyboardShortcutListSearch);
    }

    @Test
    @DisableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE)
    public void toggleKeyboardShortcuts_smallScreen_newFlagsDisabled_togglesPhoneVersion() {
        switchToSmallScreen();
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, true);
        createCentralSurfaces();

        int deviceId = 456;
        toggleKeyboardShortcuts(deviceId);

        verify(mKeyboardShortcuts).showKeyboardShortcuts(deviceId);
        verifyNoMoreInteractions(mKeyboardShortcutListSearch);
    }

    @Test
    @DisableFlags(FLAG_KEYBOARD_SHORTCUT_HELPER_REWRITE)
    public void toggleKeyboardShortcuts_smallScreen_bothFlagsDisabled_togglesPhoneVersion() {
        switchToSmallScreen();
        mFeatureFlags.set(SHORTCUT_LIST_SEARCH_LAYOUT, false);
        createCentralSurfaces();

        int deviceId = 123;
        toggleKeyboardShortcuts(deviceId);

        verify(mKeyboardShortcuts).showKeyboardShortcuts(deviceId);
        verifyNoMoreInteractions(mKeyboardShortcutListSearch);
    }

    private void dismissKeyboardShortcuts() {
        mMessageRouter.sendMessage(MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU);
        mMainExecutor.runAllReady();
    }

    private void toggleKeyboardShortcuts(int deviceId) {
        mMessageRouter.sendMessage(new CentralSurfaces.KeyboardShortcutsMessage(deviceId));
        mMainExecutor.runAllReady();
    }

    private void switchToLargeScreen() {
        switchToScreenSize(1280, 800);
    }

    private void switchToSmallScreen() {
        switchToScreenSize(504, 1122);
    }

    private void switchToScreenSize(int widthDp, int heightDp) {
        WindowMetrics windowMetrics = Mockito.mock(WindowMetrics.class);
        WindowManager windowManager = Mockito.mock(WindowManager.class);

        Configuration configuration = new Configuration();
        configuration.densityDpi = DisplayMetrics.DENSITY_DEFAULT;
        mContext.getOrCreateTestableResources().overrideConfiguration(configuration);

        when(windowMetrics.getBounds()).thenReturn(new Rect(0, 0, widthDp, heightDp));
        when(windowManager.getCurrentWindowMetrics()).thenReturn(windowMetrics);
        mContext.addMockSystemService(WindowManager.class, windowManager);
    }

    /**
     * Configures the appropriate mocks and then calls {@link CentralSurfacesImpl#updateIsKeyguard}
     * to reconfigure the keyguard to reflect the requested showing/occluded states.
     */
    private void setKeyguardShowingAndOccluded(boolean showing, boolean occluded) {
        when(mStatusBarStateController.isKeyguardRequested()).thenReturn(showing);
        when(mKeyguardStateController.isOccluded()).thenReturn(occluded);

        // If we want to show the keyguard, make sure that we think we're awake and not unlocking.
        if (showing) {
            when(mBiometricUnlockController.isWakeAndUnlock()).thenReturn(false);
            mWakefulnessLifecycle.dispatchStartedWakingUp(PowerManager.WAKE_REASON_UNKNOWN);
        }

        mCentralSurfaces.updateIsKeyguard(false /* forceStateChange */);
    }

    private void setDeviceState(int state) {
        DeviceState deviceState = new DeviceState(
                new DeviceState.Configuration.Builder(state, "TEST").build());
        ArgumentCaptor<DeviceStateManager.DeviceStateCallback> callbackCaptor =
                ArgumentCaptor.forClass(DeviceStateManager.DeviceStateCallback.class);
        verify(mDeviceStateManager).registerCallback(any(), callbackCaptor.capture());
        callbackCaptor.getValue().onDeviceStateChanged(deviceState);
    }

    private void setGoToSleepStates(int... states) {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.array.config_deviceStatesOnWhichToSleep,
                states);
    }

    private void setFoldedStates(int... states) {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.array.config_foldedDeviceStates,
                states);
    }

    private void setDozing(boolean isDozing) {
        ArgumentCaptor<StatusBarStateController.StateListener> callbackCaptor =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
        verify(mStatusBarStateController).addCallback(callbackCaptor.capture(), anyInt());
        callbackCaptor.getValue().onDozingChanged(isDozing);
    }
}
