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

import android.net.LinkProperties;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A class representing a configured network.
 * @hide
 */
public class IpConfiguration implements Parcelable {
    private static final String TAG = "IpConfiguration";

    public enum IpAssignment {
        /* Use statically configured IP settings. Configuration can be accessed
         * with linkProperties */
        STATIC,
        /* Use dynamically configured IP settigns */
        DHCP,
        /* no IP details are assigned, this is used to indicate
         * that any existing IP settings should be retained */
        UNASSIGNED
    }

    public IpAssignment ipAssignment;

    public enum ProxySettings {
        /* No proxy is to be used. Any existing proxy settings
         * should be cleared. */
        NONE,
        /* Use statically configured proxy. Configuration can be accessed
         * with linkProperties */
        STATIC,
        /* no proxy details are assigned, this is used to indicate
         * that any existing proxy settings should be retained */
        UNASSIGNED,
        /* Use a Pac based proxy.
         */
        PAC
    }

    public ProxySettings proxySettings;

    public LinkProperties linkProperties;

    public IpConfiguration(IpConfiguration source) {
        if (source != null) {
            ipAssignment = source.ipAssignment;
            proxySettings = source.proxySettings;
            linkProperties = new LinkProperties(source.linkProperties);
        } else {
            ipAssignment = IpAssignment.UNASSIGNED;
            proxySettings = ProxySettings.UNASSIGNED;
            linkProperties = new LinkProperties();
        }
    }

    public IpConfiguration() {
         this(null);
    }

    public IpConfiguration(IpAssignment ipAssignment,
                           ProxySettings proxySettings,
                           LinkProperties linkProperties) {
        this.ipAssignment = ipAssignment;
        this.proxySettings = proxySettings;
        this.linkProperties = new LinkProperties(linkProperties);
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("IP assignment: " + ipAssignment.toString());
        sbuf.append("\n");
        sbuf.append("Proxy settings: " + proxySettings.toString());
        sbuf.append("\n");
        sbuf.append(linkProperties.toString());
        sbuf.append("\n");

        return sbuf.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof IpConfiguration)) {
            return false;
        }

        IpConfiguration other = (IpConfiguration) o;
        return this.ipAssignment == other.ipAssignment &&
                this.proxySettings == other.proxySettings &&
                Objects.equals(this.linkProperties, other.linkProperties);
    }

    @Override
    public int hashCode() {
        return 13 + (linkProperties != null ? linkProperties.hashCode() : 0) +
               17 * ipAssignment.ordinal() +
               47 * proxySettings.ordinal();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface  */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(ipAssignment.name());
        dest.writeString(proxySettings.name());
        dest.writeParcelable(linkProperties, flags);
    }

    /** Implement the Parcelable interface */
    public static final Creator<IpConfiguration> CREATOR =
        new Creator<IpConfiguration>() {
            public IpConfiguration createFromParcel(Parcel in) {
                IpConfiguration config = new IpConfiguration();
                config.ipAssignment = IpAssignment.valueOf(in.readString());
                config.proxySettings = ProxySettings.valueOf(in.readString());
                config.linkProperties = in.readParcelable(null);
                return config;
            }

            public IpConfiguration[] newArray(int size) {
                return new IpConfiguration[size];
            }
        };
}
