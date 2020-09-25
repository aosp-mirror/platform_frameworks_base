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

import android.annotation.IntDef;
import android.hardware.biometrics.SensorProperties;
import android.os.Parcel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Container for fingerprint sensor properties.
 * @hide
 */
public class FingerprintSensorProperties extends SensorProperties {

    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_REAR = 1;
    public static final int TYPE_UDFPS_ULTRASONIC = 2;
    public static final int TYPE_UDFPS_OPTICAL = 3;
    public static final int TYPE_POWER_BUTTON = 4;
    public static final int TYPE_HOME_BUTTON = 5;

    @IntDef({TYPE_UNKNOWN,
            TYPE_REAR,
            TYPE_UDFPS_ULTRASONIC,
            TYPE_UDFPS_OPTICAL,
            TYPE_POWER_BUTTON,
            TYPE_HOME_BUTTON})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SensorType {}

    public final @SensorType int sensorType;
    // IBiometricsFingerprint@2.1 does not manage timeout below the HAL, so the Gatekeeper HAT
    // cannot be checked
    public final boolean resetLockoutRequiresHardwareAuthToken;

    /**
     * Initializes SensorProperties with specified values
     */
    public FingerprintSensorProperties(int sensorId, @Strength int strength,
            int maxEnrollmentsPerUser, @SensorType int sensorType,
            boolean resetLockoutRequiresHardwareAuthToken) {
        super(sensorId, strength, maxEnrollmentsPerUser);
        this.sensorType = sensorType;
        this.resetLockoutRequiresHardwareAuthToken = resetLockoutRequiresHardwareAuthToken;
    }

    protected FingerprintSensorProperties(Parcel in) {
        super(in);
        sensorType = in.readInt();
        resetLockoutRequiresHardwareAuthToken = in.readBoolean();
    }

    public static final Creator<FingerprintSensorProperties> CREATOR =
            new Creator<FingerprintSensorProperties>() {
                @Override
                public FingerprintSensorProperties createFromParcel(Parcel in) {
                    return new FingerprintSensorProperties(in);
                }

                @Override
                public FingerprintSensorProperties[] newArray(int size) {
                    return new FingerprintSensorProperties[size];
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
}
