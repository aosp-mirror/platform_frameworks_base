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
public final class QosSession implements Parcelable{

    final int qosSessionId;
    final Qos qos;
    final List<QosFilter> qosFilterList;

    public QosSession(int qosSessionId, @NonNull Qos qos, @NonNull List<QosFilter> qosFilterList) {
        this.qosSessionId = qosSessionId;
        this.qos = qos;
        this.qosFilterList = qosFilterList;
    }

    private QosSession(Parcel source) {
        qosSessionId = source.readInt();
        qos = source.readParcelable(Qos.class.getClassLoader());
        qosFilterList = new ArrayList<>();
        source.readList(qosFilterList, QosFilter.class.getClassLoader());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(qosSessionId);
        if (qos.getType() == Qos.QOS_TYPE_EPS) {
            dest.writeParcelable((EpsQos)qos, flags);
        } else {
            dest.writeParcelable((NrQos)qos, flags);
        }
        dest.writeList(qosFilterList);
    }

    public static @NonNull QosSession create(
            @NonNull android.hardware.radio.V1_6.QosSession qosSession) {
        List<QosFilter> qosFilters = new ArrayList<>();

        if (qosSession.qosFilters != null) {
            for (android.hardware.radio.V1_6.QosFilter filter : qosSession.qosFilters) {
                qosFilters.add(QosFilter.create(filter));
            }
        }

        return new QosSession(
                        qosSession.qosSessionId,
                        Qos.create(qosSession.qos),
                        qosFilters);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "QosSession {"
                + " qosSessionId=" + qosSessionId
                + " qos=" + qos
                + " qosFilterList=" + qosFilterList + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(qosSessionId, qos, qosFilterList);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (o == null || !(o instanceof QosSession)) {
            return false;
        }

        QosSession other = (QosSession) o;
        return this.qosSessionId == other.qosSessionId
                && this.qos.equals(other.qos)
                && this.qosFilterList.size() == other.qosFilterList.size()
                && this.qosFilterList.containsAll(other.qosFilterList);
    }


    public static final @NonNull Parcelable.Creator<QosSession> CREATOR =
            new Parcelable.Creator<QosSession>() {
                @Override
                public QosSession createFromParcel(Parcel source) {
                    return new QosSession(source);
                }

                @Override
                public QosSession[] newArray(int size) {
                    return new QosSession[size];
                }
            };
}
