/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.Objects;

/**
 * Aware of how to parcel different types of {@link QosFilter}s.  Any new type of qos filter must
 * have a specialized case written here.
 * <p/>
 * Specifically leveraged when transferring {@link QosFilter} from
 * {@link com.android.server.ConnectivityService} to {@link NetworkAgent} when the filter is first
 * registered.
 * <p/>
 * This is not meant to be used in other contexts.
 *
 * @hide
 */
public final class QosFilterParcelable implements Parcelable {

    private static final String LOG_TAG = QosFilterParcelable.class.getSimpleName();

    // Indicates that the filter was not successfully written to the parcel.
    private static final int NO_FILTER_PRESENT = 0;

    // The parcel is of type qos socket filter.
    private static final int QOS_SOCKET_FILTER = 1;

    private final QosFilter mQosFilter;

    /**
     * The underlying qos filter.
     * <p/>
     * Null only in the case parceling failed.
     */
    @Nullable
    public QosFilter getQosFilter() {
        return mQosFilter;
    }

    public QosFilterParcelable(@NonNull final QosFilter qosFilter) {
        Objects.requireNonNull(qosFilter, "qosFilter must be non-null");

        // NOTE: Normally a type check would belong here, but doing so breaks unit tests that rely
        // on mocking qos filter.
        mQosFilter = qosFilter;
    }

    private QosFilterParcelable(final Parcel in) {
        final int filterParcelType = in.readInt();

        switch (filterParcelType) {
            case QOS_SOCKET_FILTER: {
                mQosFilter = new QosSocketFilter(QosSocketInfo.CREATOR.createFromParcel(in));
                break;
            }

            case NO_FILTER_PRESENT:
            default: {
                mQosFilter = null;
            }
        }
    }

    public static final Creator<QosFilterParcelable> CREATOR = new Creator<QosFilterParcelable>() {
        @Override
        public QosFilterParcelable createFromParcel(final Parcel in) {
            return new QosFilterParcelable(in);
        }

        @Override
        public QosFilterParcelable[] newArray(final int size) {
            return new QosFilterParcelable[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
        if (mQosFilter instanceof QosSocketFilter) {
            dest.writeInt(QOS_SOCKET_FILTER);
            final QosSocketFilter qosSocketFilter = (QosSocketFilter) mQosFilter;
            qosSocketFilter.getQosSocketInfo().writeToParcel(dest, 0);
            return;
        }
        dest.writeInt(NO_FILTER_PRESENT);
        Log.e(LOG_TAG, "Parceling failed, unknown type of filter present: " + mQosFilter);
    }
}
