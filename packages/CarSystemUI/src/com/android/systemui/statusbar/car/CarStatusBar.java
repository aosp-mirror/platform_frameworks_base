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

package com.android.systemui.statusbar.car;

import static com.android.systemui.Dependency.TIME_TICK_HANDLER_NAME;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.car.Car;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.notification.CarNotificationListener;
import com.android.car.notification.CarNotificationView;
import com.android.car.notification.CarUxRestrictionManagerWrapper;
import com.android.car.notification.NotificationClickHandlerFactory;
import com.android.car.notification.NotificationDataManager;
import com.android.car.notification.NotificationViewController;
import com.android.car.notification.PreprocessingManager;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.InitController;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarDeviceProvisionedListener;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.classifier.FalsingLog;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.navigationbar.car.CarNavigationBarController;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.qs.car.CarQSFragment;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.ScreenPinningRequest;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.SuperStatusBarViewFactory;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.interruption.BypassHeadsUpNotifier;
import com.android.systemui.statusbar.notification.interruption.NotificationAlertingManager;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.DozeScrimController;
import com.android.systemui.statusbar.phone.DozeServiceHost;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LightsOutNotifController;
import com.android.systemui.statusbar.phone.LockscreenLockIconController;
import com.android.systemui.statusbar.phone.LockscreenWallpaper;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.PhoneStatusBarPolicy;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter;
import com.android.systemui.statusbar.phone.StatusBarTouchableRegionManager;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.volume.VolumeComponent;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Named;
import javax.inject.Provider;

import dagger.Lazy;

/**
 * A status bar tailored for the automotive use case.
 */
public class CarStatusBar extends StatusBar implements CarBatteryController.BatteryViewHandler {
    private static final String TAG = "CarStatusBar";
    // used to calculate how fast to open or close the window
    private static final float DEFAULT_FLING_VELOCITY = 0;
    // max time a fling animation takes
    private static final float FLING_ANIMATION_MAX_TIME = 0.5f;
    // acceleration rate for the fling animation
    private static final float FLING_SPEED_UP_FACTOR = 0.6f;

    private final UserSwitcherController mUserSwitcherController;
    private final ScrimController mScrimController;
    private final LockscreenLockIconController mLockscreenLockIconController;

    private float mOpeningVelocity = DEFAULT_FLING_VELOCITY;
    private float mClosingVelocity = DEFAULT_FLING_VELOCITY;

    private float mBackgroundAlphaDiff;
    private float mInitialBackgroundAlpha;

    private CarBatteryController mCarBatteryController;
    private BatteryMeterView mBatteryMeterView;
    private Drawable mNotificationPanelBackground;

    private final Object mQueueLock = new Object();
    private final CarNavigationBarController mCarNavigationBarController;
    private final FlingAnimationUtils.Builder mFlingAnimationUtilsBuilder;
    private final Lazy<PowerManagerHelper> mPowerManagerHelperLazy;
    private final ShadeController mShadeController;
    private final CarServiceProvider mCarServiceProvider;
    private final NotificationDataManager mNotificationDataManager;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final ScreenLifecycle mScreenLifecycle;
    private final CarNotificationListener mCarNotificationListener;

    private boolean mDeviceIsSetUpForUser = true;
    private boolean mIsUserSetupInProgress = false;
    private PowerManagerHelper mPowerManagerHelper;
    private FlingAnimationUtils mFlingAnimationUtils;
    private NotificationClickHandlerFactory mNotificationClickHandlerFactory;

    // The container for the notifications.
    private CarNotificationView mNotificationView;
    private RecyclerView mNotificationList;
    // The handler bar view at the bottom of notification shade.
    private View mHandleBar;
    // The controller for the notification view.
    private NotificationViewController mNotificationViewController;
    // The state of if the notification list is currently showing the bottom.
    private boolean mNotificationListAtBottom;
    // Was the notification list at the bottom when the user first touched the screen
    private boolean mNotificationListAtBottomAtTimeOfTouch;
    // To be attached to the top navigation bar (i.e. status bar) to pull down the notification
    // panel.
    private View.OnTouchListener mTopNavBarNotificationTouchListener;
    // To be attached to the navigation bars such that they can close the notification panel if
    // it's open.
    private View.OnTouchListener mNavBarNotificationTouchListener;
    // Percentage from top of the screen after which the notification shade will open. This value
    // will be used while opening the notification shade.
    private int mSettleOpenPercentage;
    // Percentage from top of the screen below which the notification shade will close. This
    // value will be used while closing the notification shade.
    private int mSettleClosePercentage;
    // Percentage of notification shade open from top of the screen.
    private int mPercentageFromBottom;
    // If notification shade is animation to close or to open.
    private boolean mIsNotificationAnimating;
    // Tracks when the notification shade is being scrolled. This refers to the glass pane being
    // scrolled not the recycler view.
    private boolean mIsTracking;
    private float mFirstTouchDownOnGlassPane;
    // If the notification card inside the recycler view is being swiped.
    private boolean mIsNotificationCardSwiping;
    // If notification shade is being swiped vertically to close.
    private boolean mIsSwipingVerticallyToClose;

