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

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Class that stores information specific to QOS.
 *
 * @hide
 */
public abstract class Qos implements Parcelable {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "QOS_TYPE_",
            value = {QOS_TYPE_EPS, QOS_TYPE_NR})
    public @interface QosType {}

    @QosType
    final int type;

    static final int QOS_TYPE_EPS = 1;
    static final int QOS_TYPE_NR = 2;

    final QosBandwidth downlink;
    final QosBandwidth uplink;

    Qos(int type, QosBandwidth downlink, QosBandwidth uplink) {
        this.type = type;
        this.downlink = downlink;
        this.uplink = uplink;
    }

    public QosBandwidth getDownlinkBandwidth() {
        return downlink;
    }

    public QosBandwidth getUplinkBandwidth() {
        return uplink;
    }

    public static class QosBandwidth implements Parcelable {
        int maxBitrateKbps;
        int guaranteedBitrateKbps;

        public QosBandwidth(int maxBitrateKbps, int guaranteedBitrateKbps) {
            this.maxBitrateKbps = maxBitrateKbps;
            this.guaranteedBitrateKbps = guaranteedBitrateKbps;
        }

        private QosBandwidth(Parcel source) {
            maxBitrateKbps = source.readInt();
            guaranteedBitrateKbps = source.readInt();
        }

        public int getMaxBitrateKbps() {
            return maxBitrateKbps;
        }

        public int getGuaranteedBitrateKbps() {
            return guaranteedBitrateKbps;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(maxBitrateKbps);
            dest.writeInt(guaranteedBitrateKbps);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(maxBitrateKbps, guaranteedBitrateKbps);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;

            if (o == null || !(o instanceof QosBandwidth)) {
                return false;
            }

            QosBandwidth other = (QosBandwidth) o;
            return maxBitrateKbps == other.maxBitrateKbps
                    && guaranteedBitrateKbps == other.guaranteedBitrateKbps;
        }

        @Override
        public String toString() {
            return "Bandwidth {"
                    + " maxBitrateKbps=" + maxBitrateKbps
                    + " guaranteedBitrateKbps=" + guaranteedBitrateKbps + "}";
        }

        public static final @NonNull Parcelable.Creator<QosBandwidth> CREATOR =
                new Parcelable.Creator<QosBandwidth>() {
                    @Override
                    public QosBandwidth createFromParcel(Parcel source) {
                        return new QosBandwidth(source);
                    }

                    @Override
                    public QosBandwidth[] newArray(int size) {
                        return new QosBandwidth[size];
                    }
                };
    };

    protected Qos(@NonNull Parcel source) {
        type = source.readInt();
        downlink = source.readParcelable(
                QosBandwidth.class.getClassLoader(), android.telephony.data.Qos.QosBandwidth.class);
        uplink = source.readParcelable(
                QosBandwidth.class.getClassLoader(), android.telephony.data.Qos.QosBandwidth.class);
    }

    /**
     * Used by child classes for parceling.
     *
     * @hide
     */
    @CallSuper
    public void writeToParcel(@QosType int type, Parcel dest, int flags) {
        dest.writeInt(type);
        dest.writeParcelable(downlink, flags);
        dest.writeParcelable(uplink, flags);
    }

    /** @hide */
    public @QosType int getType() {
        return type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(downlink, uplink);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        Qos other = (Qos) o;
        return type == other.type
                && downlink.equals(other.downlink)
                && uplink.equals(other.uplink);
    }
}
