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
import android.system.ErrnoException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;

import com.android.okhttp.HostResolver;
import com.android.okhttp.OkHttpClient;

/**
 * Identifies a {@code Network}.  This is supplied to applications via
 * {@link ConnectivityManager.NetworkCallback} in response to the active
 * {@link ConnectivityManager#requestNetwork} or passive
 * {@link ConnectivityManager#registerNetworkCallback} calls.
 * It is used to direct traffic to the given {@code Network}, either on a {@link Socket} basis
 * through a targeted {@link SocketFactory} or process-wide via
 * {@link ConnectivityManager#setProcessDefaultNetwork}.
 */
public class Network implements Parcelable {

    /**
     * @hide
     */
    public final int netId;

    // Objects used to perform per-network operations such as getSocketFactory
    // and openConnection, and a lock to protect access to them.
    private volatile NetworkBoundSocketFactory mNetworkBoundSocketFactory = null;
    private volatile OkHttpClient mOkHttpClient = null;
    private Object mLock = new Object();

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

        private Socket connectToHost(String host, int port, SocketAddress localAddress)
                throws IOException {
            // Lookup addresses only on this Network.
            InetAddress[] hostAddresses = getAllByName(host);
            // Try all addresses.
            for (int i = 0; i < hostAddresses.length; i++) {
                try {
                    Socket socket = createSocket();
                    if (localAddress != null) socket.bind(localAddress);
                    socket.connect(new InetSocketAddress(hostAddresses[i], port));
                    return socket;
                } catch (IOException e) {
                    if (i == (hostAddresses.length - 1)) throw e;
                }
            }
            throw new UnknownHostException(host);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return connectToHost(host, port, new InetSocketAddress(localHost, localPort));
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
            return connectToHost(host, port, null);
        }

        @Override
        public Socket createSocket() throws IOException {
            Socket socket = new Socket();
            bindSocket(socket);
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
            synchronized (mLock) {
                if (mNetworkBoundSocketFactory == null) {
                    mNetworkBoundSocketFactory = new NetworkBoundSocketFactory(netId);
                }
            }
        }
        return mNetworkBoundSocketFactory;
    }

    private void maybeInitHttpClient() {
        if (mOkHttpClient == null) {
            synchronized (mLock) {
                if (mOkHttpClient == null) {
                    HostResolver hostResolver = new HostResolver() {
                        @Override
                        public InetAddress[] getAllByName(String host) throws UnknownHostException {
                            return Network.this.getAllByName(host);
                        }
                    };
                    mOkHttpClient = new OkHttpClient()
                            .setSocketFactory(getSocketFactory())
                            .setHostResolver(hostResolver);
                }
            }
        }
    }

    /**
     * Opens the specified {@link URL} on this {@code Network}, such that all traffic will be sent
     * on this Network. The URL protocol must be {@code HTTP} or {@code HTTPS}.
     *
     * @return a {@code URLConnection} to the resource referred to by this URL.
     * @throws MalformedURLException if the URL protocol is not HTTP or HTTPS.
     * @throws IOException if an error occurs while opening the connection.
     * @see java.net.URL#openConnection()
     */
    public URLConnection openConnection(URL url) throws IOException {
        maybeInitHttpClient();
        String protocol = url.getProtocol();
        URLStreamHandler handler = mOkHttpClient.createURLStreamHandler(protocol);
        if (handler == null) {
            // OkHttpClient only supports HTTP and HTTPS and returns a null URLStreamHandler if
            // passed another protocol.
            throw new MalformedURLException("Invalid URL or unrecognized protocol " + protocol);
        }
        return new URL(url, "", handler).openConnection();
    }

    /**
     * Binds the specified {@link Socket} to this {@code Network}. All data traffic on the socket
     * will be sent on this {@code Network}, irrespective of any process-wide network binding set by
     * {@link ConnectivityManager#setProcessDefaultNetwork}. The socket must not be connected.
     */
    public void bindSocket(Socket socket) throws IOException {
        if (socket.isConnected()) {
            throw new SocketException("Socket is connected");
        }
        // Query a property of the underlying socket to ensure the underlying
        // socket exists so a file descriptor is available to bind to a network.
        socket.getReuseAddress();
        int err = NetworkUtils.bindSocketToNetwork(socket.getFileDescriptor$().getInt$(), netId);
        if (err != 0) {
            // bindSocketToNetwork returns negative errno.
            throw new ErrnoException("Binding socket to network " + netId, -err)
                    .rethrowAsSocketException();
        }
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

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Network == false) return false;
        Network other = (Network)obj;
        return this.netId == other.netId;
    }

    @Override
    public int hashCode() {
        return netId * 11;
    }

    @Override
    public String toString() {
        return Integer.toString(netId);
    }
}
