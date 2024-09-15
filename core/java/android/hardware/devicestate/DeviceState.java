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

package android.hardware.devicestate;

import static android.hardware.devicestate.DeviceStateManager.MAXIMUM_DEVICE_STATE_IDENTIFIER;
import static android.hardware.devicestate.DeviceStateManager.MINIMUM_DEVICE_STATE_IDENTIFIER;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * A state of the device managed by {@link DeviceStateManager}.
 * <p>
 * Device state is an abstract concept that allows mapping the current state of the device to the
 * state of the system. This is useful for variable-state devices, like foldable or rollable
 * devices, that can be configured by users into differing hardware states, which each may have a
 * different expected use case.
 *
 * @hide
 * @see DeviceStateManager
 */
@SystemApi
@FlaggedApi(android.hardware.devicestate.feature.flags.Flags.FLAG_DEVICE_STATE_PROPERTY_API)
public final class DeviceState {
    /**
     * Property that indicates that a fold-in style foldable device is currently in a fully closed
     * configuration.
     */
    public static final int PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED = 1;

    /**
     * Property that indicates that a fold-in style foldable device is currently in a half-opened
     * configuration. This signifies that the device's hinge is positioned somewhere around 90
     * degrees. Checking for display configuration properties as well can provide information
     * on which display is currently active.
     */
    public static final int PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN = 2;

    /**
     * Property that indicates that a fold-in style foldable device is currently in a fully open
     * configuration.
     */
    public static final int PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN = 3;

    /**
     * Property that indicates override requests should be cancelled when the device is physically
     * put into this state.
     * @hide
     */
    public static final int PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS = 4;

    /**
     * This property indicates that the corresponding state should be automatically canceled when
     * the requesting app is no longer on top. The app is considered not on top when (1) the top
     * activity in the system is from a different app, (2) the device is in sleep mode, or
     * (3) the keyguard shows up.
     * @hide
     */
    public static final int PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP = 5;

    /**
     * This property indicates that the corresponding state should be disabled when the device is
     * overheating and reaching the critical status.
     * @hide
     */
    public static final int PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL = 6;

    /**
     * This property indicates that the corresponding state should be disabled when power save mode
     * is enabled.
     * @hide
     */
    public static final int PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE = 7;

    /**
     * This property denotes that this state is available for applications to request and the system
     * server should deny any request that comes from a process that does not hold the
     * CONTROL_DEVICE_STATE permission if it is requesting a state that does not have this property
     * on it.
     * @hide
     */
    @TestApi
    public static final int PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST = 8;

    /**
     * Property that indicates this device state is inaccessible for applications to be made
     * visible to the user. This could be a device-state where the {@link Display#DEFAULT_DISPLAY}
     * is not enabled.
     * @hide
     */
    public static final int PROPERTY_APP_INACCESSIBLE = 9;

    /**
     * This property indidcates that this state can only be entered through emulation and has no
     * physical configuration to match.
     */
    public static final int PROPERTY_EMULATED_ONLY = 10;

    /**
     * Property that indicates that the outer display area of a foldable device is currently the
     * primary display area.
     *
     * Note: This does not necessarily mean that the outer display area is the
     * {@link Display#DEFAULT_DISPLAY}.
     */
    public static final int PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY = 11;

    /**
     * Property that indicates that the inner display area of a foldable device is currently the
     * primary display area.
     *
     * Note: This does not necessarily mean that the inner display area is the
     * {@link Display#DEFAULT_DISPLAY}.
     */
    public static final int PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY = 12;

    /**
     * Property that indicates that this device state will attempt to trigger the device to go to
     * sleep.
     */
    public static final int PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP = 13;

    /**
     * Property that indicates that this device state will attempt to trigger the device to wake up.
     */
    public static final int PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE = 14;

    /**
     * Property that indicates that an external display has been connected to the device. Specifics
     * around display mode or properties around the display should be gathered through
     * {@link android.hardware.display.DisplayManager}
     */
    public static final int PROPERTY_EXTENDED_DEVICE_STATE_EXTERNAL_DISPLAY = 15;
    /**
     * Property that indicates that this state corresponds to the device state for rear display
     * mode. This means that the active display is facing the same direction as the rear camera.
     */
    public static final int PROPERTY_FEATURE_REAR_DISPLAY = 16;

    /**
     * Property that indicates that this state corresponds to the device state where both displays
     * on a foldable are active, with the internal display being the default display.
     */
    public static final int PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT = 17;

