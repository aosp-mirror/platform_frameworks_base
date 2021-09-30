/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.phone.dagger;

import static com.android.systemui.Dependency.TIME_TICK_HANDLER_NAME;

import android.app.WallpaperManager;
import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;
import android.util.DisplayMetrics;

import com.android.internal.logging.MetricsLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.InitController;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.recents.ScreenPinningRequest;
import com.android.systemui.settings.brightness.BrightnessSlider;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.OperatorNameViewController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.legacy.VisualStabilityManager;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.interruption.BypassHeadsUpNotifier;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragmentLogger;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.DozeScrimController;
import com.android.systemui.statusbar.phone.DozeServiceHost;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LightsOutNotifController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.LockscreenWallpaper;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.PhoneStatusBarPolicy;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarLocationPublisher;
import com.android.systemui.statusbar.phone.StatusBarMoveFromCenterAnimationController;
import com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter;
import com.android.systemui.statusbar.phone.StatusBarTouchableRegionManager;
import com.android.systemui.statusbar.phone.StatusBarWindowView;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.unfold.UnfoldLightRevealOverlayAnimation;
import com.android.systemui.unfold.UnfoldTransitionWallpaperController;
import com.android.systemui.unfold.config.UnfoldTransitionConfig;
import com.android.systemui.util.WallpaperController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.MessageRouter;
import com.android.systemui.volume.VolumeComponent;
import com.android.systemui.wmshell.BubblesManager;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.startingsurface.StartingSurface;

import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Named;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module providing {@link StatusBar}.
 */
