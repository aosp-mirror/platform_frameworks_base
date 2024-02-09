/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static android.app.StatusBarManager.DISABLE_HOME;
import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.WindowVisibleState;
import static android.app.StatusBarManager.windowStateToString;

import static androidx.core.view.ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
import static androidx.core.view.ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
import static androidx.lifecycle.Lifecycle.State.RESUMED;

import static com.android.systemui.Dependency.TIME_TICK_HANDLER_NAME;
import static com.android.systemui.Flags.lightRevealMigration;
import static com.android.systemui.Flags.newAodTransition;
import static com.android.systemui.Flags.predictiveBackSysui;
import static com.android.systemui.Flags.truncatedStatusBarIconsFix;
import static com.android.systemui.charging.WirelessChargingAnimation.UNKNOWN_BATTERY_LEVEL;
import static com.android.systemui.statusbar.NotificationLockscreenUserManager.PERMISSION_SELF;
import static com.android.systemui.statusbar.StatusBarState.SHADE;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.IWallpaperManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.TaskInfo;
import android.app.UiModeManager;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.fingerprint.FingerprintManager;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.view.Display;
import android.view.IRemoteAnimationRunner;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.widget.DateTimeView;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleRegistry;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.keyguard.AuthKeyguardMessageArea;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.AutoReinflateContainer;
import com.android.systemui.CoreStartable;
import com.android.systemui.DejankUtils;
import com.android.systemui.EventLogTags;
import com.android.systemui.InitController;
import com.android.systemui.Prefs;
import com.android.systemui.accessibility.floatingmenu.AccessibilityFloatingMenuController;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.back.domain.interactor.BackActionInteractor;
import com.android.systemui.biometrics.AuthRippleController;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.camera.CameraIntents;
import com.android.systemui.charging.WiredChargingRippleController;
import com.android.systemui.charging.WirelessChargingAnimation;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.communal.domain.interactor.CommunalInteractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor;
import com.android.systemui.emergency.EmergencyGesture;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.fragments.ExtensionFragmentListener;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl;
import com.android.systemui.keyguard.ui.binder.LightRevealScrimViewBinder;
import com.android.systemui.keyguard.ui.viewmodel.LightRevealScrimViewModel;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.notetask.NoteTaskController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.OverlayPlugin;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.qs.QSFragmentLegacy;
import com.android.systemui.qs.QSPanelController;
import com.android.systemui.res.R;
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor;
import com.android.systemui.scene.shared.flag.SceneContainerFlags;
import com.android.systemui.scrim.ScrimView;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.settings.brightness.BrightnessSliderController;
import com.android.systemui.shade.CameraLauncher;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.NotificationShadeWindowViewController;
import com.android.systemui.shade.QuickSettingsController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.shade.ShadeExpansionListener;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.shade.ShadeLogger;
import com.android.systemui.shade.ShadeSurface;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.CircleReveal;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.KeyboardShortcutListSearch;
import com.android.systemui.statusbar.KeyboardShortcuts;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LiftReveal;
import com.android.systemui.statusbar.LightRevealScrim;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.PowerButtonReveal;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.core.StatusBarInitializer;
import com.android.systemui.statusbar.data.model.StatusBarMode;
import com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationLaunchAnimatorControllerProvider;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.shared.NotificationIconContainerRefactor;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.dagger.StatusBarPhoneModule;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.surfaceeffects.ripple.RippleShader.RippleShape;
import com.android.systemui.util.DumpUtilsKt;
import com.android.systemui.util.WallpaperController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.MessageRouter;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.volume.VolumeComponent;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.startingsurface.SplashscreenContentDrawer;
import com.android.wm.shell.startingsurface.StartingSurface;

import dalvik.annotation.optimization.NeverCompile;

import dagger.Lazy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

/**
 * A class handling initialization and coordination between some of the key central surfaces in
 * System UI: The notification shade, the keyguard (lockscreen), and the status bar.
 *
 * This class is not our ideal architecture because it doesn't enforce much isolation between these
 * three mostly disparate surfaces. In an ideal world, this class would not exist. Instead, we would
 * break it up into three modules -- one for each of those three surfaces -- and we would define any
 * APIs that are needed for these surfaces to communicate with each other when necessary.
 *
 * <b>If at all possible, please avoid adding additional code to this monstrous class! Our goal is
 * to break up this class into many small classes, and any code added here will slow down that goal.
 * </b>
 *
 * Note that ActivityStarter logic here is deprecated and should be added here as well as
 * {@link ActivityStarterImpl}
 */
@SysUISingleton
public class CentralSurfacesImpl implements CoreStartable, CentralSurfaces {

    private static final String BANNER_ACTION_CANCEL =
            "com.android.systemui.statusbar.banner_action_cancel";
    private static final String BANNER_ACTION_SETUP =
            "com.android.systemui.statusbar.banner_action_setup";

    private static final int MSG_LAUNCH_TRANSITION_TIMEOUT = 1003;
    // 1020-1040 reserved for BaseStatusBar

    private static final UiEventLogger sUiEventLogger = new UiEventLoggerImpl();

    private final Context mContext;
    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final DeviceStateManager mDeviceStateManager;
    private final Lazy<CentralSurfacesCommandQueueCallbacks> mCommandQueueCallbacksLazy;
    private CentralSurfacesCommandQueueCallbacks mCommandQueueCallbacks;
    private float mTransitionToFullShadeProgress = 0f;
    private final NotificationListContainer mNotifListContainer;
    private final boolean mIsShortcutListSearchEnabled;

