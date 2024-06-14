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

package com.android.systemui.doze.dagger;

import android.content.Context;
import android.hardware.Sensor;

import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.doze.DozeAuthRemover;
import com.android.systemui.doze.DozeBrightnessHostForwarder;
import com.android.systemui.doze.DozeDockHandler;
import com.android.systemui.doze.DozeFalsingManagerAdapter;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.doze.DozeMachine;
import com.android.systemui.doze.DozePauser;
import com.android.systemui.doze.DozeScreenBrightness;
import com.android.systemui.doze.DozeScreenState;
import com.android.systemui.doze.DozeScreenStatePreventingAdapter;
import com.android.systemui.doze.DozeSensors;
import com.android.systemui.doze.DozeSuppressor;
import com.android.systemui.doze.DozeSuspendScreenStatePreventingAdapter;
import com.android.systemui.doze.DozeTransitionListener;
import com.android.systemui.doze.DozeTriggers;
import com.android.systemui.doze.DozeUi;
import com.android.systemui.doze.DozeWallpaperState;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.wakelock.DelayedWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

import dagger.Module;
import dagger.Provides;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/** Dagger module for use with {@link com.android.systemui.doze.dagger.DozeComponent}. */
@Module
public abstract class DozeModule {
    @Provides
    @DozeScope
    @WrappedService
    static DozeMachine.Service providesWrappedService(DozeMachine.Service dozeMachineService,
            DozeHost dozeHost, DozeParameters dozeParameters, @UiBackground Executor bgExecutor) {
        DozeMachine.Service wrappedService = dozeMachineService;
        wrappedService = new DozeBrightnessHostForwarder(wrappedService, dozeHost, bgExecutor);
        wrappedService = DozeScreenStatePreventingAdapter.wrapIfNeeded(
                wrappedService, dozeParameters, bgExecutor);
        wrappedService = DozeSuspendScreenStatePreventingAdapter.wrapIfNeeded(
                wrappedService, dozeParameters, bgExecutor);

        return wrappedService;
    }

    @Provides
    @DozeScope
    static WakeLock providesDozeWakeLock(DelayedWakeLock.Factory delayedWakeLockFactory) {
        return delayedWakeLockFactory.create("Doze");
    }

    @Provides
    static DozeMachine.Part[] providesDozeMachineParts(DozePauser dozePauser,
            DozeFalsingManagerAdapter dozeFalsingManagerAdapter, DozeTriggers dozeTriggers,
            DozeUi dozeUi, DozeScreenState dozeScreenState,
            DozeScreenBrightness dozeScreenBrightness, DozeWallpaperState dozeWallpaperState,
            DozeDockHandler dozeDockHandler, DozeAuthRemover dozeAuthRemover,
            DozeSuppressor dozeSuppressor, DozeTransitionListener dozeTransitionListener) {
        return new DozeMachine.Part[]{
                dozePauser,
                dozeFalsingManagerAdapter,
                dozeTriggers,
                dozeUi,
                dozeScreenState,
                dozeScreenBrightness,
                dozeWallpaperState,
                dozeDockHandler,
                dozeAuthRemover,
                dozeSuppressor,
                dozeTransitionListener
        };
    }

    @Provides
    @BrightnessSensor
    static Optional<Sensor>[] providesBrightnessSensors(
            AsyncSensorManager sensorManager,
            Context context,
            DozeParameters dozeParameters) {
        String[] sensorNames = dozeParameters.brightnessNames();
        if (sensorNames.length == 0 || sensorNames == null) {
            // if no brightness names are specified, just use the brightness sensor type
            return new Optional[]{
                    Optional.ofNullable(DozeSensors.findSensor(
                            sensorManager,
                            context.getString(R.string.doze_brightness_sensor_type),
                            null
                    ))
            };
        }

        // length and index of brightnessMap correspond to DevicePostureController.DevicePostureInt:
        final Optional<Sensor>[] brightnessSensorMap =
                new Optional[DevicePostureController.SUPPORTED_POSTURES_SIZE];
        Arrays.fill(brightnessSensorMap, Optional.empty());

        // Map of sensorName => Sensor, so we reuse the same sensor if it's the same between
        // postures
        Map<String, Optional<Sensor>> nameToSensorMap = new HashMap<>();
        for (int i = 0; i < sensorNames.length; i++) {
            final String sensorName = sensorNames[i];
            if (!nameToSensorMap.containsKey(sensorName)) {
                nameToSensorMap.put(sensorName,
                        Optional.ofNullable(
                                DozeSensors.findSensor(
                                        sensorManager,
                                        context.getString(R.string.doze_brightness_sensor_type),
                                        sensorNames[i]
                                )));
            }
            brightnessSensorMap[i] = nameToSensorMap.get(sensorName);
        }
        return brightnessSensorMap;
    }
}
