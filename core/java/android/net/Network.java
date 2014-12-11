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

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;

import com.android.okhttp.ConnectionPool;
import com.android.okhttp.HostResolver;
import com.android.okhttp.HttpHandler;
import com.android.okhttp.HttpsHandler;
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
    // mLock should be used to control write access to mConnectionPool and mHostResolver.
    // maybeInitHttpClient() must be called prior to reading either variable.
    private volatile ConnectionPool mConnectionPool = null;
    private volatile HostResolver mHostResolver = null;
    private Object mLock = new Object();

    // Default connection pool values. These are evaluated at startup, just
    // like the OkHttp code. Also like the OkHttp code, we will throw parse
    // exceptions at class loading time if the properties are set but are not
    // valid integers.
    private static final boolean httpKeepAlive =
            Boolean.parseBoolean(System.getProperty("http.keepAlive", "true"));
    private static final int httpMaxConnections =
            httpKeepAlive ? Integer.parseInt(System.getProperty("http.maxConnections", "5")) : 0;
    private static final long httpKeepAliveDurationMs =
            Long.parseLong(System.getProperty("http.keepAliveDuration", "300000"));  // 5 minutes.

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

    // TODO: This creates a connection pool and host resolver for
    // every Network object, instead of one for every NetId. This is
    // suboptimal, because an app could potentially have more than one
    // Network object for the same NetId, causing increased memory footprint
    // and performance penalties due to lack of connection reuse (connection
    // setup time, congestion window growth time, etc.).
    //
    // Instead, investigate only having one connection pool and host resolver
    // for every NetId, perhaps by using a static HashMap of NetIds to
    // connection pools and host resolvers. The tricky part is deciding when
    // to remove a map entry; a WeakHashMap shouldn't be used because whether
    // a Network is referenced doesn't correlate with whether a new Network
    // will be instantiated in the near future with the same NetID. A good
    // solution would involve purging empty (or when all connections are timed
    // out) ConnectionPools.
    private void maybeInitHttpClient() {
        synchronized (mLock) {
            if (mHostResolver == null) {
                mHostResolver = new HostResolver() {
                    @Override
                    public InetAddress[] getAllByName(String host) throws UnknownHostException {
                        return Network.this.getAllByName(host);
                    }
                };
            }
            if (mConnectionPool == null) {
                mConnectionPool = new ConnectionPool(httpMaxConnections,
                        httpKeepAliveDurationMs);
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
        final ConnectivityManager cm = ConnectivityManager.getInstance();
        // TODO: Should this be optimized to avoid fetching the global proxy for every request?
        ProxyInfo proxyInfo = cm.getGlobalProxy();
        if (proxyInfo == null) {
            // TODO: Should this be optimized to avoid fetching LinkProperties for every request?
            final LinkProperties lp = cm.getLinkProperties(this);
            if (lp != null) proxyInfo = lp.getHttpProxy();
        }
        java.net.Proxy proxy = null;
        if (proxyInfo != null) {
            proxy = proxyInfo.makeProxy();
        } else {
            proxy = java.net.Proxy.NO_PROXY;
        }
        return openConnection(url, proxy);
    }

    /**
     * Opens the specified {@link URL} on this {@code Network}, such that all traffic will be sent
     * on this Network. The URL protocol must be {@code HTTP} or {@code HTTPS}.
     *
     * @param proxy the proxy through which the connection will be established.
     * @return a {@code URLConnection} to the resource referred to by this URL.
     * @throws MalformedURLException if the URL protocol is not HTTP or HTTPS.
     * @throws IllegalArgumentException if the argument proxy is null.
     * @throws IOException if an error occurs while opening the connection.
     * @see java.net.URL#openConnection()
     * @hide
     */
    public URLConnection openConnection(URL url, java.net.Proxy proxy) throws IOException {
        if (proxy == null) throw new IllegalArgumentException("proxy is null");
        maybeInitHttpClient();
        String protocol = url.getProtocol();
        OkHttpClient client;
        // TODO: HttpHandler creates OkHttpClients that share the default ResponseCache.
        // Could this cause unexpected behavior?
        if (protocol.equals("http")) {
            client = HttpHandler.createHttpOkHttpClient(proxy);
        } else if (protocol.equals("https")) {
            client = HttpsHandler.createHttpsOkHttpClient(proxy);
        } else {
            // OkHttpClient only supports HTTP and HTTPS and returns a null URLStreamHandler if
            // passed another protocol.
            throw new MalformedURLException("Invalid URL or unrecognized protocol " + protocol);
        }
        return client.setSocketFactory(getSocketFactory())
                .setHostResolver(mHostResolver)
                .setConnectionPool(mConnectionPool)
                .open(url);
    }

    /**
     * Binds the specified {@link DatagramSocket} to this {@code Network}. All data traffic on the
     * socket will be sent on this {@code Network}, irrespective of any process-wide network binding
     * set by {@link ConnectivityManager#setProcessDefaultNetwork}. The socket must not be
     * connected.
     */
    public void bindSocket(DatagramSocket socket) throws IOException {
        // Apparently, the kernel doesn't update a connected UDP socket's routing upon mark changes.
        if (socket.isConnected()) {
            throw new SocketException("Socket is connected");
        }
        // Query a property of the underlying socket to ensure that the socket's file descriptor
        // exists, is available to bind to a network and is not closed.
        socket.getReuseAddress();
        bindSocketFd(socket.getFileDescriptor$());
    }

    /**
     * Binds the specified {@link Socket} to this {@code Network}. All data traffic on the socket
     * will be sent on this {@code Network}, irrespective of any process-wide network binding set by
     * {@link ConnectivityManager#setProcessDefaultNetwork}. The socket must not be connected.
     */
    public void bindSocket(Socket socket) throws IOException {
        // Apparently, the kernel doesn't update a connected TCP socket's routing upon mark changes.
        if (socket.isConnected()) {
            throw new SocketException("Socket is connected");
        }
        // Query a property of the underlying socket to ensure that the socket's file descriptor
        // exists, is available to bind to a network and is not closed.
        socket.getReuseAddress();
        bindSocketFd(socket.getFileDescriptor$());
    }

    private void bindSocketFd(FileDescriptor fd) throws IOException {
        int err = NetworkUtils.bindSocketToNetwork(fd.getInt$(), netId);
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
