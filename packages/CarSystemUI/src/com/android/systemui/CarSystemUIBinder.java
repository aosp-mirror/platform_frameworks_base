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

package com.android.systemui;

import static com.android.systemui.Dependency.ALLOW_NOTIFICATION_LONG_PRESS_NAME;

import android.content.Context;
import android.os.PowerManager;
import android.util.DisplayMetrics;

import com.android.internal.logging.MetricsLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.navigationbar.car.CarNavigationBar;
import com.android.systemui.navigationbar.car.CarNavigationBarController;
import com.android.systemui.pip.PipUI;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.power.PowerUI;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsModule;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.car.CarStatusBar;
import com.android.systemui.statusbar.notification.BypassHeadsUpNotifier;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NewNotifPipeline;
import com.android.systemui.statusbar.notification.NotificationAlertingManager;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.logging.NotifLog;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.DozeScrimController;
import com.android.systemui.statusbar.phone.DozeServiceHost;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LockscreenWallpaper;
import com.android.systemui.statusbar.phone.NotificationGroupAlertTransferHelper;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarWindowController;
import com.android.systemui.statusbar.phone.StatusBarWindowViewController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.InjectionInflationController;
import com.android.systemui.util.leak.GarbageMonitor;
import com.android.systemui.volume.VolumeUI;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Binds;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/** Binder for car specific {@link SystemUI} modules. */
@Module(includes = {RecentsModule.class})
public abstract class CarSystemUIBinder {
    /** */
    @Binds
    @IntoMap
    @ClassKey(CarNavigationBar.class)
    public abstract SystemUI bindCarNavigationBar(CarNavigationBar sysui);

    /** Inject into GarbageMonitor.Service. */
    @Binds
    @IntoMap
    @ClassKey(GarbageMonitor.Service.class)
    public abstract SystemUI bindGarbageMonitorService(GarbageMonitor.Service service);

    /** Inject into KeyguardViewMediator. */
    @Binds
    @IntoMap
    @ClassKey(KeyguardViewMediator.class)
    public abstract SystemUI bindKeyguardViewMediator(KeyguardViewMediator sysui);

    /** Inject into LatencyTests. */
    @Binds
    @IntoMap
    @ClassKey(LatencyTester.class)
    public abstract SystemUI bindLatencyTester(LatencyTester sysui);

    /** Inject into PipUI. */
    @Binds
    @IntoMap
    @ClassKey(PipUI.class)
    public abstract SystemUI bindPipUI(PipUI sysui);

    /** Inject into PowerUI. */
    @Binds
    @IntoMap
    @ClassKey(PowerUI.class)
    public abstract SystemUI bindPowerUI(PowerUI sysui);

    /** Inject into Recents. */
    @Binds
    @IntoMap
    @ClassKey(Recents.class)
    public abstract SystemUI bindRecents(Recents sysui);

    /** Inject into ScreenDecorations. */
    @Binds
    @IntoMap
    @ClassKey(ScreenDecorations.class)
    public abstract SystemUI bindScreenDecorations(ScreenDecorations sysui);

    /** Inject into StatusBar. */
    @Binds
    @IntoMap
    @ClassKey(StatusBar.class)
    public abstract SystemUI bindsStatusBar(CarStatusBar sysui);

    /** Inject into StatusBarGoogle. */
    @Binds
    @IntoMap
    @ClassKey(CarStatusBar.class)
    public abstract SystemUI bindsCarStatusBar(CarStatusBar sysui);

    /** Inject into VolumeUI. */
    @Binds
    @IntoMap
    @ClassKey(VolumeUI.class)
    public abstract SystemUI bindVolumeUI(VolumeUI sysui);

