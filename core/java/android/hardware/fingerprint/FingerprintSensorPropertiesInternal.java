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

import static android.hardware.fingerprint.FingerprintSensorProperties.TYPE_POWER_BUTTON;
import static android.hardware.fingerprint.FingerprintSensorProperties.TYPE_UDFPS_OPTICAL;
import static android.hardware.fingerprint.FingerprintSensorProperties.TYPE_UDFPS_ULTRASONIC;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.biometrics.SensorProperties;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.os.Parcel;

import java.util.List;

/**
 * Container for fingerprint sensor properties.
 * @hide
 */
public class FingerprintSensorPropertiesInternal extends SensorPropertiesInternal {
    /**
     * See {@link FingerprintSensorProperties.SensorType}.
     */
    public final @FingerprintSensorProperties.SensorType int sensorType;
    public final boolean halControlsIllumination;

    private final List<SensorLocationInternal> mSensorLocations;

    public FingerprintSensorPropertiesInternal(int sensorId,
            @SensorProperties.Strength int strength, int maxEnrollmentsPerUser,
            @NonNull List<ComponentInfoInternal> componentInfo,
            @FingerprintSensorProperties.SensorType int sensorType,
            boolean halControlsIllumination,
            boolean resetLockoutRequiresHardwareAuthToken,
            @NonNull List<SensorLocationInternal> sensorLocations) {
        // IBiometricsFingerprint@2.1 handles lockout in the framework, so the challenge is not
        // required as it can only be generated/attested/verified by TEE components.
        // IFingerprint@1.0 handles lockout below the HAL, but does not require a challenge. See
        // the HAL interface for more details.
        super(sensorId, strength, maxEnrollmentsPerUser, componentInfo,
            resetLockoutRequiresHardwareAuthToken, false /* resetLockoutRequiresChallenge */);
        this.sensorType = sensorType;
        this.halControlsIllumination = halControlsIllumination;
        this.mSensorLocations = List.copyOf(sensorLocations);
    }

    /**
     * Initializes SensorProperties with specified values
     */
    public FingerprintSensorPropertiesInternal(int sensorId,
            @SensorProperties.Strength int strength, int maxEnrollmentsPerUser,
            @NonNull List<ComponentInfoInternal> componentInfo,
            @FingerprintSensorProperties.SensorType int sensorType,
            boolean resetLockoutRequiresHardwareAuthToken) {
        // TODO(b/179175438): Value should be provided from the HAL
        this(sensorId, strength, maxEnrollmentsPerUser, componentInfo, sensorType,
                false /* halControlsIllumination */, resetLockoutRequiresHardwareAuthToken,
                List.of(new SensorLocationInternal("" /* displayId */, 540 /* sensorLocationX */,
                        1636 /* sensorLocationY */, 130 /* sensorRadius */)));
    }

    protected FingerprintSensorPropertiesInternal(Parcel in) {
        super(in);
        sensorType = in.readInt();
        halControlsIllumination = in.readBoolean();
        mSensorLocations = in.createTypedArrayList(SensorLocationInternal.CREATOR);
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
        dest.writeBoolean(halControlsIllumination);
        dest.writeTypedList(mSensorLocations);
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

    /**
     * Returns if sensor type is side-FPS
     * @return true if sensor is side-fps, false otherwise
     */
    public boolean isAnySidefpsType() {
        switch (sensorType) {
            case TYPE_POWER_BUTTON:
                return true;
            default:
                return false;
        }
    }

    /**
     * Get the default location.
     *
     * Use this method when the sensor's relationship to the displays on the device do not
     * matter.
     * @return
     */
    @NonNull
    public SensorLocationInternal getLocation() {
        final SensorLocationInternal location = getLocation("" /* displayId */);
        return location != null ? location : SensorLocationInternal.DEFAULT;
    }

    /**
     * Get the location of a sensor relative to a physical display layout.
     *
     * @param displayId stable display id
     * @return location or null if none is specified
     */
    @Nullable
    public SensorLocationInternal getLocation(String displayId) {
        for (SensorLocationInternal location : mSensorLocations) {
            if (location.displayId.equals(displayId)) {
                return location;
            }
        }
        return null;
    }

    /**
     * Gets all locations relative to all supported display layouts.
     * @return supported locations
     */
    @NonNull
    public List<SensorLocationInternal> getAllLocations() {
        return mSensorLocations;
    }

    @Override
    public String toString() {
        return "ID: " + sensorId + ", Strength: " + sensorStrength + ", Type: " + sensorType;
    }
}
