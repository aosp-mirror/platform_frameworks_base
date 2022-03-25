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
public final class NrQos extends Qos implements Parcelable {
    int qosFlowId;
    int fiveQi;
    int averagingWindowMs;

    public NrQos(QosBandwidth downlink, QosBandwidth uplink, int qosFlowId, int fiveQi,
            int averagingWindowMs) {
        super(Qos.QOS_TYPE_NR, downlink, uplink);
        this.qosFlowId = qosFlowId;
        this.fiveQi = fiveQi;
        this.averagingWindowMs = averagingWindowMs;
    }

    private NrQos(Parcel source) {
        super(source);
        this.qosFlowId = source.readInt();
        this.fiveQi = source.readInt();
        this.averagingWindowMs = source.readInt();
    }

    public static @NonNull NrQos createFromParcelBody(@NonNull Parcel in) {
        return new NrQos(in);
    }

    public int get5Qi() {
        return fiveQi;
    }

    public int getQfi() {
        return qosFlowId;
    }

    public int getAveragingWindow() {
        return averagingWindowMs;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(Qos.QOS_TYPE_NR, dest, flags);
        dest.writeInt(qosFlowId);
        dest.writeInt(fiveQi);
        dest.writeInt(averagingWindowMs);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), qosFlowId, fiveQi, averagingWindowMs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (o == null || !(o instanceof NrQos)) {
            return false;
        }

        NrQos other = (NrQos) o;

        if (!super.equals(other)) {
            return false;
        }

        return this.qosFlowId == other.qosFlowId
            && this.fiveQi == other.fiveQi
            && this.averagingWindowMs == other.averagingWindowMs;
    }

    @Override
    public String toString() {
        return "NrQos {"
                + " fiveQi=" + fiveQi
                + " downlink=" + downlink
                + " uplink=" + uplink
                + " qosFlowId=" + qosFlowId
                + " averagingWindowMs=" + averagingWindowMs + "}";
    }

    public static final @NonNull Parcelable.Creator<NrQos> CREATOR =
            new Parcelable.Creator<NrQos>() {
                @Override
                public NrQos createFromParcel(Parcel source) {
                    return new NrQos(source);
                }

                @Override
                public NrQos[] newArray(int size) {
                    return new NrQos[size];
                }
            };
}
