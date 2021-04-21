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

package android.hardware.face;

import android.annotation.NonNull;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.SensorProperties;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.os.Parcel;

import java.util.List;

/**
 * Container for face sensor properties.
 * @hide
 */
public class FaceSensorPropertiesInternal extends SensorPropertiesInternal {
    /**
     * See {@link FaceSensorProperties.SensorType}.
     */
    public final @FaceSensorProperties.SensorType int sensorType;

    /**
     * True if the sensor is able to perform generic face detection, without running the
     * matching algorithm, and without affecting the lockout counter.
     */
    public final boolean supportsFaceDetection;
    /**
     * True if the sensor is able to provide self illumination in dark scenarios, without support
     * from above the HAL.
     */
    public final boolean supportsSelfIllumination;

    /**
     * Initializes SensorProperties with specified values
     */
    public FaceSensorPropertiesInternal(int sensorId, @SensorProperties.Strength int strength,
            int maxEnrollmentsPerUser, @NonNull List<ComponentInfoInternal> componentInfo,
            @FaceSensorProperties.SensorType int sensorType, boolean supportsFaceDetection,
            boolean supportsSelfIllumination, boolean resetLockoutRequiresChallenge) {
        // resetLockout is managed by the HAL and requires a HardwareAuthToken for all face
        // HAL interfaces (IBiometricsFace@1.0 HIDL and IFace@1.0 AIDL).
        super(sensorId, strength, maxEnrollmentsPerUser, componentInfo,
            true /* resetLockoutRequiresHardwareAuthToken */, resetLockoutRequiresChallenge);
        this.sensorType = sensorType;
        this.supportsFaceDetection = supportsFaceDetection;
        this.supportsSelfIllumination = supportsSelfIllumination;
    }

    protected FaceSensorPropertiesInternal(Parcel in) {
        super(in);
        sensorType = in.readInt();
        supportsFaceDetection = in.readBoolean();
        supportsSelfIllumination = in.readBoolean();
    }

    public static final Creator<FaceSensorPropertiesInternal> CREATOR =
            new Creator<FaceSensorPropertiesInternal>() {
        @Override
        public FaceSensorPropertiesInternal createFromParcel(Parcel in) {
            return new FaceSensorPropertiesInternal(in);
        }

        @Override
        public FaceSensorPropertiesInternal[] newArray(int size) {
            return new FaceSensorPropertiesInternal[size];
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
        dest.writeBoolean(supportsFaceDetection);
        dest.writeBoolean(supportsSelfIllumination);
    }

    @Override
    public String toString() {
        return "ID: " + sensorId + ", Strength: " + sensorStrength + ", Type: " + sensorType
                + ", SupportsFaceDetection: " + supportsFaceDetection;
    }
}