    private final KeyguardStateController.Callback mKeyguardStateControllerCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    boolean occluded = mKeyguardStateController.isOccluded();
                    mStatusBarHideIconsForBouncerManager.setIsOccludedAndTriggerUpdate(occluded);
                    mScrimController.setKeyguardOccluded(occluded);
                }
            };

    void onStatusBarWindowStateChanged(@WindowVisibleState int state) {
        mStatusBarWindowState = state;
        updateBubblesVisibility();
    }

    @Override
    public void acquireGestureWakeLock(long time) {
        mGestureWakeLock.acquire(time);
    }

    @Override
    public void resendMessage(int msg) {
        mMessageRouter.cancelMessages(msg);
        mMessageRouter.sendMessage(msg);
    }

    @Override
    public void resendMessage(Object msg) {
        mMessageRouter.cancelMessages(msg.getClass());
        mMessageRouter.sendMessage(msg);
    }

    @Override
    public void setLastCameraLaunchSource(int source) {
        mLastCameraLaunchSource = source;
    }

    @Override
    public void setLaunchCameraOnFinishedGoingToSleep(boolean launch) {
        mLaunchCameraOnFinishedGoingToSleep = launch;
    }

    @Override
    public void setLaunchCameraOnFinishedWaking(boolean launch) {
        mLaunchCameraWhenFinishedWaking = launch;
    }

    @Override
    public void setLaunchEmergencyActionOnFinishedGoingToSleep(boolean launch) {
        mLaunchEmergencyActionOnFinishedGoingToSleep = launch;
    }

    @Override
    public void setLaunchEmergencyActionOnFinishedWaking(boolean launch) {
        mLaunchEmergencyActionWhenFinishedWaking = launch;
    }

    @Override
    public QSPanelController getQSPanelController() {
        return mQSPanelController;
    }

    /**
     * The {@link StatusBarState} of the status bar.
     */
    protected int mState; // TODO: remove this. Just use StatusBarStateController
    protected boolean mBouncerShowing;

    private final PhoneStatusBarPolicy mIconPolicy;

    private final VolumeComponent mVolumeComponent;
    private BrightnessMirrorController mBrightnessMirrorController;
    private boolean mBrightnessMirrorVisible;
    private BiometricUnlockController mBiometricUnlockController;
    private final LightBarController mLightBarController;
    private final AutoHideController mAutoHideController;

    private final Point mCurrentDisplaySize = new Point();

    protected PhoneStatusBarView mStatusBarView;
    private PhoneStatusBarViewController mPhoneStatusBarViewController;
    private PhoneStatusBarTransitions mStatusBarTransitions;
    private final AuthRippleController mAuthRippleController;
    @WindowVisibleState private int mStatusBarWindowState = WINDOW_STATE_SHOWING;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final StatusBarInitializer mStatusBarInitializer;
    private final StatusBarWindowController mStatusBarWindowController;
    private final StatusBarModeRepositoryStore mStatusBarModeRepository;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @VisibleForTesting
    DozeServiceHost mDozeServiceHost;
    private final LightRevealScrim mLightRevealScrim;
    private PowerButtonReveal mPowerButtonReveal;

    /**
     * Whether we should delay the wakeup animation (which shows the notifications and moves the
     * clock view). This is typically done when waking up from a 'press to unlock' gesture on a
     * device with a side fingerprint sensor, so that if the fingerprint scan is successful, we
     * can play the unlock animation directly rather than interrupting the wakeup animation part
     * way through.
     */
    private boolean mShouldDelayWakeUpAnimation = false;

    /**
     * Whether we should delay the AOD->Lockscreen animation.
     * If false, the animation will start in onStartedWakingUp().
     * If true, the animation will start in onFinishedWakingUp().
     */
    private boolean mShouldDelayLockscreenTransitionFromAod = false;

    private final Object mQueueLock = new Object();

    private final PulseExpansionHandler mPulseExpansionHandler;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;
    private final KeyguardBypassController mKeyguardBypassController;
    private final KeyguardStateController mKeyguardStateController;
    private final HeadsUpManager mHeadsUpManager;
    private final StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    private final FalsingCollector mFalsingCollector;
    private final FalsingManager mFalsingManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final ConfigurationController mConfigurationController;
    private final Lazy<NotificationShadeWindowViewController>
            mNotificationShadeWindowViewControllerLazy;
    private final DozeParameters mDozeParameters;
    private final Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    private final PluginManager mPluginManager;
    private final ShadeController mShadeController;
    private final WindowRootViewVisibilityInteractor mWindowRootViewVisibilityInteractor;
    private final InitController mInitController;
    private final Lazy<CameraLauncher> mCameraLauncherLazy;
    private final AlternateBouncerInteractor mAlternateBouncerInteractor;

    private final PluginDependencyProvider mPluginDependencyProvider;
    private final ExtensionController mExtensionController;
    private final UserInfoControllerImpl mUserInfoControllerImpl;
    private final DemoModeController mDemoModeController;
    private final NotificationsController mNotificationsController;
    private final StatusBarSignalPolicy mStatusBarSignalPolicy;
    private final StatusBarHideIconsForBouncerManager mStatusBarHideIconsForBouncerManager;
    private final Lazy<LightRevealScrimViewModel> mLightRevealScrimViewModelLazy;

    /** Controller for the Shade. */
    private final ShadeSurface mShadeSurface;
    private final ShadeLogger mShadeLogger;

    // settings
    private QSPanelController mQSPanelController;
    private final QuickSettingsController mQsController;

    KeyguardIndicationController mKeyguardIndicationController;

    private View mReportRejectedTouch;

    private final NotificationGutsManager mGutsManager;
    private final ShadeExpansionStateManager mShadeExpansionStateManager;
    private final KeyguardViewMediator mKeyguardViewMediator;
    private final BrightnessSliderController.Factory mBrightnessSliderFactory;
    private final FeatureFlags mFeatureFlags;
    private final FragmentService mFragmentService;
    private final ScreenOffAnimationController mScreenOffAnimationController;
    private final WallpaperController mWallpaperController;
    private final KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private final MessageRouter mMessageRouter;
    private final WallpaperManager mWallpaperManager;
    private final UserTracker mUserTracker;
    private final Provider<FingerprintManager> mFingerprintManager;
    private final ActivityStarter mActivityStarter;

    private final DisplayMetrics mDisplayMetrics;

    // XXX: gesture research
    private final GestureRecorder mGestureRec = DEBUG_GESTURES
        ? new GestureRecorder("/sdcard/statusbar_gestures.dat")
        : null;

    private final MetricsLogger mMetricsLogger;

    // ensure quick settings is disabled until the current user makes it through the setup wizard
    @VisibleForTesting
    protected boolean mUserSetup = false;

    @VisibleForTesting
    public enum StatusBarUiEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Secured lockscreen is opened.")
        LOCKSCREEN_OPEN_SECURE(405),

        @UiEvent(doc = "Lockscreen without security is opened.")
        LOCKSCREEN_OPEN_INSECURE(406),

        @UiEvent(doc = "Secured lockscreen is closed.")
        LOCKSCREEN_CLOSE_SECURE(407),

        @UiEvent(doc = "Lockscreen without security is closed.")
        LOCKSCREEN_CLOSE_INSECURE(408),

        @UiEvent(doc = "Secured bouncer is opened.")
        BOUNCER_OPEN_SECURE(409),

        @UiEvent(doc = "Bouncer without security is opened.")
        BOUNCER_OPEN_INSECURE(410),

        @UiEvent(doc = "Secured bouncer is closed.")
        BOUNCER_CLOSE_SECURE(411),

        @UiEvent(doc = "Bouncer without security is closed.")
        BOUNCER_CLOSE_INSECURE(412);

        private final int mId;

        StatusBarUiEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    private final DelayableExecutor mMainExecutor;

    private int mInteractingWindows;

    private final ViewMediatorCallback mKeyguardViewMediatorCallback;
    private final ScrimController mScrimController;
    protected DozeScrimController mDozeScrimController;
    private final BackActionInteractor mBackActionInteractor;
    private final JavaAdapter mJavaAdapter;
    private final Executor mUiBgExecutor;

    protected boolean mDozing;
    boolean mCloseQsBeforeScreenOff;

    private final NotificationMediaManager mMediaManager;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private boolean mWallpaperSupported;

    private Runnable mLaunchTransitionEndRunnable;
    private Runnable mLaunchTransitionCancelRunnable;
    private boolean mLaunchCameraWhenFinishedWaking;
    private boolean mLaunchCameraOnFinishedGoingToSleep;
    private boolean mLaunchEmergencyActionWhenFinishedWaking;
    private boolean mLaunchEmergencyActionOnFinishedGoingToSleep;
    private int mLastCameraLaunchSource;
    protected PowerManager.WakeLock mGestureWakeLock;

    // Fingerprint (as computed by getLoggingFingerprint() of the last logged state.
    private int mLastLoggedStateFingerprint;
    private boolean mIsLaunchingActivityOverLockscreen;

    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);
    protected final BatteryController mBatteryController;
    private UiModeManager mUiModeManager;
    private LogMaker mStatusBarStateLog;
    protected final NotificationIconAreaController mNotificationIconAreaController;
    @Nullable private View mAmbientIndicationContainer;
    private final SysuiColorExtractor mColorExtractor;
    private final ScreenLifecycle mScreenLifecycle;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    protected final PowerInteractor mPowerInteractor;

    private final CommunalInteractor mCommunalInteractor;

    /**
     * True if the device is showing the glanceable hub. See
     * {@link CommunalInteractor#isIdleOnCommunal()} for more details.
     */
    private boolean mIsIdleOnCommunal = false;
    private final Consumer<Boolean> mIdleOnCommunalConsumer = (Boolean idleOnCommunal) -> {
        if (idleOnCommunal == mIsIdleOnCommunal) {
            // Ignore initial value coming through the flow.
            return;
        }

        mIsIdleOnCommunal = idleOnCommunal;
        // Trigger an update for the scrim state when we enter or exit glanceable hub, so that we
        // can transition to/from ScrimState.GLANCEABLE_HUB if needed.
        updateScrimController();
    };

    private boolean mNoAnimationOnNextBarModeChange;
    private final SysuiStatusBarStateController mStatusBarStateController;

    private final ActivityTransitionAnimator mActivityTransitionAnimator;
    private final NotificationLaunchAnimatorControllerProvider mNotificationAnimationProvider;
    private final Lazy<NotificationPresenter> mPresenterLazy;
    private final Lazy<NotificationActivityStarter> mNotificationActivityStarterLazy;
    private final Lazy<NotificationShadeDepthController> mNotificationShadeDepthControllerLazy;
    private final Optional<Bubbles> mBubblesOptional;
    private final Lazy<NoteTaskController> mNoteTaskControllerLazy;
    private final Optional<StartingSurface> mStartingSurfaceOptional;

    private final ActivityIntentHelper mActivityIntentHelper;

    public final NotificationStackScrollLayoutController mStackScrollerController;

    private final ColorExtractor.OnColorsChangedListener mOnColorsChangedListener =
            (extractor, which) -> updateTheme();

    private final SceneContainerFlags mSceneContainerFlags;

    /**
     * Public constructor for CentralSurfaces.
     *
     * CentralSurfaces is considered optional, and therefore can not be marked as @Inject directly.
     * Instead, an @Provide method is included. See {@link StatusBarPhoneModule}.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Inject
    public CentralSurfacesImpl(
            Context context,
            NotificationsController notificationsController,
            FragmentService fragmentService,
            LightBarController lightBarController,
            AutoHideController autoHideController,
            StatusBarInitializer statusBarInitializer,
            StatusBarWindowController statusBarWindowController,
            StatusBarWindowStateController statusBarWindowStateController,
            StatusBarModeRepositoryStore statusBarModeRepository,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            StatusBarSignalPolicy statusBarSignalPolicy,
            PulseExpansionHandler pulseExpansionHandler,
            NotificationWakeUpCoordinator notificationWakeUpCoordinator,
            KeyguardBypassController keyguardBypassController,
            KeyguardStateController keyguardStateController,
            HeadsUpManager headsUpManager,
            FalsingManager falsingManager,
            FalsingCollector falsingCollector,
            BroadcastDispatcher broadcastDispatcher,
            NotificationGutsManager notificationGutsManager,
            ShadeExpansionStateManager shadeExpansionStateManager,
            KeyguardViewMediator keyguardViewMediator,
            DisplayMetrics displayMetrics,
            MetricsLogger metricsLogger,
            ShadeLogger shadeLogger,
            JavaAdapter javaAdapter,
            @UiBackground Executor uiBgExecutor,
            ShadeSurface shadeSurface,
            NotificationMediaManager notificationMediaManager,
            NotificationLockscreenUserManager lockScreenUserManager,
            NotificationRemoteInputManager remoteInputManager,
            QuickSettingsController quickSettingsController,
            BatteryController batteryController,
            SysuiColorExtractor colorExtractor,
            ScreenLifecycle screenLifecycle,
            WakefulnessLifecycle wakefulnessLifecycle,
            PowerInteractor powerInteractor,
            CommunalInteractor communalInteractor,
            SysuiStatusBarStateController statusBarStateController,
            Optional<Bubbles> bubblesOptional,
            Lazy<NoteTaskController> noteTaskControllerLazy,
            DeviceProvisionedController deviceProvisionedController,
            NavigationBarController navigationBarController,
            AccessibilityFloatingMenuController accessibilityFloatingMenuController,
            Lazy<AssistManager> assistManagerLazy,
            ConfigurationController configurationController,
            NotificationShadeWindowController notificationShadeWindowController,
            Lazy<NotificationShadeWindowViewController> notificationShadeWindowViewControllerLazy,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            // Lazys due to b/298099682.
            Lazy<NotificationPresenter> notificationPresenterLazy,
            Lazy<NotificationActivityStarter> notificationActivityStarterLazy,
            NotificationLaunchAnimatorControllerProvider notifTransitionAnimatorControllerProvider,
            DozeParameters dozeParameters,
            ScrimController scrimController,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            AuthRippleController authRippleController,
            DozeServiceHost dozeServiceHost,
            BackActionInteractor backActionInteractor,
            PowerManager powerManager,
            DozeScrimController dozeScrimController,
            VolumeComponent volumeComponent,
            CommandQueue commandQueue,
            Lazy<CentralSurfacesCommandQueueCallbacks> commandQueueCallbacksLazy,
            PluginManager pluginManager,
            ShadeController shadeController,
            WindowRootViewVisibilityInteractor windowRootViewVisibilityInteractor,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            ViewMediatorCallback viewMediatorCallback,
            InitController initController,
            @Named(TIME_TICK_HANDLER_NAME) Handler timeTickHandler,
            PluginDependencyProvider pluginDependencyProvider,
            ExtensionController extensionController,
            UserInfoControllerImpl userInfoControllerImpl,
            PhoneStatusBarPolicy phoneStatusBarPolicy,
            KeyguardIndicationController keyguardIndicationController,
            DemoModeController demoModeController,
            Lazy<NotificationShadeDepthController> notificationShadeDepthControllerLazy,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager,
            NotificationIconAreaController notificationIconAreaController,
            BrightnessSliderController.Factory brightnessSliderFactory,
            ScreenOffAnimationController screenOffAnimationController,
            WallpaperController wallpaperController,
            StatusBarHideIconsForBouncerManager statusBarHideIconsForBouncerManager,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            FeatureFlags featureFlags,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            @Main DelayableExecutor delayableExecutor,
            @Main MessageRouter messageRouter,
            WallpaperManager wallpaperManager,
            Optional<StartingSurface> startingSurfaceOptional,
            ActivityTransitionAnimator activityTransitionAnimator,
            DeviceStateManager deviceStateManager,
            WiredChargingRippleController wiredChargingRippleController,
            IDreamManager dreamManager,
            Lazy<CameraLauncher> cameraLauncherLazy,
            Lazy<LightRevealScrimViewModel> lightRevealScrimViewModelLazy,
            LightRevealScrim lightRevealScrim,
            AlternateBouncerInteractor alternateBouncerInteractor,
            UserTracker userTracker,
            Provider<FingerprintManager> fingerprintManager,
            ActivityStarter activityStarter,
            SceneContainerFlags sceneContainerFlags
    ) {
        mContext = context;
        mNotificationsController = notificationsController;
        mFragmentService = fragmentService;
        mLightBarController = lightBarController;
        mAutoHideController = autoHideController;
        mStatusBarInitializer = statusBarInitializer;
        mStatusBarWindowController = statusBarWindowController;
        mStatusBarModeRepository = statusBarModeRepository;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mPulseExpansionHandler = pulseExpansionHandler;
        mWakeUpCoordinator = notificationWakeUpCoordinator;
        mKeyguardBypassController = keyguardBypassController;
        mKeyguardStateController = keyguardStateController;
        mHeadsUpManager = headsUpManager;
        mBackActionInteractor = backActionInteractor;
        mKeyguardIndicationController = keyguardIndicationController;
        mStatusBarTouchableRegionManager = statusBarTouchableRegionManager;
        mFalsingCollector = falsingCollector;
        mFalsingManager = falsingManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mGutsManager = notificationGutsManager;
        mShadeExpansionStateManager = shadeExpansionStateManager;
        mKeyguardViewMediator = keyguardViewMediator;
        mDisplayMetrics = displayMetrics;
        mMetricsLogger = metricsLogger;
        mShadeLogger = shadeLogger;
        mJavaAdapter = javaAdapter;
        mUiBgExecutor = uiBgExecutor;
        mShadeSurface = shadeSurface;
        mMediaManager = notificationMediaManager;
        mLockscreenUserManager = lockScreenUserManager;
        mRemoteInputManager = remoteInputManager;
        mQsController = quickSettingsController;
        mBatteryController = batteryController;
        mColorExtractor = colorExtractor;
        mScreenLifecycle = screenLifecycle;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mPowerInteractor = powerInteractor;
        mCommunalInteractor = communalInteractor;
        mStatusBarStateController = statusBarStateController;
        mBubblesOptional = bubblesOptional;
        mNoteTaskControllerLazy = noteTaskControllerLazy;
        mDeviceProvisionedController = deviceProvisionedController;
        mNavigationBarController = navigationBarController;
        mAccessibilityFloatingMenuController = accessibilityFloatingMenuController;
        mAssistManagerLazy = assistManagerLazy;
        mConfigurationController = configurationController;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mNotificationShadeWindowViewControllerLazy = notificationShadeWindowViewControllerLazy;
        mStackScrollerController = notificationStackScrollLayoutController;
        mStackScroller = mStackScrollerController.getView();
        mNotifListContainer = mStackScrollerController.getNotificationListContainer();
        mPresenterLazy = notificationPresenterLazy;
        mNotificationActivityStarterLazy = notificationActivityStarterLazy;
        mNotificationAnimationProvider = notifTransitionAnimatorControllerProvider;
        mDozeServiceHost = dozeServiceHost;
        mPowerManager = powerManager;
        mDozeParameters = dozeParameters;
        mScrimController = scrimController;
        mDozeScrimController = dozeScrimController;
        mBiometricUnlockControllerLazy = biometricUnlockControllerLazy;
        mAuthRippleController = authRippleController;
        mNotificationShadeDepthControllerLazy = notificationShadeDepthControllerLazy;
        mVolumeComponent = volumeComponent;
        mCommandQueue = commandQueue;
        mCommandQueueCallbacksLazy = commandQueueCallbacksLazy;
        mPluginManager = pluginManager;
        mShadeController = shadeController;
        mWindowRootViewVisibilityInteractor = windowRootViewVisibilityInteractor;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mKeyguardViewMediatorCallback = viewMediatorCallback;
        mInitController = initController;
        mPluginDependencyProvider = pluginDependencyProvider;
        mExtensionController = extensionController;
        mUserInfoControllerImpl = userInfoControllerImpl;
        mIconPolicy = phoneStatusBarPolicy;
        mDemoModeController = demoModeController;
        mNotificationIconAreaController = notificationIconAreaController;
        mBrightnessSliderFactory = brightnessSliderFactory;
        mWallpaperController = wallpaperController;
        mStatusBarSignalPolicy = statusBarSignalPolicy;
        mStatusBarHideIconsForBouncerManager = statusBarHideIconsForBouncerManager;
        mFeatureFlags = featureFlags;
        mIsShortcutListSearchEnabled = featureFlags.isEnabled(Flags.SHORTCUT_LIST_SEARCH_LAYOUT);
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mMainExecutor = delayableExecutor;
        mMessageRouter = messageRouter;
        mWallpaperManager = wallpaperManager;
        mCameraLauncherLazy = cameraLauncherLazy;
        mAlternateBouncerInteractor = alternateBouncerInteractor;
        mUserTracker = userTracker;
        mFingerprintManager = fingerprintManager;
        mActivityStarter = activityStarter;
        mSceneContainerFlags = sceneContainerFlags;

        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        mStartingSurfaceOptional = startingSurfaceOptional;
        mDreamManager = dreamManager;
        lockscreenShadeTransitionController.setCentralSurfaces(this);
        statusBarWindowStateController.addListener(this::onStatusBarWindowStateChanged);

        mScreenOffAnimationController = screenOffAnimationController;

        ShadeExpansionListener shadeExpansionListener = this::onPanelExpansionChanged;
        ShadeExpansionChangeEvent currentState =
                mShadeExpansionStateManager.addExpansionListener(shadeExpansionListener);
        shadeExpansionListener.onPanelExpansionChanged(currentState);

        mActivityIntentHelper = new ActivityIntentHelper(mContext);
        mActivityTransitionAnimator = activityTransitionAnimator;

        // TODO(b/190746471): Find a better home for this.
        DateTimeView.setReceiverHandler(timeTickHandler);

        mMessageRouter.subscribeTo(KeyboardShortcutsMessage.class,
                data -> toggleKeyboardShortcuts(data.mDeviceId));
        mMessageRouter.subscribeTo(MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU,
                id -> dismissKeyboardShortcuts());
        mMessageRouter.subscribeTo(AnimateExpandSettingsPanelMessage.class,
                data -> mCommandQueueCallbacks.animateExpandSettingsPanel(data.mSubpanel));
        mMessageRouter.subscribeTo(MSG_LAUNCH_TRANSITION_TIMEOUT,
                id -> onLaunchTransitionTimeout());

        mDeviceStateManager = deviceStateManager;
        wiredChargingRippleController.registerCallbacks();

        mLightRevealScrimViewModelLazy = lightRevealScrimViewModelLazy;
        mLightRevealScrim = lightRevealScrim;

        // Based on teamfood flag, turn predictive back dispatch on at runtime.
        if (predictiveBackSysui()) {
            mContext.getApplicationInfo().setEnableOnBackInvokedCallback(true);
        }
    }

    private void initBubbles(Bubbles bubbles) {
        final Bubbles.BubbleExpandListener listener = (isExpanding, key) ->
                mContext.getMainExecutor().execute(() -> {
                    updateScrimController();
                    mNoteTaskControllerLazy.get().onBubbleExpandChanged(isExpanding, key);
                });
        bubbles.setExpandListener(listener);
    }

    @Override
    public void start() {
        mScreenLifecycle.addObserver(mScreenObserver);
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
        mBubblesOptional.ifPresent(this::initBubbles);

        mStatusBarSignalPolicy.init();
        mKeyguardIndicationController.init();

        mColorExtractor.addOnColorsChangedListener(mOnColorsChangedListener);

        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);

        mDisplay = mContext.getDisplay();
        mDisplayId = mDisplay.getDisplayId();
        updateDisplaySize();
        mStatusBarHideIconsForBouncerManager.setDisplayId(mDisplayId);

        initShadeVisibilityListener();

        // start old BaseStatusBar.start().
        mWindowManagerService = WindowManagerGlobal.getWindowManagerService();
        mDevicePolicyManager = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);

        mAccessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);

        mKeyguardUpdateMonitor.setKeyguardBypassController(mKeyguardBypassController);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mWallpaperSupported = mWallpaperManager.isWallpaperSupported();

        RegisterStatusBarResult result = null;
        try {
            result = mBarService.registerStatusBar(mCommandQueue);
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }

        createAndAddWindows(result);

        // Set up the initial notification state. This needs to happen before CommandQueue.disable()
        setUpPresenter();

        if ((result.mTransientBarTypes & WindowInsets.Type.statusBars()) != 0) {
            mStatusBarModeRepository.getDefaultDisplay().showTransient();
        }
        mCommandQueueCallbacks.onSystemBarAttributesChanged(mDisplayId, result.mAppearance,
                result.mAppearanceRegions, result.mNavbarColorManagedByIme, result.mBehavior,
                result.mRequestedVisibleTypes, result.mPackageName, result.mLetterboxDetails);

        // StatusBarManagerService has a back up of IME token and it's restored here.
        mCommandQueueCallbacks.setImeWindowStatus(mDisplayId, result.mImeToken,
                result.mImeWindowVis, result.mImeBackDisposition, result.mShowImeSwitcher);

        // Set up the initial icon state
        int numIcons = result.mIcons.size();
        for (int i = 0; i < numIcons; i++) {
            mCommandQueue.setIcon(result.mIcons.keyAt(i), result.mIcons.valueAt(i));
        }

        if (DEBUG) {
            Log.d(TAG, String.format(
                    "init: icons=%d disabled=0x%08x lights=0x%08x imeButton=0x%08x",
                    numIcons,
                    result.mDisabledFlags1,
                    result.mAppearance,
                    result.mImeWindowVis));
        }

        IntentFilter internalFilter = new IntentFilter();
        internalFilter.addAction(BANNER_ACTION_CANCEL);
        internalFilter.addAction(BANNER_ACTION_SETUP);
        mContext.registerReceiver(mBannerActionBroadcastReceiver, internalFilter, PERMISSION_SELF,
                null, Context.RECEIVER_EXPORTED_UNAUDITED);

        if (mWallpaperSupported) {
            IWallpaperManager wallpaperManager = IWallpaperManager.Stub.asInterface(
                    ServiceManager.getService(Context.WALLPAPER_SERVICE));
            try {
                wallpaperManager.setInAmbientMode(false /* ambientMode */, 0L /* duration */);
            } catch (RemoteException e) {
                // Just pass, nothing critical.
            }
        }

        // end old BaseStatusBar.start().

        // Lastly, call to the icon policy to install/update all the icons.
        mIconPolicy.init();

        mKeyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onUnlockedChanged() {
                logStateToEventlog();
            }

            @Override
            public void onKeyguardGoingAwayChanged() {
                if (lightRevealMigration()) {
                    // This code path is not used if the KeyguardTransitionRepository is managing
                    // the lightreveal scrim.
                    return;
                }

                // The light reveal scrim should always be fully revealed by the time the keyguard
                // is done going away. Double check that this is true.
                if (!mKeyguardStateController.isKeyguardGoingAway()) {
                    if (mLightRevealScrim.getRevealAmount() != 1f) {
                        Log.e(TAG, "Keyguard is done going away, but someone left the light reveal "
                                + "scrim at reveal amount: " + mLightRevealScrim.getRevealAmount());
                    }

                    // If the auth ripple is still playing, let it finish.
                    if (!mAuthRippleController.isAnimatingLightRevealScrim()) {
                        mLightRevealScrim.setRevealAmount(1f);
                    }
                }
            }
        });
        startKeyguard();

        mKeyguardUpdateMonitor.registerCallback(mUpdateCallback);
        mDozeServiceHost.initialize(
                this,
                mStatusBarKeyguardViewManager,
                getNotificationShadeWindowViewController(),
                mShadeSurface,
                mAmbientIndicationContainer);
        updateLightRevealScrimVisibility();

        mConfigurationController.addCallback(mConfigurationListener);

        mBatteryController.observe(mLifecycle, mBatteryStateChangeCallback);
        mLifecycle.setCurrentState(RESUMED);

        mAccessibilityFloatingMenuController.init();

        // set the initial view visibility
        int disabledFlags1 = result.mDisabledFlags1;
        int disabledFlags2 = result.mDisabledFlags2;
        mInitController.addPostInitTask(() -> {
            setUpDisableFlags(disabledFlags1, disabledFlags2);
            try {
                // NOTE(b/262059863): Force-update the disable flags after applying the flags
                // returned from registerStatusBar(). The result's disabled flags may be stale
                // if StatusBarManager's disabled flags are updated between registering the bar and
                // this handling this post-init task. We force an update in this case, and use a new
                // token to not conflict with any other disabled flags already requested by SysUI
                Binder token = new Binder();
                mBarService.disable(DISABLE_HOME, token, mContext.getPackageName());
                mBarService.disable(0, token, mContext.getPackageName());
            } catch (RemoteException ex) {
                ex.rethrowFromSystemServer();
            }
        });

        registerCallbacks();

        mFalsingManager.addFalsingBeliefListener(mFalsingBeliefListener);

        mPluginManager.addPluginListener(
                new PluginListener<OverlayPlugin>() {
                    private final ArraySet<OverlayPlugin> mOverlays = new ArraySet<>();

                    @Override
                    public void onPluginConnected(OverlayPlugin plugin, Context pluginContext) {
                        mMainExecutor.execute(
                                () -> plugin.setup(
                                        mNotificationShadeWindowController.getWindowRootView(),
                                        getNavigationBarView(),
                                        new Callback(plugin), mDozeParameters));
                    }

                    @Override
                    public void onPluginDisconnected(OverlayPlugin plugin) {
                        mMainExecutor.execute(() -> {
                            mOverlays.remove(plugin);
                            mNotificationShadeWindowController
                                    .setForcePluginOpen(mOverlays.size() != 0, this);
                        });
                    }

                    class Callback implements OverlayPlugin.Callback {
                        private final OverlayPlugin mPlugin;

                        Callback(OverlayPlugin plugin) {
                            mPlugin = plugin;
                        }

                        @Override
                        public void onHoldStatusBarOpenChange() {
                            if (mPlugin.holdStatusBarOpen()) {
                                mOverlays.add(mPlugin);
                            } else {
                                mOverlays.remove(mPlugin);
                            }
                            mMainExecutor.execute(() -> {
                                mNotificationShadeWindowController
                                        .setStateListener(b -> mOverlays.forEach(
                                                o -> o.setCollapseDesired(b)));
                                mNotificationShadeWindowController
                                        .setForcePluginOpen(mOverlays.size() != 0, this);
                            });
                        }
                    }
                }, OverlayPlugin.class, true /* Allow multiple plugins */);

        mStartingSurfaceOptional.ifPresent(startingSurface -> startingSurface.setSysuiProxy(
                (requestTopUi, componentTag) -> mMainExecutor.execute(() ->
                        mNotificationShadeWindowController.setRequestTopUi(
                                requestTopUi, componentTag))));
    }

    @VisibleForTesting
    /** Registers listeners/callbacks with external dependencies. */
    void registerCallbacks() {
        //TODO(b/264502026) move the rest of the listeners here.
        mDeviceStateManager.registerCallback(mMainExecutor,
                new FoldStateListener(mContext, this::onFoldedStateChanged));

        mJavaAdapter.alwaysCollectFlow(
                mCommunalInteractor.isIdleOnCommunal(),
                mIdleOnCommunalConsumer);
    }

    /**
     * @deprecated use {@link
     * WindowRootViewVisibilityInteractor.isLockscreenOrShadeVisible} instead.
     */    @VisibleForTesting
    @Deprecated
    void initShadeVisibilityListener() {
        mShadeController.setVisibilityListener(new ShadeController.ShadeVisibilityListener() {
            @Override
            public void expandedVisibleChanged(boolean expandedVisible) {
                if (expandedVisible) {
                    setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true);
                } else {
                    onExpandedInvisible();
                }
            }
        });
    }

    private void onFoldedStateChanged(boolean isFolded, boolean willGoToSleep) {
        Trace.beginSection("CentralSurfaces#onFoldedStateChanged");
        onFoldedStateChangedInternal(isFolded, willGoToSleep);
        Trace.endSection();
    }

    private void onFoldedStateChangedInternal(boolean isFolded, boolean willGoToSleep) {
        // Folded state changes are followed by a screen off event.
        // By default turning off the screen also closes the shade.
        // We want to make sure that the shade status is kept after folding/unfolding.
        boolean isShadeOpen = mShadeController.isShadeFullyOpen();
        boolean isShadeExpandingOrCollapsing = mShadeController.isExpandingOrCollapsing();
        boolean leaveOpen = isShadeOpen && !willGoToSleep && mState == SHADE;
        if (DEBUG) {
            Log.d(TAG, String.format(
                    "#onFoldedStateChanged(): "
                            + "isFolded=%s, "
                            + "willGoToSleep=%s, "
                            + "isShadeOpen=%s, "
                            + "isShadeExpandingOrCollapsing=%s, "
                            + "leaveOpen=%s",
                    isFolded, willGoToSleep, isShadeOpen, isShadeExpandingOrCollapsing, leaveOpen));
        }
        if (leaveOpen) {
            // below makes shade stay open when going from folded to unfolded
            mStatusBarStateController.setLeaveOpenOnKeyguardHide(true);
        }
        if (mState != SHADE && (isShadeOpen || isShadeExpandingOrCollapsing)) {
            // When device state changes on KEYGUARD/SHADE_LOCKED we don't want to keep the state of
            // the shade and instead we open clean state of keyguard with shade closed.
            // Normally some parts of QS state (like expanded/collapsed) are persisted and
            // that causes incorrect UI rendering, especially when changing state with QS
            // expanded. To prevent that we can close QS which resets QS and some parts of
            // the shade to its default state. Read more in b/201537421
            mCloseQsBeforeScreenOff = true;
        }
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    protected void makeStatusBarView(@Nullable RegisterStatusBarResult result) {
        updateDisplaySize(); // populates mDisplayMetrics
        updateResources();
        updateTheme();

        setUpShade();
        getNotificationShadeWindowView().setOnTouchListener(getStatusBarWindowTouchListener());
        mWallpaperController.setRootView(getNotificationShadeWindowView());

        mDemoModeController.addCallback(mDemoModeCallback);
        mJavaAdapter.alwaysCollectFlow(
                mStatusBarModeRepository.getDefaultDisplay().isTransientShown(),
                this::onTransientShownChanged);
        mJavaAdapter.alwaysCollectFlow(
                mStatusBarModeRepository.getDefaultDisplay().getStatusBarMode(),
                this::updateBarMode);

        mCommandQueueCallbacks = mCommandQueueCallbacksLazy.get();
        mCommandQueue.addCallback(mCommandQueueCallbacks);

        // TODO: Deal with the ugliness that comes from having some of the status bar broken out
        // into fragments, but the rest here, it leaves some awkward lifecycle and whatnot.
        ShadeExpansionChangeEvent currentState =
                mShadeExpansionStateManager.addExpansionListener(mWakeUpCoordinator);
        mWakeUpCoordinator.onPanelExpansionChanged(currentState);

        // Allow plugins to reference DarkIconDispatcher and StatusBarStateController
        mPluginDependencyProvider.allowPluginDependency(DarkIconDispatcher.class);
        mPluginDependencyProvider.allowPluginDependency(StatusBarStateController.class);

        // Set up CollapsedStatusBarFragment and PhoneStatusBarView
        mStatusBarInitializer.setStatusBarViewUpdatedListener(
                (statusBarView, statusBarViewController, statusBarTransitions) -> {
                    mStatusBarView = statusBarView;
                    mPhoneStatusBarViewController = statusBarViewController;
                    mStatusBarTransitions = statusBarTransitions;
                    getNotificationShadeWindowViewController()
                            .setStatusBarViewController(mPhoneStatusBarViewController);
                    // Ensure we re-propagate panel expansion values to the panel controller and
                    // any listeners it may have, such as PanelBar. This will also ensure we
                    // re-display the notification panel if necessary (for example, if
                    // a heads-up notification was being displayed and should continue being
                    // displayed).
                    mShadeSurface.updateExpansionAndVisibility();
                    setBouncerShowingForStatusBarComponents(mBouncerShowing);
                    checkBarModes();
                });
        mStatusBarInitializer.initializeStatusBar();

        mStatusBarTouchableRegionManager.setup(getNotificationShadeWindowView());

        createNavigationBar(result);

        mAmbientIndicationContainer = getNotificationShadeWindowView().findViewById(
                R.id.ambient_indication_container);

        mAutoHideController.setStatusBar(new AutoHideUiElement() {
            @Override
            public void synchronizeState() {
                checkBarModes();
            }

            @Override
            public boolean shouldHideOnTouch() {
                return !mRemoteInputManager.isRemoteInputActive();
            }

            @Override
            public boolean isVisible() {
                return isTransientShown();
            }

            @Override
            public void hide() {
                mStatusBarModeRepository.getDefaultDisplay().clearTransient();
            }
        });

        ScrimView scrimBehind = getNotificationShadeWindowView().findViewById(R.id.scrim_behind);
        ScrimView notificationsScrim = getNotificationShadeWindowView()
                .findViewById(R.id.scrim_notifications);
        ScrimView scrimInFront = getNotificationShadeWindowView().findViewById(R.id.scrim_in_front);

        mScrimController.setScrimVisibleListener(scrimsVisible -> {
            mNotificationShadeWindowController.setScrimsVisibility(scrimsVisible);
        });
        mScrimController.attachViews(scrimBehind, notificationsScrim, scrimInFront);

        if (lightRevealMigration()) {
            LightRevealScrimViewBinder.bind(
                    mLightRevealScrim, mLightRevealScrimViewModelLazy.get());
        }

        mLightRevealScrim.setScrimOpaqueChangedListener((opaque) -> {
            Runnable updateOpaqueness = () -> {
                mNotificationShadeWindowController.setLightRevealScrimOpaque(
                        mLightRevealScrim.isScrimOpaque());
                mScreenOffAnimationController
                        .onScrimOpaqueChanged(mLightRevealScrim.isScrimOpaque());
            };
            if (opaque) {
                // Delay making the view opaque for a frame, because it needs some time to render
                // otherwise this can lead to a flicker where the scrim doesn't cover the screen
                mLightRevealScrim.post(updateOpaqueness);
            } else {
                updateOpaqueness.run();
            }
        });

        mScreenOffAnimationController.initialize(this, mShadeSurface, mLightRevealScrim);
        updateLightRevealScrimVisibility();

        mShadeSurface.initDependencies(
                this,
                mGestureRec,
                mShadeController::makeExpandedInvisible,
                mHeadsUpManager);

        // Set up the quick settings tile panel
        final View container = getNotificationShadeWindowView().findViewById(R.id.qs_frame);
        if (container != null && !mSceneContainerFlags.isEnabled()) {
            FragmentHostManager fragmentHostManager =
                    mFragmentService.getFragmentHostManager(container);
            ExtensionFragmentListener.attachExtensonToFragment(
                    mFragmentService,
                    container,
                    QS.TAG,
                    R.id.qs_frame,
                    mExtensionController
                            .newExtension(QS.class)
                            .withPlugin(QS.class)
                            .withDefault(this::createDefaultQSFragment)
                            .build());
            mBrightnessMirrorController = new BrightnessMirrorController(
                    getNotificationShadeWindowView(),
                    mShadeSurface,
                    mNotificationShadeDepthControllerLazy.get(),
                    mBrightnessSliderFactory,
                    (visible) -> {
                        mBrightnessMirrorVisible = visible;
                        updateScrimController();
                    });
            fragmentHostManager.addTagListener(QS.TAG, (tag, f) -> {
                QS qs = (QS) f;
                if (qs instanceof QSFragmentLegacy) {
                    QSFragmentLegacy qsFragment = (QSFragmentLegacy) qs;
                    mQSPanelController = qsFragment.getQSPanelController();
                    qsFragment.setBrightnessMirrorController(mBrightnessMirrorController);
                }
            });
        }

        mReportRejectedTouch = getNotificationShadeWindowView()
                .findViewById(R.id.report_rejected_touch);
        if (mReportRejectedTouch != null) {
            updateReportRejectedTouchVisibility();
            mReportRejectedTouch.setOnClickListener(v -> {
                Uri session = mFalsingManager.reportRejectedTouch();
                if (session == null) { return; }

                StringWriter message = new StringWriter();
                message.write("Build info: ");
                message.write(SystemProperties.get("ro.build.description"));
                message.write("\nSerial number: ");
                message.write(SystemProperties.get("ro.serialno"));
                message.write("\n");

                mActivityStarter.startActivityDismissingKeyguard(Intent.createChooser(new Intent(
                                                Intent.ACTION_SEND)
                                                .setType("*/*")
                                                .putExtra(Intent.EXTRA_SUBJECT, "Rejected touch "
                                                        + "report")
                                                .putExtra(Intent.EXTRA_STREAM, session)
                                                .putExtra(Intent.EXTRA_TEXT, message.toString()),
                                        "Share rejected touch report")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        true /* onlyProvisioned */, true /* dismissShade */);
            });
        }

        if (!mPowerManager.isInteractive()) {
            mBroadcastReceiver.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));
        }
        mGestureWakeLock = mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "sysui:GestureWakeLock");

        // receive broadcasts
        registerBroadcastReceiver();

        // listen for USER_SETUP_COMPLETE setting (per-user)
        mDeviceProvisionedController.addCallback(mUserSetupObserver);
        mUserSetupObserver.onUserSetupChanged();

        // disable profiling bars, since they overlap and clutter the output on app windows
        ThreadedRenderer.overrideProperty("disableProfileBars", "true");

        // Private API call to make the shadows look better for Recents
        ThreadedRenderer.overrideProperty("ambientRatio", String.valueOf(1.5f));
    }


    /**
     * When swiping up to dismiss the lock screen, the panel expansion fraction goes from 1f to 0f.
     * This results in the clock/notifications/other content disappearing off the top of the screen.
     *
     * We also use the expansion fraction to animate in the app/launcher surface from the bottom of
     * the screen, 'pushing' off the notifications and other content. To do this, we dispatch the
     * expansion fraction to the KeyguardViewMediator if we're in the process of dismissing the
     * keyguard.
     */
    private void dispatchPanelExpansionForKeyguardDismiss(float fraction, boolean trackingTouch) {
        // Things that mean we're not swiping to dismiss the keyguard, and should ignore this
        // expansion:
        // - Keyguard isn't even visible.
        // - We're swiping on the bouncer, not the lockscreen.
        // - Keyguard is occluded. Expansion changes here are the shade being expanded over the
        //   occluding activity.
        // - Keyguard is visible, but can't be dismissed (swiping up will show PIN/password prompt).
        // - The SIM is locked, you can't swipe to unlock. If the SIM is locked but there is no
        //   device lock set, canDismissLockScreen returns true even though you should not be able
        //   to dismiss the lock screen until entering the SIM PIN.
        // - QS is expanded and we're swiping - swiping up now will hide QS, not dismiss the
        //   keyguard.
        // - Shade is in QQS over keyguard - swiping up should take us back to keyguard
        if (!mKeyguardStateController.isShowing()
                || mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing()
                || mKeyguardStateController.isOccluded()
                || !mKeyguardStateController.canDismissLockScreen()
                || mKeyguardViewMediator.isAnySimPinSecure()
                || (mQsController.getExpanded() && trackingTouch)
                || mShadeSurface.getBarState() == StatusBarState.SHADE_LOCKED) {
            return;
        }

        // Otherwise, we should let the keyguard know about this if we're tracking touch, or if we
        // are already animating the keyguard dismiss (since we will need to either finish or cancel
        // the animation).
        if (trackingTouch
                || mKeyguardViewMediator.isAnimatingBetweenKeyguardAndSurfaceBehindOrWillBe()
                || mKeyguardUnlockAnimationController.isUnlockingWithSmartSpaceTransition()) {
            mKeyguardStateController.notifyKeyguardDismissAmountChanged(
                    1f - fraction, trackingTouch);
        }
    }

    private void onPanelExpansionChanged(ShadeExpansionChangeEvent event) {
        float fraction = event.getFraction();
        boolean tracking = event.getTracking();
        dispatchPanelExpansionForKeyguardDismiss(fraction, tracking);

        if (fraction == 0 || fraction == 1) {
            if (getNavigationBarView() != null) {
                getNavigationBarView().onStatusBarPanelStateChanged();
            }
            if (getShadeViewController() != null) {
                // Needed to update SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED and
                // SYSUI_STATE_QUICK_SETTINGS_EXPANDED
                getShadeViewController().updateSystemUiStateFlags();
            }
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @VisibleForTesting
    protected void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter, null, UserHandle.ALL);
    }

    protected QS createDefaultQSFragment() {
        return mFragmentService
                .getFragmentHostManager(getNotificationShadeWindowView())
                .create(QSFragmentLegacy.class);
    }

    private void setUpPresenter() {
        // Set up the initial notification state.
        mActivityTransitionAnimator.setCallback(mActivityTransitionAnimatorCallback);
        mActivityTransitionAnimator.addListener(mActivityTransitionAnimatorListener);
        mRemoteInputManager.addControllerCallback(mNotificationShadeWindowController);
        mStackScrollerController.setNotificationActivityStarter(
                mNotificationActivityStarterLazy.get());
        mGutsManager.setNotificationActivityStarter(mNotificationActivityStarterLazy.get());
        mShadeController.setNotificationPresenter(mPresenterLazy.get());
        mNotificationsController.initialize(
                mPresenterLazy.get(),
                mNotifListContainer,
                mStackScrollerController.getNotifStackController(),
                mNotificationActivityStarterLazy.get());
        mWindowRootViewVisibilityInteractor.setUp(mPresenterLazy.get(), mNotificationsController);
    }

    /**
     * Post-init task of {@link #start()}
     * @param state1 disable1 flags
     * @param state2 disable2 flags
     */
    protected void setUpDisableFlags(int state1, int state2) {
        mCommandQueue.disable(mDisplayId, state1, state2, false /* animate */);
    }

    // TODO(b/117478341): This was left such that CarStatusBar can override this method.
    // Try to remove this.
    protected void createNavigationBar(@Nullable RegisterStatusBarResult result) {
        mNavigationBarController.createNavigationBars(true /* includeDefaultDisplay */, result);
    }

    /**
     * Returns the {@link android.view.View.OnTouchListener} that will be invoked when the
     * background window of the status bar is clicked.
     */
    protected View.OnTouchListener getStatusBarWindowTouchListener() {
        return (v, event) -> {
            mAutoHideController.checkUserAutoHide(event);
            mRemoteInputManager.checkRemoteInputOutside(event);
            if (!KeyguardShadeMigrationNssl.isEnabled()) {
                mShadeController.onStatusBarTouch(event);
            }
            return getNotificationShadeWindowView().onTouchEvent(event);
        };
    }

    private void setUpShade() {
        // Ideally, NotificationShadeWindowController could automatically fetch the window root view
        // in #attach or a CoreStartable.start method or something similar. But for now, to avoid
        // regressions, we'll continue standing up the root view in CentralSurfaces.
        mNotificationShadeWindowController.fetchWindowRootView();
        getNotificationShadeWindowViewController().setupExpandedStatusBar();
        getNotificationShadeWindowViewController().setupCommunalHubLayout();
        mShadeController.setNotificationShadeWindowViewController(
                getNotificationShadeWindowViewController());
        mBackActionInteractor.setup(mQsController, mShadeSurface);
    }

    protected NotificationShadeWindowViewController getNotificationShadeWindowViewController() {
        return mNotificationShadeWindowViewControllerLazy.get();
    }

    protected NotificationShadeWindowView getNotificationShadeWindowView() {
        return getNotificationShadeWindowViewController().getView();
    }

    protected void startKeyguard() {
        Trace.beginSection("CentralSurfaces#startKeyguard");
        mStatusBarStateController.addCallback(mStateListener,
                SysuiStatusBarStateController.RANK_STATUS_BAR);
        mBiometricUnlockController = mBiometricUnlockControllerLazy.get();
        mBiometricUnlockController.addListener(
                new BiometricUnlockController.BiometricUnlockEventsListener() {
                    @Override
                    public void onResetMode() {
                        setWakeAndUnlocking(false);
                        notifyBiometricAuthModeChanged();
                    }

                    @Override
                    public void onModeChanged(int mode) {
                        switch (mode) {
                            case BiometricUnlockController.MODE_WAKE_AND_UNLOCK_FROM_DREAM:
                            case BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING:
                            case BiometricUnlockController.MODE_WAKE_AND_UNLOCK:
                                setWakeAndUnlocking(true);
                        }
                        notifyBiometricAuthModeChanged();
                    }

                    private void setWakeAndUnlocking(boolean wakeAndUnlocking) {
                        if (getNavigationBarView() != null) {
                            getNavigationBarView().setWakeAndUnlocking(wakeAndUnlocking);
                        }
                    }
                });
        mKeyguardViewMediator.registerCentralSurfaces(
                /* statusBar= */ this,
                mShadeSurface,
                mShadeExpansionStateManager,
                mBiometricUnlockController,
                mStackScroller,
                mKeyguardBypassController);
        mKeyguardStateController.addCallback(mKeyguardStateControllerCallback);
        mKeyguardIndicationController
                .setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        mBiometricUnlockController.setKeyguardViewController(mStatusBarKeyguardViewManager);
        mRemoteInputManager.addControllerCallback(mStatusBarKeyguardViewManager);

        mLightBarController.setBiometricUnlockController(mBiometricUnlockController);
        Trace.endSection();
    }

    protected ShadeViewController getShadeViewController() {
        return mShadeSurface;
    }

    @Override
    public AuthKeyguardMessageArea getKeyguardMessageArea() {
        return getNotificationShadeWindowViewController().getKeyguardMessageArea();
    }

    private void updateReportRejectedTouchVisibility() {
        if (mReportRejectedTouch == null) {
            return;
        }
        mReportRejectedTouch.setVisibility(mState == StatusBarState.KEYGUARD && !mDozing
                && mFalsingCollector.isReportingEnabled() ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Whether we are currently animating an activity launch above the lockscreen (occluding
     * activity).
     */
    @Override
    public boolean isLaunchingActivityOverLockscreen() {
        return mIsLaunchingActivityOverLockscreen;
    }

    /**
     * To be called when there's a state change in StatusBarKeyguardViewManager.
     */
    @Override
    public void onKeyguardViewManagerStatesUpdated() {
        logStateToEventlog();
    }

    @VisibleForTesting
    @Override
    public void setBarStateForTest(int state) {
        mState = state;
    }

    static class AnimateExpandSettingsPanelMessage {
        final String mSubpanel;

        AnimateExpandSettingsPanelMessage(String subpanel) {
            mSubpanel = subpanel;
        }
    }

    private void maybeEscalateHeadsUp() {
        mHeadsUpManager.getAllEntries().forEach(entry -> {
            final StatusBarNotification sbn = entry.getSbn();
            final Notification notification = sbn.getNotification();
            if (notification.fullScreenIntent != null) {
                if (DEBUG) {
                    Log.d(TAG, "converting a heads up to fullScreen");
                }
                try {
                    EventLog.writeEvent(EventLogTags.SYSUI_HEADS_UP_ESCALATION,
                            sbn.getKey());
                    mPowerInteractor.wakeUpForFullScreenIntent();
                    ActivityOptions opts = ActivityOptions.makeBasic();
                    opts.setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    notification.fullScreenIntent.send(opts.toBundle());
                    entry.notifyFullScreenIntentLaunched();
                } catch (PendingIntent.CanceledException e) {
                }
            }
        });
        mHeadsUpManager.releaseAllImmediately();
    }

    private void onExpandedInvisible() {
        setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
        if (!mNotificationActivityStarterLazy.get().isCollapsingToShowActivityOverLockscreen()) {
            showBouncerOrLockScreenIfKeyguard();
        } else if (DEBUG) {
            Log.d(TAG, "Not showing bouncer due to activity showing over lockscreen");
        }
    }

    @Override
    public boolean getCommandQueuePanelsEnabled() {
        return mCommandQueue.panelsEnabled();
    }

    private void onTransientShownChanged(boolean transientShown) {
        if (transientShown) {
            mNoAnimationOnNextBarModeChange = true;
        }
    }

    private void updateBarMode(StatusBarMode barMode) {
        checkBarModes();
        mAutoHideController.touchAutoHide();
        updateBubblesVisibility();
    }

    @Override
    public void showWirelessChargingAnimation(int batteryLevel) {
        showChargingAnimation(batteryLevel, UNKNOWN_BATTERY_LEVEL, 0);
    }

    protected void showChargingAnimation(int batteryLevel, int transmittingBatteryLevel,
            long animationDelay) {
        WirelessChargingAnimation.makeWirelessChargingAnimation(mContext, null,
                transmittingBatteryLevel, batteryLevel,
                new WirelessChargingAnimation.Callback() {
                    @Override
                    public void onAnimationStarting() {
                        mNotificationShadeWindowController.setRequestTopUi(true, TAG);
                    }

                    @Override
                    public void onAnimationEnded() {
                        mNotificationShadeWindowController.setRequestTopUi(false, TAG);
                    }
                }, /* isDozing= */ false, RippleShape.CIRCLE,
                sUiEventLogger).show(animationDelay);
    }

    @Override
    public void checkBarModes() {
        if (mDemoModeController.isInDemoMode()) return;
        if (mStatusBarTransitions != null) {
            checkBarMode(
                    mStatusBarModeRepository.getDefaultDisplay().getStatusBarMode().getValue(),
                    mStatusBarWindowState,
                    mStatusBarTransitions);
        }
        mNavigationBarController.checkNavBarModes(mDisplayId);
        mNoAnimationOnNextBarModeChange = false;
    }

    /** Temporarily hides Bubbles if the status bar is hidden. */
    @Override
    public void updateBubblesVisibility() {
        StatusBarMode mode =
                mStatusBarModeRepository.getDefaultDisplay().getStatusBarMode().getValue();
        mBubblesOptional.ifPresent(bubbles -> bubbles.onStatusBarVisibilityChanged(
                mode != StatusBarMode.LIGHTS_OUT
                        && mode != StatusBarMode.LIGHTS_OUT_TRANSPARENT
                        && mStatusBarWindowState != WINDOW_STATE_HIDDEN));
    }

    void checkBarMode(
            StatusBarMode mode,
            @WindowVisibleState int windowState,
            BarTransitions transitions) {
        final boolean anim = !mNoAnimationOnNextBarModeChange && mDeviceInteractive
                && windowState != WINDOW_STATE_HIDDEN;
        transitions.transitionTo(mode.toTransitionModeInt(), anim);
    }

    private void finishBarAnimations() {
        if (mStatusBarTransitions != null) {
            mStatusBarTransitions.finishAnimations();
        }
        mNavigationBarController.finishBarAnimations(mDisplayId);
    }

    private final Runnable mCheckBarModes = this::checkBarModes;

    @Override
    public void setInteracting(int barWindow, boolean interacting) {
        mInteractingWindows = interacting
                ? (mInteractingWindows | barWindow)
                : (mInteractingWindows & ~barWindow);
        if (mInteractingWindows != 0) {
            mAutoHideController.suspendAutoHide();
        } else {
            mAutoHideController.resumeSuspendedAutoHide();
        }
        checkBarModes();
    }

    private void dismissVolumeDialog() {
        if (mVolumeComponent != null) {
            mVolumeComponent.dismissNow();
        }
    }

    @NeverCompile
    @Override
    public void dump(PrintWriter pwOriginal, String[] args) {
        IndentingPrintWriter pw = DumpUtilsKt.asIndenting(pwOriginal);
        synchronized (mQueueLock) {
            pw.println("Current Status Bar state:");
            pw.println("  mExpandedVisible=" + mShadeController.isExpandedVisible());
            pw.println("  mDisplayMetrics=" + mDisplayMetrics);
            pw.print("  mStackScroller: " + CentralSurfaces.viewInfo(mStackScroller));
            pw.print(" scroll " + mStackScroller.getScrollX()
                    + "," + mStackScroller.getScrollY());
            pw.println(" translationX " + mStackScroller.getTranslationX());
        }

        pw.print("  mInteractingWindows="); pw.println(mInteractingWindows);
        pw.print("  mStatusBarWindowState=");
        pw.println(windowStateToString(mStatusBarWindowState));
        pw.print("  mDozing="); pw.println(mDozing);
        pw.print("  mWallpaperSupported= "); pw.println(mWallpaperSupported);

        CentralSurfaces.dumpBarTransitions(
                pw, "PhoneStatusBarTransitions", mStatusBarTransitions);

        pw.println("  mMediaManager: ");
        if (mMediaManager != null) {
            mMediaManager.dump(pw, args);
        }

        pw.println("  Panels: ");
        pw.println("  mStackScroller: " + mStackScroller + " (dump moved)");
        pw.println("  Theme:");
        String nightMode = mUiModeManager == null ? "null" : mUiModeManager.getNightMode() + "";
        pw.println("    dark theme: " + nightMode +
                " (auto: " + UiModeManager.MODE_NIGHT_AUTO +
                ", yes: " + UiModeManager.MODE_NIGHT_YES +
                ", no: " + UiModeManager.MODE_NIGHT_NO + ")");
        final boolean lightWpTheme = mContext.getThemeResId()
                == R.style.Theme_SystemUI_LightWallpaper;
        pw.println("    light wallpaper theme: " + lightWpTheme);

        if (mKeyguardIndicationController != null) {
            mKeyguardIndicationController.dump(pw, args);
        }

        if (mScrimController != null) {
            mScrimController.dump(pw, args);
        }

        if (mLightRevealScrim != null) {
            pw.println(
                    "mLightRevealScrim.getRevealEffect(): " + mLightRevealScrim.getRevealEffect());
            pw.println(
                    "mLightRevealScrim.getRevealAmount(): " + mLightRevealScrim.getRevealAmount());
        }

        if (mStatusBarKeyguardViewManager != null) {
            mStatusBarKeyguardViewManager.dump(pw);
        }

        if (DEBUG_GESTURES) {
            pw.print("  status bar gestures: ");
            mGestureRec.dump(pw, args);
        }

        if (mHeadsUpManager != null) {
            mHeadsUpManager.dump(pw, args);
        } else {
            pw.println("  mHeadsUpManager: null");
        }

        if (mStatusBarTouchableRegionManager != null) {
            mStatusBarTouchableRegionManager.dump(pw, args);
        } else {
            pw.println("  mStatusBarTouchableRegionManager: null");
        }

        if (mLightBarController != null) {
            mLightBarController.dump(pw, args);
        }

        pw.println("SharedPreferences:");
        for (Map.Entry<String, ?> entry : Prefs.getAll(mContext).entrySet()) {
            pw.print("  "); pw.print(entry.getKey()); pw.print("="); pw.println(entry.getValue());
        }

        pw.println("Camera gesture intents:");
        pw.println("   Insecure camera: " + CameraIntents.getInsecureCameraIntent(mContext, mUserTracker.getUserId()));
        pw.println("   Secure camera: " + CameraIntents.getSecureCameraIntent(mContext, mUserTracker.getUserId()));
        pw.println("   Override package: "
                + CameraIntents.getOverrideCameraPackage(mContext, mUserTracker.getUserId()));
    }

    private void createAndAddWindows(@Nullable RegisterStatusBarResult result) {
        makeStatusBarView(result);
        mNotificationShadeWindowController.attach();
        mStatusBarWindowController.attach();
    }

    // called by makeStatusbar and also by PhoneStatusBarView
    void updateDisplaySize() {
        mDisplay.getMetrics(mDisplayMetrics);
        mDisplay.getSize(mCurrentDisplaySize);
        if (DEBUG_GESTURES) {
            mGestureRec.tag("display",
                    String.format("%dx%d", mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels));
        }
    }

    @Override
    @Deprecated
    public float getDisplayDensity() {
        return mDisplayMetrics.density;
    }

    @Override
    @Deprecated
    public float getDisplayWidth() {
        return mDisplayMetrics.widthPixels;
    }

    @Override
    @Deprecated
    public float getDisplayHeight() {
        return mDisplayMetrics.heightPixels;
    }

    @Override
    public int getRotation() {
        return mDisplay.getRotation();
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Trace.beginSection("CentralSurfaces#onReceive");
            if (DEBUG) Log.v(TAG, "onReceive: " + intent);
            String action = intent.getAction();
            String reason = intent.getStringExtra(SYSTEM_DIALOG_REASON_KEY);
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                if (mIsShortcutListSearchEnabled && Utilities.isLargeScreen(mContext)) {
                    KeyboardShortcutListSearch.dismiss();
                } else {
                    KeyboardShortcuts.dismiss();
                }
                mRemoteInputManager.closeRemoteInputs();
                if (mLockscreenUserManager.isCurrentProfile(getSendingUserId())) {
                    mShadeLogger.d("ACTION_CLOSE_SYSTEM_DIALOGS intent: closing shade");
                    int flags = CommandQueue.FLAG_EXCLUDE_NONE;
                    if (reason != null) {
                        if (reason.equals(SYSTEM_DIALOG_REASON_RECENT_APPS)) {
                            flags |= CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL;
                        }
                        // Do not collapse notifications when starting dreaming if the notifications
                        // shade is used for the screen off animation. It might require expanded
                        // state for the scrims to be visible
                        if (reason.equals(SYSTEM_DIALOG_REASON_DREAM)
                                && mScreenOffAnimationController.shouldExpandNotifications()) {
                            flags |= CommandQueue.FLAG_EXCLUDE_NOTIFICATION_PANEL;
                        }
                    }
                    mShadeController.animateCollapseShade(flags);
                } else {
                    mShadeLogger.d("ACTION_CLOSE_SYSTEM_DIALOGS intent: non-matching user ID");
                }
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (mNotificationShadeWindowController != null) {
                    mNotificationShadeWindowController.setNotTouchable(false);
                }
                finishBarAnimations();
                mNotificationsController.resetUserExpandedStates();
            }
            Trace.endSection();
        }
    };

    /**
     * Reload some of our resources when the configuration changes.
     *
     * We don't reload everything when the configuration changes -- we probably
     * should, but getting that smooth is tough.  Someday we'll fix that.  In the
     * meantime, just update the things that we know change.
     */
    void updateResources() {
        // Update the quick setting tiles
        if (mQSPanelController != null) {
            mQSPanelController.updateResources();
        }

        if (!truncatedStatusBarIconsFix()) {
            if (mStatusBarWindowController != null) {
                mStatusBarWindowController.refreshStatusBarHeight();
            }
        }
        if (mShadeSurface != null) {
            mShadeSurface.updateResources();
        }
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.updateResources();
        }
        if (mStatusBarKeyguardViewManager != null) {
            mStatusBarKeyguardViewManager.updateResources();
        }

        mPowerButtonReveal = new PowerButtonReveal(mContext.getResources().getDimensionPixelSize(
                com.android.systemui.res.R.dimen.physical_power_button_center_screen_location_y));
    }

    private void logStateToEventlog() {
        boolean isShowing = mKeyguardStateController.isShowing();
        boolean isOccluded = mKeyguardStateController.isOccluded();
        boolean isBouncerShowing = mStatusBarKeyguardViewManager.isBouncerShowing();
        boolean isSecure = mKeyguardStateController.isMethodSecure();
        boolean unlocked = mKeyguardStateController.canDismissLockScreen();
        int stateFingerprint = getLoggingFingerprint(mState,
                isShowing,
                isOccluded,
                isBouncerShowing,
                isSecure,
                unlocked);
        if (stateFingerprint != mLastLoggedStateFingerprint) {
            if (mStatusBarStateLog == null) {
                mStatusBarStateLog = new LogMaker(MetricsEvent.VIEW_UNKNOWN);
            }
            mMetricsLogger.write(mStatusBarStateLog
                    .setCategory(isBouncerShowing ? MetricsEvent.BOUNCER : MetricsEvent.LOCKSCREEN)
                    .setType(isShowing ? MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE)
                    .setSubtype(isSecure ? 1 : 0));
            EventLogTags.writeSysuiStatusBarState(mState,
                    isShowing ? 1 : 0,
                    isOccluded ? 1 : 0,
                    isBouncerShowing ? 1 : 0,
                    isSecure ? 1 : 0,
                    unlocked ? 1 : 0);
            mLastLoggedStateFingerprint = stateFingerprint;

            StringBuilder uiEventValueBuilder = new StringBuilder();
            uiEventValueBuilder.append(isBouncerShowing ? "BOUNCER" : "LOCKSCREEN");
            uiEventValueBuilder.append(isShowing ? "_OPEN" : "_CLOSE");
            uiEventValueBuilder.append(isSecure ? "_SECURE" : "_INSECURE");
            sUiEventLogger.log(StatusBarUiEvent.valueOf(uiEventValueBuilder.toString()));
        }
    }

    /**
     * Returns a fingerprint of fields logged to eventlog
     */
    private static int getLoggingFingerprint(int statusBarState, boolean keyguardShowing,
            boolean keyguardOccluded, boolean bouncerShowing, boolean secure,
            boolean currentlyInsecure) {
        // Reserve 8 bits for statusBarState. We'll never go higher than
        // that, right? Riiiight.
        return (statusBarState & 0xFF)
                | ((keyguardShowing   ? 1 : 0) <<  8)
                | ((keyguardOccluded  ? 1 : 0) <<  9)
                | ((bouncerShowing    ? 1 : 0) << 10)
                | ((secure            ? 1 : 0) << 11)
                | ((currentlyInsecure ? 1 : 0) << 12);
    }

    @Override
    public void showKeyguard() {
        mStatusBarStateController.setKeyguardRequested(true);
        mStatusBarStateController.setLeaveOpenOnKeyguardHide(false);
        updateIsKeyguard();
        mAssistManagerLazy.get().onLockscreenShown();
    }

    @Override
    public boolean hideKeyguard() {
        mStatusBarStateController.setKeyguardRequested(false);
        return updateIsKeyguard();
    }

    @Override
    public boolean updateIsKeyguard() {
        return updateIsKeyguard(false /* forceStateChange */);
    }

    @Override
    public boolean updateIsKeyguard(boolean forceStateChange) {
        boolean wakeAndUnlocking = mBiometricUnlockController.isWakeAndUnlock();

        // For dozing, keyguard needs to be shown whenever the device is non-interactive. Otherwise
        // there's no surface we can show to the user. Note that the device goes fully interactive
        // late in the transition, so we also allow the device to start dozing once the screen has
        // turned off fully.
        boolean keyguardShowingUnOccluded =
                mKeyguardStateController.isShowing() && !mKeyguardStateController.isOccluded();
        boolean keyguardForDozing = mDozeServiceHost.getDozingRequested()
                && (!mDeviceInteractive || (isGoingToSleep()
                && (isScreenFullyOff() || keyguardShowingUnOccluded)));
        boolean isWakingAndOccluded = mKeyguardStateController.isOccluded() && isWakingOrAwake();
        boolean shouldBeKeyguard = (mStatusBarStateController.isKeyguardRequested()
                || keyguardForDozing) && !wakeAndUnlocking && !isWakingAndOccluded;
        if (keyguardForDozing) {
            updatePanelExpansionForKeyguard();
        }
        if (shouldBeKeyguard) {
            if (mScreenOffAnimationController.isKeyguardShowDelayed()
                    || (isGoingToSleep()
                    && mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_TURNING_OFF)) {
                // Delay showing the keyguard until screen turned off.
            } else {
                showKeyguardImpl();
            }
        } else {
            // During folding a foldable device this might be called as a result of
            // 'onScreenTurnedOff' call for the inner display.
            // In this case:
            //  * When phone is locked on folding: it doesn't make sense to hide keyguard as it
            //    will be immediately locked again
            //  * When phone is unlocked: we still don't want to execute hiding of the keyguard
            //    as the animation could prepare 'fake AOD' interface (without actually
            //    transitioning to keyguard state) and this might reset the view states
            // Log for b/290627350
            Log.d(TAG, "!shouldBeKeyguard mStatusBarStateController.isKeyguardRequested() "
                    + mStatusBarStateController.isKeyguardRequested() + " keyguardForDozing "
                    + keyguardForDozing + " wakeAndUnlocking " + wakeAndUnlocking
                    + " isWakingAndOccluded " + isWakingAndOccluded);
            if (!mScreenOffAnimationController.isKeyguardHideDelayed()
                    // If we're animating occluded, there's an activity launching over the keyguard
                    // UI. Wait to hide it until after the animation concludes.
                    && !mKeyguardViewMediator.isOccludeAnimationPlaying()) {
                Log.d(TAG, "hideKeyguardImpl " + forceStateChange);
                return hideKeyguardImpl(forceStateChange);
            }
        }
        return false;
    }

    @Override
    public void showKeyguardImpl() {
        Trace.beginSection("CentralSurfaces#showKeyguard");
        if (mKeyguardStateController.isLaunchTransitionFadingAway()) {
            mShadeSurface.cancelAnimation();
            onLaunchTransitionFadingEnded();
        }
        mMessageRouter.cancelMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        if (!mLockscreenShadeTransitionController.isWakingToShadeLocked()) {
            mStatusBarStateController.setState(StatusBarState.KEYGUARD);
        }
        updatePanelExpansionForKeyguard();
        Trace.endSection();
    }

    private void updatePanelExpansionForKeyguard() {
        if (mState == StatusBarState.KEYGUARD && mBiometricUnlockController.getMode()
                != BiometricUnlockController.MODE_WAKE_AND_UNLOCK && !mBouncerShowing) {
            mShadeController.instantExpandShade();
        }
    }

    private void onLaunchTransitionFadingEnded() {
        mShadeSurface.resetAlpha();
        mCameraLauncherLazy.get().setLaunchingAffordance(false);
        releaseGestureWakeLock();
        runLaunchTransitionEndRunnable();
        mKeyguardStateController.setLaunchTransitionFadingAway(false);
    }

    /**
     * Fades the content of the keyguard away after the launch transition is done.
     *
     * @param beforeFading the runnable to be run when the circle is fully expanded and the fading
     *                     starts
     * @param endRunnable the runnable to be run when the transition is done. Will not run
     *                    if the transition is cancelled, instead cancelRunnable will run
     * @param cancelRunnable the runnable to be run if the transition is cancelled
     */
    @Override
    public void fadeKeyguardAfterLaunchTransition(final Runnable beforeFading,
            Runnable endRunnable, Runnable cancelRunnable) {
        mMessageRouter.cancelMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        mLaunchTransitionEndRunnable = endRunnable;
        mLaunchTransitionCancelRunnable = cancelRunnable;
        Runnable hideRunnable = () -> {
            mKeyguardStateController.setLaunchTransitionFadingAway(true);
            if (beforeFading != null) {
                beforeFading.run();
            }
            updateScrimController();
            mShadeSurface.resetAlpha();
            mShadeSurface.fadeOut(
                    FADE_KEYGUARD_START_DELAY, FADE_KEYGUARD_DURATION,
                    this::onLaunchTransitionFadingEnded);
            mCommandQueue.appTransitionStarting(mDisplayId, SystemClock.uptimeMillis(),
                    LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        };
        hideRunnable.run();
    }

    private void cancelAfterLaunchTransitionRunnables() {
        if (mLaunchTransitionCancelRunnable != null) {
            mLaunchTransitionCancelRunnable.run();
        }
        mLaunchTransitionEndRunnable = null;
        mLaunchTransitionCancelRunnable = null;
    }

    /**
     * Starts the timeout when we try to start the affordances on Keyguard. We usually rely that
     * Keyguard goes away via fadeKeyguardAfterLaunchTransition, however, that might not happen
     * because the launched app crashed or something else went wrong.
     */
    @Override
    public void startLaunchTransitionTimeout() {
        mMessageRouter.sendMessageDelayed(
                MSG_LAUNCH_TRANSITION_TIMEOUT, LAUNCH_TRANSITION_TIMEOUT_MS);
    }

    private void onLaunchTransitionTimeout() {
        Log.w(TAG, "Launch transition: Timeout!");
        mCameraLauncherLazy.get().setLaunchingAffordance(false);
        releaseGestureWakeLock();
        mShadeSurface.resetViews(false /* animate */);
    }

    private void runLaunchTransitionEndRunnable() {
        mLaunchTransitionCancelRunnable = null;
        if (mLaunchTransitionEndRunnable != null) {
            Runnable r = mLaunchTransitionEndRunnable;

            // mLaunchTransitionEndRunnable might call showKeyguard, which would execute it again,
            // which would lead to infinite recursion. Protect against it.
            mLaunchTransitionEndRunnable = null;
            r.run();
        }
    }

    /**
     * @return true if we would like to stay in the shade, false if it should go away entirely
     */
    @Override
    public boolean hideKeyguardImpl(boolean forceStateChange) {
        Trace.beginSection("CentralSurfaces#hideKeyguard");
        boolean staying = mStatusBarStateController.leaveOpenOnKeyguardHide();
        int previousState = mStatusBarStateController.getState();
        if (!(mStatusBarStateController.setState(StatusBarState.SHADE, forceStateChange))) {
            //TODO: StatusBarStateController should probably know about hiding the keyguard and
            // notify listeners.

            // If the state didn't change, we may still need to update public mode
            mLockscreenUserManager.updatePublicMode();
        }
        if (mStatusBarStateController.leaveOpenOnKeyguardHide()) {
            if (!mStatusBarStateController.isKeyguardRequested()) {
                mStatusBarStateController.setLeaveOpenOnKeyguardHide(false);
            }
            long delay = mKeyguardStateController.calculateGoingToFullShadeDelay();
            mLockscreenShadeTransitionController.onHideKeyguard(delay, previousState);

            // Disable layout transitions in navbar for this transition because the load is just
            // too heavy for the CPU and GPU on any device.
            mNavigationBarController.disableAnimationsDuringHide(mDisplayId, delay);
        } else if (!mShadeSurface.isCollapsing()) {
            mShadeController.instantCollapseShade();
        }

        // Keyguard state has changed, but QS is not listening anymore. Make sure to update the tile
        // visibilities so next time we open the panel we know the correct height already.
        if (mQSPanelController != null) {
            mQSPanelController.refreshAllTiles();
        }
        mMessageRouter.cancelMessages(MSG_LAUNCH_TRANSITION_TIMEOUT);
        releaseGestureWakeLock();
        mCameraLauncherLazy.get().setLaunchingAffordance(false);
        mShadeSurface.resetAlpha();
        mShadeSurface.resetTranslation();
        mShadeSurface.resetViewGroupFade();
        updateDozingState();
        updateScrimController();
        Trace.endSection();
        return staying;
    }

    private void releaseGestureWakeLock() {
        if (mGestureWakeLock.isHeld()) {
            mGestureWakeLock.release();
        }
    }

    /**
     * Notifies the status bar that Keyguard is going away very soon.
     */
    @Override
    public void keyguardGoingAway() {
        // Treat Keyguard exit animation as an app transition to achieve nice transition for status
        // bar.
        mKeyguardStateController.notifyKeyguardGoingAway(true);
        mCommandQueue.appTransitionPending(mDisplayId, true /* forced */);
        updateScrimController();
    }

    /**
     * Notifies the status bar the Keyguard is fading away with the specified timings.
     * @param startTime the start time of the animations in uptime millis
     * @param delay the precalculated animation delay in milliseconds
     * @param fadeoutDuration the duration of the exit animation, in milliseconds
     */
    @Override
    public void setKeyguardFadingAway(long startTime, long delay, long fadeoutDuration) {
        mCommandQueue.appTransitionStarting(mDisplayId, startTime + fadeoutDuration
                        - LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION,
                LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        mCommandQueue.recomputeDisableFlags(mDisplayId, fadeoutDuration > 0 /* animate */);
        mCommandQueue.appTransitionStarting(mDisplayId,
                    startTime - LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION,
                    LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION, true);
        mKeyguardStateController.notifyKeyguardFadingAway(delay, fadeoutDuration);
    }

    /**
     * Notifies that the Keyguard fading away animation is done.
     */
    @Override
    public void finishKeyguardFadingAway() {
        mKeyguardStateController.notifyKeyguardDoneFading();
        mScrimController.setExpansionAffectsAlpha(true);

        // If the device was re-locked while unlocking, we might have a pending lock that was
        // delayed because the keyguard was in the middle of going away.
        mKeyguardViewMediator.maybeHandlePendingLock();
    }

    /**
     * Switches theme from light to dark and vice-versa.
     */
    protected void updateTheme() {
        // Set additional scrim only if the lock and system wallpaper are different to prevent
        // applying the dimming effect twice.
        mUiBgExecutor.execute(() -> {
            float dimAmount = 0f;
            if (mWallpaperManager.lockScreenWallpaperExists()) {
                dimAmount = mWallpaperManager.getWallpaperDimAmount();
            }
            final float scrimDimAmount = dimAmount;
            mMainExecutor.execute(() -> {
                mScrimController.setAdditionalScrimBehindAlphaKeyguard(scrimDimAmount);
                mScrimController.applyCompositeAlphaOnScrimBehindKeyguard();
            });
        });

        // Lock wallpaper defines the color of the majority of the views, hence we'll use it
        // to set our default theme.
        final boolean lockDarkText = mColorExtractor.getNeutralColors().supportsDarkText();
        final int themeResId = lockDarkText ? R.style.Theme_SystemUI_LightWallpaper
                : R.style.Theme_SystemUI;
        if (mContext.getThemeResId() != themeResId) {
            mContext.setTheme(themeResId);
            mConfigurationController.notifyThemeChanged();
        }
    }

    public boolean shouldDelayWakeUpAnimation() {
        return mShouldDelayWakeUpAnimation;
    }

    private void updateDozingState() {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_APP)) {
            Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, "Dozing", 0);
            Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_APP, "Dozing", String.valueOf(mDozing),
                    0);
        }
        Trace.beginSection("CentralSurfaces#updateDozingState");

        boolean keyguardVisible = mKeyguardStateController.isVisible();
        // If we're dozing and we'll be animating the screen off, the keyguard isn't currently
        // visible but will be shortly for the animation, so we should proceed as if it's visible.
        boolean keyguardVisibleOrWillBe =
                keyguardVisible || (mDozing && mDozeParameters.shouldDelayKeyguardShow());

        boolean animate = (!mDozing && shouldAnimateDozeWakeup())
                || (mDozing && mDozeParameters.shouldControlScreenOff() && keyguardVisibleOrWillBe);

        mShadeSurface.setDozing(mDozing, animate);
        Trace.endSection();
    }

    @Override
    public void userActivity() {
        if (mState == StatusBarState.KEYGUARD) {
            mKeyguardViewMediatorCallback.userActivity();
        }
    }

    @Override
    public void endAffordanceLaunch() {
        releaseGestureWakeLock();
        mCameraLauncherLazy.get().setLaunchingAffordance(false);
    }

    /**
     * Returns whether the keyguard should hide immediately (as opposed to via an animation).
     * Non-scrimmed bouncers have a special animation tied to the notification panel expansion.
     * @return whether the keyguard should be immediately hidden.
     */
    @Override
    public boolean shouldKeyguardHideImmediately() {
        return mScrimController.getState() == ScrimState.BOUNCER_SCRIMMED;
    }

    private void showBouncerOrLockScreenIfKeyguard() {
        // If the keyguard is animating away, we aren't really the keyguard anymore and should not
        // show the bouncer/lockscreen.
        if (!mKeyguardViewMediator.isHiding() && !mKeyguardUpdateMonitor.isKeyguardGoingAway()) {
            if (mState == StatusBarState.SHADE_LOCKED) {
                // shade is showing while locked on the keyguard, so go back to showing the
                // lock screen where users can use the UDFPS affordance to enter the device
                mStatusBarKeyguardViewManager.reset(true);
            } else if (mState == StatusBarState.KEYGUARD
                    && !mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing()
                    && mStatusBarKeyguardViewManager.isSecure()) {
                mStatusBarKeyguardViewManager.showBouncer(true /* scrimmed */);
            }
        }
    }

    /**
     * Show the bouncer if we're currently on the keyguard or shade locked and aren't hiding.
     * @param performAction the action to perform when the bouncer is dismissed.
     * @param cancelAction the action to perform when unlock is aborted.
     */
    @Override
    public void showBouncerWithDimissAndCancelIfKeyguard(OnDismissAction performAction,
            Runnable cancelAction) {
        if ((mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED)
                && !mKeyguardViewMediator.isHiding()) {
            mStatusBarKeyguardViewManager.dismissWithAction(performAction, cancelAction,
                    false /* afterKeyguardGone */);
        } else if (cancelAction != null) {
            cancelAction.run();
        }
    }

    /**
     * Updates the light reveal effect to reflect the reason we're waking or sleeping (for example,
     * from the power button).
     * @param wakingUp Whether we're updating because we're waking up (true) or going to sleep
     *                 (false).
     */
    private void updateRevealEffect(boolean wakingUp) {
        if (mLightRevealScrim == null) {
            return;
        }

        if (lightRevealMigration()) {
            return;
        }

        final boolean wakingUpFromPowerButton = wakingUp
                && !(mLightRevealScrim.getRevealEffect() instanceof CircleReveal)
                && mWakefulnessLifecycle.getLastWakeReason()
                == PowerManager.WAKE_REASON_POWER_BUTTON;
        final boolean sleepingFromPowerButton = !wakingUp
                && mWakefulnessLifecycle.getLastSleepReason()
                == PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON;

        if (wakingUpFromPowerButton || sleepingFromPowerButton) {
            mLightRevealScrim.setRevealEffect(mPowerButtonReveal);
            mLightRevealScrim.setRevealAmount(1f - mStatusBarStateController.getDozeAmount());
        } else if (!wakingUp || !(mLightRevealScrim.getRevealEffect() instanceof CircleReveal)) {
            // If we're going to sleep, but it's not from the power button, use the default reveal.
            // If we're waking up, only use the default reveal if the biometric controller didn't
            // already set it to the circular reveal because we're waking up from a fingerprint/face
            // auth.
            mLightRevealScrim.setRevealEffect(LiftReveal.INSTANCE);
            mLightRevealScrim.setRevealAmount(1f - mStatusBarStateController.getDozeAmount());
        }
    }

    // TODO: Figure out way to remove these.
    @Override
    public NavigationBarView getNavigationBarView() {
        return mNavigationBarController.getNavigationBarView(mDisplayId);
    }

    /**
     * Propagation of the bouncer state, indicating that it's fully visible.
     */
    @Override
    public void setBouncerShowing(boolean bouncerShowing) {
        mBouncerShowing = bouncerShowing;
        mKeyguardBypassController.setBouncerShowing(bouncerShowing);
        mPulseExpansionHandler.setBouncerShowing(bouncerShowing);
        mStackScrollerController.setBouncerShowingFromCentralSurfaces(bouncerShowing);
        setBouncerShowingForStatusBarComponents(bouncerShowing);
        mStatusBarHideIconsForBouncerManager.setBouncerShowingAndTriggerUpdate(bouncerShowing);
        mCommandQueue.recomputeDisableFlags(mDisplayId, true /* animate */);
        if (mBouncerShowing) {
            mPowerInteractor.wakeUpIfDozing("BOUNCER_VISIBLE", PowerManager.WAKE_REASON_GESTURE);
        }
        updateScrimController();
        if (!mBouncerShowing) {
            updatePanelExpansionForKeyguard();
        }
    }

    /**
     * Propagate the bouncer state to status bar components.
     *
     * Separate from {@link #setBouncerShowing} because we sometimes re-create the status bar and
     * should update only the status bar components.
     */
    private void setBouncerShowingForStatusBarComponents(boolean bouncerShowing) {
        int importance = bouncerShowing
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        if (mPhoneStatusBarViewController != null) {
            mPhoneStatusBarViewController.setImportantForAccessibility(importance);
        }
        mShadeSurface.setImportantForAccessibility(importance);
        mShadeSurface.setBouncerShowing(bouncerShowing);
    }

    @VisibleForTesting
    final WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onFinishedGoingToSleep() {
            mCameraLauncherLazy.get().setLaunchingAffordance(false);
            releaseGestureWakeLock();
            mLaunchCameraWhenFinishedWaking = false;
            mDeviceInteractive = false;

            updateNotificationPanelTouchState();
            getNotificationShadeWindowViewController().cancelCurrentTouch();
            if (mLaunchCameraOnFinishedGoingToSleep) {
                mLaunchCameraOnFinishedGoingToSleep = false;

                // This gets executed before we will show Keyguard, so post it in order that the state
                // is correct.
                mMainExecutor.execute(() -> mCommandQueueCallbacks.onCameraLaunchGestureDetected(
                        mLastCameraLaunchSource));
            }

            if (mLaunchEmergencyActionOnFinishedGoingToSleep) {
                mLaunchEmergencyActionOnFinishedGoingToSleep = false;

                // This gets executed before we will show Keyguard, so post it in order that the
                // state is correct.
                mMainExecutor.execute(
                        () -> mCommandQueueCallbacks.onEmergencyActionLaunchGestureDetected());
            }
            updateIsKeyguard();
        }

        @Override
        public void onStartedGoingToSleep() {
            String tag = "CentralSurfaces#onStartedGoingToSleep";
            DejankUtils.startDetectingBlockingIpcs(tag);

            //  cancel stale runnables that could put the device in the wrong state
            cancelAfterLaunchTransitionRunnables();

            updateRevealEffect(false /* wakingUp */);
            updateNotificationPanelTouchState();
            maybeEscalateHeadsUp();
            dismissVolumeDialog();
            mWakeUpCoordinator.setFullyAwake(false);
            mKeyguardBypassController.onStartedGoingToSleep();
            mStatusBarTouchableRegionManager.updateTouchableRegion();

            // The unlocked screen off and fold to aod animations might use our LightRevealScrim -
            // we need to be expanded for it to be visible.
            if (mDozeParameters.shouldShowLightRevealScrim()) {
                mShadeController.makeExpandedVisible(true);
            }

            DejankUtils.stopDetectingBlockingIpcs(tag);
        }

        @Override
        public void onStartedWakingUp() {
            // Between onStartedWakingUp() and onFinishedWakingUp(), the system is changing the
            // display power mode. To avoid jank, animations should NOT run during these power
            // mode transitions, which means that whenever possible, animations should
            // start running during the onFinishedWakingUp() callback instead of this callback.
            String tag = "CentralSurfaces#onStartedWakingUp";
            DejankUtils.startDetectingBlockingIpcs(tag);
            mNotificationShadeWindowController.batchApplyWindowLayoutParams(()-> {
                mDeviceInteractive = true;

                boolean isFlaggedOff = newAodTransition() && KeyguardShadeMigrationNssl.isEnabled();
                if (!isFlaggedOff && shouldAnimateDozeWakeup()) {
                    // If this is false, the power button must be physically pressed in order to
                    // trigger fingerprint authentication.
                    final boolean touchToUnlockAnytime = Settings.Secure.getIntForUser(
                            mContext.getContentResolver(),
                            Settings.Secure.SFPS_PERFORMANT_AUTH_ENABLED,
                            -1,
                            mUserTracker.getUserId()) > 0;

                    // Delay if we're waking up, not mid-doze animation (which means we are
                    // cancelling a sleep), from the power button, on a device with a power button
                    // FPS, and 'press to unlock' is required.
                    mShouldDelayWakeUpAnimation =
                            !mDozeServiceHost.isPulsing()
                                    && mStatusBarStateController.getDozeAmount() == 1f
                                    && mWakefulnessLifecycle.getLastWakeReason()
                                    == PowerManager.WAKE_REASON_POWER_BUTTON
                                    && mFingerprintManager.get() != null
                                    && mFingerprintManager.get().isPowerbuttonFps()
                                    && mKeyguardUpdateMonitor
                                    .isUnlockWithFingerprintPossible(
                                            mUserTracker.getUserId())
                                    && !touchToUnlockAnytime;
                    if (DEBUG_WAKEUP_DELAY) {
                        Log.d(TAG, "mShouldDelayWakeUpAnimation=" + mShouldDelayWakeUpAnimation);
                    }
                } else {
                    // If we're not animating anyway, we do not need to delay it.
                    mShouldDelayWakeUpAnimation = false;
                    if (DEBUG_WAKEUP_DELAY) {
                        Log.d(TAG, "mShouldDelayWakeUpAnimation CLEARED");
                    }
                }

                mShadeSurface.setWillPlayDelayedDozeAmountAnimation(
                        mShouldDelayWakeUpAnimation);
                mWakeUpCoordinator.setWakingUp(
                        /* wakingUp= */ true,
                        mShouldDelayWakeUpAnimation);

                updateIsKeyguard();
                // TODO(b/301913237): can't delay transition if config_displayBlanksAfterDoze=true,
                // otherwise, the clock will flicker during LOCKSCREEN_TRANSITION_FROM_AOD
                mShouldDelayLockscreenTransitionFromAod = mDozeParameters.getAlwaysOn()
                        && !mDozeParameters.getDisplayNeedsBlanking()
                        && mFeatureFlags.isEnabled(
                                Flags.ZJ_285570694_LOCKSCREEN_TRANSITION_FROM_AOD);
                if (!mShouldDelayLockscreenTransitionFromAod) {
                    startLockscreenTransitionFromAod();
                }
            });
            DejankUtils.stopDetectingBlockingIpcs(tag);
        }

        /**
         * Private helper for starting the LOCKSCREEN_TRANSITION_FROM_AOD animation - only necessary
         * so we can start it from either onFinishedWakingUp() or onFinishedWakingUp().
         */
        private void startLockscreenTransitionFromAod() {
            // stopDozing() starts the LOCKSCREEN_TRANSITION_FROM_AOD animation.
            mDozeServiceHost.stopDozing();
            // This is intentionally below the stopDozing call above, since it avoids that we're
            // unnecessarily animating the wakeUp transition. Animations should only be enabled
            // once we fully woke up.
            updateRevealEffect(true /* wakingUp */);
            updateNotificationPanelTouchState();
            mStatusBarTouchableRegionManager.updateTouchableRegion();

            // If we are waking up during the screen off animation, we should undo making the
            // expanded visible (we did that so the LightRevealScrim would be visible).
            if (mScreenOffAnimationController.shouldHideLightRevealScrimOnWakeUp()) {
                mShadeController.makeExpandedInvisible();
            }
        }

        @Override
        public void onFinishedWakingUp() {
            if (mShouldDelayLockscreenTransitionFromAod) {
                mNotificationShadeWindowController.batchApplyWindowLayoutParams(
                        this::startLockscreenTransitionFromAod);
            }
            mWakeUpCoordinator.setFullyAwake(true);
            mWakeUpCoordinator.setWakingUp(false, false);
            if (mKeyguardStateController.isOccluded()
                    && !mDozeParameters.canControlUnlockedScreenOff()) {
                // When the keyguard is occluded we don't use the KEYGUARD state which would
                // normally cause these redaction updates.  If AOD is on, the KEYGUARD state is used
                // to show the doze, AND UnlockedScreenOffAnimationController.onFinishedWakingUp()
                // would force a KEYGUARD state that would take care of recalculating redaction.
                // So if AOD is off or unsupported we need to trigger these updates at screen on
                // when the keyguard is occluded.
                mLockscreenUserManager.updatePublicMode();
                mStackScrollerController.updateSensitivenessForOccludedWakeup();
            }
            if (mLaunchCameraWhenFinishedWaking) {
                mCameraLauncherLazy.get().launchCamera(mLastCameraLaunchSource,
                        mShadeSurface.isFullyCollapsed());
                mLaunchCameraWhenFinishedWaking = false;
            }
            if (mLaunchEmergencyActionWhenFinishedWaking) {
                mLaunchEmergencyActionWhenFinishedWaking = false;
                Intent emergencyIntent = getEmergencyActionIntent();
                if (emergencyIntent != null) {
                    mContext.startActivityAsUser(emergencyIntent,
                            getActivityUserHandle(emergencyIntent));
                }
            }
            updateScrimController();
        }
    };

    /**
     * We need to disable touch events because these might
     * collapse the panel after we expanded it, and thus we would end up with a blank
     * Keyguard.
     */
    @Override
    public void updateNotificationPanelTouchState() {
        boolean goingToSleepWithoutAnimation = isGoingToSleep()
                && !mDozeParameters.shouldControlScreenOff();
        boolean disabled = (!mDeviceInteractive && !mDozeServiceHost.isPulsing())
                || goingToSleepWithoutAnimation
                || mDeviceProvisionedController.isFrpActive();
        mShadeLogger.logUpdateNotificationPanelTouchState(disabled, isGoingToSleep(),
                !mDozeParameters.shouldControlScreenOff(), !mDeviceInteractive,
                !mDozeServiceHost.isPulsing(), mDeviceProvisionedController.isFrpActive());

        mShadeSurface.setTouchAndAnimationDisabled(disabled);
        if (!NotificationIconContainerRefactor.isEnabled()) {
            mNotificationIconAreaController.setAnimationsEnabled(!disabled);
        }
    }

    final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurningOn() {
            mFalsingCollector.onScreenTurningOn();
            mShadeSurface.onScreenTurningOn();
        }

        @Override
        public void onScreenTurnedOn() {
            mScrimController.onScreenTurnedOn();
        }

        @Override
        public void onScreenTurnedOff() {
            Trace.beginSection("CentralSurfaces#onScreenTurnedOff");
            mFalsingCollector.onScreenOff();
            mScrimController.onScreenTurnedOff();
            if (mCloseQsBeforeScreenOff) {
                mQsController.closeQs();
                mCloseQsBeforeScreenOff = false;
            }
            updateIsKeyguard();
            Trace.endSection();
        }
    };

    /**
     * @return true if the screen is currently fully off, i.e. has finished turning off and has
     * since not started turning on.
     */
    @Override
    public boolean isScreenFullyOff() {
        return mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_OFF;
    }

    @Nullable
    @Override
    public Intent getEmergencyActionIntent() {
        Intent emergencyIntent = new Intent(EmergencyGesture.ACTION_LAUNCH_EMERGENCY);
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> emergencyActivities = pm.queryIntentActivities(emergencyIntent,
                PackageManager.MATCH_SYSTEM_ONLY);
        ResolveInfo resolveInfo = getTopEmergencySosInfo(emergencyActivities);
        if (resolveInfo == null) {
            Log.wtf(TAG, "Couldn't find an app to process the emergency intent.");
            return null;
        }
        emergencyIntent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName,
                resolveInfo.activityInfo.name));
        emergencyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return emergencyIntent;
    }

    /**
     * Select and return the "best" ResolveInfo for Emergency SOS Activity.
     */
    private @Nullable ResolveInfo getTopEmergencySosInfo(List<ResolveInfo> emergencyActivities) {
        // No matched activity.
        if (emergencyActivities == null || emergencyActivities.isEmpty()) {
            return null;
        }

        // Of multiple matched Activities, give preference to the pre-set package name.
        String preferredAppPackageName =
                mContext.getString(R.string.config_preferredEmergencySosPackage);

        // If there is no preferred app, then return first match.
        if (TextUtils.isEmpty(preferredAppPackageName)) {
            return emergencyActivities.get(0);
        }

        for (ResolveInfo emergencyInfo: emergencyActivities) {
            // If activity is from the preferred app, use it.
            if (TextUtils.equals(emergencyInfo.activityInfo.packageName, preferredAppPackageName)) {
                return emergencyInfo;
            }
        }
        // No matching activity: return first match
        return emergencyActivities.get(0);
    }

    @Override
    public boolean isCameraAllowedByAdmin() {
        if (mDevicePolicyManager.getCameraDisabled(null,
                mLockscreenUserManager.getCurrentUserId())) {
            return false;
        } else if (mKeyguardStateController.isShowing()
                && mStatusBarKeyguardViewManager.isSecure()) {
            // Check if the admin has disabled the camera specifically for the keyguard
            return (mDevicePolicyManager.getKeyguardDisabledFeatures(null,
                    mLockscreenUserManager.getCurrentUserId())
                    & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) == 0;
        }
        return true;
    }

    @Override
    public boolean isGoingToSleep() {
        return mWakefulnessLifecycle.getWakefulness()
                == WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP;
    }

    boolean isWakingOrAwake() {
        return mWakefulnessLifecycle.getWakefulness() == WakefulnessLifecycle.WAKEFULNESS_WAKING
                || mWakefulnessLifecycle.getWakefulness() == WakefulnessLifecycle.WAKEFULNESS_AWAKE;
    }

    @Override
    public void notifyBiometricAuthModeChanged() {
        mDozeServiceHost.updateDozing();
        if (mBiometricUnlockController.getMode()
                == BiometricUnlockController.MODE_DISMISS_BOUNCER) {
            // Don't update the scrim controller at this time, in favor of the transition repository
            // updating the scrim
            return;
        }
        updateScrimController();
    }

    /**
     * Set the amount of progress we are currently in if we're transitioning to the full shade.
     * 0.0f means we're not transitioning yet, while 1 means we're all the way in the full
     * shade.
     */
    @Override
    public void setTransitionToFullShadeProgress(float transitionToFullShadeProgress) {
        mTransitionToFullShadeProgress = transitionToFullShadeProgress;
    }

    /**
     * Sets the amount of progress to the bouncer being fully hidden/visible. 1 means the bouncer
     * is fully hidden, while 0 means the bouncer is visible.
     */
    @Override
    public void setPrimaryBouncerHiddenFraction(float expansion) {
        mScrimController.setBouncerHiddenFraction(expansion);
    }

    @Override
    @VisibleForTesting
    public void updateScrimController() {
        Trace.beginSection("CentralSurfaces#updateScrimController");

        boolean unlocking = mKeyguardStateController.isShowing() && (
                mBiometricUnlockController.isWakeAndUnlock()
                        || mKeyguardStateController.isKeyguardFadingAway()
                        || mKeyguardStateController.isKeyguardGoingAway()
                        || mKeyguardViewMediator.requestedShowSurfaceBehindKeyguard()
                        || mKeyguardViewMediator.isAnimatingBetweenKeyguardAndSurfaceBehind());

        mScrimController.setExpansionAffectsAlpha(!unlocking);

        if (mAlternateBouncerInteractor.isVisibleState()) {
            if (!DeviceEntryUdfpsRefactor.isEnabled()) {
                if ((!mKeyguardStateController.isOccluded() || mShadeSurface.isPanelExpanded())
                        && (mState == StatusBarState.SHADE || mState == StatusBarState.SHADE_LOCKED
                        || mTransitionToFullShadeProgress > 0f)) {
                    mScrimController.transitionTo(ScrimState.AUTH_SCRIMMED_SHADE);
                } else {
                    mScrimController.transitionTo(ScrimState.AUTH_SCRIMMED);
                }
            }

            // This will cancel the keyguardFadingAway animation if it is running. We need to do
            // this as otherwise it can remain pending and leave keyguard in a weird state.
            mUnlockScrimCallback.onCancelled();
        } else if (mBouncerShowing && !unlocking) {
            // Bouncer needs the front scrim when it's on top of an activity,
            // tapping on a notification, editing QS or being dismissed by
            // FLAG_DISMISS_KEYGUARD_ACTIVITY.
            ScrimState state = mStatusBarKeyguardViewManager.primaryBouncerNeedsScrimming()
                    ? ScrimState.BOUNCER_SCRIMMED : ScrimState.BOUNCER;
            mScrimController.transitionTo(state);
        } else if (mBrightnessMirrorVisible) {
            mScrimController.transitionTo(ScrimState.BRIGHTNESS_MIRROR);
        } else if (mState == StatusBarState.SHADE_LOCKED) {
            mScrimController.transitionTo(ScrimState.SHADE_LOCKED);
        } else if (mDozeServiceHost.isPulsing()) {
            mScrimController.transitionTo(ScrimState.PULSING,
                    mDozeScrimController.getScrimCallback());
        } else if (mDozeServiceHost.hasPendingScreenOffCallback()) {
            mScrimController.transitionTo(ScrimState.OFF, new ScrimController.Callback() {
                @Override
                public void onFinished() {
                    mDozeServiceHost.executePendingScreenOffCallback();
                }
            });
        } else if (mDozing && !unlocking) {
            mScrimController.transitionTo(ScrimState.AOD);
            // This will cancel the keyguardFadingAway animation if it is running. We need to do
            // this as otherwise it can remain pending and leave keyguard in a weird state.
            mUnlockScrimCallback.onCancelled();
        } else if (mIsIdleOnCommunal) {
            mScrimController.transitionTo(ScrimState.GLANCEABLE_HUB);
        } else if (mKeyguardStateController.isShowing()
                && !mKeyguardStateController.isOccluded()
                && !unlocking) {
            mScrimController.transitionTo(ScrimState.KEYGUARD);
        } else if (mKeyguardStateController.isShowing() && mKeyguardUpdateMonitor.isDreaming()
                && !unlocking) {
            mScrimController.transitionTo(ScrimState.DREAMING);
        } else {
            mScrimController.transitionTo(ScrimState.UNLOCKED, mUnlockScrimCallback);
        }
        updateLightRevealScrimVisibility();

        Trace.endSection();
    }
    @Override
    public boolean shouldIgnoreTouch() {
        return (mStatusBarStateController.isDozing()
                && mDozeServiceHost.getIgnoreTouchWhilePulsing())
                || mScreenOffAnimationController.shouldIgnoreKeyguardTouches();
    }

    // Begin Extra BaseStatusBar methods.

    protected final CommandQueue mCommandQueue;
    protected IStatusBarService mBarService;

    // all notifications
    private final NotificationStackScrollLayout mStackScroller;

    protected AccessibilityManager mAccessibilityManager;

    protected boolean mDeviceInteractive;

    protected DevicePolicyManager mDevicePolicyManager;
    private final PowerManager mPowerManager;
    protected StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    protected KeyguardManager mKeyguardManager;
    private final DeviceProvisionedController mDeviceProvisionedController;

    private final NavigationBarController mNavigationBarController;
    private final AccessibilityFloatingMenuController mAccessibilityFloatingMenuController;

    // UI-specific methods

    protected WindowManager mWindowManager;
    protected IWindowManager mWindowManagerService;
    private final IDreamManager mDreamManager;

    protected Display mDisplay;
    private int mDisplayId;

    private final Lazy<AssistManager> mAssistManagerLazy;

    @Override
    public boolean isDeviceInteractive() {
        return mDeviceInteractive;
    }

    private final BroadcastReceiver mBannerActionBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BANNER_ACTION_CANCEL.equals(action) || BANNER_ACTION_SETUP.equals(action)) {
                NotificationManager noMan = (NotificationManager)
                        mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                noMan.cancel(com.android.internal.messages.nano.SystemMessageProto.SystemMessage.
                        NOTE_HIDDEN_NOTIFICATIONS);

                Settings.Secure.putInt(mContext.getContentResolver(),
                        Settings.Secure.SHOW_NOTE_ABOUT_NOTIFICATION_HIDING, 0);
                if (BANNER_ACTION_SETUP.equals(action)) {
                    mShadeController.animateCollapseShadeForced();
                    mContext.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_REDACTION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    );
                }
            }
        }
    };

    @Override
    public void handleDreamTouch(MotionEvent event) {
        getNotificationShadeWindowViewController().handleDreamTouch(event);
    }

    @Override
    public void awakenDreams() {
        mUiBgExecutor.execute(() -> {
            try {
                mDreamManager.awaken();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        });
    }

    protected void toggleKeyboardShortcuts(int deviceId) {
        if (mIsShortcutListSearchEnabled && Utilities.isLargeScreen(mContext)) {
            KeyboardShortcutListSearch.toggle(mContext, deviceId);
        } else {
            KeyboardShortcuts.toggle(mContext, deviceId);
        }
    }

    protected void dismissKeyboardShortcuts() {
        if (mIsShortcutListSearchEnabled && Utilities.isLargeScreen(mContext)) {
            KeyboardShortcutListSearch.dismiss();
        } else {
            KeyboardShortcuts.dismiss();
        }
    }

    private void clearNotificationEffects() {
        try {
            mBarService.clearNotificationEffects();
        } catch (RemoteException e) {
            // Won't fail unless the world has ended.
        }
    }

    /**
     * @return Whether the security bouncer from Keyguard is showing.
     */
    @Override
    public boolean isBouncerShowing() {
        return mBouncerShowing;
    }

    /**
     * @return Whether the security bouncer from Keyguard is showing.
     */
    @Override
    public boolean isBouncerShowingScrimmed() {
        return isBouncerShowing() && mStatusBarKeyguardViewManager.primaryBouncerNeedsScrimming();
    }

    // End Extra BaseStatusBarMethods.

    boolean isTransientShown() {
        return mStatusBarModeRepository.getDefaultDisplay().isTransientShown().getValue();
    }

    private void updateLightRevealScrimVisibility() {
        if (mLightRevealScrim == null) {
            // status bar may not be inflated yet
            return;
        }

        if (!lightRevealMigration()) {
            mLightRevealScrim.setAlpha(mScrimController.getState().getMaxLightRevealScrimAlpha());
        }
    }

    private final KeyguardUpdateMonitorCallback mUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onDreamingStateChanged(boolean dreaming) {
                    updateScrimController();
                    if (dreaming) {
                        maybeEscalateHeadsUp();
                    }
                }
            };


    private final FalsingManager.FalsingBeliefListener mFalsingBeliefListener =
            new FalsingManager.FalsingBeliefListener() {
                @Override
                public void onFalse() {
                    // Hides quick settings, bouncer, and quick-quick settings.
                    mStatusBarKeyguardViewManager.reset(true);
                }
            };

    // Notifies StatusBarKeyguardViewManager every time the keyguard transition is over,
    // this animation is tied to the scrim for historic reasons.
    // TODO: notify when keyguard has faded away instead of the scrim.
    private final ScrimController.Callback mUnlockScrimCallback = new ScrimController
            .Callback() {
        @Override
        public void onFinished() {
            if (mKeyguardStateController.isKeyguardFadingAway()) {
                mStatusBarKeyguardViewManager.onKeyguardFadedAway();
            }
        }

        @Override
        public void onCancelled() {
            onFinished();
        }
    };

    private final DeviceProvisionedListener mUserSetupObserver = new DeviceProvisionedListener() {
        @Override
        public void onUserSetupChanged() {
            final boolean userSetup = mDeviceProvisionedController.isCurrentUserSetup();
            Log.d(TAG, "mUserSetupObserver - DeviceProvisionedListener called for "
                    + "current user");
            if (MULTIUSER_DEBUG) {
                Log.d(TAG, String.format("User setup changed: userSetup=%s mUserSetup=%s",
                        userSetup, mUserSetup));
            }

            if (userSetup != mUserSetup) {
                mUserSetup = userSetup;
                if (!mUserSetup && mState == StatusBarState.SHADE) {
                    mShadeSurface.collapse(true /* animate */, false  /* delayed */,
                            1.0f  /* speedUpFactor */);
                }
            }
        }
    };

    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onConfigChanged(Configuration newConfig) {
            updateResources();
            updateDisplaySize(); // populates mDisplayMetrics

            if (DEBUG) {
                Log.v(TAG, "configuration changed: " + mContext.getResources().getConfiguration());
            }
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            // TODO: Remove this.
            if (mBrightnessMirrorController != null) {
                mBrightnessMirrorController.onDensityOrFontScaleChanged();
            }
            // TODO: Bring these out of CentralSurfaces.
            mUserInfoControllerImpl.onDensityOrFontScaleChanged();
            if (!NotificationIconContainerRefactor.isEnabled()) {
                mNotificationIconAreaController.onDensityOrFontScaleChanged(mContext);
            }
        }

        @Override
        public void onThemeChanged() {
            if (mBrightnessMirrorController != null) {
                mBrightnessMirrorController.onOverlayChanged();
            }
            // We need the new R.id.keyguard_indication_area before recreating
            // mKeyguardIndicationController
            mShadeSurface.onThemeChanged();

            if (mStatusBarKeyguardViewManager != null) {
                mStatusBarKeyguardViewManager.onThemeChanged();
            }
            if (mAmbientIndicationContainer instanceof AutoReinflateContainer) {
                ((AutoReinflateContainer) mAmbientIndicationContainer).inflateLayout();
            }
            if (!NotificationIconContainerRefactor.isEnabled()) {
                mNotificationIconAreaController.onThemeChanged();
            }
        }

        @Override
        public void onUiModeChanged() {
            if (mBrightnessMirrorController != null) {
                mBrightnessMirrorController.onUiModeChanged();
            }
        }
    };

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStatePreChange(int oldState, int newState) {
                    // If we're visible and switched to SHADE_LOCKED (the user dragged
                    // down on the lockscreen), clear notification LED, vibration,
                    // ringing.
                    // Other transitions are covered in WindowRootViewVisibilityInteractor.
                    if (mWindowRootViewVisibilityInteractor.isLockscreenOrShadeVisible().getValue()
                            && (newState == StatusBarState.SHADE_LOCKED
                            || mStatusBarStateController.goingToFullShade())) {
                        clearNotificationEffects();
                    }
                    if (newState == StatusBarState.KEYGUARD) {
                        mRemoteInputManager.onPanelCollapsed();
                        maybeEscalateHeadsUp();
                    }
                }

                @Override
                public void onStateChanged(int newState) {
                    mState = newState;
                    updateReportRejectedTouchVisibility();
                    mDozeServiceHost.updateDozing();
                    updateTheme();
                    mNavigationBarController.touchAutoDim(mDisplayId);
                    Trace.beginSection("CentralSurfaces#updateKeyguardState");
                    if (mState == StatusBarState.KEYGUARD) {
                        mShadeSurface.cancelPendingCollapse();
                    }
                    updateDozingState();
                    checkBarModes();
                    updateScrimController();
                    Trace.endSection();
                }

                @Override
                public void onDozeAmountChanged(float linear, float eased) {
                    if (!lightRevealMigration()
                            && !(mLightRevealScrim.getRevealEffect() instanceof CircleReveal)) {
                        mLightRevealScrim.setRevealAmount(1f - linear);
                    }
                }

                @Override
                public void onDozingChanged(boolean isDozing) {
                    Trace.beginSection("CentralSurfaces#updateDozing");
                    mDozing = isDozing;

                    boolean dozingAnimated = mDozeServiceHost.getDozingRequested()
                            && mDozeParameters.shouldControlScreenOff();
                    // resetting views is already done when going into doze, there's no need to
                    // reset them again when we're waking up
                    mShadeSurface.resetViews(dozingAnimated && isDozing);

                    mKeyguardViewMediator.setDozing(mDozing);

                    updateDozingState();
                    mDozeServiceHost.updateDozing();
                    updateScrimController();

                    if (mBiometricUnlockController.isWakeAndUnlock()) {
                        // Usually doze changes are to/from lockscreen/AOD, but if we're wake and
                        // unlocking we should hide the keyguard ASAP if necessary.
                        updateIsKeyguard();
                    }

                    updateReportRejectedTouchVisibility();
                    Trace.endSection();
                }
            };

    private final BatteryController.BatteryStateChangeCallback mBatteryStateChangeCallback =
            new BatteryController.BatteryStateChangeCallback() {
                @Override
                public void onPowerSaveChanged(boolean isPowerSave) {
                    mMainExecutor.execute(mCheckBarModes);
                    if (mDozeServiceHost != null) {
                        mDozeServiceHost.firePowerSaveChanged(isPowerSave);
                    }
                }
            };

    private final ActivityTransitionAnimator.Callback mActivityTransitionAnimatorCallback =
            new ActivityTransitionAnimator.Callback() {
                @Override
                public boolean isOnKeyguard() {
                    return mKeyguardStateController.isShowing();
                }

                @Override
                public void hideKeyguardWithAnimation(IRemoteAnimationRunner runner) {
                    // We post to the main thread for 2 reasons:
                    //   1. KeyguardViewMediator is not thread-safe.
                    //   2. To ensure that ViewMediatorCallback#keyguardDonePending is called before
                    //      ViewMediatorCallback#readyForKeyguardDone. The wrong order could occur
                    //      when doing
                    //      dismissKeyguardThenExecute { hideKeyguardWithAnimation(runner) }.
                    mMainExecutor.execute(() -> mKeyguardViewMediator.hideWithAnimation(runner));
                }

                @Override
                public int getBackgroundColor(TaskInfo task) {
                    if (!mStartingSurfaceOptional.isPresent()) {
                        Log.w(TAG, "No starting surface, defaulting to SystemBGColor");
                        return SplashscreenContentDrawer.getSystemBGColor();
                    }

                    return mStartingSurfaceOptional.get().getBackgroundColor(task);
                }
            };

    private final ActivityTransitionAnimator.Listener mActivityTransitionAnimatorListener =
            new ActivityTransitionAnimator.Listener() {
                @Override
                public void onTransitionAnimationStart() {
                    mKeyguardViewMediator.setBlursDisabledForAppLaunch(true);
                }

                @Override
                public void onTransitionAnimationEnd() {
                    mKeyguardViewMediator.setBlursDisabledForAppLaunch(false);
                }
            };

    private final DemoMode mDemoModeCallback = new DemoMode() {
        @Override
        public void onDemoModeFinished() {
            checkBarModes();
        }

        @Override
        public void dispatchDemoCommand(String command, Bundle args) { }
    };

    /**
     *  Determines what UserHandle to use when launching an activity.
     *
     *  We want to ensure that activities that are launched within the systemui process should be
     *  launched as user of the current process.
     * @param intent
     * @return UserHandle
     *
     * Logic is duplicated in {@link ActivityStarterImpl}. Please add it there too.
     */
    private UserHandle getActivityUserHandle(Intent intent) {
        String[] packages = mContext.getResources().getStringArray(R.array.system_ui_packages);
        for (String pkg : packages) {
            if (intent.getComponent() == null) break;
            if (pkg.equals(intent.getComponent().getPackageName())) {
                return new UserHandle(UserHandle.myUserId());
            }
        }
        return mUserTracker.getUserHandle();
    }

    /**
     * Whether we want to animate the wake animation AOD to lockscreen. This is done only if the
     * doze service host says we can, and also we're not wake and unlocking (in which case the
     * AOD instantly hides).
     */
    private boolean shouldAnimateDozeWakeup() {
        return mDozeServiceHost.shouldAnimateWakeup()
                && mBiometricUnlockController.getMode()
                != BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
    }

    @Override
    public void setIsLaunchingActivityOverLockscreen(boolean isLaunchingActivityOverLockscreen) {
        mIsLaunchingActivityOverLockscreen = isLaunchingActivityOverLockscreen;
        mKeyguardViewMediator.launchingActivityOverLockscreen(mIsLaunchingActivityOverLockscreen);
    }

    @Override
    public ActivityTransitionAnimator.Controller getAnimatorControllerFromNotification(
            ExpandableNotificationRow associatedView) {
        return mNotificationAnimationProvider.getAnimatorController(associatedView);
    }
}