    private final CarUxRestrictionManagerWrapper mCarUxRestrictionManagerWrapper;

    public CarStatusBar(
            Context context,
            NotificationsController notificationsController,
            LightBarController lightBarController,
            AutoHideController autoHideController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            StatusBarIconController statusBarIconController,
            PulseExpansionHandler pulseExpansionHandler,
            NotificationWakeUpCoordinator notificationWakeUpCoordinator,
            KeyguardBypassController keyguardBypassController,
            KeyguardStateController keyguardStateController,
            HeadsUpManagerPhone headsUpManagerPhone,
            DynamicPrivacyController dynamicPrivacyController,
            BypassHeadsUpNotifier bypassHeadsUpNotifier,
            FalsingManager falsingManager,
            BroadcastDispatcher broadcastDispatcher,
            RemoteInputQuickSettingsDisabler remoteInputQuickSettingsDisabler,
            NotificationGutsManager notificationGutsManager,
            NotificationLogger notificationLogger,
            NotificationInterruptStateProvider notificationInterruptStateProvider,
            NotificationViewHierarchyManager notificationViewHierarchyManager,
            KeyguardViewMediator keyguardViewMediator,
            NotificationAlertingManager notificationAlertingManager,
            DisplayMetrics displayMetrics,
            MetricsLogger metricsLogger,
            @UiBackground Executor uiBgExecutor,
            NotificationMediaManager notificationMediaManager,
            NotificationLockscreenUserManager lockScreenUserManager,
            NotificationRemoteInputManager remoteInputManager,
            UserSwitcherController userSwitcherController,
            NetworkController networkController,
            BatteryController batteryController,
            SysuiColorExtractor colorExtractor,
            ScreenLifecycle screenLifecycle,
            WakefulnessLifecycle wakefulnessLifecycle,
            SysuiStatusBarStateController statusBarStateController,
            VibratorHelper vibratorHelper,
            BubbleController bubbleController,
            NotificationGroupManager groupManager,
            VisualStabilityManager visualStabilityManager,
            CarDeviceProvisionedController carDeviceProvisionedController,
            NavigationBarController navigationBarController,
            Lazy<AssistManager> assistManagerLazy,
            ConfigurationController configurationController,
            NotificationShadeWindowController notificationShadeWindowController,
            LockscreenLockIconController lockscreenLockIconController,
            DozeParameters dozeParameters,
            ScrimController scrimController,
            Lazy<LockscreenWallpaper> lockscreenWallpaperLazy,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            DozeServiceHost dozeServiceHost,
            PowerManager powerManager,
            ScreenPinningRequest screenPinningRequest,
            DozeScrimController dozeScrimController,
            VolumeComponent volumeComponent,
            CommandQueue commandQueue,
            Optional<Recents> recents,
            Provider<StatusBarComponent.Builder> statusBarComponentBuilder,
            PluginManager pluginManager,
            Optional<Divider> dividerOptional,
            SuperStatusBarViewFactory superStatusBarViewFactory,
            LightsOutNotifController lightsOutNotifController,
            StatusBarNotificationActivityStarter.Builder
                    statusBarNotificationActivityStarterBuilder,
            ShadeController shadeController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            ViewMediatorCallback viewMediatorCallback,
            InitController initController,
            DarkIconDispatcher darkIconDispatcher,
            @Named(TIME_TICK_HANDLER_NAME) Handler timeTickHandler,
            PluginDependencyProvider pluginDependencyProvider,
            KeyguardDismissUtil keyguardDismissUtil,
            ExtensionController extensionController,
            UserInfoControllerImpl userInfoControllerImpl,
            PhoneStatusBarPolicy phoneStatusBarPolicy,
            KeyguardIndicationController keyguardIndicationController,
            DismissCallbackRegistry dismissCallbackRegistry,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager,
            /* Car Settings injected components. */
            CarServiceProvider carServiceProvider,
            Lazy<PowerManagerHelper> powerManagerHelperLazy,
            CarNavigationBarController carNavigationBarController,
            FlingAnimationUtils.Builder flingAnimationUtilsBuilder,
            NotificationDataManager notificationDataManager,
            CarUxRestrictionManagerWrapper carUxRestrictionManagerWrapper,
            CarNotificationListener carNotificationListener) {
        super(
                context,
                notificationsController,
                lightBarController,
                autoHideController,
                keyguardUpdateMonitor,
                statusBarIconController,
                pulseExpansionHandler,
                notificationWakeUpCoordinator,
                keyguardBypassController,
                keyguardStateController,
                headsUpManagerPhone,
                dynamicPrivacyController,
                bypassHeadsUpNotifier,
                falsingManager,
                broadcastDispatcher,
                remoteInputQuickSettingsDisabler,
                notificationGutsManager,
                notificationLogger,
                notificationInterruptStateProvider,
                notificationViewHierarchyManager,
                keyguardViewMediator,
                notificationAlertingManager,
                displayMetrics,
                metricsLogger,
                uiBgExecutor,
                notificationMediaManager,
                lockScreenUserManager,
                remoteInputManager,
                userSwitcherController,
                networkController,
                batteryController,
                colorExtractor,
                screenLifecycle,
                wakefulnessLifecycle,
                statusBarStateController,
                vibratorHelper,
                bubbleController,
                groupManager,
                visualStabilityManager,
                carDeviceProvisionedController,
                navigationBarController,
                assistManagerLazy,
                configurationController,
                notificationShadeWindowController,
                lockscreenLockIconController,
                dozeParameters,
                scrimController,
                null /* keyguardLiftController */,
                lockscreenWallpaperLazy,
                biometricUnlockControllerLazy,
                dozeServiceHost,
                powerManager,
                screenPinningRequest,
                dozeScrimController,
                volumeComponent,
                commandQueue,
                recents,
                statusBarComponentBuilder,
                pluginManager,
                dividerOptional,
                lightsOutNotifController,
                statusBarNotificationActivityStarterBuilder,
                shadeController,
                superStatusBarViewFactory,
                statusBarKeyguardViewManager,
                viewMediatorCallback,
                initController,
                darkIconDispatcher,
                timeTickHandler,
                pluginDependencyProvider,
                keyguardDismissUtil,
                extensionController,
                userInfoControllerImpl,
                phoneStatusBarPolicy,
                keyguardIndicationController,
                dismissCallbackRegistry,
                statusBarTouchableRegionManager);
        mUserSwitcherController = userSwitcherController;
        mScrimController = scrimController;
        mLockscreenLockIconController = lockscreenLockIconController;
        mCarDeviceProvisionedController = carDeviceProvisionedController;
        mShadeController = shadeController;
        mCarServiceProvider = carServiceProvider;
        mPowerManagerHelperLazy = powerManagerHelperLazy;
        mCarNavigationBarController = carNavigationBarController;
        mFlingAnimationUtilsBuilder = flingAnimationUtilsBuilder;
        mScreenLifecycle = screenLifecycle;
        mNotificationDataManager = notificationDataManager;
        mCarUxRestrictionManagerWrapper = carUxRestrictionManagerWrapper;
        mCarNotificationListener = carNotificationListener;
    }

