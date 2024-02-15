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

package com.android.systemui.statusbar.policy.dagger;

import android.content.Context;
import android.content.res.Resources;
import android.os.UserManager;

import com.android.internal.R;
import com.android.settingslib.devicestate.DeviceStateRotationLockSettingsManager;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.LogBufferFactory;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.connectivity.AccessPointController;
import com.android.systemui.statusbar.connectivity.AccessPointControllerImpl;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.NetworkControllerImpl;
import com.android.systemui.statusbar.connectivity.WifiPickerTrackerFactory;
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl;
import com.android.systemui.statusbar.policy.BatteryControllerLogger;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothControllerImpl;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastControllerImpl;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DeviceControlsController;
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.statusbar.policy.DevicePostureControllerImpl;
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
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmControllerImpl;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockControllerImpl;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.SecurityControllerImpl;
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController;
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionControllerImpl;
import com.android.systemui.statusbar.policy.SplitShadeStateController;
import com.android.systemui.statusbar.policy.SplitShadeStateControllerImpl;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.WalletController;
import com.android.systemui.statusbar.policy.WalletControllerImpl;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeControllerImpl;
import com.android.systemui.statusbar.policy.bluetooth.BluetoothRepository;
import com.android.systemui.statusbar.policy.bluetooth.BluetoothRepositoryImpl;
import com.android.systemui.statusbar.policy.data.repository.DeviceProvisioningRepositoryModule;
import com.android.systemui.statusbar.policy.data.repository.ZenModeRepositoryModule;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

import java.util.concurrent.Executor;

import javax.inject.Named;

/** Dagger Module for code in the statusbar.policy package. */
@Module(includes = { DeviceProvisioningRepositoryModule.class, ZenModeRepositoryModule.class })
public interface StatusBarPolicyModule {

    String DEVICE_STATE_ROTATION_LOCK_DEFAULTS = "DEVICE_STATE_ROTATION_LOCK_DEFAULTS";

    /** */
    @Binds
    BluetoothController provideBluetoothController(BluetoothControllerImpl controllerImpl);

    /** */
    @Binds
    BluetoothRepository provideBluetoothRepository(BluetoothRepositoryImpl impl);

    /** */
    @Binds
    CastController provideCastController(CastControllerImpl controllerImpl);

    /** */
    @Binds
    ConfigurationController bindConfigurationController(ConfigurationControllerImpl impl);

    /** */
    @Binds
    ExtensionController provideExtensionController(ExtensionControllerImpl controllerImpl);

    /** */
    @Binds
    FlashlightController provideFlashlightController(FlashlightControllerImpl controllerImpl);

    /** */
    @Binds
    KeyguardStateController provideKeyguardMonitor(KeyguardStateControllerImpl controllerImpl);

    /** */
    @Binds
    SplitShadeStateController provideSplitShadeStateController(
            SplitShadeStateControllerImpl splitShadeStateControllerImpl);

    /** */
    @Binds
    HotspotController provideHotspotController(HotspotControllerImpl controllerImpl);

    /** */
    @Binds
    LocationController provideLocationController(LocationControllerImpl controllerImpl);

    /** */
    @Binds
    NetworkController provideNetworkController(NetworkControllerImpl controllerImpl);

    /** */
    @Binds
    NextAlarmController provideNextAlarmController(NextAlarmControllerImpl controllerImpl);

    /** */
    @Binds
    RotationLockController provideRotationLockController(RotationLockControllerImpl controllerImpl);

    /** */
    @Binds
    SecurityController provideSecurityController(SecurityControllerImpl controllerImpl);

    /** */
    @Binds
    SensitiveNotificationProtectionController provideSensitiveNotificationProtectionController(
            SensitiveNotificationProtectionControllerImpl controllerImpl);

    /** */
    @Binds
    UserInfoController provideUserInfoContrller(UserInfoControllerImpl controllerImpl);

    /** */
    @Binds
    ZenModeController provideZenModeController(ZenModeControllerImpl controllerImpl);

    /** */
    @Binds
    DeviceControlsController provideDeviceControlsController(
            DeviceControlsControllerImpl controllerImpl);

    /** */
    @Binds
    WalletController provideWalletController(WalletControllerImpl controllerImpl);

    /** */
    @Binds
    AccessPointController provideAccessPointController(
            AccessPointControllerImpl accessPointControllerImpl);

    /** */
    @Binds
    DevicePostureController provideDevicePostureController(
            DevicePostureControllerImpl devicePostureControllerImpl);

    /** */
    @SysUISingleton
    @Provides
    static AccessPointControllerImpl  provideAccessPointControllerImpl(
            UserManager userManager,
            UserTracker userTracker,
            @Main Executor mainExecutor,
            WifiPickerTrackerFactory wifiPickerTrackerFactory
    ) {
        AccessPointControllerImpl controller = new AccessPointControllerImpl(
                userManager,
                userTracker,
                mainExecutor,
                wifiPickerTrackerFactory
        );
        controller.init();
        return controller;
    }

    /** Returns a singleton instance of DeviceStateRotationLockSettingsManager */
    @SysUISingleton
    @Provides
    static DeviceStateRotationLockSettingsManager provideAutoRotateSettingsManager(
            Context context) {
        return DeviceStateRotationLockSettingsManager.getInstance(context);
    }

    /**
     * Default values for per-device state rotation lock settings.
     */
    @Provides
    @Named(DEVICE_STATE_ROTATION_LOCK_DEFAULTS)
    static String[] providesDeviceStateRotationLockDefaults(@Main Resources resources) {
        return resources.getStringArray(
                R.array.config_perDeviceStateRotationLockDefaults);
    }

    /** */
    @Provides
    @SysUISingleton
    static DataSaverController provideDataSaverController(NetworkController networkController) {
        return networkController.getDataSaverController();
    }

    /** Provides a log buffer for BatteryControllerImpl */
    @Provides
    @SysUISingleton
    @BatteryControllerLog
    static LogBuffer provideBatteryControllerLog(LogBufferFactory factory) {
        return factory.create(BatteryControllerLogger.TAG, 30);
    }
}