@Module(includes = {StatusBarPhoneDependenciesModule.class})
public interface StatusBarPhoneModule {
    /**
     * Provides our instance of StatusBar which is considered optional.
     */
    @Provides
    @SysUISingleton
    static StatusBar provideStatusBar(
            Context context,
            NotificationsController notificationsController,
            LightBarController lightBarController,
            AutoHideController autoHideController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            PulseExpansionHandler pulseExpansionHandler,
            NotificationWakeUpCoordinator notificationWakeUpCoordinator,
            KeyguardBypassController keyguardBypassController,
            KeyguardStateController keyguardStateController,
            HeadsUpManagerPhone headsUpManagerPhone,
            DynamicPrivacyController dynamicPrivacyController,
            BypassHeadsUpNotifier bypassHeadsUpNotifier,
            FalsingManager falsingManager,
            FalsingCollector falsingCollector,
            BroadcastDispatcher broadcastDispatcher,
            NotificationEntryManager notificationEntryManager,
            NotificationGutsManager notificationGutsManager,
            NotificationLogger notificationLogger,
            NotificationInterruptStateProvider notificationInterruptStateProvider,
            NotificationViewHierarchyManager notificationViewHierarchyManager,
            KeyguardViewMediator keyguardViewMediator,
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
            Optional<BubblesManager> bubblesManagerOptional,
            Optional<Bubbles> bubblesOptional,
            VisualStabilityManager visualStabilityManager,
            DeviceProvisionedController deviceProvisionedController,
            NavigationBarController navigationBarController,
            Lazy<AssistManager> assistManagerLazy,
            ConfigurationController configurationController,
            NotificationShadeWindowController notificationShadeWindowController,
            DozeParameters dozeParameters,
            ScrimController scrimController,
            Lazy<LockscreenWallpaper> lockscreenWallpaperLazy,
            LockscreenGestureLogger lockscreenGestureLogger,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            DozeServiceHost dozeServiceHost,
            PowerManager powerManager,
            ScreenPinningRequest screenPinningRequest,
            DozeScrimController dozeScrimController,
            VolumeComponent volumeComponent,
            CommandQueue commandQueue,
            CollapsedStatusBarFragmentLogger collapsedStatusBarFragmentLogger,
            StatusBarComponent.Factory statusBarComponentFactory,
            PluginManager pluginManager,
            Optional<LegacySplitScreen> splitScreenOptional,
            LightsOutNotifController lightsOutNotifController,
            StatusBarNotificationActivityStarter.Builder
                    statusBarNotificationActivityStarterBuilder,
            ShadeController shadeController,
            StatusBarWindowView statusBarWindowView,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            ViewMediatorCallback viewMediatorCallback,
            InitController initController,
            @Named(TIME_TICK_HANDLER_NAME) Handler timeTickHandler,
            PluginDependencyProvider pluginDependencyProvider,
            KeyguardDismissUtil keyguardDismissUtil,
            ExtensionController extensionController,
            UserInfoControllerImpl userInfoControllerImpl,
            OperatorNameViewController.Factory operatorNameViewControllerFactory,
            PhoneStatusBarPolicy phoneStatusBarPolicy,
            KeyguardIndicationController keyguardIndicationController,
            DemoModeController demoModeController,
            Lazy<NotificationShadeDepthController> notificationShadeDepthController,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager,
            NotificationIconAreaController notificationIconAreaController,
            BrightnessSlider.Factory brightnessSliderFactory,
            UnfoldTransitionConfig unfoldTransitionConfig,
            Lazy<UnfoldLightRevealOverlayAnimation> unfoldLightRevealOverlayAnimation,
            Lazy<UnfoldTransitionWallpaperController> unfoldTransitionWallpaperController,
            Lazy<StatusBarMoveFromCenterAnimationController> statusBarMoveFromCenterAnimation,
            WallpaperController wallpaperController,
            OngoingCallController ongoingCallController,
            SystemStatusAnimationScheduler animationScheduler,
            StatusBarLocationPublisher locationPublisher,
            StatusBarIconController statusBarIconController,
            LockscreenShadeTransitionController transitionController,
            FeatureFlags featureFlags,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            @Main Handler mainHandler,
            @Main DelayableExecutor delayableExecutor,
            @Main MessageRouter messageRouter,
            WallpaperManager wallpaperManager,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            Optional<StartingSurface> startingSurfaceOptional,
            TunerService tunerService,
            DumpManager dumpManager,
            ActivityLaunchAnimator activityLaunchAnimator,
            DialogLaunchAnimator dialogLaunchAnimator) {
        return new StatusBar(
                context,
                notificationsController,
                lightBarController,
                autoHideController,
                keyguardUpdateMonitor,
                pulseExpansionHandler,
                notificationWakeUpCoordinator,
                keyguardBypassController,
                keyguardStateController,
                headsUpManagerPhone,
                dynamicPrivacyController,
                bypassHeadsUpNotifier,
                falsingManager,
                falsingCollector,
                broadcastDispatcher,
                notificationEntryManager,
                notificationGutsManager,
                notificationLogger,
                notificationInterruptStateProvider,
                notificationViewHierarchyManager,
                keyguardViewMediator,
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
                bubblesManagerOptional,
                bubblesOptional,
                visualStabilityManager,
                deviceProvisionedController,
                navigationBarController,
                assistManagerLazy,
                configurationController,
                notificationShadeWindowController,
                dozeParameters,
                scrimController,
                lockscreenWallpaperLazy,
                lockscreenGestureLogger,
                biometricUnlockControllerLazy,
                dozeServiceHost,
                powerManager,
                screenPinningRequest,
                dozeScrimController,
                volumeComponent,
                commandQueue,
                collapsedStatusBarFragmentLogger,
                statusBarComponentFactory,
                pluginManager,
                splitScreenOptional,
                lightsOutNotifController,
                statusBarNotificationActivityStarterBuilder,
                shadeController,
                statusBarWindowView,
                statusBarKeyguardViewManager,
                viewMediatorCallback,
                initController,
                timeTickHandler,
                pluginDependencyProvider,
                keyguardDismissUtil,
                extensionController,
                userInfoControllerImpl,
                operatorNameViewControllerFactory,
                phoneStatusBarPolicy,
                keyguardIndicationController,
                demoModeController,
                notificationShadeDepthController,
                statusBarTouchableRegionManager,
                notificationIconAreaController,
                brightnessSliderFactory,
                unfoldTransitionConfig,
                unfoldLightRevealOverlayAnimation,
                unfoldTransitionWallpaperController,
                statusBarMoveFromCenterAnimation,
                wallpaperController,
                ongoingCallController,
                animationScheduler,
                locationPublisher,
                statusBarIconController,
                transitionController,
                featureFlags,
                keyguardUnlockAnimationController,
                mainHandler,
                delayableExecutor,
                messageRouter,
                wallpaperManager,
                unlockedScreenOffAnimationController,
                startingSurfaceOptional,
                tunerService,
                dumpManager,
                activityLaunchAnimator,
                dialogLaunchAnimator);
    }
}
