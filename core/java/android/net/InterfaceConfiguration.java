/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.os.Parcelable;
import android.os.Parcel;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * A simple object for retrieving / setting an interfaces configuration
 * @hide
 */
public class InterfaceConfiguration implements Parcelable {
    public String hwAddr;
    public InetAddress addr;
    public InetAddress mask;
    public String interfaceFlags;

    public InterfaceConfiguration() {
        super();
    }

    public String toString() {
        StringBuffer str = new StringBuffer();

        str.append("ipddress "); str.append(addr.toString());
        str.append(" netmask "); str.append(mask.toString());
        str.append(" flags ").append(interfaceFlags);
        str.append(" hwaddr ").append(hwAddr);

        return str.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(hwAddr);
        if (addr != null) {
            dest.writeByte((byte)1);
            dest.writeByteArray(addr.getAddress());
        } else {
            dest.writeByte((byte)0);
        }
        if (mask != null) {
            dest.writeByte((byte)1);
            dest.writeByteArray(mask.getAddress());
        } else {
            dest.writeByte((byte)0);
        }
        dest.writeString(interfaceFlags);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<InterfaceConfiguration> CREATOR =
        new Creator<InterfaceConfiguration>() {
            public InterfaceConfiguration createFromParcel(Parcel in) {
                InterfaceConfiguration info = new InterfaceConfiguration();
                info.hwAddr = in.readString();
                if (in.readByte() == 1) {
                    try {
                        info.addr = InetAddress.getByAddress(in.createByteArray());
                    } catch (UnknownHostException e) {}
                }
                if (in.readByte() == 1) {
                    try {
                        info.mask = InetAddress.getByAddress(in.createByteArray());
                    } catch (UnknownHostException e) {}
                }
                info.interfaceFlags = in.readString();
                return info;
            }

            public InterfaceConfiguration[] newArray(int size) {
                return new InterfaceConfiguration[size];
            }
        };
}
