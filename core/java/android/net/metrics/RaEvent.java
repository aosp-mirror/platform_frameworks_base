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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * An event logged when the APF packet socket receives an RA packet.
 * {@hide}
 */
@SystemApi
@TestApi
public final class RaEvent implements IpConnectivityLog.Event {

    private static final long NO_LIFETIME = -1L;

    // Lifetime in seconds of options found in a single RA packet.
    // When an option is not set, the value of the associated field is -1;
    /** @hide */
    public final long routerLifetime;
    /** @hide */
    public final long prefixValidLifetime;
    /** @hide */
    public final long prefixPreferredLifetime;
    /** @hide */
    public final long routeInfoLifetime;
    /** @hide */
    public final long rdnssLifetime;
    /** @hide */
    public final long dnsslLifetime;

    /** @hide */
    public RaEvent(long routerLifetime, long prefixValidLifetime, long prefixPreferredLifetime,
            long routeInfoLifetime, long rdnssLifetime, long dnsslLifetime) {
        this.routerLifetime = routerLifetime;
        this.prefixValidLifetime = prefixValidLifetime;
        this.prefixPreferredLifetime = prefixPreferredLifetime;
        this.routeInfoLifetime = routeInfoLifetime;
        this.rdnssLifetime = rdnssLifetime;
        this.dnsslLifetime = dnsslLifetime;
    }

    /** @hide */
    private RaEvent(Parcel in) {
        routerLifetime          = in.readLong();
        prefixValidLifetime     = in.readLong();
        prefixPreferredLifetime = in.readLong();
        routeInfoLifetime       = in.readLong();
        rdnssLifetime           = in.readLong();
        dnsslLifetime           = in.readLong();
    }

    /** @hide */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(routerLifetime);
        out.writeLong(prefixValidLifetime);
        out.writeLong(prefixPreferredLifetime);
        out.writeLong(routeInfoLifetime);
        out.writeLong(rdnssLifetime);
        out.writeLong(dnsslLifetime);
    }

    /** @hide */
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

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj.getClass().equals(RaEvent.class))) return false;
        final RaEvent other = (RaEvent) obj;
        return routerLifetime == other.routerLifetime
                && prefixValidLifetime == other.prefixValidLifetime
                && prefixPreferredLifetime == other.prefixPreferredLifetime
                && routeInfoLifetime == other.routeInfoLifetime
                && rdnssLifetime == other.rdnssLifetime
                && dnsslLifetime == other.dnsslLifetime;
    }

    /** @hide */
    public static final @android.annotation.NonNull Parcelable.Creator<RaEvent> CREATOR = new Parcelable.Creator<RaEvent>() {
        public RaEvent createFromParcel(Parcel in) {
            return new RaEvent(in);
        }

        public RaEvent[] newArray(int size) {
            return new RaEvent[size];
        }
    };

    public static final class Builder {

        long routerLifetime          = NO_LIFETIME;
        long prefixValidLifetime     = NO_LIFETIME;
        long prefixPreferredLifetime = NO_LIFETIME;
        long routeInfoLifetime       = NO_LIFETIME;
        long rdnssLifetime           = NO_LIFETIME;
        long dnsslLifetime           = NO_LIFETIME;

        public Builder() {
        }

        public @NonNull RaEvent build() {
            return new RaEvent(routerLifetime, prefixValidLifetime, prefixPreferredLifetime,
                    routeInfoLifetime, rdnssLifetime, dnsslLifetime);
        }

        public @NonNull Builder updateRouterLifetime(long lifetime) {
            routerLifetime = updateLifetime(routerLifetime, lifetime);
            return this;
        }

        public @NonNull Builder updatePrefixValidLifetime(long lifetime) {
            prefixValidLifetime = updateLifetime(prefixValidLifetime, lifetime);
            return this;
        }

        public @NonNull Builder updatePrefixPreferredLifetime(long lifetime) {
            prefixPreferredLifetime = updateLifetime(prefixPreferredLifetime, lifetime);
            return this;
        }

        public @NonNull Builder updateRouteInfoLifetime(long lifetime) {
            routeInfoLifetime = updateLifetime(routeInfoLifetime, lifetime);
            return this;
        }

        public @NonNull Builder updateRdnssLifetime(long lifetime) {
            rdnssLifetime = updateLifetime(rdnssLifetime, lifetime);
            return this;
        }

        public @NonNull Builder updateDnsslLifetime(long lifetime) {
            dnsslLifetime = updateLifetime(dnsslLifetime, lifetime);
            return this;
        }

        private long updateLifetime(long currentLifetime, long newLifetime) {
            if (currentLifetime == RaEvent.NO_LIFETIME) {
                return newLifetime;
            }
            return Math.min(currentLifetime, newLifetime);
        }
    }
}
