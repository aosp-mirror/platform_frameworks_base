/*
 * Copyright (C) 2018 The Android Open Source Project
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * Describe a network connection including local and remote address/port of a connection and the
 * transport protocol.
 *
 * @hide
 */
public final class ConnectionInfo implements Parcelable {
    public final int protocol;
    public final InetSocketAddress local;
    public final InetSocketAddress remote;

    @Override
    public int describeContents() {
        return 0;
    }

    public ConnectionInfo(int protocol, InetSocketAddress local, InetSocketAddress remote) {
        this.protocol = protocol;
        this.local = local;
        this.remote = remote;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(protocol);
        out.writeByteArray(local.getAddress().getAddress());
        out.writeInt(local.getPort());
        out.writeByteArray(remote.getAddress().getAddress());
        out.writeInt(remote.getPort());
    }

    public static final Creator<ConnectionInfo> CREATOR = new Creator<ConnectionInfo>() {
        public ConnectionInfo createFromParcel(Parcel in) {
            int protocol = in.readInt();
            InetAddress localAddress;
            try {
                localAddress = InetAddress.getByAddress(in.createByteArray());
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid InetAddress");
            }
            int localPort = in.readInt();
            InetAddress remoteAddress;
            try {
                remoteAddress = InetAddress.getByAddress(in.createByteArray());
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid InetAddress");
            }
            int remotePort = in.readInt();
            InetSocketAddress local = new InetSocketAddress(localAddress, localPort);
            InetSocketAddress remote = new InetSocketAddress(remoteAddress, remotePort);
            return new ConnectionInfo(protocol, local, remote);
        }

        public ConnectionInfo[] newArray(int size) {
            return new ConnectionInfo[size];
        }
    };
}
