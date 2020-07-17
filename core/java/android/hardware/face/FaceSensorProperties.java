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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Container for face sensor properties.
 * @hide
 */
public class FaceSensorProperties implements Parcelable {

    public final int sensorId;
    public final boolean supportsFaceDetection;

    /**
     * Initializes SensorProperties with specified values
     */
    public FaceSensorProperties(int sensorId, boolean supportsFaceDetection) {
        this.sensorId = sensorId;
        this.supportsFaceDetection = supportsFaceDetection;
    }

    protected FaceSensorProperties(Parcel in) {
        sensorId = in.readInt();
        supportsFaceDetection = in.readBoolean();
    }

    public static final Creator<FaceSensorProperties> CREATOR =
            new Creator<FaceSensorProperties>() {
        @Override
        public FaceSensorProperties createFromParcel(Parcel in) {
            return new FaceSensorProperties(in);
        }

        @Override
        public FaceSensorProperties[] newArray(int size) {
            return new FaceSensorProperties[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(sensorId);
        dest.writeBoolean(supportsFaceDetection);
    }
}