    /** @hide */
    @IntDef(prefix = {"PROPERTY_"}, flag = false, value = {
            PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED,
            PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN,
            PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN,
            PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS,
            PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP,
            PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL,
            PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE,
            PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST,
            PROPERTY_APP_INACCESSIBLE,
            PROPERTY_EMULATED_ONLY,
            PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
            PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY,
            PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP,
            PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE,
            PROPERTY_EXTENDED_DEVICE_STATE_EXTERNAL_DISPLAY,
            PROPERTY_FEATURE_REAR_DISPLAY,
            PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT
    })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface DeviceStateProperties {}

    /** @hide */
    @IntDef(prefix = {"PROPERTY_"}, flag = false, value = {
            PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED,
            PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN,
            PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN
    })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface PhysicalDeviceStateProperties {}

    /** @hide */
    @IntDef(prefix = {"PROPERTY_"}, flag = false, value = {
            PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS,
            PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP,
            PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL,
            PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE,
            PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST,
            PROPERTY_APP_INACCESSIBLE,
            PROPERTY_EMULATED_ONLY,
            PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
            PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY,
            PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP,
            PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE,
            PROPERTY_EXTENDED_DEVICE_STATE_EXTERNAL_DISPLAY,
            PROPERTY_FEATURE_REAR_DISPLAY,
            PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT
    })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface SystemDeviceStateProperties {}

    @NonNull
    private final DeviceState.Configuration mDeviceStateConfiguration;

    /** @hide */
    @TestApi
    public DeviceState(@NonNull DeviceState.Configuration deviceStateConfiguration) {
        Objects.requireNonNull(deviceStateConfiguration, "Device StateConfiguration is null");
        mDeviceStateConfiguration = deviceStateConfiguration;
    }

    /** Returns the unique identifier for the device state. */
    @IntRange(from = MINIMUM_DEVICE_STATE_IDENTIFIER)
    public int getIdentifier() {
        return mDeviceStateConfiguration.getIdentifier();
    }

    /** Returns a string description of the device state. */
    @NonNull
    public String getName() {
        return mDeviceStateConfiguration.getName();
    }

    @Override
    public String toString() {
        return "DeviceState{" + "identifier=" + mDeviceStateConfiguration.getIdentifier()
                + ", name='" + mDeviceStateConfiguration.getName() + '\''
                + ", app_accessible=" + !mDeviceStateConfiguration.getSystemProperties().contains(
                PROPERTY_APP_INACCESSIBLE)
                + ", cancel_when_requester_not_on_top="
                + mDeviceStateConfiguration.getSystemProperties().contains(
                PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP)
                + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeviceState that = (DeviceState) o;
        return Objects.equals(mDeviceStateConfiguration, that.mDeviceStateConfiguration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceStateConfiguration);
    }

    /**
     * Checks if a specific property is set on this state
     */
    public boolean hasProperty(@DeviceStateProperties int propertyToCheckFor) {
        return mDeviceStateConfiguration.mSystemProperties.contains(propertyToCheckFor)
                || mDeviceStateConfiguration.mPhysicalProperties.contains(propertyToCheckFor);
    }

