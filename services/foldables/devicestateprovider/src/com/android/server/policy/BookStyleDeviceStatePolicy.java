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

import static android.hardware.devicestate.DeviceState.PROPERTY_EMULATED_ONLY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT;
import static android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_REAR_DISPLAY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_REAR_DISPLAY_OUTER_DEFAULT;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN;
import static android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN;
import static android.hardware.devicestate.DeviceState.PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST;
import static android.hardware.devicestate.DeviceState.PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS;
import static android.hardware.devicestate.DeviceState.PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP;
import static android.hardware.devicestate.DeviceState.PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE;
import static android.hardware.devicestate.DeviceState.PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL;
import static android.hardware.devicestate.DeviceState.PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP;
import static android.hardware.devicestate.DeviceState.PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE;

import static com.android.server.policy.BookStyleStateTransitions.DEFAULT_STATE_TRANSITIONS;
import static com.android.server.policy.FoldableDeviceStateProvider.DeviceStatePredicateWrapper.createConfig;
import static com.android.server.policy.FoldableDeviceStateProvider.DeviceStatePredicateWrapper.createTentModeClosedState;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.devicestate.DeviceState;
import android.hardware.display.DisplayManager;

import com.android.server.devicestate.DeviceStatePolicy;
import com.android.server.devicestate.DeviceStateProvider;
import com.android.server.policy.FoldableDeviceStateProvider.DeviceStatePredicateWrapper;
import com.android.server.policy.feature.flags.FeatureFlags;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final int DEVICE_STATE_REAR_DISPLAY = 3;
    private static final int DEVICE_STATE_CONCURRENT_INNER_DEFAULT = 4;
    private static final int DEVICE_STATE_REAR_DISPLAY_OUTER_DEFAULT = 5;
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
        if (mEnablePostureBasedClosedState) {
            // This configuration doesn't require listening to hall sensor, it solely relies
            // on the fused hinge angle sensor
            hallSensor = null;
        }

        mIsDualDisplayBlockingEnabled = featureFlags.enableDualDisplayBlocking();

        final DeviceStatePredicateWrapper[] configuration = createConfiguration(
                leftAccelerometerSensor, rightAccelerometerSensor, closeAngleDegrees);

        mProvider = new FoldableDeviceStateProvider(mContext, sensorManager, hingeAngleSensor,
                hallSensor, displayManager, configuration);
    }

    private DeviceStatePredicateWrapper[] createConfiguration(
            @Nullable Sensor leftAccelerometerSensor, @Nullable Sensor rightAccelerometerSensor,
            Integer closeAngleDegrees) {
        return new DeviceStatePredicateWrapper[]{
                createClosedConfiguration(leftAccelerometerSensor, rightAccelerometerSensor,
                        closeAngleDegrees),
                createConfig(getHalfOpenedDeviceState(), /* activeStatePredicate= */
                        (provider) -> {
                            final float hingeAngle = provider.getHingeAngle();
                            return hingeAngle >= MAX_CLOSED_ANGLE_DEGREES
                                    && hingeAngle <= TABLE_TOP_MODE_SWITCH_ANGLE_DEGREES;
                        }),
                createConfig(getOpenedDeviceState(),
                        /* activeStatePredicate= */ ALLOWED),
                createConfig(getRearDisplayDeviceState(),
                        /* activeStatePredicate= */ NOT_ALLOWED),
                createConfig(getDualDisplayDeviceState(),
                        /* activeStatePredicate= */ NOT_ALLOWED,
                        /* availabilityPredicate= */ provider -> !mIsDualDisplayBlockingEnabled
                                || provider.hasNoConnectedExternalDisplay()),
                createConfig(getRearDisplayOuterDefaultState(),
                        /* activeStatePredicate= */ NOT_ALLOWED)
        };
    }

    private DeviceStatePredicateWrapper createClosedConfiguration(
            @Nullable Sensor leftAccelerometerSensor, @Nullable Sensor rightAccelerometerSensor,
            @Nullable Integer closeAngleDegrees) {

        if (closeAngleDegrees != null) {
            // Switch displays at closeAngleDegrees in both ways (folding and unfolding)
            return createConfig(getClosedDeviceState(), /* activeStatePredicate= */
                    (provider) -> {
                        final float hingeAngle = provider.getHingeAngle();
                        return hingeAngle <= closeAngleDegrees;
                    });
        }

        if (mEnablePostureBasedClosedState) {
            // Use smart closed state predicate that will use different switch angles
            // based on the device posture (e.g. wedge mode, tent mode, reverse wedge mode)
            return createConfig(getClosedDeviceState(), /* activeStatePredicate= */
                    new BookStyleClosedStatePredicate(mContext, this, leftAccelerometerSensor,
                            rightAccelerometerSensor, DEFAULT_STATE_TRANSITIONS));
        }

        // Switch to the outer display only at 0 degrees but use TENT_MODE_SWITCH_ANGLE_DEGREES
        // angle when switching to the inner display
        return createTentModeClosedState(getClosedDeviceState(),
                MIN_CLOSED_ANGLE_DEGREES, MAX_CLOSED_ANGLE_DEGREES, TENT_MODE_SWITCH_ANGLE_DEGREES);
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

    /** Returns the {@link DeviceState.Configuration} that represents the closed state. */
    @NonNull
    private DeviceState getClosedDeviceState() {
        Set<@DeviceState.SystemDeviceStateProperties Integer> systemProperties = new HashSet<>(
                List.of(PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS,
                        PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
                        PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP));

        Set<@DeviceState.PhysicalDeviceStateProperties Integer> physicalProperties = new HashSet<>(
                List.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED));

        return new DeviceState(new  DeviceState.Configuration.Builder(DEVICE_STATE_CLOSED, "CLOSED")
                .setSystemProperties(systemProperties)
                .setPhysicalProperties(physicalProperties)
                .build());
    }

    /** Returns the {@link DeviceState.Configuration} that represents the half_opened state. */
    @NonNull
    private DeviceState getHalfOpenedDeviceState() {
        Set<@DeviceState.SystemDeviceStateProperties Integer> systemProperties = new HashSet<>(
                List.of(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY,
                        PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE));

        Set<@DeviceState.PhysicalDeviceStateProperties Integer> physicalProperties = new HashSet<>(
                List.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN));

        return new DeviceState(new DeviceState.Configuration.Builder(DEVICE_STATE_HALF_OPENED,
                "HALF_OPENED")
                .setSystemProperties(systemProperties)
                .setPhysicalProperties(physicalProperties)
                .build());
    }

    /** Returns the {@link DeviceState.Configuration} that represents the opened state */
    @NonNull
    private DeviceState getOpenedDeviceState() {
        Set<@DeviceState.SystemDeviceStateProperties Integer> systemProperties = new HashSet<>(
                List.of(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY,
                        PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE));
        Set<@DeviceState.PhysicalDeviceStateProperties Integer> physicalProperties = new HashSet<>(
                List.of(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN));

        return new DeviceState(new DeviceState.Configuration.Builder(DEVICE_STATE_OPENED, "OPENED")
                .setSystemProperties(systemProperties)
                .setPhysicalProperties(physicalProperties)
                .build());
    }

    /** Returns the {@link DeviceState.Configuration} that represents the rear display state. */
    @NonNull
    private DeviceState getRearDisplayDeviceState() {
        Set<@DeviceState.SystemDeviceStateProperties Integer> systemProperties = new HashSet<>(
                List.of(PROPERTY_EMULATED_ONLY,
                        PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
                        PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST, PROPERTY_FEATURE_REAR_DISPLAY));

        return new DeviceState(new DeviceState.Configuration.Builder(DEVICE_STATE_REAR_DISPLAY,
                "REAR_DISPLAY_STATE")
                .setSystemProperties(systemProperties)
                .build());
    }

    /** Returns the {@link DeviceState.Configuration} that represents the dual display state. */
    @NonNull
    private DeviceState getDualDisplayDeviceState() {
        Set<@DeviceState.SystemDeviceStateProperties Integer> systemProperties = new HashSet<>(
                List.of(PROPERTY_EMULATED_ONLY, PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP,
                        PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST,
                        PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL,
                        PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE,
                        PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY,
                        PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT));

        return new DeviceState(new DeviceState.Configuration.Builder(
                DEVICE_STATE_CONCURRENT_INNER_DEFAULT, "CONCURRENT_INNER_DEFAULT")
                .setSystemProperties(systemProperties)
                .build());
    }

    /**
     * Returns the {link DeviceState.Configuration} that represents the new rear display state
     * where the inner display is also enabled, showing a system affordance to exit the state.
     */
    @NonNull
    private DeviceState getRearDisplayOuterDefaultState() {
        Set<@DeviceState.SystemDeviceStateProperties Integer> systemProperties = new HashSet<>(
                List.of(PROPERTY_EMULATED_ONLY,
                        PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
                        PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST,
                        PROPERTY_FEATURE_REAR_DISPLAY,
                        PROPERTY_FEATURE_REAR_DISPLAY_OUTER_DEFAULT));

        return new DeviceState(new DeviceState.Configuration.Builder(
                DEVICE_STATE_REAR_DISPLAY_OUTER_DEFAULT,
                "REAR_DISPLAY_OUTER_DEFAULT")
                .setSystemProperties(systemProperties)
                .build());
    }
}
