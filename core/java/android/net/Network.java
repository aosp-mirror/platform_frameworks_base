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

import android.net.NetworkUtils;
import android.os.Parcelable;
import android.os.Parcel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import javax.net.SocketFactory;

/**
 * Identifies a {@code Network}.  This is supplied to applications via
 * {@link ConnectivityManager.NetworkCallbackListener} in response to
 * {@link ConnectivityManager#requestNetwork} or {@link ConnectivityManager#listenForNetwork}.
 * It is used to direct traffic to the given {@code Network}, either on a {@link Socket} basis
 * through a targeted {@link SocketFactory} or process-wide via
 * {@link ConnectivityManager#setProcessDefaultNetwork}.
 */
public class Network implements Parcelable {

    /**
     * @hide
     */
    public final int netId;

    private NetworkBoundSocketFactory mNetworkBoundSocketFactory = null;

    /**
     * @hide
     */
    public Network(int netId) {
        this.netId = netId;
    }

    /**
     * @hide
     */
    public Network(Network that) {
        this.netId = that.netId;
    }

    /**
     * Operates the same as {@code InetAddress.getAllByName} except that host
     * resolution is done on this network.
     *
     * @param host the hostname or literal IP string to be resolved.
     * @return the array of addresses associated with the specified host.
     * @throws UnknownHostException if the address lookup fails.
     */
    public InetAddress[] getAllByName(String host) throws UnknownHostException {
        return InetAddress.getAllByNameOnNet(host, netId);
    }

    /**
     * Operates the same as {@code InetAddress.getByName} except that host
     * resolution is done on this network.
     *
     * @param host
     *            the hostName to be resolved to an address or {@code null}.
     * @return the {@code InetAddress} instance representing the host.
     * @throws UnknownHostException
     *             if the address lookup fails.
     */
    public InetAddress getByName(String host) throws UnknownHostException {
        return InetAddress.getByNameOnNet(host, netId);
    }

    /**
     * A {@code SocketFactory} that produces {@code Socket}'s bound to this network.
     */
    private class NetworkBoundSocketFactory extends SocketFactory {
        private final int mNetId;

        public NetworkBoundSocketFactory(int netId) {
            super();
            mNetId = netId;
        }

        private void connectToHost(Socket socket, String host, int port) throws IOException {
            // Lookup addresses only on this Network.
            InetAddress[] hostAddresses = getAllByName(host);
            // Try all but last address ignoring exceptions.
            for (int i = 0; i < hostAddresses.length - 1; i++) {
                try {
                    socket.connect(new InetSocketAddress(hostAddresses[i], port));
                    return;
                } catch (IOException e) {
                }
            }
            // Try last address.  Do throw exceptions.
            socket.connect(new InetSocketAddress(hostAddresses[hostAddresses.length - 1], port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(localHost, localPort));
            connectToHost(socket, host, port);
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(address, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = createSocket();
            connectToHost(socket, host, port);
            return socket;
        }

        @Override
        public Socket createSocket() throws IOException {
            Socket socket = new Socket();
            // Query a property of the underlying socket to ensure the underlying
            // socket exists so a file descriptor is available to bind to a network.
            socket.getReuseAddress();
            NetworkUtils.bindSocketToNetwork(socket.getFileDescriptor$().getInt$(), mNetId);
            return socket;
        }
    }

    /**
     * Returns a {@link SocketFactory} bound to this network.  Any {@link Socket} created by
     * this factory will have its traffic sent over this {@code Network}.  Note that if this
     * {@code Network} ever disconnects, this factory and any {@link Socket} it produced in the
     * past or future will cease to work.
     *
     * @return a {@link SocketFactory} which produces {@link Socket} instances bound to this
     *         {@code Network}.
     */
    public SocketFactory getSocketFactory() {
        if (mNetworkBoundSocketFactory == null) {
            mNetworkBoundSocketFactory = new NetworkBoundSocketFactory(netId);
        }
        return mNetworkBoundSocketFactory;
    }

    // implement the Parcelable interface
    public int describeContents() {
        return 0;
    }
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(netId);
    }

    public static final Creator<Network> CREATOR =
        new Creator<Network>() {
            public Network createFromParcel(Parcel in) {
                int netId = in.readInt();

                return new Network(netId);
            }

            public Network[] newArray(int size) {
                return new Network[size];
            }
    };

    public boolean equals(Object obj) {
        if (obj instanceof Network == false) return false;
        Network other = (Network)obj;
        return this.netId == other.netId;
    }

    public int hashCode() {
        return netId * 11;
    }
}
