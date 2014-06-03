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
 * Defines a request for a network, made by calling {@link ConnectivityManager#requestNetwork}
 * or {@link ConnectivityManager#listenForNetwork}.
 *
 * This token records the {@link NetworkCapabilities} used to make the request and identifies
 * the request.  It should be used to release the request via
 * {@link ConnectivityManager#releaseNetworkRequest} when the network is no longer desired.
 */
public class NetworkRequest implements Parcelable {
    /**
     * The {@link NetworkCapabilities} that define this request.  This should not be modified.
     * The networkCapabilities of the request are set when
     * {@link ConnectivityManager#requestNetwork} is called and the value is presented here
     * as a convenient reminder of what was requested.
     */
    public final NetworkCapabilities networkCapabilities;

    /**
     * Identifies the request.  NetworkRequests should only be constructed by
     * the Framework and given out to applications as tokens to be used to identify
     * the request.
     * @hide
     */
    public final int requestId;

    /**
     * Set for legacy requests and the default.  Set to TYPE_NONE for none.
     * Causes CONNECTIVITY_ACTION broadcasts to be sent.
     * @hide
     */
    public final int legacyType;

    /**
     * @hide
     */
    public NetworkRequest(NetworkCapabilities nc, int legacyType, int rId) {
        requestId = rId;
        networkCapabilities = nc;
        this.legacyType = legacyType;
    }

    /**
     * @hide
     */
    public NetworkRequest(NetworkRequest that) {
        networkCapabilities = new NetworkCapabilities(that.networkCapabilities);
        requestId = that.requestId;
        this.legacyType = that.legacyType;
    }

    // implement the Parcelable interface
    public int describeContents() {
        return 0;
    }
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(networkCapabilities, flags);
        dest.writeInt(legacyType);
        dest.writeInt(requestId);
    }
    public static final Creator<NetworkRequest> CREATOR =
        new Creator<NetworkRequest>() {
            public NetworkRequest createFromParcel(Parcel in) {
                NetworkCapabilities nc = (NetworkCapabilities)in.readParcelable(null);
                int legacyType = in.readInt();
                int requestId = in.readInt();
                NetworkRequest result = new NetworkRequest(nc, legacyType, requestId);
                return result;
            }
            public NetworkRequest[] newArray(int size) {
                return new NetworkRequest[size];
            }
        };

    public String toString() {
        return "NetworkRequest [ id=" + requestId + ", legacyType=" + legacyType +
                ", " + networkCapabilities.toString() + " ]";
    }

    public boolean equals(Object obj) {
        if (obj instanceof NetworkRequest == false) return false;
        NetworkRequest that = (NetworkRequest)obj;
        return (that.legacyType == this.legacyType &&
                that.requestId == this.requestId &&
                ((that.networkCapabilities == null && this.networkCapabilities == null) ||
                 (that.networkCapabilities != null &&
                  that.networkCapabilities.equals(this.networkCapabilities))));
    }

    public int hashCode() {
        return requestId + (legacyType * 1013) +
                (networkCapabilities.hashCode() * 1051);
    }
}
