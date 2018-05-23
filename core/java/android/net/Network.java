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
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.proto.ProtoOutputStream;

import com.android.okhttp.internalandroidapi.Dns;
import com.android.okhttp.internalandroidapi.HttpURLConnectionFactory;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

/**
 * Identifies a {@code Network}.  This is supplied to applications via
 * {@link ConnectivityManager.NetworkCallback} in response to the active
 * {@link ConnectivityManager#requestNetwork} or passive
 * {@link ConnectivityManager#registerNetworkCallback} calls.
 * It is used to direct traffic to the given {@code Network}, either on a {@link Socket} basis
 * through a targeted {@link SocketFactory} or process-wide via
 * {@link ConnectivityManager#bindProcessToNetwork}.
 */
public class Network implements Parcelable {

    /**
     * @hide
     */
    public final int netId;

    // Objects used to perform per-network operations such as getSocketFactory
    // and openConnection, and a lock to protect access to them.
    private volatile NetworkBoundSocketFactory mNetworkBoundSocketFactory = null;
    // mLock should be used to control write access to mUrlConnectionFactory.
    // maybeInitUrlConnectionFactory() must be called prior to reading this field.
    private volatile HttpURLConnectionFactory mUrlConnectionFactory;
    private final Object mLock = new Object();

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
    // Value used to obfuscate network handle longs.
    // The HANDLE_MAGIC value MUST be kept in sync with the corresponding
    // value in the native/android/net.c NDK implementation.
    private static final long HANDLE_MAGIC = 0xcafed00dL;
    private static final int HANDLE_MAGIC_SIZE = 32;

