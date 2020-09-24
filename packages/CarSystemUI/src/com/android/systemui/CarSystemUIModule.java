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
import static com.android.systemui.Dependency.LEAK_REPORT_EMAIL_NAME;

import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;

import com.android.keyguard.KeyguardViewController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarDeviceProvisionedControllerImpl;
import com.android.systemui.car.keyguard.CarKeyguardViewController;
import com.android.systemui.car.statusbar.CarStatusBar;
import com.android.systemui.car.statusbar.CarStatusBarKeyguardViewManager;
import com.android.systemui.car.statusbar.DozeServiceHost;
import com.android.systemui.car.statusbar.DummyNotificationShadeWindowController;
import com.android.systemui.car.volume.CarVolumeDialogComponent;
import com.android.systemui.dagger.SystemUIRootComponent;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManagerImpl;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.plugins.qs.QSFactory;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.power.EnhancedEstimatesImpl;
import com.android.systemui.qs.dagger.QSModule;
import com.android.systemui.qs.tileimpl.QSFactoryImpl;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsImplementation;
import com.android.systemui.stackdivider.DividerModule;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManagerImpl;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardEnvironmentImpl;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.ShadeControllerImpl;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryControllerImpl;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.volume.VolumeDialogComponent;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

@Module(includes = {DividerModule.class, QSModule.class})
public abstract class CarSystemUIModule {

    @Singleton
    @Provides
    @Named(ALLOW_NOTIFICATION_LONG_PRESS_NAME)
    static boolean provideAllowNotificationLongPress() {
        return false;
    }

    @Singleton
    @Provides
    static HeadsUpManagerPhone provideHeadsUpManagerPhone(
            Context context,
            StatusBarStateController statusBarStateController,
            KeyguardBypassController bypassController,
            NotificationGroupManager groupManager,
            ConfigurationController configurationController) {
        return new HeadsUpManagerPhone(context, statusBarStateController, bypassController,
                groupManager, configurationController);
    }

    @Singleton
    @Provides
    @Named(LEAK_REPORT_EMAIL_NAME)
    static String provideLeakReportEmail() {
        return "buganizer-system+181579@google.com";
    }

    @Provides
    @Singleton
    static Recents provideRecents(Context context, RecentsImplementation recentsImplementation,
            CommandQueue commandQueue) {
        return new Recents(context, recentsImplementation, commandQueue);
    }

    @Binds
    abstract HeadsUpManager bindHeadsUpManagerPhone(HeadsUpManagerPhone headsUpManagerPhone);

    @Binds
    abstract EnhancedEstimates bindEnhancedEstimates(EnhancedEstimatesImpl enhancedEstimates);

    @Binds
    abstract NotificationLockscreenUserManager bindNotificationLockscreenUserManager(
            NotificationLockscreenUserManagerImpl notificationLockscreenUserManager);

    @Provides
    @Singleton
    static BatteryController provideBatteryController(Context context,
            EnhancedEstimates enhancedEstimates, PowerManager powerManager,
            BroadcastDispatcher broadcastDispatcher, @Main Handler mainHandler,
            @Background Handler bgHandler) {
        BatteryController bC = new BatteryControllerImpl(context, enhancedEstimates, powerManager,
                broadcastDispatcher, mainHandler, bgHandler);
        bC.init();
        return bC;
    }

    @Binds
    @Singleton
    public abstract QSFactory bindQSFactory(QSFactoryImpl qsFactoryImpl);

    @Binds
    abstract DockManager bindDockManager(DockManagerImpl dockManager);

    @Binds
    abstract NotificationEntryManager.KeyguardEnvironment bindKeyguardEnvironment(
            KeyguardEnvironmentImpl keyguardEnvironment);

    @Binds
    abstract ShadeController provideShadeController(ShadeControllerImpl shadeController);

    @Binds
    abstract SystemUIRootComponent bindSystemUIRootComponent(
            CarSystemUIRootComponent systemUIRootComponent);

    @Binds
    public abstract StatusBar bindStatusBar(CarStatusBar statusBar);

    @Binds
    abstract VolumeDialogComponent bindVolumeDialogComponent(
            CarVolumeDialogComponent carVolumeDialogComponent);

    @Binds
    abstract StatusBarKeyguardViewManager bindStatusBarKeyguardViewManager(
            CarStatusBarKeyguardViewManager keyguardViewManager);

    @Binds
    abstract KeyguardViewController bindKeyguardViewController(
            CarKeyguardViewController carKeyguardViewController);

    @Binds
    abstract DeviceProvisionedController bindDeviceProvisionedController(
            CarDeviceProvisionedControllerImpl deviceProvisionedController);

    @Binds
    abstract CarDeviceProvisionedController bindCarDeviceProvisionedController(
            CarDeviceProvisionedControllerImpl deviceProvisionedController);

    @Binds
    abstract NotificationShadeWindowController bindNotificationShadeWindowController(
            DummyNotificationShadeWindowController notificationShadeWindowController);

    @Binds
    abstract DozeHost bindDozeHost(DozeServiceHost dozeServiceHost);
}
