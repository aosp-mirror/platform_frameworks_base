/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.policy;

import static com.android.server.devicestate.DeviceState.FLAG_CANCEL_OVERRIDE_REQUESTS;
import static com.android.server.devicestate.DeviceState.FLAG_EMULATED_ONLY;
import static com.android.server.policy.FoldableDeviceStateProvider.DeviceStateConfiguration.createConfig;
import static com.android.server.policy.FoldableDeviceStateProvider.DeviceStateConfiguration.createTentModeClosedState;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;

import com.android.internal.util.CollectionUtils;
import com.android.server.devicestate.DeviceStatePolicy;
import com.android.server.devicestate.DeviceStateProvider;
import com.android.server.policy.FoldableDeviceStateProvider.DeviceStateConfiguration;

import java.util.List;
import java.util.Objects;

/**
 * Device state policy for a foldable device that supports tent mode: a mode when the device
 * keeps the outer display on until reaching a certain hinge angle threshold.
 *
 * Contains configuration for {@link FoldableDeviceStateProvider}.
 */
public class TentModeDeviceStatePolicy extends DeviceStatePolicy {

    private static final int DEVICE_STATE_CLOSED = 0;
    private static final int DEVICE_STATE_HALF_OPENED = 1;
    private static final int DEVICE_STATE_OPENED = 2;
    private static final int DEVICE_STATE_REAR_DISPLAY_STATE = 3;

    private static final int TENT_MODE_SWITCH_ANGLE_DEGREES = 90;
    private static final int TABLE_TOP_MODE_SWITCH_ANGLE_DEGREES = 125;
    private static final int MIN_CLOSED_ANGLE_DEGREES = 0;
    private static final int MAX_CLOSED_ANGLE_DEGREES = 5;

    private final DeviceStateProvider mProvider;

    protected TentModeDeviceStatePolicy(@NonNull Context context) {
        super(context);

        final SensorManager sensorManager = mContext.getSystemService(SensorManager.class);
        final Sensor hingeAngleSensor =
                sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, /* wakeUp= */ true);

        final List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        final Sensor hallSensor = CollectionUtils.find(sensors,
                (sensor) -> Objects.equals(sensor.getStringType(),
                        "com.google.sensor.hall_effect"));

        mProvider = new FoldableDeviceStateProvider(sensorManager, hingeAngleSensor, hallSensor,
                createConfiguration());
    }

    private DeviceStateConfiguration[] createConfiguration() {
        return new DeviceStateConfiguration[]{
                createTentModeClosedState(DEVICE_STATE_CLOSED,
                        /* name= */ "CLOSED",
                        /* flags= */ FLAG_CANCEL_OVERRIDE_REQUESTS,
                        MIN_CLOSED_ANGLE_DEGREES,
                        MAX_CLOSED_ANGLE_DEGREES,
                        TENT_MODE_SWITCH_ANGLE_DEGREES),
                createConfig(DEVICE_STATE_HALF_OPENED,
                        /* name= */ "HALF_OPENED",
                        (provider) -> {
                            final float hingeAngle = provider.getHingeAngle();
                            return hingeAngle >= MAX_CLOSED_ANGLE_DEGREES
                                    && hingeAngle <= TABLE_TOP_MODE_SWITCH_ANGLE_DEGREES;
                        }),
                createConfig(DEVICE_STATE_OPENED,
                        /* name= */ "OPENED",
                        (provider) -> true),
                createConfig(DEVICE_STATE_REAR_DISPLAY_STATE,
                        /* name= */ "REAR_DISPLAY_STATE",
                        /* flags= */ FLAG_EMULATED_ONLY,
                        (provider) -> false)
        };
    }

    @Override
    public DeviceStateProvider getDeviceStateProvider() {
        return mProvider;
    }

    @Override
    public void configureDeviceForState(int state, @NonNull Runnable onComplete) {
        onComplete.run();
    }

    public static class Provider implements DeviceStatePolicy.Provider {

        @Override
        public DeviceStatePolicy instantiate(@NonNull Context context) {
            return new TentModeDeviceStatePolicy(context);
        }
    }
}
