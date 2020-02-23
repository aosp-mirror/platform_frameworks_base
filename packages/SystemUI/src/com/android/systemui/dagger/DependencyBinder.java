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

package com.android.systemui.dagger;

import com.android.systemui.ActivityStarterDelegate;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.appops.AppOpsControllerImpl;
import com.android.systemui.classifier.FalsingManagerProxy;
import com.android.systemui.controls.dagger.ControlsModule;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.globalactions.GlobalActionsComponent;
import com.android.systemui.globalactions.GlobalActionsImpl;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.PowerNotificationWarnings;
import com.android.systemui.power.PowerUI;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.DarkIconDispatcherImpl;
import com.android.systemui.statusbar.phone.DozeServiceHost;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.phone.ManagedProfileControllerImpl;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconControllerImpl;
import com.android.systemui.statusbar.phone.StatusBarRemoteInputCallback;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothControllerImpl;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastControllerImpl;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.ExtensionControllerImpl;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.FlashlightControllerImpl;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.HotspotControllerImpl;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.KeyguardStateControllerImpl;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationControllerImpl;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkControllerImpl;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmControllerImpl;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockControllerImpl;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.SecurityControllerImpl;
import com.android.systemui.statusbar.policy.SensorPrivacyController;
import com.android.systemui.statusbar.policy.SensorPrivacyControllerImpl;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeControllerImpl;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerServiceImpl;
import com.android.systemui.volume.VolumeComponent;
import com.android.systemui.volume.VolumeDialogComponent;
import com.android.systemui.volume.VolumeDialogControllerImpl;

import dagger.Binds;
import dagger.Module;

/**
 * Maps interfaces to implementations for use with Dagger.
 */
@Module(includes = {ControlsModule.class})
public abstract class DependencyBinder {

    /**
     */
    @Binds
    public abstract ActivityStarter provideActivityStarter(ActivityStarterDelegate delegate);

    /**
     */
    @Binds
    public abstract BluetoothController provideBluetoothController(
            BluetoothControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract GlobalActions provideGlobalActions(GlobalActionsImpl controllerImpl);

    /**
     */
    @Binds
    public abstract GlobalActions.GlobalActionsManager provideGlobalActionsManager(
            GlobalActionsComponent controllerImpl);

    /**
     */
    @Binds
    public abstract LocationController provideLocationController(
            LocationControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract RotationLockController provideRotationLockController(
            RotationLockControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract NetworkController provideNetworkController(
            NetworkControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract ZenModeController provideZenModeController(
            ZenModeControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract HotspotController provideHotspotController(
            HotspotControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract AppOpsController provideAppOpsController(
            AppOpsControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract NotificationRemoteInputManager.Callback provideNotificationRemoteInputManager(
            StatusBarRemoteInputCallback callbackImpl);

    /**
     */
    @Binds
    public abstract CastController provideCastController(CastControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract FlashlightController provideFlashlightController(
            FlashlightControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract KeyguardStateController provideKeyguardMonitor(
            KeyguardStateControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract UserInfoController provideUserInfoContrller(
            UserInfoControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract ManagedProfileController provideManagedProfileController(
            ManagedProfileControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract NextAlarmController provideNextAlarmController(
            NextAlarmControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract SecurityController provideSecurityController(
            SecurityControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract TunerService provideTunerService(TunerServiceImpl controllerImpl);

    /**
     */
    @Binds
    public abstract DarkIconDispatcher provideDarkIconDispatcher(
            DarkIconDispatcherImpl controllerImpl);

    /**
     */
    @Binds
    public abstract StatusBarStateController provideStatusBarStateController(
            StatusBarStateControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract SysuiStatusBarStateController providesSysuiStatusBarStateController(
            StatusBarStateControllerImpl statusBarStateControllerImpl);

    /**
     */
    @Binds
    public abstract StatusBarIconController provideStatusBarIconController(
            StatusBarIconControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract ExtensionController provideExtensionController(
            ExtensionControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract VolumeDialogController provideVolumeDialogController(
            VolumeDialogControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract PowerUI.WarningsUI provideWarningsUi(PowerNotificationWarnings controllerImpl);

    /**
     */
    @Binds
    public abstract SensorPrivacyController provideSensorPrivacyControllerImpl(
            SensorPrivacyControllerImpl controllerImpl);

    /**
     */
    @Binds
    public abstract QSHost provideQsHost(QSTileHost controllerImpl);

    /**
     */
    @Binds
    public abstract FalsingManager provideFalsingManager(FalsingManagerProxy falsingManagerImpl);

    /**
     */
    @Binds
    public abstract DozeHost provideDozeHost(DozeServiceHost dozeServiceHost);

    /**
     */
    @Binds
    public abstract VolumeComponent provideVolumeComponent(
            VolumeDialogComponent volumeDialogComponent);
}
