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
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Handler;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.dock.DockManager;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.AsyncSensorManager;
import com.android.systemui.util.wakelock.DelayedWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

public class DozeFactory {

    public DozeFactory() {
    }

    /** Creates a DozeMachine with its parts for {@code dozeService}. */
    public DozeMachine assembleMachine(DozeService dozeService) {
        Context context = dozeService;
        SensorManager sensorManager = Dependency.get(AsyncSensorManager.class);
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        DockManager dockManager = SysUiServiceProvider.getComponent(context, DockManager.class);

        DozeHost host = getHost(dozeService);
        AmbientDisplayConfiguration config = new AmbientDisplayConfiguration(context);
        DozeParameters params = DozeParameters.getInstance(context);
        Handler handler = new Handler();
        WakeLock wakeLock = new DelayedWakeLock(handler,
                WakeLock.createPartial(context, "Doze"));

        DozeMachine.Service wrappedService = dozeService;
        wrappedService = new DozeBrightnessHostForwarder(wrappedService, host);
        wrappedService = DozeScreenStatePreventingAdapter.wrapIfNeeded(wrappedService, params);
        wrappedService = DozeSuspendScreenStatePreventingAdapter.wrapIfNeeded(wrappedService,
                params);

        DozeMachine machine = new DozeMachine(wrappedService, config, wakeLock);
        machine.setParts(new DozeMachine.Part[]{
                new DozePauser(handler, machine, alarmManager, params.getPolicy()),
                new DozeFalsingManagerAdapter(FalsingManager.getInstance(context)),
                createDozeTriggers(context, sensorManager, host, alarmManager, config, params,
                        handler, wakeLock, machine, dockManager),
                createDozeUi(context, host, wakeLock, machine, handler, alarmManager, params),
                new DozeScreenState(wrappedService, handler, params, wakeLock),
                createDozeScreenBrightness(context, wrappedService, sensorManager, host, params,
                        handler),
                new DozeWallpaperState(context),
                new DozeDockHandler(context, machine, host, config, handler, dockManager)
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

    private DozeTriggers createDozeTriggers(Context context, SensorManager sensorManager,
            DozeHost host, AlarmManager alarmManager, AmbientDisplayConfiguration config,
            DozeParameters params, Handler handler, WakeLock wakeLock, DozeMachine machine,
            DockManager dockManager) {
        boolean allowPulseTriggers = true;
        return new DozeTriggers(context, machine, host, alarmManager, config, params,
                sensorManager, handler, wakeLock, allowPulseTriggers, dockManager);
    }

    private DozeMachine.Part createDozeUi(Context context, DozeHost host, WakeLock wakeLock,
            DozeMachine machine, Handler handler, AlarmManager alarmManager,
            DozeParameters params) {
        return new DozeUi(context, alarmManager, machine, wakeLock, host, handler, params,
                KeyguardUpdateMonitor.getInstance(context));
    }

    public static DozeHost getHost(DozeService service) {
        Application appCandidate = service.getApplication();
        final SystemUIApplication app = (SystemUIApplication) appCandidate;
        return app.getComponent(DozeHost.class);
    }
}
