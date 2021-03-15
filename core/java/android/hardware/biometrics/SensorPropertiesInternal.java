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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The base class containing all modality-agnostic information. This is a superset of the
 * {@link android.hardware.biometrics.common.CommonProps}, and provides backwards-compatible
 * behavior with the older generation of HIDL (non-AIDL) interfaces.
 * @hide
 */
public class SensorPropertiesInternal implements Parcelable {

    public final int sensorId;
    @SensorProperties.Strength public final int sensorStrength;
    public final int maxEnrollmentsPerUser;
    public final boolean resetLockoutRequiresHardwareAuthToken;

    public static SensorPropertiesInternal from(@NonNull SensorPropertiesInternal prop) {
        return new SensorPropertiesInternal(prop.sensorId, prop.sensorStrength,
                prop.maxEnrollmentsPerUser, prop.resetLockoutRequiresHardwareAuthToken);
    }

    protected SensorPropertiesInternal(int sensorId, @SensorProperties.Strength int sensorStrength,
            int maxEnrollmentsPerUser, boolean resetLockoutRequiresHardwareAuthToken) {
        this.sensorId = sensorId;
        this.sensorStrength = sensorStrength;
        this.maxEnrollmentsPerUser = maxEnrollmentsPerUser;
        this.resetLockoutRequiresHardwareAuthToken = resetLockoutRequiresHardwareAuthToken;
    }

    protected SensorPropertiesInternal(Parcel in) {
        sensorId = in.readInt();
        sensorStrength = in.readInt();
        maxEnrollmentsPerUser = in.readInt();
        resetLockoutRequiresHardwareAuthToken = in.readBoolean();
    }

    public static final Creator<SensorPropertiesInternal> CREATOR =
            new Creator<SensorPropertiesInternal>() {
        @Override
        public SensorPropertiesInternal createFromParcel(Parcel in) {
            return new SensorPropertiesInternal(in);
        }

        @Override
        public SensorPropertiesInternal[] newArray(int size) {
            return new SensorPropertiesInternal[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(sensorId);
        dest.writeInt(sensorStrength);
        dest.writeInt(maxEnrollmentsPerUser);
        dest.writeBoolean(resetLockoutRequiresHardwareAuthToken);
    }

    @Override
    public String toString() {
        return "ID: " + sensorId + ", Strength: " + sensorStrength
                + ", MaxEnrollmentsPerUser: " + maxEnrollmentsPerUser;
    }
}