    @Override
    public void start() {
        mDeviceIsSetUpForUser = mCarDeviceProvisionedController.isCurrentUserSetup();
        mIsUserSetupInProgress = mCarDeviceProvisionedController.isCurrentUserSetupInProgress();

        // Notification bar related setup.
        mInitialBackgroundAlpha = (float) mContext.getResources().getInteger(
                R.integer.config_initialNotificationBackgroundAlpha) / 100;
        if (mInitialBackgroundAlpha < 0 || mInitialBackgroundAlpha > 100) {
            throw new RuntimeException(
                    "Unable to setup notification bar due to incorrect initial background alpha"
                            + " percentage");
        }
        float finalBackgroundAlpha = Math.max(
                mInitialBackgroundAlpha,
                (float) mContext.getResources().getInteger(
                        R.integer.config_finalNotificationBackgroundAlpha) / 100);
        if (finalBackgroundAlpha < 0 || finalBackgroundAlpha > 100) {
            throw new RuntimeException(
                    "Unable to setup notification bar due to incorrect final background alpha"
                            + " percentage");
        }
        mBackgroundAlphaDiff = finalBackgroundAlpha - mInitialBackgroundAlpha;

        super.start();

        mNotificationPanelViewController.setScrollingEnabled(true);
        mSettleOpenPercentage = mContext.getResources().getInteger(
                R.integer.notification_settle_open_percentage);
        mSettleClosePercentage = mContext.getResources().getInteger(
                R.integer.notification_settle_close_percentage);
        mFlingAnimationUtils = mFlingAnimationUtilsBuilder
                .setMaxLengthSeconds(FLING_ANIMATION_MAX_TIME)
                .setSpeedUpFactor(FLING_SPEED_UP_FACTOR)
                .build();

        createBatteryController();
        mCarBatteryController.startListening();

        mPowerManagerHelper = mPowerManagerHelperLazy.get();
        mPowerManagerHelper.setCarPowerStateListener(
                state -> {
                    // When the car powers on, clear all notifications and mute/unread states.
                    Log.d(TAG, "New car power state: " + state);
                    if (state == CarPowerStateListener.ON) {
                        if (mNotificationClickHandlerFactory != null) {
                            mNotificationClickHandlerFactory.clearAllNotifications();
                        }
                        mNotificationDataManager.clearAll();
                    }
                });
        mPowerManagerHelper.connectToCarService();

        mCarDeviceProvisionedController.addCallback(
                new CarDeviceProvisionedListener() {
                    @Override
                    public void onUserSetupInProgressChanged() {
                        mDeviceIsSetUpForUser = mCarDeviceProvisionedController
                                .isCurrentUserSetup();
                        mIsUserSetupInProgress = mCarDeviceProvisionedController
                                .isCurrentUserSetupInProgress();
                    }

                    @Override
                    public void onUserSetupChanged() {
                        mDeviceIsSetUpForUser = mCarDeviceProvisionedController
                                .isCurrentUserSetup();
                        mIsUserSetupInProgress = mCarDeviceProvisionedController
                                .isCurrentUserSetupInProgress();
                    }

                    @Override
                    public void onUserSwitched() {
                        mDeviceIsSetUpForUser = mCarDeviceProvisionedController
                                .isCurrentUserSetup();
                        mIsUserSetupInProgress = mCarDeviceProvisionedController
                                .isCurrentUserSetupInProgress();
                    }
                });

        mNotificationInterruptStateProvider.addSuppressor(new NotificationInterruptSuppressor() {
            @Override
            public String getName() {
                return TAG;
            }

            @Override
            public boolean suppressInterruptions(NotificationEntry entry) {
                // Because space is usually constrained in the auto use-case, there should not be a
                // pinned notification when the shade has been expanded.
                // Ensure this by not allowing any interruptions (ie: pinning any notifications) if
                // the shade is already opened.
                return !getPresenter().isPresenterFullyCollapsed();
            }
        });
    }

