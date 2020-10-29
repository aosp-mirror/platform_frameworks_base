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

package android.hardware.fingerprint;

import static android.hardware.fingerprint.FingerprintSensorProperties.TYPE_UDFPS_OPTICAL;
import static android.hardware.fingerprint.FingerprintSensorProperties.TYPE_UDFPS_ULTRASONIC;

import android.hardware.biometrics.SensorProperties;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.os.Parcel;

/**
 * Container for fingerprint sensor properties.
 * @hide
 */
public class FingerprintSensorPropertiesInternal extends SensorPropertiesInternal {
    /**
     * See {@link FingerprintSensorProperties.SensorType}.
     */
    public final @FingerprintSensorProperties.SensorType int sensorType;

    /**
     * IBiometricsFingerprint@2.1 does not manage timeout below the HAL, so the Gatekeeper HAT
     * cannot be checked
     */
    public final boolean resetLockoutRequiresHardwareAuthToken;

    /**
     * The location of the center of the sensor if applicable. For example, sensors of type
     * {@link FingerprintSensorProperties#TYPE_UDFPS_OPTICAL} would report this value as the
     * distance in pixels, measured from the left edge of the screen.
     * TODO: Value should be provided from the HAL
     */
    public final int sensorLocationX = 540;

    /**
     * The location of the center of the sensor if applicable. For example, sensors of type
     * {@link FingerprintSensorProperties#TYPE_UDFPS_OPTICAL} would report this value as the
     * distance in pixels, measured from the top edge of the screen.
     * TODO: Value should be provided from the HAL
     */
    public final int sensorLocationY = 1636;

    /**
     * The radius of the sensor if applicable. For example, sensors of type
     * {@link FingerprintSensorProperties#TYPE_UDFPS_OPTICAL} would report this value as the radius
     * of the sensor, in pixels.
     */
    public final int sensorRadius = 130;

    /**
     * Initializes SensorProperties with specified values
     */
    public FingerprintSensorPropertiesInternal(int sensorId,
            @SensorProperties.Strength int strength, int maxEnrollmentsPerUser,
            @FingerprintSensorProperties.SensorType int sensorType,
            boolean resetLockoutRequiresHardwareAuthToken) {
        super(sensorId, strength, maxEnrollmentsPerUser);
        this.sensorType = sensorType;
        this.resetLockoutRequiresHardwareAuthToken = resetLockoutRequiresHardwareAuthToken;
    }

    protected FingerprintSensorPropertiesInternal(Parcel in) {
        super(in);
        sensorType = in.readInt();
        resetLockoutRequiresHardwareAuthToken = in.readBoolean();
    }

    public static final Creator<FingerprintSensorPropertiesInternal> CREATOR =
            new Creator<FingerprintSensorPropertiesInternal>() {
                @Override
                public FingerprintSensorPropertiesInternal createFromParcel(Parcel in) {
                    return new FingerprintSensorPropertiesInternal(in);
                }

                @Override
                public FingerprintSensorPropertiesInternal[] newArray(int size) {
                    return new FingerprintSensorPropertiesInternal[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(sensorType);
        dest.writeBoolean(resetLockoutRequiresHardwareAuthToken);
    }

    public boolean isAnyUdfpsType() {
        switch (sensorType) {
            case TYPE_UDFPS_OPTICAL:
            case TYPE_UDFPS_ULTRASONIC:
                return true;
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return "ID: " + sensorId + ", Strength: " + sensorStrength + ", Type: " + sensorType;
    }
}
