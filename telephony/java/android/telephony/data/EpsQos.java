/**
 * Copyright 2020 The Android Open Source Project
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

package android.telephony.data;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;


/**
 * Class that stores information specific to NR QOS.
 *
 * @hide
 */
public final class EpsQos extends Qos implements Parcelable {

    int qosClassId;

    public EpsQos(QosBandwidth downlink, QosBandwidth uplink, int qosClassId) {
        super(Qos.QOS_TYPE_EPS, downlink, uplink);
        this.qosClassId = qosClassId;
    }

    private EpsQos(Parcel source) {
        super(source);
        qosClassId = source.readInt();
    }

    public int getQci() {
        return qosClassId;
    }

    public static @NonNull EpsQos createFromParcelBody(@NonNull Parcel in) {
        return new EpsQos(in);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(Qos.QOS_TYPE_EPS, dest, flags);
        dest.writeInt(qosClassId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), qosClassId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (o == null || !(o instanceof EpsQos)) {
            return false;
        }

        EpsQos other = (EpsQos) o;

        return this.qosClassId == other.qosClassId
               && super.equals(other);
    }

    @Override
    public String toString() {
        return "EpsQos {"
                + " qosClassId=" + qosClassId
                + " downlink=" + downlink
                + " uplink=" + uplink + "}";
    }

    public static final @NonNull Parcelable.Creator<EpsQos> CREATOR =
            new Parcelable.Creator<EpsQos>() {
                @Override
                public EpsQos createFromParcel(Parcel source) {
                    return new EpsQos(source);
                }

                @Override
                public EpsQos[] newArray(int size) {
                    return new EpsQos[size];
                }
            };
}
