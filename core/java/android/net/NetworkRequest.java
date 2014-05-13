/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @hide
 */
public class NetworkRequest implements Parcelable {
    public final NetworkCapabilities networkCapabilities;
    public final int requestId;
    public final boolean legacy;
    private static final AtomicInteger sRequestId = new AtomicInteger();

    public NetworkRequest(NetworkCapabilities nc) {
        this(nc, false, sRequestId.incrementAndGet());
    }

    public NetworkRequest(NetworkCapabilities nc, boolean legacy) {
        this(nc, legacy, sRequestId.incrementAndGet());
    }

    private NetworkRequest(NetworkCapabilities nc, boolean legacy, int rId) {
        requestId = rId;
        networkCapabilities = nc;
        this.legacy = legacy;
    }

    // implement the Parcelable interface
    public int describeContents() {
        return 0;
    }
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(networkCapabilities, flags);
        dest.writeInt(legacy ? 1 : 0);
        dest.writeInt(requestId);
    }
    public static final Creator<NetworkRequest> CREATOR =
        new Creator<NetworkRequest>() {
            public NetworkRequest createFromParcel(Parcel in) {
                NetworkCapabilities nc = (NetworkCapabilities)in.readParcelable(null);
                boolean legacy = (in.readInt() == 1);
                int requestId = in.readInt();
                return new NetworkRequest(nc, legacy, requestId);
            }
            public NetworkRequest[] newArray(int size) {
                return new NetworkRequest[size];
            }
        };

    public String toString() {
        return "NetworkRequest [ id=" + requestId + ", legacy=" + legacy + ", " +
                networkCapabilities.toString() + " ]";
    }

    public boolean equals(Object obj) {
        if (obj instanceof NetworkRequest == false) return false;
        NetworkRequest that = (NetworkRequest)obj;
        return (that.legacy == this.legacy &&
                that.requestId == this.requestId &&
                ((that.networkCapabilities == null && this.networkCapabilities == null) ||
                 (that.networkCapabilities != null &&
                  that.networkCapabilities.equals(this.networkCapabilities))));
    }

    public int hashCode() {
        return requestId + (legacy ? 1013 : 2026) + (networkCapabilities.hashCode() * 1051);
    }
}