    /**
     * Checks if a list of properties are all set on this state
     */
    public boolean hasProperties(@NonNull @DeviceStateProperties int... properties) {
        for (int i = 0; i < properties.length; i++) {
            if (!hasProperty(properties[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the underlying {@link DeviceState.Configuration} object used to model the
     * device state.
     * @hide
     */
    public Configuration getConfiguration() {
        return mDeviceStateConfiguration;
    }

    /**
     * Detailed description of a {@link DeviceState} that includes separated sets of
     * {@link DeviceStateProperties} for properties that correspond to the state of the system when
     * the device is in this state, as well as physical properties that describe this state.
     *
     * Instantiation of this class should only be done by the system server, and clients of
     * {@link DeviceStateManager} will receive {@link DeviceState} objects.
     *
     * @see DeviceStateManager
     * @hide
     */
    @TestApi
    public static final class Configuration implements Parcelable {
        /** Unique identifier for the device state. */
        @IntRange(from = MINIMUM_DEVICE_STATE_IDENTIFIER, to = MAXIMUM_DEVICE_STATE_IDENTIFIER)
        private final int mIdentifier;

        /** String description of the device state. */
        @NonNull
        private final String mName;

        /** {@link ArraySet} of system properties that apply to this state. */
        @NonNull
        private final ArraySet<@SystemDeviceStateProperties Integer> mSystemProperties;

        /** {@link ArraySet} of physical device properties that apply to this state. */
        @NonNull
        private final ArraySet<@PhysicalDeviceStateProperties Integer> mPhysicalProperties;

        private Configuration(int identifier, @NonNull String name,
                @NonNull ArraySet<@SystemDeviceStateProperties Integer> systemProperties,
                @NonNull ArraySet<@PhysicalDeviceStateProperties Integer> physicalProperties) {
            mIdentifier = identifier;
            mName = name;
            mSystemProperties = systemProperties;
            mPhysicalProperties = physicalProperties;
        }

        /** Returns the unique identifier for the device state. */
        public int getIdentifier() {
            return mIdentifier;
        }

        /** Returns a string description of the device state. */
        @NonNull
        public String getName() {
            return mName;
        }

        /** Returns the {@link Set} of system properties that apply to this state. */
        @NonNull
        public Set<@SystemDeviceStateProperties Integer> getSystemProperties() {
            return mSystemProperties;
        }

        /** Returns the {@link Set} of physical device properties that apply to this state. */
        @NonNull
        public Set<@DeviceStateProperties Integer> getPhysicalProperties() {
            return mPhysicalProperties;
        }

        @Override
        public String toString() {
            return "DeviceState{" + "identifier=" + mIdentifier
                    + ", name='" + mName + '\''
                    + ", app_accessible=" + mSystemProperties.contains(PROPERTY_APP_INACCESSIBLE)
                    + ", cancel_when_requester_not_on_top="
                    + mSystemProperties.contains(PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP)
                    + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DeviceState.Configuration that = (DeviceState.Configuration) o;
            return mIdentifier == that.mIdentifier
                    && Objects.equals(mName, that.mName)
                    && Objects.equals(mSystemProperties, that.mSystemProperties)
                    && Objects.equals(mPhysicalProperties, that.mPhysicalProperties);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mIdentifier, mName, mSystemProperties, mPhysicalProperties);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mIdentifier);
            dest.writeString8(mName);
            dest.writeArraySet(mSystemProperties);
            dest.writeArraySet(mPhysicalProperties);
        }

        @NonNull
        public static final Creator<DeviceState.Configuration> CREATOR = new Creator<>() {
            @Override
            public DeviceState.Configuration createFromParcel(Parcel source) {
                int identifier = source.readInt();
                String name = source.readString8();
                ArraySet<@SystemDeviceStateProperties Integer> systemProperties =
                        (ArraySet<Integer>) source.readArraySet(null /* classLoader */);
                ArraySet<@PhysicalDeviceStateProperties Integer> physicalProperties =
                        (ArraySet<Integer>) source.readArraySet(null /* classLoader */);

                return new DeviceState.Configuration(identifier, name, systemProperties,
                        physicalProperties);
            }

            @Override
            public DeviceState.Configuration[] newArray(int size) {
                return new DeviceState.Configuration[size];
            }
        };

        /** @hide */
        @TestApi
        public static final class Builder {
            private final int mIdentifier;
            @NonNull
            private final String mName;
            @NonNull
            private Set<@SystemDeviceStateProperties Integer> mSystemProperties =
                    Collections.emptySet();
            @NonNull
            private Set<@PhysicalDeviceStateProperties Integer> mPhysicalProperties =
                    Collections.emptySet();

            public Builder(int identifier, @NonNull String name) {
                mIdentifier = identifier;
                mName = name;
            }

            /** Sets the system properties for this {@link DeviceState.Configuration.Builder} */
            @NonNull
            public Builder setSystemProperties(
                    @NonNull Set<@SystemDeviceStateProperties Integer> systemProperties) {
                mSystemProperties = systemProperties;
                return this;
            }

            /** Sets the system properties for this {@link DeviceState.Configuration.Builder} */
            @NonNull
            public Builder setPhysicalProperties(
                    @NonNull Set<@PhysicalDeviceStateProperties Integer> physicalProperties) {
                mPhysicalProperties = physicalProperties;
                return this;
            }

            /**
             * Returns a new {@link DeviceState.Configuration} whose values match the values set on
             * the builder.
             */
            @NonNull
            public DeviceState.Configuration build() {
                return new DeviceState.Configuration(mIdentifier, mName,
                        new ArraySet<>(mSystemProperties), new ArraySet<>(mPhysicalProperties));
            }
        }
    }
}
