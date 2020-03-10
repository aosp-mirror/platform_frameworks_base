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

package com.android.systemui.statusbar.car;

import static com.android.systemui.Dependency.TIME_TICK_HANDLER_NAME;

import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;
import android.util.DisplayMetrics;

import com.android.car.notification.CarNotificationListener;
import com.android.car.notification.CarUxRestrictionManagerWrapper;
import com.android.car.notification.NotificationDataManager;
import com.android.internal.logging.MetricsLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.InitController;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.navigationbar.car.CarNavigationBarController;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.PluginDependencyProvider;
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
import com.android.systemui.statusbar.dagger.StatusBarDependenciesModule;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.interruption.BypassHeadsUpNotifier;
import com.android.systemui.statusbar.notification.interruption.NotificationAlertingManager;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationRowModule;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
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
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter;
import com.android.systemui.statusbar.phone.StatusBarTouchableRegionManager;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.phone.dagger.StatusBarPhoneDependenciesModule;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.volume.VolumeComponent;

import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module providing {@link CarStatusBar}.
 */
@Module(includes = {StatusBarDependenciesModule.class, StatusBarPhoneDependenciesModule.class,
        NotificationRowModule.class})
public class CarStatusBarModule {
    /**
     * Provides our instance of StatusBar which is considered optional.
     */
    @Provides
    @Singleton
    static CarStatusBar provideStatusBar(
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
            NotificationInterruptStateProvider notificationInterruptionStateProvider,
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
            Optional<Recents> recentsOptional,
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
            CarServiceProvider carServiceProvider,
            Lazy<PowerManagerHelper> powerManagerHelperLazy,
            CarNavigationBarController carNavigationBarController,
            FlingAnimationUtils.Builder flingAnimationUtilsBuilder,
            NotificationDataManager notificationDataManager,
            CarUxRestrictionManagerWrapper carUxRestrictionManagerWrapper,
            CarNotificationListener carNotificationListener) {
        return new CarStatusBar(
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
                notificationInterruptionStateProvider,
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
                lockscreenWallpaperLazy,
                biometricUnlockControllerLazy,
                dozeServiceHost,
                powerManager,
                screenPinningRequest,
                dozeScrimController,
                volumeComponent,
                commandQueue,
                recentsOptional,
                statusBarComponentBuilder,
                pluginManager,
                dividerOptional,
                superStatusBarViewFactory,
                lightsOutNotifController,
                statusBarNotificationActivityStarterBuilder,
                shadeController,
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
                statusBarTouchableRegionManager,
                carServiceProvider,
                powerManagerHelperLazy,
                carNavigationBarController,
                flingAnimationUtilsBuilder,
                notificationDataManager,
                carUxRestrictionManagerWrapper,
                carNotificationListener);
    }
}
