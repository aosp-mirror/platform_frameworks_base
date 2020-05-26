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

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.IWallpaperManager;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.concurrency.DelayableExecutor;
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
    private final ProximitySensor mProximitySensor;
    private final DelayedWakeLock.Builder mDelayedWakeLockBuilder;
    private final Handler mHandler;
    private final DelayableExecutor mDelayableExecutor;
    private final BiometricUnlockController mBiometricUnlockController;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final DozeHost mDozeHost;

    @Inject
    public DozeFactory(FalsingManager falsingManager, DozeLog dozeLog,
            DozeParameters dozeParameters, BatteryController batteryController,
            AsyncSensorManager asyncSensorManager, AlarmManager alarmManager,
            WakefulnessLifecycle wakefulnessLifecycle, KeyguardUpdateMonitor keyguardUpdateMonitor,
            DockManager dockManager, @Nullable IWallpaperManager wallpaperManager,
            ProximitySensor proximitySensor,
            DelayedWakeLock.Builder delayedWakeLockBuilder, @Main Handler handler,
            DelayableExecutor delayableExecutor,
            BiometricUnlockController biometricUnlockController,
            BroadcastDispatcher broadcastDispatcher, DozeHost dozeHost) {
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
        mProximitySensor = proximitySensor;
        mDelayedWakeLockBuilder = delayedWakeLockBuilder;
        mHandler = handler;
        mDelayableExecutor = delayableExecutor;
        mBiometricUnlockController = biometricUnlockController;
        mBroadcastDispatcher = broadcastDispatcher;
        mDozeHost = dozeHost;
    }

    /** Creates a DozeMachine with its parts for {@code dozeService}. */
    DozeMachine assembleMachine(DozeService dozeService) {
        AmbientDisplayConfiguration config = new AmbientDisplayConfiguration(dozeService);
        WakeLock wakeLock = mDelayedWakeLockBuilder.setHandler(mHandler).setTag("Doze").build();

        DozeMachine.Service wrappedService = dozeService;
        wrappedService = new DozeBrightnessHostForwarder(wrappedService, mDozeHost);
        wrappedService = DozeScreenStatePreventingAdapter.wrapIfNeeded(
                wrappedService, mDozeParameters);
        wrappedService = DozeSuspendScreenStatePreventingAdapter.wrapIfNeeded(
                wrappedService, mDozeParameters);

        DozeMachine machine = new DozeMachine(wrappedService, config, wakeLock,
                mWakefulnessLifecycle, mBatteryController, mDozeLog, mDockManager,
                mDozeHost);
        machine.setParts(new DozeMachine.Part[]{
                new DozePauser(mHandler, machine, mAlarmManager, mDozeParameters.getPolicy()),
                new DozeFalsingManagerAdapter(mFalsingManager),
                createDozeTriggers(dozeService, mAsyncSensorManager, mDozeHost,
                        mAlarmManager, config, mDozeParameters, mDelayableExecutor, wakeLock,
                        machine, mDockManager, mDozeLog),
                createDozeUi(dozeService, mDozeHost, wakeLock, machine, mHandler,
                        mAlarmManager, mDozeParameters, mDozeLog),
                new DozeScreenState(wrappedService, mHandler, mDozeHost, mDozeParameters,
                        wakeLock),
                createDozeScreenBrightness(dozeService, wrappedService, mAsyncSensorManager,
                        mDozeHost, mDozeParameters, mHandler),
                new DozeWallpaperState(mWallpaperManager, mBiometricUnlockController,
                        mDozeParameters),
                new DozeDockHandler(config, machine, mDockManager),
                new DozeAuthRemover(dozeService)
        });

        return machine;
    }

    private DozeMachine.Part createDozeScreenBrightness(Context context,
            DozeMachine.Service service, SensorManager sensorManager, DozeHost host,
            DozeParameters params, Handler handler) {
        Sensor sensor = DozeSensors.findSensorWithType(sensorManager,
                context.getString(R.string.doze_brightness_sensor_type));
        return new DozeScreenBrightness(context, service, sensorManager, sensor,
                mBroadcastDispatcher, host, handler, params.getPolicy());
    }

    private DozeTriggers createDozeTriggers(Context context, AsyncSensorManager sensorManager,
            DozeHost host, AlarmManager alarmManager, AmbientDisplayConfiguration config,
            DozeParameters params, DelayableExecutor delayableExecutor, WakeLock wakeLock,
            DozeMachine machine, DockManager dockManager, DozeLog dozeLog) {
        boolean allowPulseTriggers = true;
        return new DozeTriggers(context, machine, host, alarmManager, config, params,
                sensorManager, delayableExecutor, wakeLock, allowPulseTriggers, dockManager,
                mProximitySensor, dozeLog, mBroadcastDispatcher);

    }

    private DozeMachine.Part createDozeUi(Context context, DozeHost host, WakeLock wakeLock,
            DozeMachine machine, Handler handler, AlarmManager alarmManager,
            DozeParameters params, DozeLog dozeLog) {
        return new DozeUi(context, alarmManager, machine, wakeLock, host, handler, params,
                          mKeyguardUpdateMonitor, dozeLog);
    }
}
