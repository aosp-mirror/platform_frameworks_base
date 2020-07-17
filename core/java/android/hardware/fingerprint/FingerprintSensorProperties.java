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
import android.hardware.face.FaceSensorProperties;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Container for fingerprint sensor properties.
 * @hide
 */
public class FingerprintSensorProperties implements Parcelable {

    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_REAR = 1;
    public static final int TYPE_UDFPS = 2;
    public static final int TYPE_POWER_BUTTON = 3;

    @IntDef({
            TYPE_UNKNOWN,
            TYPE_REAR,
            TYPE_UDFPS,
            TYPE_POWER_BUTTON})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SensorType {}

    public final int sensorId;
    public final @SensorType int sensorType;

    /**
     * Initializes SensorProperties with specified values
     */
    public FingerprintSensorProperties(int sensorId, @SensorType int sensorType) {
        this.sensorId = sensorId;
        this.sensorType = sensorType;
    }

    protected FingerprintSensorProperties(Parcel in) {
        sensorId = in.readInt();
        sensorType = in.readInt();
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
        dest.writeInt(sensorId);
        dest.writeInt(sensorType);
    }
}
