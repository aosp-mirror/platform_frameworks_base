/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.doze;

import android.app.AlarmManager;
import android.app.Application;
import android.app.IWallpaperManager;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.wakelock.DelayedWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

import javax.inject.Inject;

public class DozeFactory {

    private final FalsingManager mFalsingManager;
    private final DozeLog mDozeLog;
    private final DozeParameters mDozeParameters;
    private final BatteryController mBatteryController;
    private final AsyncSensorManager mAsyncSensorManager;
    private final AlarmManager mAlarmManager;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final DockManager mDockManager;
    private final IWallpaperManager mWallpaperManager;

    @Inject
    public DozeFactory(FalsingManager falsingManager, DozeLog dozeLog,
            DozeParameters dozeParameters, BatteryController batteryController,
            AsyncSensorManager asyncSensorManager, AlarmManager alarmManager,
            WakefulnessLifecycle wakefulnessLifecycle, KeyguardUpdateMonitor keyguardUpdateMonitor,
            DockManager dockManager, IWallpaperManager wallpaperManager) {
        mFalsingManager = falsingManager;
        mDozeLog = dozeLog;
        mDozeParameters = dozeParameters;
        mBatteryController = batteryController;
        mAsyncSensorManager = asyncSensorManager;
        mAlarmManager = alarmManager;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mDockManager = dockManager;
        mWallpaperManager = wallpaperManager;
    }

    /** Creates a DozeMachine with its parts for {@code dozeService}. */
    public DozeMachine assembleMachine(DozeService dozeService) {
        DozeHost host = getHost(dozeService);
        AmbientDisplayConfiguration config = new AmbientDisplayConfiguration(dozeService);
        Handler handler = new Handler();
        WakeLock wakeLock = new DelayedWakeLock(handler,
                WakeLock.createPartial(dozeService, "Doze"));

        DozeMachine.Service wrappedService = dozeService;
        wrappedService = new DozeBrightnessHostForwarder(wrappedService, host);
        wrappedService = DozeScreenStatePreventingAdapter.wrapIfNeeded(
                wrappedService, mDozeParameters);
        wrappedService = DozeSuspendScreenStatePreventingAdapter.wrapIfNeeded(
                wrappedService, mDozeParameters);

        DozeMachine machine = new DozeMachine(wrappedService, config, wakeLock,
                                              mWakefulnessLifecycle, mBatteryController, mDozeLog);
        machine.setParts(new DozeMachine.Part[]{
                new DozePauser(handler, machine, mAlarmManager, mDozeParameters.getPolicy()),
                new DozeFalsingManagerAdapter(mFalsingManager),
                createDozeTriggers(dozeService, mAsyncSensorManager, host, mAlarmManager, config,
                        mDozeParameters, handler, wakeLock, machine, mDockManager, mDozeLog),
                createDozeUi(dozeService, host, wakeLock, machine, handler, mAlarmManager,
                        mDozeParameters, mDozeLog),
                new DozeScreenState(wrappedService, handler, host, mDozeParameters, wakeLock),
                createDozeScreenBrightness(dozeService, wrappedService, mAsyncSensorManager, host,
                        mDozeParameters, handler),
                new DozeWallpaperState(
                        mWallpaperManager,
                        getBiometricUnlockController(dozeService),
                        mDozeParameters),
                new DozeDockHandler(dozeService, machine, host, config, handler, mDockManager),
                new DozeAuthRemover(dozeService)
        });

        return machine;
    }

    private DozeMachine.Part createDozeScreenBrightness(Context context,
            DozeMachine.Service service, SensorManager sensorManager, DozeHost host,
            DozeParameters params, Handler handler) {
        Sensor sensor = DozeSensors.findSensorWithType(sensorManager,
                context.getString(R.string.doze_brightness_sensor_type));
        return new DozeScreenBrightness(context, service, sensorManager, sensor, host, handler,
                params.getPolicy());
    }

    private DozeTriggers createDozeTriggers(Context context, AsyncSensorManager sensorManager,
            DozeHost host, AlarmManager alarmManager, AmbientDisplayConfiguration config,
            DozeParameters params, Handler handler, WakeLock wakeLock, DozeMachine machine,
            DockManager dockManager, DozeLog dozeLog) {
        boolean allowPulseTriggers = true;
        return new DozeTriggers(context, machine, host, alarmManager, config, params,
                sensorManager, handler, wakeLock, allowPulseTriggers, dockManager,
                new ProximitySensor(context, sensorManager), dozeLog);

    }

    private DozeMachine.Part createDozeUi(Context context, DozeHost host, WakeLock wakeLock,
            DozeMachine machine, Handler handler, AlarmManager alarmManager,
            DozeParameters params, DozeLog dozeLog) {
        return new DozeUi(context, alarmManager, machine, wakeLock, host, handler, params,
                          mKeyguardUpdateMonitor, dozeLog);
    }

    public static DozeHost getHost(DozeService service) {
        Application appCandidate = service.getApplication();
        final SystemUIApplication app = (SystemUIApplication) appCandidate;
        return app.getComponent(DozeHost.class);
    }

    public static BiometricUnlockController getBiometricUnlockController(DozeService service) {
        Application appCandidate = service.getApplication();
        final SystemUIApplication app = (SystemUIApplication) appCandidate;
        return app.getComponent(BiometricUnlockController.class);
    }
}
