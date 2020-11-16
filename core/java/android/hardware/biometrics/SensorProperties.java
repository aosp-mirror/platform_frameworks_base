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
import android.annotation.TestApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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

    private final int mSensorId;
    @Strength private final int mSensorStrength;

    /**
     * @hide
     */
    public SensorProperties(int sensorId, @Strength int sensorStrength) {
        mSensorId = sensorId;
        mSensorStrength = sensorStrength;
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
     * Constructs a {@link SensorProperties} from the internal parcelable representation.
     * @hide
     */
    public static SensorProperties from(SensorPropertiesInternal internalProp) {
        return new SensorProperties(internalProp.sensorId, internalProp.sensorStrength);
    }
}
