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

package android.hardware.biometrics;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.TestApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * The base class containing all modality-agnostic information.
 * @hide
 */
@TestApi
public class SensorProperties {
    /**
     * A sensor that meets the requirements for Class 1 biometrics as defined in the CDD. This does
     * not correspond to a public BiometricManager.Authenticators constant. Sensors of this strength
     * are not available to applications via the public API surface.
     */
    public static final int STRENGTH_CONVENIENCE = 0;

    /**
     * A sensor that meets the requirements for Class 2 biometrics as defined in the CDD.
     * Corresponds to BiometricManager.Authenticators.BIOMETRIC_WEAK.
     */
    public static final int STRENGTH_WEAK = 1;

    /**
     * A sensor that meets the requirements for Class 3 biometrics as defined in the CDD.
     * Corresponds to BiometricManager.Authenticators.BIOMETRIC_STRONG.
     *
     * Notably, this is the only strength that allows generation of HardwareAuthToken(s).
     */
    public static final int STRENGTH_STRONG = 2;

    /**
     * @hide
     */
    @IntDef({STRENGTH_CONVENIENCE, STRENGTH_WEAK, STRENGTH_STRONG})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Strength {}

    /**
     * A class storing the component info for a subsystem of the sensor.
     */
    public static final class ComponentInfo {
        @NonNull private final String mComponentId;
        @NonNull private final String mHardwareVersion;
        @NonNull private final String mFirmwareVersion;
        @NonNull private final String mSerialNumber;
        @NonNull private final String mSoftwareVersion;

        /**
         * @hide
         */
        public ComponentInfo(@NonNull String componentId, @NonNull String hardwareVersion,
                @NonNull String firmwareVersion, @NonNull String serialNumber,
                @NonNull String softwareVersion) {
            mComponentId = componentId;
            mHardwareVersion = hardwareVersion;
            mFirmwareVersion = firmwareVersion;
            mSerialNumber = serialNumber;
            mSoftwareVersion = softwareVersion;
        }

        /**
         * @return The unique identifier for the subsystem.
         */
        @NonNull
        public String getComponentId() {
            return mComponentId;
        }

        /**
         * @return The hardware version for the subsystem. For example, <vendor>/<model>/<revision>.
         */
        @NonNull
        public String getHardwareVersion() {
            return mHardwareVersion;
        }

        /**
         * @return The firmware version for the subsystem.
         */
        @NonNull
        public String getFirmwareVersion() {
            return mFirmwareVersion;
        }

        /**
         * @return The serial number for the subsystem.
         */
        @NonNull
        public String getSerialNumber() {
            return mSerialNumber;
        }

        /**
         * @return The software version for the subsystem.
         * For example, <vendor>/<version>/<revision>.
         */
        @NonNull
        public String getSoftwareVersion() {
            return mSoftwareVersion;
        }

        /**
         * Constructs a {@link ComponentInfo} from the internal parcelable representation.
         * @hide
         */
        public static ComponentInfo from(ComponentInfoInternal internalComp) {
            return new ComponentInfo(internalComp.componentId, internalComp.hardwareVersion,
                    internalComp.firmwareVersion, internalComp.serialNumber,
                    internalComp.softwareVersion);
        }
    }

    private final int mSensorId;
    @Strength private final int mSensorStrength;
    private final List<ComponentInfo> mComponentInfo;

    /**
     * @hide
     */
    public SensorProperties(int sensorId, @Strength int sensorStrength,
            List<ComponentInfo> componentInfo) {
        mSensorId = sensorId;
        mSensorStrength = sensorStrength;
        mComponentInfo = componentInfo;
    }

    /**
     * @return The sensor's unique identifier.
     */
    public int getSensorId() {
        return mSensorId;
    }

    /**
     * @return The sensor's strength.
     */
    @Strength
    public int getSensorStrength() {
        return mSensorStrength;
    }

    /**
     * @return The sensor's component info.
     */
    @NonNull
    public List<ComponentInfo> getComponentInfo() {
        return mComponentInfo;
    }

    /**
     * Constructs a {@link SensorProperties} from the internal parcelable representation.
     * @hide
     */
    public static SensorProperties from(SensorPropertiesInternal internalProp) {
        final List<ComponentInfo> componentInfo = new ArrayList<>();
        for (ComponentInfoInternal internalComp : internalProp.componentInfo) {
            componentInfo.add(ComponentInfo.from(internalComp));
        }
        return new SensorProperties(internalProp.sensorId, internalProp.sensorStrength,
                componentInfo);
    }
}
