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

/**
 * A grab-bag of information (metadata, policies, properties, etc) about a
 * {@link Network}. Since this contains PII, it should not be sent outside the
 * system.
 *
 * @hide
 */
public class NetworkMisc implements Parcelable {

    /**
     * If the {@link Network} is a VPN, whether apps are allowed to bypass the
     * VPN. This is set by a {@link VpnService} and used by
     * {@link ConnectivityManager} when creating a VPN.
     */
    public boolean allowBypass;

    /**
     * Set if the network was manually/explicitly connected to by the user either from settings
     * or a 3rd party app.  For example, turning on cell data is not explicit but tapping on a wifi
     * ap in the wifi settings to trigger a connection is explicit.  A 3rd party app asking to
     * connect to a particular access point is also explicit, though this may change in the future
     * as we want apps to use the multinetwork apis.
     */
    public boolean explicitlySelected;

    /**
     * For mobile networks, this is the subscriber ID (such as IMSI).
     */
    public String subscriberId;

    public NetworkMisc() {
    }

    public NetworkMisc(NetworkMisc nm) {
        if (nm != null) {
            allowBypass = nm.allowBypass;
            explicitlySelected = nm.explicitlySelected;
            subscriberId = nm.subscriberId;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(allowBypass ? 1 : 0);
        out.writeInt(explicitlySelected ? 1 : 0);
        out.writeString(subscriberId);
    }

    public static final Creator<NetworkMisc> CREATOR = new Creator<NetworkMisc>() {
        @Override
        public NetworkMisc createFromParcel(Parcel in) {
            NetworkMisc networkMisc = new NetworkMisc();
            networkMisc.allowBypass = in.readInt() != 0;
            networkMisc.explicitlySelected = in.readInt() != 0;
            networkMisc.subscriberId = in.readString();
            return networkMisc;
        }

        @Override
        public NetworkMisc[] newArray(int size) {
            return new NetworkMisc[size];
        }
    };
}
