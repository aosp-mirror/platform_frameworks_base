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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


/**
 * Class that stores information specific to QOS session.
 *
 * @hide
 */
public final class QosBearerSession implements Parcelable{

    final int qosBearerSessionId;
    final Qos qos;
    final List<QosBearerFilter> qosBearerFilterList;

    public QosBearerSession(int qosBearerSessionId, @NonNull Qos qos, @NonNull List<QosBearerFilter> qosBearerFilterList) {
        this.qosBearerSessionId = qosBearerSessionId;
        this.qos = qos;
        this.qosBearerFilterList = qosBearerFilterList;
    }

    private QosBearerSession(Parcel source) {
        qosBearerSessionId = source.readInt();
        qos = source.readParcelable(Qos.class.getClassLoader());
        qosBearerFilterList = new ArrayList<>();
        source.readList(qosBearerFilterList, QosBearerFilter.class.getClassLoader());
    }

    public int getQosBearerSessionId() {
        return qosBearerSessionId;
    }

    public Qos getQos() {
        return qos;
    }

    public List<QosBearerFilter> getQosBearerFilterList() {
        return qosBearerFilterList;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(qosBearerSessionId);
        if (qos.getType() == Qos.QOS_TYPE_EPS) {
            dest.writeParcelable((EpsQos)qos, flags);
        } else {
            dest.writeParcelable((NrQos)qos, flags);
        }
        dest.writeList(qosBearerFilterList);
    }

    public static @NonNull QosBearerSession create(
            @NonNull android.hardware.radio.V1_6.QosSession qosSession) {
        List<QosBearerFilter> qosBearerFilters = new ArrayList<>();

        if (qosSession.qosFilters != null) {
            for (android.hardware.radio.V1_6.QosFilter filter : qosSession.qosFilters) {
                qosBearerFilters.add(QosBearerFilter.create(filter));
            }
        }

        return new QosBearerSession(
                        qosSession.qosSessionId,
                        Qos.create(qosSession.qos),
                        qosBearerFilters);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "QosBearerSession {"
                + " qosBearerSessionId=" + qosBearerSessionId
                + " qos=" + qos
                + " qosBearerFilterList=" + qosBearerFilterList + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(qosBearerSessionId, qos, qosBearerFilterList);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (o == null || !(o instanceof QosBearerSession)) {
            return false;
        }

        QosBearerSession other = (QosBearerSession) o;
        return this.qosBearerSessionId == other.qosBearerSessionId
                && this.qos.equals(other.qos)
                && this.qosBearerFilterList.size() == other.qosBearerFilterList.size()
                && this.qosBearerFilterList.containsAll(other.qosBearerFilterList);
    }


    public static final @NonNull Parcelable.Creator<QosBearerSession> CREATOR =
            new Parcelable.Creator<QosBearerSession>() {
                @Override
                public QosBearerSession createFromParcel(Parcel source) {
                    return new QosBearerSession(source);
                }

                @Override
                public QosBearerSession[] newArray(int size) {
                    return new QosBearerSession[size];
                }
            };
}