    @Override
    public boolean hideKeyguard() {
        boolean result = super.hideKeyguard();
        mCarNavigationBarController.hideAllKeyguardButtons(isDeviceSetupForUser());
        return result;
    }

    @Override
    public void showKeyguard() {
        super.showKeyguard();
        mCarNavigationBarController.showAllKeyguardButtons(isDeviceSetupForUser());
    }

    private boolean isDeviceSetupForUser() {
        return mDeviceIsSetUpForUser && !mIsUserSetupInProgress;
    }

    @Override
    protected void makeStatusBarView(@Nullable RegisterStatusBarResult result) {
        super.makeStatusBarView(result);

        mNotificationPanelBackground = getDefaultWallpaper();
        mScrimController.setScrimBehindDrawable(mNotificationPanelBackground);

        FragmentHostManager manager = FragmentHostManager.get(mPhoneStatusBarWindow);
        manager.addTagListener(CollapsedStatusBarFragment.TAG, (tag, fragment) -> {
            mBatteryMeterView = fragment.getView().findViewById(R.id.battery);

            // By default, the BatteryMeterView should not be visible. It will be toggled
            // when a device has connected by bluetooth.
            mBatteryMeterView.setVisibility(View.GONE);
        });

        connectNotificationsUI();
    }

    /**
     * Attach the notification listeners and controllers to the UI as well as build all the
     * touch listeners needed for opening and closing the notification panel
     */
    private void connectNotificationsUI() {
        // Attached to the top navigation bar (i.e. status bar) to detect pull down of the
        // notification shade.
        GestureDetector openGestureDetector = new GestureDetector(mContext,
                new OpenNotificationGestureListener() {
                    @Override
                    protected void openNotification() {
                        animateExpandNotificationsPanel();
                    }
                });
        // Attached to the notification ui to detect close request of the notification shade.
        GestureDetector closeGestureDetector = new GestureDetector(mContext,
                new CloseNotificationGestureListener() {
                    @Override
                    protected void close() {
                        if (mPanelExpanded) {
                            mShadeController.animateCollapsePanels();
                        }
                    }
                });
        // Attached to the NavBars to close the notification shade
        GestureDetector navBarCloseNotificationGestureDetector = new GestureDetector(mContext,
                new NavBarCloseNotificationGestureListener() {
                    @Override
                    protected void close() {
                        if (mPanelExpanded) {
                            mShadeController.animateCollapsePanels();
                        }
                    }
                });

        // Attached to the Handle bar to close the notification shade
        GestureDetector handleBarCloseNotificationGestureDetector = new GestureDetector(mContext,
                new HandleBarCloseNotificationGestureListener());

        mTopNavBarNotificationTouchListener = (v, event) -> {
            if (!isDeviceSetupForUser()) {
                return true;
            }
            boolean consumed = openGestureDetector.onTouchEvent(event);
            if (consumed) {
                return true;
            }
            maybeCompleteAnimation(event);
            return true;
        };

        mNavBarNotificationTouchListener =
                (v, event) -> {
                    boolean consumed = navBarCloseNotificationGestureDetector.onTouchEvent(event);
                    if (consumed) {
                        return true;
                    }
                    maybeCompleteAnimation(event);
                    return true;
                };

        mNotificationClickHandlerFactory = new NotificationClickHandlerFactory(mBarService);
        mNotificationClickHandlerFactory.registerClickListener((launchResult, alertEntry) -> {
            if (launchResult == ActivityManager.START_TASK_TO_FRONT
                    || launchResult == ActivityManager.START_SUCCESS) {
                mShadeController.animateCollapsePanels();
            }
        });

        mNotificationDataManager.setOnUnseenCountUpdateListener(() -> {
            onUseenCountUpdate(mNotificationDataManager.getUnseenNotificationCount());
        });

        mNotificationClickHandlerFactory.setNotificationDataManager(mNotificationDataManager);

        final View glassPane = mNotificationShadeWindowView.findViewById(R.id.glass_pane);
        mNotificationView = mNotificationShadeWindowView.findViewById(R.id.notification_view);
        mHandleBar = mNotificationShadeWindowView.findViewById(R.id.handle_bar);
        mNotificationView.setClickHandlerFactory(mNotificationClickHandlerFactory);
        mNotificationView.setNotificationDataManager(mNotificationDataManager);

        // The glass pane is used to view touch events before passed to the notification list.
        // This allows us to initialize gesture listeners and detect when to close the notifications
        glassPane.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mFirstTouchDownOnGlassPane = event.getRawX();
                mNotificationListAtBottomAtTimeOfTouch = mNotificationListAtBottom;
                // Reset the tracker when there is a touch down on the glass pane.
                mIsTracking = false;
                // Pass the down event to gesture detector so that it knows where the touch event
                // started.
                closeGestureDetector.onTouchEvent(event);
            }
            return false;
        });

        mHandleBar.setOnTouchListener((v, event) -> {
            handleBarCloseNotificationGestureDetector.onTouchEvent(event);
            maybeCompleteAnimation(event);
            return true;
        });

        mNotificationList = mNotificationView.findViewById(R.id.notifications);
        mNotificationList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (!mNotificationList.canScrollVertically(1)) {
                    mNotificationListAtBottom = true;
                    return;
                }
                mNotificationListAtBottom = false;
                mIsSwipingVerticallyToClose = false;
                mNotificationListAtBottomAtTimeOfTouch = false;
            }
        });
        mNotificationList.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                mIsNotificationCardSwiping = Math.abs(mFirstTouchDownOnGlassPane - event.getRawX())
                        > SWIPE_MAX_OFF_PATH;
                if (mNotificationListAtBottomAtTimeOfTouch && mNotificationListAtBottom) {
                    // We need to save the state here as if notification card is swiping we will
                    // change the mNotificationListAtBottomAtTimeOfTouch. This is to protect
                    // closing the notification shade while the notification card is being swiped.
                    mIsSwipingVerticallyToClose = true;
                }

                // If the card is swiping we should not allow the notification shade to close.
                // Hence setting mNotificationListAtBottomAtTimeOfTouch to false will stop that
                // for us. We are also checking for mIsTracking because while swiping the
                // notification shade to close if the user goes a bit horizontal while swiping
                // upwards then also this should close.
                if (mIsNotificationCardSwiping && !mIsTracking) {
                    mNotificationListAtBottomAtTimeOfTouch = false;
                }

                boolean handled = closeGestureDetector.onTouchEvent(event);
                boolean isTracking = mIsTracking;
                Rect rect = mNotificationView.getClipBounds();
                float clippedHeight = 0;
                if (rect != null) {
                    clippedHeight = rect.bottom;
                }
                if (!handled && event.getActionMasked() == MotionEvent.ACTION_UP
                        && mIsSwipingVerticallyToClose) {
                    if (mSettleClosePercentage < mPercentageFromBottom && isTracking) {
                        animateNotificationPanel(DEFAULT_FLING_VELOCITY, false);
                    } else if (clippedHeight != mNotificationView.getHeight() && isTracking) {
                        // this can be caused when user is at the end of the list and trying to
                        // fling to top of the list by scrolling down.
                        animateNotificationPanel(DEFAULT_FLING_VELOCITY, true);
                    }
                }

                // Updating the mNotificationListAtBottomAtTimeOfTouch state has to be done after
                // the event has been passed to the closeGestureDetector above, such that the
                // closeGestureDetector sees the up event before the state has changed.
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    mNotificationListAtBottomAtTimeOfTouch = false;
                }
                return handled || isTracking;
            }
        });
        mCarServiceProvider.addListener(car -> {
            CarUxRestrictionsManager carUxRestrictionsManager =
                    (CarUxRestrictionsManager)
                            car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
            mCarUxRestrictionManagerWrapper.setCarUxRestrictionsManager(
                    carUxRestrictionsManager);

                    mNotificationViewController = new NotificationViewController(
                            mNotificationView,
                            PreprocessingManager.getInstance(mContext),
                            mCarNotificationListener,
                            mCarUxRestrictionManagerWrapper,
                            mNotificationDataManager);
                    mNotificationViewController.enable();
        });
    }

    /**
     * This method is automatically called whenever there is an update to the number of unseen
     * notifications. This method can be extended by OEMs to customize the desired logic.
     */
    protected void onUseenCountUpdate(int unseenNotificationCount) {
        boolean hasUnseen = unseenNotificationCount > 0;
        mCarNavigationBarController.toggleAllNotificationsUnseenIndicator(isDeviceSetupForUser(),
                hasUnseen);
    }

    /**
     * @return true if the notification panel is currently visible
     */
    boolean isNotificationPanelOpen() {
        return mPanelExpanded;
    }

    @Override
    public void animateExpandNotificationsPanel() {
        if (!mCommandQueue.panelsEnabled() || !mUserSetup) {
            return;
        }
        // scroll to top
        mNotificationList.scrollToPosition(0);
        mNotificationShadeWindowController.setPanelVisible(true);
        mNotificationView.setVisibility(View.VISIBLE);
        animateNotificationPanel(mOpeningVelocity, false);

        setPanelExpanded(true);
    }

    public CarNotificationView getCarNotificationView() {
        return mNotificationView;
    }

    public float getClosingVelocity() {
        return mClosingVelocity;
    }

    public boolean isTracking() {
        return mIsTracking;
    }

    private void maybeCompleteAnimation(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                && mNotificationView.getVisibility() == View.VISIBLE) {
            if (mSettleClosePercentage < mPercentageFromBottom) {
                animateNotificationPanel(DEFAULT_FLING_VELOCITY, false);
            } else {
                animateNotificationPanel(DEFAULT_FLING_VELOCITY, true);
            }
        }
    }

    /**
     * Animates the notification shade from one position to other. This is used to either open or
     * close the notification shade completely with a velocity. If the animation is to close the
     * notification shade this method also makes the view invisible after animation ends.
     */
    void animateNotificationPanel(float velocity, boolean isClosing) {
        float to = 0;
        if (!isClosing) {
            to = mNotificationView.getHeight();
        }

        Rect rect = mNotificationView.getClipBounds();
        if (rect != null && rect.bottom != to) {
            float from = rect.bottom;
            animate(from, to, velocity, isClosing);
            return;
        }

        // We will only be here if the shade is being opened programmatically or via button when
        // height of the layout was not calculated.
        ViewTreeObserver notificationTreeObserver = mNotificationView.getViewTreeObserver();
        notificationTreeObserver.addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        ViewTreeObserver obs = mNotificationView.getViewTreeObserver();
                        obs.removeOnGlobalLayoutListener(this);
                        float to = mNotificationView.getHeight();
                        animate(/* from= */ 0, to, velocity, isClosing);
                    }
                });
    }

    private void animate(float from, float to, float velocity, boolean isClosing) {
        if (mIsNotificationAnimating) {
            return;
        }
        mIsNotificationAnimating = true;
        mIsTracking = true;
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.addUpdateListener(
                animation -> {
                    float animatedValue = (Float) animation.getAnimatedValue();
                    setNotificationViewClipBounds((int) animatedValue);
                });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mIsNotificationAnimating = false;
                mIsTracking = false;
                mOpeningVelocity = DEFAULT_FLING_VELOCITY;
                mClosingVelocity = DEFAULT_FLING_VELOCITY;
                if (isClosing) {
                    mNotificationShadeWindowController.setPanelVisible(false);
                    mNotificationView.setVisibility(View.INVISIBLE);
                    mNotificationView.setClipBounds(null);
                    mNotificationViewController.onVisibilityChanged(false);
                    // let the status bar know that the panel is closed
                    setPanelExpanded(false);
                } else {
                    mNotificationViewController.onVisibilityChanged(true);
                    // let the status bar know that the panel is open
                    mNotificationView.setVisibleNotificationsAsSeen();
                    setPanelExpanded(true);
                }
            }
        });
        mFlingAnimationUtils.apply(animator, from, to, Math.abs(velocity));
        animator.start();
    }

    @Override
    protected QS createDefaultQSFragment() {
        return new CarQSFragment();
    }

    private BatteryController createBatteryController() {
        mCarBatteryController = new CarBatteryController(mContext);
        mCarBatteryController.addBatteryViewHandler(this);
        return mCarBatteryController;
    }

    @Override
    protected void createNavigationBar(@Nullable RegisterStatusBarResult result) {
        registerNavBarListeners();
    }

    private void registerNavBarListeners() {
        // In CarStatusBar, navigation bars are built by NavigationBar.java
        // Instead, we register necessary callbacks to the navigation bar controller.
        mCarNavigationBarController.registerTopBarTouchListener(
                mTopNavBarNotificationTouchListener);

        mCarNavigationBarController.registerBottomBarTouchListener(
                mNavBarNotificationTouchListener);

        mCarNavigationBarController.registerLeftBarTouchListener(
                mNavBarNotificationTouchListener);

        mCarNavigationBarController.registerRightBarTouchListener(
                mNavBarNotificationTouchListener);

        mCarNavigationBarController.registerNotificationController(() -> togglePanel());
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        //When executing dump() function simultaneously, we need to serialize them
        //to get mStackScroller's position correctly.
        synchronized (mQueueLock) {
            pw.println("  mStackScroller: " + viewInfo(mStackScroller));
            pw.println("  mStackScroller: " + viewInfo(mStackScroller)
                    + " scroll " + mStackScroller.getScrollX()
                    + "," + mStackScroller.getScrollY());
        }
        pw.print("  mCarBatteryController=");
        pw.println(mCarBatteryController);
        pw.print("  mBatteryMeterView=");
        pw.println(mBatteryMeterView);

        if (Dependency.get(KeyguardUpdateMonitor.class) != null) {
            Dependency.get(KeyguardUpdateMonitor.class).dump(fd, pw, args);
        }

        FalsingLog.dump(pw);

        pw.println("SharedPreferences:");
        for (Map.Entry<String, ?> entry : Prefs.getAll(mContext).entrySet()) {
            pw.print("  ");
            pw.print(entry.getKey());
            pw.print("=");
            pw.println(entry.getValue());
        }
    }

    @Override
    public void showBatteryView() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "showBatteryView(). mBatteryMeterView: " + mBatteryMeterView);
        }

        if (mBatteryMeterView != null) {
            mBatteryMeterView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void hideBatteryView() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "hideBatteryView(). mBatteryMeterView: " + mBatteryMeterView);
        }

        if (mBatteryMeterView != null) {
            mBatteryMeterView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void createUserSwitcher() {
        if (!mUserSwitcherController.useFullscreenUserSwitcher()) {
            super.createUserSwitcher();
        }
    }

    /**
     * Dismisses the keyguard and shows bouncer if authentication is necessary.
     */
    public void dismissKeyguard() {
        // Don't dismiss keyguard when the screen is off.
        if (mScreenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_OFF) {
            return;
        }
        executeRunnableDismissingKeyguard(null/* runnable */, null /* cancelAction */,
                true /* dismissShade */, true /* afterKeyguardGone */, true /* deferred */);
    }

    /**
     * Ensures that relevant child views are appropriately recreated when the device's density
     * changes.
     */
    @Override
    public void onDensityOrFontScaleChanged() {
        super.onDensityOrFontScaleChanged();
        registerNavBarListeners();
        // Need to update the background on density changed in case the change was due to night
        // mode.
        mNotificationPanelBackground = getDefaultWallpaper();
        mScrimController.setScrimBehindDrawable(mNotificationPanelBackground);
    }

    @Override
    public void onLocaleListChanged() {
        connectNotificationsUI();
        registerNavBarListeners();
    }

    /**
     * Returns the {@link Drawable} that represents the wallpaper that the user has currently set.
     */
    private Drawable getDefaultWallpaper() {
        return mContext.getDrawable(com.android.internal.R.drawable.default_wallpaper);
    }

    private void setNotificationViewClipBounds(int height) {
        if (height > mNotificationView.getHeight()) {
            height = mNotificationView.getHeight();
        }
        Rect clipBounds = new Rect();
        clipBounds.set(0, 0, mNotificationView.getWidth(), height);
        // Sets the clip region on the notification list view.
        mNotificationView.setClipBounds(clipBounds);
        if (mHandleBar != null) {
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) mHandleBar.getLayoutParams();
            mHandleBar.setTranslationY(height - mHandleBar.getHeight() - lp.bottomMargin);
        }
        if (mNotificationView.getHeight() > 0) {
            Drawable background = mNotificationView.getBackground().mutate();
            background.setAlpha((int) (getBackgroundAlpha(height) * 255));
            mNotificationView.setBackground(background);
        }
    }

    /**
     * Calculates the alpha value for the background based on how much of the notification
     * shade is visible to the user. When the notification shade is completely open then
     * alpha value will be 1.
     */
    private float getBackgroundAlpha(int height) {
        return mInitialBackgroundAlpha +
            ((float) height / mNotificationView.getHeight() * mBackgroundAlphaDiff);
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        super.onConfigChanged(newConfig);

        int uiModeNightMask = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK);

        boolean dayNightModeChanged = uiModeNightMask == Configuration.UI_MODE_NIGHT_YES
                || uiModeNightMask == Configuration.UI_MODE_NIGHT_NO;

        if (dayNightModeChanged) {
            mNotificationView.setBackgroundColor(
                    mContext.getColor(R.color.notification_shade_background_color));
        }
    }

    private void calculatePercentageFromBottom(float height) {
        if (mNotificationView.getHeight() > 0) {
            mPercentageFromBottom = (int) Math.abs(
                    height / mNotificationView.getHeight() * 100);
        }
    }

    private static final int SWIPE_DOWN_MIN_DISTANCE = 25;
    private static final int SWIPE_MAX_OFF_PATH = 75;
    private static final int SWIPE_THRESHOLD_VELOCITY = 200;

    /**
     * Only responsible for open hooks. Since once the panel opens it covers all elements
     * there is no need to merge with close.
     */
    private abstract class OpenNotificationGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {

            if (mNotificationView.getVisibility() == View.INVISIBLE) {
                // when the on-scroll is called for the first time to open.
                mNotificationList.scrollToPosition(0);
            }
            mNotificationShadeWindowController.setPanelVisible(true);
            mNotificationView.setVisibility(View.VISIBLE);

            // clips the view for the notification shade when the user scrolls to open.
            setNotificationViewClipBounds((int) event2.getRawY());

            // Initially the scroll starts with height being zero. This checks protects from divide
            // by zero error.
            calculatePercentageFromBottom(event2.getRawY());

            mIsTracking = true;
            return true;
        }


        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                float velocityX, float velocityY) {
            if (velocityY > SWIPE_THRESHOLD_VELOCITY) {
                mOpeningVelocity = velocityY;
                openNotification();
                return true;
            }
            animateNotificationPanel(DEFAULT_FLING_VELOCITY, true);

            return false;
        }

        protected abstract void openNotification();
    }

    /**
     * To be installed on the open panel notification panel
     */
    private abstract class CloseNotificationGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onSingleTapUp(MotionEvent motionEvent) {
            if (mPanelExpanded) {
                animateNotificationPanel(DEFAULT_FLING_VELOCITY, true);
            }
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            // should not clip while scroll to the bottom of the list.
            if (!mNotificationListAtBottomAtTimeOfTouch) {
                return false;
            }
            float actualNotificationHeight =
                    mNotificationView.getHeight() - (event1.getRawY() - event2.getRawY());
            if (actualNotificationHeight > mNotificationView.getHeight()) {
                actualNotificationHeight = mNotificationView.getHeight();
            }
            if (mNotificationView.getHeight() > 0) {
                mPercentageFromBottom = (int) Math.abs(
                        actualNotificationHeight / mNotificationView.getHeight() * 100);
                boolean isUp = distanceY > 0;

                // This check is to figure out if onScroll was called while swiping the card at
                // bottom of the list. At that time we should not allow notification shade to
                // close. We are also checking for the upwards swipe gesture here because it is
                // possible if a user is closing the notification shade and while swiping starts
                // to open again but does not fling. At that time we should allow the
                // notification shade to close fully or else it would stuck in between.
                if (Math.abs(mNotificationView.getHeight() - actualNotificationHeight)
                        > SWIPE_DOWN_MIN_DISTANCE && isUp) {
                    setNotificationViewClipBounds((int) actualNotificationHeight);
                    mIsTracking = true;
                } else if (!isUp) {
                    setNotificationViewClipBounds((int) actualNotificationHeight);
                }
            }
            // if we return true the the items in RV won't be scrollable.
            return false;
        }


        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                float velocityX, float velocityY) {
            // should not fling if the touch does not start when view is at the bottom of the list.
            if (!mNotificationListAtBottomAtTimeOfTouch) {
                return false;
            }
            if (Math.abs(event1.getX() - event2.getX()) > SWIPE_MAX_OFF_PATH
                    || Math.abs(velocityY) < SWIPE_THRESHOLD_VELOCITY) {
                // swipe was not vertical or was not fast enough
                return false;
            }
            boolean isUp = velocityY < 0;
            if (isUp) {
                close();
                return true;
            } else {
                // we should close the shade
                animateNotificationPanel(velocityY, false);
            }
            return false;
        }

        protected abstract void close();
    }

    /**
     * To be installed on the nav bars.
     */
    private abstract class NavBarCloseNotificationGestureListener extends
            CloseNotificationGestureListener {
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            mClosingVelocity = DEFAULT_FLING_VELOCITY;
            if (mPanelExpanded) {
                close();
            }
            return super.onSingleTapUp(e);
        }

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            calculatePercentageFromBottom(event2.getRawY());
            setNotificationViewClipBounds((int) event2.getRawY());
            return true;
        }
    }

    /**
     * To be installed on the handle bar.
     */
    private class HandleBarCloseNotificationGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onScroll(MotionEvent event1, MotionEvent event2, float distanceX,
                float distanceY) {
            calculatePercentageFromBottom(event2.getRawY());
            // To prevent the jump in the clip bounds while closing the notification shade using
            // the handle bar we should calculate the height using the diff of event1 and event2.
            // This will help the notification shade to clip smoothly as the event2 value changes
            // as event1 value will be fixed.
            int clipHeight =
                    mNotificationView.getHeight() - (int) (event1.getRawY() - event2.getRawY());
            setNotificationViewClipBounds(clipHeight);
            return true;
        }
    }
}
