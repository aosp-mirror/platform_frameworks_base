/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.hardware.devicestate.DeviceState.FLAG_CANCEL_OVERRIDE_REQUESTS;
import static android.hardware.devicestate.DeviceState.FLAG_CANCEL_WHEN_REQUESTER_NOT_ON_TOP;
import static android.hardware.devicestate.DeviceState.FLAG_EMULATED_ONLY;
import static android.hardware.devicestate.DeviceState.FLAG_UNSUPPORTED_WHEN_POWER_SAVE_MODE;
import static android.hardware.devicestate.DeviceState.FLAG_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL;

import static com.android.server.policy.BookStyleStateTransitions.DEFAULT_STATE_TRANSITIONS;
import static com.android.server.policy.FoldableDeviceStateProvider.DeviceStateConfiguration.createConfig;
import static com.android.server.policy.FoldableDeviceStateProvider.DeviceStateConfiguration.createTentModeClosedState;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;

import com.android.server.devicestate.DeviceStatePolicy;
import com.android.server.devicestate.DeviceStateProvider;
import com.android.server.policy.FoldableDeviceStateProvider.DeviceStateConfiguration;
import com.android.server.policy.feature.flags.FeatureFlags;

import java.io.PrintWriter;
import java.util.function.Predicate;

/**
 * Device state policy for a foldable device with two screens in a book style, where the hinge is
 * located on the left side of the device when in folded posture.
 * The policy supports tent/wedge mode: a mode when the device keeps the outer display on
 * until reaching certain conditions like hinge angle threshold.
 *
 * Contains configuration for {@link FoldableDeviceStateProvider}.
 */
