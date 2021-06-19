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
import android.os.Handler;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
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
import com.android.systemui.doze.DozeSuspendScreenStatePreventingAdapter;
import com.android.systemui.doze.DozeTriggers;
import com.android.systemui.doze.DozeUi;
import com.android.systemui.doze.DozeWallpaperState;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.wakelock.DelayedWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

import java.util.Optional;

import dagger.Module;
import dagger.Provides;

/** Dagger module for use with {@link com.android.systemui.doze.dagger.DozeComponent}. */
@Module
public abstract class DozeModule {
    @Provides
    @DozeScope
    @WrappedService
    static DozeMachine.Service providesWrappedService(DozeMachine.Service dozeMachineService,
            DozeHost dozeHost, DozeParameters dozeParameters) {
        DozeMachine.Service wrappedService = dozeMachineService;
        wrappedService = new DozeBrightnessHostForwarder(wrappedService, dozeHost);
        wrappedService = DozeScreenStatePreventingAdapter.wrapIfNeeded(
                wrappedService, dozeParameters);
        wrappedService = DozeSuspendScreenStatePreventingAdapter.wrapIfNeeded(
                wrappedService, dozeParameters);

        return wrappedService;
    }

    @Provides
    @DozeScope
    static WakeLock providesDozeWakeLock(DelayedWakeLock.Builder delayedWakeLockBuilder,
            @Main Handler handler) {
        return delayedWakeLockBuilder.setHandler(handler).setTag("Doze").build();
    }

    @Provides
    static DozeMachine.Part[] providesDozeMachinePartes(DozePauser dozePauser,
            DozeFalsingManagerAdapter dozeFalsingManagerAdapter, DozeTriggers dozeTriggers,
            DozeUi dozeUi, DozeScreenState dozeScreenState,
            DozeScreenBrightness dozeScreenBrightness, DozeWallpaperState dozeWallpaperState,
            DozeDockHandler dozeDockHandler, DozeAuthRemover dozeAuthRemover) {
        return new DozeMachine.Part[]{
                dozePauser,
                dozeFalsingManagerAdapter,
                dozeTriggers,
                dozeUi,
                dozeScreenState,
                dozeScreenBrightness,
                dozeWallpaperState,
                dozeDockHandler,
                dozeAuthRemover
        };
    }

    @Provides
    @BrightnessSensor
    static Optional<Sensor> providesBrightnessSensor(
            AsyncSensorManager sensorManager, Context context) {
        return Optional.ofNullable(DozeSensors.findSensorWithType(sensorManager,
                context.getString(R.string.doze_brightness_sensor_type)));
    }
}
