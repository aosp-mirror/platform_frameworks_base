/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.devicestate;

import static android.hardware.devicestate.DeviceStateManager.MAXIMUM_DEVICE_STATE;
import static android.hardware.devicestate.DeviceStateManager.MINIMUM_DEVICE_STATE;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.hardware.devicestate.DeviceStateManager;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A state of the device defined by the {@link DeviceStateProvider} and managed by the
 * {@link DeviceStateManagerService}.
 * <p>
 * Device state is an abstract concept that allows mapping the current state of the device to the
 * state of the system. This is useful for variable-state devices, like foldable or rollable
 * devices, that can be configured by users into differing hardware states, which each may have a
 * different expected use case.
 *
 * @see DeviceStateProvider
 * @see DeviceStateManagerService
 */
public final class DeviceState {
    /**
     * Flag that indicates override requests should be cancelled when this device state becomes the
     * base device state.
     */
    public static final int FLAG_CANCEL_OVERRIDE_REQUESTS = 1 << 0;

    /**
     * Flag that indicates this device state is inaccessible for applications to be placed in. This
     * could be a device-state where the {@link DEFAULT_DISPLAY} is not enabled.
     */
    public static final int FLAG_APP_INACCESSIBLE = 1 << 1;

    /**
     * Some device states can be both entered through a physical configuration as well as emulation
     * through {@link DeviceStateManager#requestState}, while some states can only be entered
     * through emulation and have no physical configuration to match.
     *
     * This flag indicates that the corresponding state can only be entered through emulation.
     */
    public static final int FLAG_EMULATED_ONLY = 1 << 2;

    /**
     * This flag indicates that the corresponding state should be automatically canceled when the
     * requesting app is no longer on top. The app is considered not on top when (1) the top
     * activity in the system is from a different app, (2) the device is in sleep mode, or
     * (3) the keyguard shows up.
     */
    public static final int FLAG_CANCEL_WHEN_REQUESTER_NOT_ON_TOP = 1 << 3;

    /**
     * This flag indicates that the corresponding state should be disabled when the device is
     * overheating and reaching the critical status.
     */
    public static final int FLAG_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL = 1 << 4;

    /**
     * This flag indicates that the corresponding state should be disabled when power save mode
     * is enabled.
     */
    public static final int FLAG_UNSUPPORTED_WHEN_POWER_SAVE_MODE = 1 << 5;

    /** @hide */
    @IntDef(prefix = {"FLAG_"}, flag = true, value = {
            FLAG_CANCEL_OVERRIDE_REQUESTS,
            FLAG_APP_INACCESSIBLE,
            FLAG_EMULATED_ONLY,
            FLAG_CANCEL_WHEN_REQUESTER_NOT_ON_TOP,
            FLAG_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL,
            FLAG_UNSUPPORTED_WHEN_POWER_SAVE_MODE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeviceStateFlags {}

    /** Unique identifier for the device state. */
    @IntRange(from = MINIMUM_DEVICE_STATE, to = MAXIMUM_DEVICE_STATE)
    private final int mIdentifier;

    /** String description of the device state. */
    @NonNull
    private final String mName;

    @DeviceStateFlags
    private final int mFlags;

    public DeviceState(
            @IntRange(from = MINIMUM_DEVICE_STATE, to = MAXIMUM_DEVICE_STATE) int identifier,
            @NonNull String name,
            @DeviceStateFlags int flags) {
        Preconditions.checkArgumentInRange(identifier, MINIMUM_DEVICE_STATE, MAXIMUM_DEVICE_STATE,
                "identifier");

        mIdentifier = identifier;
        mName = name;
        mFlags = flags;
    }

    /** Returns the unique identifier for the device state. */
    @IntRange(from = MINIMUM_DEVICE_STATE, to = MAXIMUM_DEVICE_STATE)
    public int getIdentifier() {
        return mIdentifier;
    }

    /** Returns a string description of the device state. */
    @NonNull
    public String getName() {
        return mName;
    }

    @DeviceStateFlags
    public int getFlags() {
        return mFlags;
    }

    @Override
    public String toString() {
        return "DeviceState{" + "identifier=" + mIdentifier + ", name='" + mName + '\''
                + ", app_accessible=" + !hasFlag(FLAG_APP_INACCESSIBLE)
                + ", cancel_when_requester_not_on_top="
                + hasFlag(FLAG_CANCEL_WHEN_REQUESTER_NOT_ON_TOP)
                + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeviceState that = (DeviceState) o;
        return mIdentifier == that.mIdentifier
                && Objects.equals(mName, that.mName)
                && mFlags == that.mFlags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIdentifier, mName, mFlags);
    }

    /** Checks if a specific flag is set
     */
    public boolean hasFlag(int flagToCheckFor) {
        return (mFlags & flagToCheckFor) == flagToCheckFor;
    }
}
