/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.metrics;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * An event logged when the APF packet socket receives an RA packet.
 * {@hide}
 */
@SystemApi
public final class RaEvent implements Parcelable {

    // Lifetime in seconds of options found in a single RA packet.
    // When an option is not set, the value of the associated field is -1;
    public final long routerLifetime;
    public final long prefixValidLifetime;
    public final long prefixPreferredLifetime;
    public final long routeInfoLifetime;
    public final long rdnssLifetime;
    public final long dnsslLifetime;

    /** {@hide} */
    public RaEvent(long routerLifetime, long prefixValidLifetime, long prefixPreferredLifetime,
            long routeInfoLifetime, long rdnssLifetime, long dnsslLifetime) {
        this.routerLifetime = routerLifetime;
        this.prefixValidLifetime = prefixValidLifetime;
        this.prefixPreferredLifetime = prefixPreferredLifetime;
        this.routeInfoLifetime = routeInfoLifetime;
        this.rdnssLifetime = rdnssLifetime;
        this.dnsslLifetime = dnsslLifetime;
    }

    private RaEvent(Parcel in) {
        routerLifetime          = in.readLong();
        prefixValidLifetime     = in.readLong();
        prefixPreferredLifetime = in.readLong();
        routeInfoLifetime       = in.readLong();
        rdnssLifetime           = in.readLong();
        dnsslLifetime           = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(routerLifetime);
        out.writeLong(prefixValidLifetime);
        out.writeLong(prefixPreferredLifetime);
        out.writeLong(routeInfoLifetime);
        out.writeLong(rdnssLifetime);
        out.writeLong(dnsslLifetime);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return new StringBuilder("RaEvent(lifetimes: ")
                .append(String.format("router=%ds, ", routerLifetime))
                .append(String.format("prefix_valid=%ds, ", prefixValidLifetime))
                .append(String.format("prefix_preferred=%ds, ", prefixPreferredLifetime))
                .append(String.format("route_info=%ds, ", routeInfoLifetime))
                .append(String.format("rdnss=%ds, ", rdnssLifetime))
                .append(String.format("dnssl=%ds)", dnsslLifetime))
                .toString();
    }

    public static final Parcelable.Creator<RaEvent> CREATOR = new Parcelable.Creator<RaEvent>() {
        public RaEvent createFromParcel(Parcel in) {
            return new RaEvent(in);
        }

        public RaEvent[] newArray(int size) {
            return new RaEvent[size];
        }
    };
}
