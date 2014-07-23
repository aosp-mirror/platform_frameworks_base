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
 * A grab-bag of information (metadata, policies, properties, etc) about a {@link Network}.
 *
 * @hide
 */
public class NetworkMisc implements Parcelable {
    /**
     * If the {@link Network} is a VPN, whether apps are allowed to bypass the VPN. This is set by
     * a {@link VpnService} and used by {@link ConnectivityService} when creating a VPN.
     */
    public boolean allowBypass;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(allowBypass ? 1 : 0);
    }

    public static final Creator<NetworkMisc> CREATOR = new Creator<NetworkMisc>() {
        @Override
        public NetworkMisc createFromParcel(Parcel in) {
            NetworkMisc networkMisc = new NetworkMisc();
            networkMisc.allowBypass = in.readInt() != 0;
            return networkMisc;
        }

        @Override
        public NetworkMisc[] newArray(int size) {
            return new NetworkMisc[size];
        }
    };
}