    // A boolean to control how getAllByName()/getByName() behaves in the face
    // of Private DNS.
    //
    // When true, these calls will request that DNS resolution bypass any
    // Private DNS that might otherwise apply. Use of this feature is restricted
    // and permission checks are made by netd (attempts to bypass Private DNS
    // without appropriate permission are silently turned into vanilla DNS
    // requests). This only affects DNS queries made using this network object.
    //
    // It it not parceled to receivers because (a) it can be set or cleared at
    // anytime and (b) receivers should be explicit about attempts to bypass
    // Private DNS so that the intent of the code is easily determined and
    // code search audits are possible.
    private boolean mPrivateDnsBypass = false;

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
        return InetAddress.getAllByNameOnNet(host, getNetIdForResolv());
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
        return InetAddress.getByNameOnNet(host, getNetIdForResolv());
    }

    /**
     * Specify whether or not Private DNS should be bypassed when attempting
     * to use {@link getAllByName()}/{@link getByName()} methods on the given
     * instance for hostname resolution.
     *
     * @hide
     */
    public void setPrivateDnsBypass(boolean bypass) {
        mPrivateDnsBypass = bypass;
    }

    /**
     * Returns a netid marked with the Private DNS bypass flag.
     *
     * This flag must be kept in sync with the NETID_USE_LOCAL_NAMESERVERS flag
     * in system/netd/include/NetdClient.h.
     *
     * @hide
     */
    public int getNetIdForResolv() {
        return mPrivateDnsBypass
                ? (int) (0x80000000L | (long) netId)  // Non-portable DNS resolution flag.
                : netId;
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
                    boolean failed = true;
                    try {
                        if (localAddress != null) socket.bind(localAddress);
                        socket.connect(new InetSocketAddress(hostAddresses[i], port));
                        failed = false;
                        return socket;
                    } finally {
                        if (failed) IoUtils.closeQuietly(socket);
                    }
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
            boolean failed = true;
            try {
                socket.bind(new InetSocketAddress(localAddress, localPort));
                socket.connect(new InetSocketAddress(address, port));
                failed = false;
            } finally {
                if (failed) IoUtils.closeQuietly(socket);
            }
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = createSocket();
            boolean failed = true;
            try {
                socket.connect(new InetSocketAddress(host, port));
                failed = false;
            } finally {
                if (failed) IoUtils.closeQuietly(socket);
            }
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return connectToHost(host, port, null);
        }

        @Override
        public Socket createSocket() throws IOException {
            Socket socket = new Socket();
            boolean failed = true;
            try {
                bindSocket(socket);
                failed = false;
            } finally {
                if (failed) IoUtils.closeQuietly(socket);
            }
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
    private void maybeInitUrlConnectionFactory() {
        synchronized (mLock) {
            if (mUrlConnectionFactory == null) {
                // Set configuration on the HttpURLConnectionFactory that will be good for all
                // connections created by this Network. Configuration that might vary is left
                // until openConnection() and passed as arguments.
                Dns dnsLookup = hostname -> Arrays.asList(Network.this.getAllByName(hostname));
                HttpURLConnectionFactory urlConnectionFactory = new HttpURLConnectionFactory();
                urlConnectionFactory.setDns(dnsLookup); // Let traffic go via dnsLookup
                // A private connection pool just for this Network.
                urlConnectionFactory.setNewConnectionPool(httpMaxConnections,
                        httpKeepAliveDurationMs, TimeUnit.MILLISECONDS);
                mUrlConnectionFactory = urlConnectionFactory;
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
        final ConnectivityManager cm = ConnectivityManager.getInstanceOrNull();
        if (cm == null) {
            throw new IOException("No ConnectivityManager yet constructed, please construct one");
        }
        // TODO: Should this be optimized to avoid fetching the global proxy for every request?
        final ProxyInfo proxyInfo = cm.getProxyForNetwork(this);
        final java.net.Proxy proxy;
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
     */
    public URLConnection openConnection(URL url, java.net.Proxy proxy) throws IOException {
        if (proxy == null) throw new IllegalArgumentException("proxy is null");
        maybeInitUrlConnectionFactory();
        SocketFactory socketFactory = getSocketFactory();
        return mUrlConnectionFactory.openConnection(url, socketFactory, proxy);
    }

    /**
     * Binds the specified {@link DatagramSocket} to this {@code Network}. All data traffic on the
     * socket will be sent on this {@code Network}, irrespective of any process-wide network binding
     * set by {@link ConnectivityManager#bindProcessToNetwork}. The socket must not be
     * connected.
     */
    public void bindSocket(DatagramSocket socket) throws IOException {
        // Query a property of the underlying socket to ensure that the socket's file descriptor
        // exists, is available to bind to a network and is not closed.
        socket.getReuseAddress();
        bindSocket(socket.getFileDescriptor$());
    }

    /**
     * Binds the specified {@link Socket} to this {@code Network}. All data traffic on the socket
     * will be sent on this {@code Network}, irrespective of any process-wide network binding set by
     * {@link ConnectivityManager#bindProcessToNetwork}. The socket must not be connected.
     */
    public void bindSocket(Socket socket) throws IOException {
        // Query a property of the underlying socket to ensure that the socket's file descriptor
        // exists, is available to bind to a network and is not closed.
        socket.getReuseAddress();
        bindSocket(socket.getFileDescriptor$());
    }

    /**
     * Binds the specified {@link FileDescriptor} to this {@code Network}. All data traffic on the
     * socket represented by this file descriptor will be sent on this {@code Network},
     * irrespective of any process-wide network binding set by
     * {@link ConnectivityManager#bindProcessToNetwork}. The socket must not be connected.
     */
    public void bindSocket(FileDescriptor fd) throws IOException {
        try {
            final SocketAddress peer = Os.getpeername(fd);
            final InetAddress inetPeer = ((InetSocketAddress) peer).getAddress();
            if (!inetPeer.isAnyLocalAddress()) {
                // Apparently, the kernel doesn't update a connected UDP socket's
                // routing upon mark changes.
                throw new SocketException("Socket is connected");
            }
        } catch (ErrnoException e) {
            // getpeername() failed.
            if (e.errno != OsConstants.ENOTCONN) {
                throw e.rethrowAsSocketException();
            }
        } catch (ClassCastException e) {
            // Wasn't an InetSocketAddress.
            throw new SocketException("Only AF_INET/AF_INET6 sockets supported");
        }

        final int err = NetworkUtils.bindSocketToNetwork(fd.getInt$(), netId);
        if (err != 0) {
            // bindSocketToNetwork returns negative errno.
            throw new ErrnoException("Binding socket to network " + netId, -err)
                    .rethrowAsSocketException();
        }
    }

    /**
     * Returns a {@link Network} object given a handle returned from {@link #getNetworkHandle}.
     *
     * @param networkHandle a handle returned from {@link #getNetworkHandle}.
     * @return A {@link Network} object derived from {@code networkHandle}.
     */
    public static Network fromNetworkHandle(long networkHandle) {
        if (networkHandle == 0) {
            throw new IllegalArgumentException(
                    "Network.fromNetworkHandle refusing to instantiate NETID_UNSET Network.");
        }
        if ((networkHandle & ((1L << HANDLE_MAGIC_SIZE) - 1)) != HANDLE_MAGIC
                || networkHandle < 0) {
            throw new IllegalArgumentException(
                    "Value passed to fromNetworkHandle() is not a network handle.");
        }
        return new Network((int) (networkHandle >> HANDLE_MAGIC_SIZE));
    }

    /**
     * Returns a handle representing this {@code Network}, for use with the NDK API.
     */
    public long getNetworkHandle() {
        // The network handle is explicitly not the same as the netId.
        //
        // The netId is an implementation detail which might be changed in the
        // future, or which alone (i.e. in the absence of some additional
        // context) might not be sufficient to fully identify a Network.
        //
        // As such, the intention is to prevent accidental misuse of the API
        // that might result if a developer assumed that handles and netIds
        // were identical and passing a netId to a call expecting a handle
        // "just worked".  Such accidental misuse, if widely deployed, might
        // prevent future changes to the semantics of the netId field or
        // inhibit the expansion of state required for Network objects.
        //
        // This extra layer of indirection might be seen as paranoia, and might
        // never end up being necessary, but the added complexity is trivial.
        // At some future date it may be desirable to realign the handle with
        // Multiple Provisioning Domains API recommendations, as made by the
        // IETF mif working group.
        if (netId == 0) {
            return 0L;  // make this zero condition obvious for debugging
        }
        return (((long) netId) << HANDLE_MAGIC_SIZE) | HANDLE_MAGIC;
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

    /** @hide */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(NetworkProto.NET_ID, netId);
        proto.end(token);
    }
}