public class BookStyleDeviceStatePolicy extends DeviceStatePolicy implements
        BookStyleClosedStatePredicate.ClosedStateUpdatesListener {

    private static final int DEVICE_STATE_CLOSED = 0;
    private static final int DEVICE_STATE_HALF_OPENED = 1;
    private static final int DEVICE_STATE_OPENED = 2;
    private static final int DEVICE_STATE_REAR_DISPLAY_STATE = 3;
    private static final int DEVICE_STATE_CONCURRENT_INNER_DEFAULT = 4;

    private static final int TENT_MODE_SWITCH_ANGLE_DEGREES = 90;
    private static final int TABLE_TOP_MODE_SWITCH_ANGLE_DEGREES = 125;
    private static final int MIN_CLOSED_ANGLE_DEGREES = 0;
    private static final int MAX_CLOSED_ANGLE_DEGREES = 5;

    private final FoldableDeviceStateProvider mProvider;

    private final boolean mIsDualDisplayBlockingEnabled;
    private final boolean mEnablePostureBasedClosedState;
    private static final Predicate<FoldableDeviceStateProvider> ALLOWED = p -> true;
    private static final Predicate<FoldableDeviceStateProvider> NOT_ALLOWED = p -> false;

    /**
     * Creates TentModeDeviceStatePolicy
     *
     * @param context           Android context
     * @param hingeAngleSensor  hinge angle sensor that will be used to switch between states
     * @param hallSensor        hall sensor that will be used to switch between states
     * @param closeAngleDegrees if non-zero, this angle will be used as a threshold to switch
     *                          between folded and unfolded modes, otherwise when folding the
     *                          display switch will happen at 0 degrees
     */
    public BookStyleDeviceStatePolicy(@NonNull FeatureFlags featureFlags, @NonNull Context context,
            @NonNull Sensor hingeAngleSensor, @NonNull Sensor hallSensor,
            @Nullable Sensor leftAccelerometerSensor, @Nullable Sensor rightAccelerometerSensor,
            Integer closeAngleDegrees) {
        super(context);

        final SensorManager sensorManager = mContext.getSystemService(SensorManager.class);
        final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);

        mEnablePostureBasedClosedState = featureFlags.enableFoldablesPostureBasedClosedState();
        mIsDualDisplayBlockingEnabled = featureFlags.enableDualDisplayBlocking();

        final DeviceStateConfiguration[] configuration = createConfiguration(
                leftAccelerometerSensor, rightAccelerometerSensor, closeAngleDegrees);

        mProvider = new FoldableDeviceStateProvider(mContext, sensorManager,
                hingeAngleSensor, hallSensor, displayManager, configuration);
    }

    private DeviceStateConfiguration[] createConfiguration(@Nullable Sensor leftAccelerometerSensor,
            @Nullable Sensor rightAccelerometerSensor, Integer closeAngleDegrees) {
        return new DeviceStateConfiguration[]{
                createClosedConfiguration(leftAccelerometerSensor, rightAccelerometerSensor,
                        closeAngleDegrees),
                createConfig(DEVICE_STATE_HALF_OPENED,
                        /* name= */ "HALF_OPENED",
                        /* activeStatePredicate= */ (provider) -> {
                            final float hingeAngle = provider.getHingeAngle();
                            return hingeAngle >= MAX_CLOSED_ANGLE_DEGREES
                                    && hingeAngle <= TABLE_TOP_MODE_SWITCH_ANGLE_DEGREES;
                        }),
                createConfig(DEVICE_STATE_OPENED,
                        /* name= */ "OPENED",
                        /* activeStatePredicate= */ ALLOWED),
                createConfig(DEVICE_STATE_REAR_DISPLAY_STATE,
                        /* name= */ "REAR_DISPLAY_STATE",
                        /* flags= */ FLAG_EMULATED_ONLY,
                        /* activeStatePredicate= */ NOT_ALLOWED),
                createConfig(DEVICE_STATE_CONCURRENT_INNER_DEFAULT,
                        /* name= */ "CONCURRENT_INNER_DEFAULT",
                        /* flags= */ FLAG_EMULATED_ONLY | FLAG_CANCEL_WHEN_REQUESTER_NOT_ON_TOP
                                | FLAG_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL
                                | FLAG_UNSUPPORTED_WHEN_POWER_SAVE_MODE,
                        /* activeStatePredicate= */ NOT_ALLOWED,
                        /* availabilityPredicate= */
                        provider -> !mIsDualDisplayBlockingEnabled
                                || provider.hasNoConnectedExternalDisplay())
        };
    }

    private DeviceStateConfiguration createClosedConfiguration(
            @Nullable Sensor leftAccelerometerSensor, @Nullable Sensor rightAccelerometerSensor,
            @Nullable Integer closeAngleDegrees) {
        if (closeAngleDegrees != null) {
            // Switch displays at closeAngleDegrees in both ways (folding and unfolding)
            return createConfig(
                    DEVICE_STATE_CLOSED,
                    /* name= */ "CLOSED",
                    /* flags= */ FLAG_CANCEL_OVERRIDE_REQUESTS,
                    /* activeStatePredicate= */ (provider) -> {
                        final float hingeAngle = provider.getHingeAngle();
                        return hingeAngle <= closeAngleDegrees;
                    }
            );
        }

        if (mEnablePostureBasedClosedState) {
            // Use smart closed state predicate that will use different switch angles
            // based on the device posture (e.g. wedge mode, tent mode, reverse wedge mode)
            return createConfig(
                    DEVICE_STATE_CLOSED,
                    /* name= */ "CLOSED",
                    /* flags= */ FLAG_CANCEL_OVERRIDE_REQUESTS,
                    /* activeStatePredicate= */ new BookStyleClosedStatePredicate(mContext,
                            this, leftAccelerometerSensor, rightAccelerometerSensor,
                            DEFAULT_STATE_TRANSITIONS)
            );
        }

        // Switch to the outer display only at 0 degrees but use TENT_MODE_SWITCH_ANGLE_DEGREES
        // angle when switching to the inner display
        return createTentModeClosedState(DEVICE_STATE_CLOSED,
                /* name= */ "CLOSED",
                /* flags= */ FLAG_CANCEL_OVERRIDE_REQUESTS,
                MIN_CLOSED_ANGLE_DEGREES,
                MAX_CLOSED_ANGLE_DEGREES,
                TENT_MODE_SWITCH_ANGLE_DEGREES);
    }

    @Override
    public void onClosedStateUpdated() {
        mProvider.notifyDeviceStateChangedIfNeeded();
    }

    @Override
    public DeviceStateProvider getDeviceStateProvider() {
        return mProvider;
    }

    @Override
    public void configureDeviceForState(int state, @NonNull Runnable onComplete) {
        onComplete.run();
    }

    @Override
    public void dump(@NonNull PrintWriter writer, @Nullable String[] args) {
        mProvider.dump(writer, args);
    }
}