    /**
     * Provides our instance of StatusBar which is considered optional.
     */
    @Provides
    @Singleton
    static CarStatusBar provideStatusBar(
            Context context,
            FeatureFlags featureFlags,
            LightBarController lightBarController,
            AutoHideController autoHideController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            StatusBarIconController statusBarIconController,
            DozeLog dozeLog,
            InjectionInflationController injectionInflationController,
            PulseExpansionHandler pulseExpansionHandler,
            NotificationWakeUpCoordinator notificationWakeUpCoordinator,
            KeyguardBypassController keyguardBypassController,
            KeyguardStateController keyguardStateController,
            HeadsUpManagerPhone headsUpManagerPhone,
            DynamicPrivacyController dynamicPrivacyController,
            BypassHeadsUpNotifier bypassHeadsUpNotifier,
            @Named(ALLOW_NOTIFICATION_LONG_PRESS_NAME) boolean allowNotificationLongPress,
            Lazy<NewNotifPipeline> newNotifPipeline,
            FalsingManager falsingManager,
            BroadcastDispatcher broadcastDispatcher,
            RemoteInputQuickSettingsDisabler remoteInputQuickSettingsDisabler,
            NotificationGutsManager notificationGutsManager,
            NotificationLogger notificationLogger,
            NotificationEntryManager notificationEntryManager,
            NotificationInterruptionStateProvider notificationInterruptionStateProvider,
            NotificationViewHierarchyManager notificationViewHierarchyManager,
            ForegroundServiceController foregroundServiceController,
            AppOpsController appOpsController,
            KeyguardViewMediator keyguardViewMediator,
            ZenModeController zenModeController,
            NotificationAlertingManager notificationAlertingManager,
            DisplayMetrics displayMetrics,
            MetricsLogger metricsLogger,
            UiOffloadThread uiOffloadThread,
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
            NotificationGroupAlertTransferHelper groupAlertTransferHelper,
            VisualStabilityManager visualStabilityManager,
            DeviceProvisionedController deviceProvisionedController,
            NavigationBarController navigationBarController,
            AssistManager assistManager,
            NotificationListener notificationListener,
            ConfigurationController configurationController,
            StatusBarWindowController statusBarWindowController,
            StatusBarWindowViewController.Builder statusBarWindowViewControllerBuilder,
            NotifLog notifLog,
            DozeParameters dozeParameters,
            ScrimController scrimController,
            Lazy<LockscreenWallpaper> lockscreenWallpaperLazy,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            DozeServiceHost dozeServiceHost,
            PowerManager powerManager,
            DozeScrimController dozeScrimController,
            CarNavigationBarController carNavigationBarController) {
        return new CarStatusBar(
                context,
                featureFlags,
                lightBarController,
                autoHideController,
                keyguardUpdateMonitor,
                statusBarIconController,
                dozeLog,
                injectionInflationController,
                pulseExpansionHandler,
                notificationWakeUpCoordinator,
                keyguardBypassController,
                keyguardStateController,
                headsUpManagerPhone,
                dynamicPrivacyController,
                bypassHeadsUpNotifier,
                allowNotificationLongPress,
                newNotifPipeline,
                falsingManager,
                broadcastDispatcher,
                remoteInputQuickSettingsDisabler,
                notificationGutsManager,
                notificationLogger,
                notificationEntryManager,
                notificationInterruptionStateProvider,
                notificationViewHierarchyManager,
                foregroundServiceController,
                appOpsController,
                keyguardViewMediator,
                zenModeController,
                notificationAlertingManager,
                displayMetrics,
                metricsLogger,
                uiOffloadThread,
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
                groupAlertTransferHelper,
                visualStabilityManager,
                deviceProvisionedController,
                navigationBarController,
                assistManager,
                notificationListener,
                configurationController,
                statusBarWindowController,
                statusBarWindowViewControllerBuilder,
                notifLog,
                dozeParameters,
                scrimController,
                lockscreenWallpaperLazy,
                biometricUnlockControllerLazy,
                dozeServiceHost,
                powerManager,
                dozeScrimController,
                carNavigationBarController);
    }
}
