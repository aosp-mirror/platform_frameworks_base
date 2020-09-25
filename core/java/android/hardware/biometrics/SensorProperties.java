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
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The base class containing all sensor-agnostic information. This is a superset of the
 * {@link android.hardware.biometrics.common.CommonProps}, and provides backwards-compatible
 * behavior with the older generation of HIDL (non-AIDL) interfaces.
 * @hide
 */
public class SensorProperties implements Parcelable {

    public static final int STRENGTH_CONVENIENCE = 0;
    public static final int STRENGTH_WEAK = 1;
    public static final int STRENGTH_STRONG = 2;

    @IntDef({STRENGTH_CONVENIENCE, STRENGTH_WEAK, STRENGTH_STRONG})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Strength {}

    public final int sensorId;
    @Strength public final int sensorStrength;
    public final int maxEnrollmentsPerUser;

    protected SensorProperties(int sensorId, @Strength int sensorStrength,
            int maxEnrollmentsPerUser) {
        this.sensorId = sensorId;
        this.sensorStrength = sensorStrength;
        this.maxEnrollmentsPerUser = maxEnrollmentsPerUser;
    }

    protected SensorProperties(Parcel in) {
        sensorId = in.readInt();
        sensorStrength = in.readInt();
        maxEnrollmentsPerUser = in.readInt();
    }

    public static final Creator<SensorProperties> CREATOR = new Creator<SensorProperties>() {
        @Override
        public SensorProperties createFromParcel(Parcel in) {
            return new SensorProperties(in);
        }

        @Override
        public SensorProperties[] newArray(int size) {
            return new SensorProperties[size];
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
    }
}
