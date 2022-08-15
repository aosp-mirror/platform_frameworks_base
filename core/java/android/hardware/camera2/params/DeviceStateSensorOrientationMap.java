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

package android.hardware.camera2.params;

import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.utils.HashCodeHelpers;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;

/**
 * Immutable class that maps the device fold state to sensor orientation.
 *
 * <p>Some {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA logical}
 * cameras on foldables can include physical sensors with different sensor orientation
 * values. As a result, the values of the logical camera device can potentially change depending
 * on the device fold state.</p>
 *
 * <p>The device fold state to sensor orientation map will contain information about the
 * respective logical camera sensor orientation given a device state. Clients
 * can query the mapping for all possible supported folded states.
 *
 * @see CameraCharacteristics#SENSOR_ORIENTATION
 */
public final class DeviceStateSensorOrientationMap {
    /**
     *  Needs to be kept in sync with the HIDL/AIDL DeviceState
     */

    /**
     * The device is in its normal physical configuration. This is the default if the
     * device does not support multiple different states.
     */
    public static final long NORMAL = 0;

    /**
     * The device is folded.  If not set, the device is unfolded or does not
     * support folding.
     *
     * The exact point when this status change happens during the folding
     * operation is device-specific.
     */
    public static final long FOLDED = 1 << 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @LongDef(prefix = {"DEVICE_STATE"}, value =
            {NORMAL,
             FOLDED })
    public @interface DeviceState {};

    private final HashMap<Long, Integer> mDeviceStateOrientationMap;

    /**
     * Create a new immutable DeviceStateOrientationMap instance.
     *
     * <p>The array is a list of pairs of elements (deviceState, angle):</p>
     *
     * <code>[state0, angle0, state1, angle1,..., stateN, angleN]</code>
     *
     * <p>Each pair describes the camera sensor orientation when the device is in the
     * matching deviceState. The angle is in degrees, and must be a multiple of 90.</p>
     *
     * <p>This constructor takes over the array; do not write to the array afterwards.</p>
     *
     * @param elements
     *          An array of elements describing the map
     *
     * @throws IllegalArgumentException
     *            if the {@code elements} array length is invalid, not divisible by 2 or contains
     *            invalid element values
     * @throws NullPointerException
     *            if {@code elements} is {@code null}
     *
     * @hide
     */
    public DeviceStateSensorOrientationMap(@NonNull final long[] elements) {
        mElements = Objects.requireNonNull(elements, "elements must not be null");
        mDeviceStateOrientationMap = new HashMap<>();
        if ((elements.length % 2) != 0) {
            throw new IllegalArgumentException("Device state sensor orientation map length " +
                    elements.length + " is not even!");
        }

        for (int i = 0; i < elements.length; i += 2) {
            if ((elements[i+1] % 90) != 0) {
                throw new IllegalArgumentException("Sensor orientation not divisible by 90: " +
                        elements[i+1]);
            }

            mDeviceStateOrientationMap.put(elements[i], Math.toIntExact(elements[i + 1]));
        }
    }

    /**
     * Used by the Builder only.
     *
     * @hide
     */
    private DeviceStateSensorOrientationMap(@NonNull final ArrayList<Long> elements,
            @NonNull final HashMap<Long, Integer> deviceStateOrientationMap) {
        mElements = new long[elements.size()];
        for (int i = 0; i < elements.size(); i++) {
            mElements[i] = elements.get(i);
        }
        mDeviceStateOrientationMap = deviceStateOrientationMap;
    }

    /**
     * Return the logical camera sensor orientation given a specific device fold state.
     *
     * @param deviceState Device fold state
     *
     * @return Valid {@link android.hardware.camera2.CameraCharacteristics#SENSOR_ORIENTATION} for
     *         any supported device fold state
     *
     * @throws IllegalArgumentException if the given device state is invalid
     */
    public int getSensorOrientation(@DeviceState long deviceState) {
        if (!mDeviceStateOrientationMap.containsKey(deviceState)) {
            throw new IllegalArgumentException("Invalid device state: " + deviceState);
        }

        return mDeviceStateOrientationMap.get(deviceState);
    }

    /**
     * Check if this DeviceStateSensorOrientationMap is equal to another
     * DeviceStateSensorOrientationMap.
     *
     * <p>Two device state orientation maps are equal if and only if all of their elements are
     * {@link Object#equals equal}.</p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (obj instanceof DeviceStateSensorOrientationMap) {
            final DeviceStateSensorOrientationMap other = (DeviceStateSensorOrientationMap) obj;
            return Arrays.equals(mElements, other.mElements);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCodeGeneric(mElements);
    }

    private final long[] mElements;

    /**
     * Builds a DeviceStateSensorOrientationMap object.
     *
     * <p>This builder is public to allow for easier application testing by
     * creating custom object instances. It's not necessary to construct these
     * objects during normal use of the camera API.</p>
     */
    public static final class Builder {
        public Builder() {
            // Empty
        }

        /**
         * Add a sensor orientation for a given device state.
         *
         * <p>Each pair of deviceState and angle describes the camera sensor orientation when the
         * device is in the matching deviceState. The angle is in degrees, and must be a multiple
         * of 90.</p>
         *
         * @param deviceState The deviceState.
         * @param angle The orientation angle in degrees.
         * @return This builder.
         *
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder addOrientationForState(@DeviceState long deviceState, long angle) {
            if (angle % 90 != 0) {
                throw new IllegalArgumentException("Sensor orientation not divisible by 90: "
                        + angle);
            }
            mDeviceStateOrientationMap.put(deviceState, Math.toIntExact(angle));
            mElements.add(deviceState);
            mElements.add(angle);
            return this;
        }

        /**
         * Returns an instance of <code>DeviceStateSensorOrientationMap</code> created from the
         * fields set on this builder.
         *
         * @return A DeviceStateSensorOrientationMap.
         */
        public @NonNull DeviceStateSensorOrientationMap build() {
            if (mElements.size() == 0) {
                throw new IllegalStateException("Cannot build a DeviceStateSensorOrientationMap"
                        + " with zero elements.");
            }
            return new DeviceStateSensorOrientationMap(mElements, mDeviceStateOrientationMap);
        }

        private final ArrayList<Long> mElements = new ArrayList<>();
        private final HashMap<Long, Integer> mDeviceStateOrientationMap = new HashMap<>();
    }
}
