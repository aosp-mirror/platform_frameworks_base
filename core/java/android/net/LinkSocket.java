/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.LinkSocketNotifier;

import android.util.Log;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

/** @hide */
public class LinkSocket extends Socket {
    private final static String TAG = "LinkSocket";
    private final static boolean DBG = true;

    /**
     * Default constructor
     */
    public LinkSocket() {
        if (DBG) log("LinkSocket() EX");
    }

    /**
     * Creates a new unconnected socket.
     * @param notifier a reference to a class that implements {@code LinkSocketNotifier}
     */
    public LinkSocket(LinkSocketNotifier notifier) {
        if (DBG) log("LinkSocket(notifier) EX");
    }

    /**
     * Creates a new unconnected socket usign the given proxy type.
     * @param notifier a reference to a class that implements {@code LinkSocketNotifier}
     * @param proxy the specified proxy for this socket
     * @throws IllegalArgumentException if the argument proxy is null or of an invalid type.
     * @throws SecurityException if a security manager exists and it denies the permission
     *                           to connect to the given proxy.
     */
    public LinkSocket(LinkSocketNotifier notifier, Proxy proxy) {
        if (DBG) log("LinkSocket(notifier, proxy) EX");
    }

    /**
     * @return the {@code LinkProperties} for the socket
     */
    public LinkProperties getLinkProperties() {
        if (DBG) log("LinkProperties() EX");
        return new LinkProperties();
    }

    /**
     * Set the {@code LinkCapabilies} needed for this socket.  If the socket is already connected
     * or is a duplicate socket the request is ignored and {@code false} will
     * be returned. A needs map can be created via the {@code createNeedsMap} static
     * method.
     * @param needs the needs of the socket
     * @return {@code true} if needs are successfully set, {@code false} otherwise
     */
    public boolean setNeededCapabilities(LinkCapabilities needs) {
        if (DBG) log("setNeeds() EX");
        return false;
    }

    /**
     * @return the LinkCapabilites set by setNeededCapabilities, empty if none has been set
     */
    public LinkCapabilities getNeededCapabilities() {
        if (DBG) log("getNeeds() EX");
        return null;
    }

    /**
     * @return all of the {@code LinkCapabilities} of the link used by this socket
     */
    public LinkCapabilities getCapabilities() {
        if (DBG) log("getCapabilities() EX");
        return null;
    }

    /**
     * Returns this LinkSockets set of capabilities, filtered according to
     * the given {@code Set}.  Capabilities in the Set but not available from
     * the link will not be reported in the results.  Capabilities of the link
     * but not listed in the Set will also not be reported in the results.
     * @param capabilities {@code Set} of capabilities requested
     * @return the filtered {@code LinkCapabilities} of this LinkSocket, may be empty
     */
    public LinkCapabilities getCapabilities(Set<Integer> capabilities) {
        if (DBG) log("getCapabilities(capabilities) EX");
        return new LinkCapabilities();
    }

    /**
     * Provide the set of capabilities the application is interested in tracking
     * for this LinkSocket.
     * @param capabilities a {@code Set} of capabilities to track
     */
    public void setTrackedCapabilities(Set<Integer> capabilities) {
        if (DBG) log("setTrackedCapabilities(capabilities) EX");
    }

    /**
     * @return the {@code LinkCapabilities} that are tracked, empty if none has been set.
     */
    public Set<Integer> getTrackedCapabilities() {
        if (DBG) log("getTrackedCapabilities(capabilities) EX");
        return new HashSet<Integer>();
    }

    /**
     * Connects this socket to the given remote host address and port specified
     * by dstName and dstPort.
     * @param dstName the address of the remote host to connect to
     * @param dstPort the port to connect to on the remote host
     * @param timeout the timeout value in milliseconds or 0 for infinite timeout
     * @throws UnknownHostException if the given dstName is invalid
     * @throws IOException if the socket is already connected or an error occurs
     *                     while connecting
     * @throws SocketTimeoutException if the timeout fires
     */
    public void connect(String dstName, int dstPort, int timeout)
            throws UnknownHostException, IOException, SocketTimeoutException {
        if (DBG) log("connect(dstName, dstPort, timeout) EX");
    }

    /**
     * Connects this socket to the given remote host address and port specified
     * by dstName and dstPort.
     * @param dstName the address of the remote host to connect to
     * @param dstPort the port to connect to on the remote host
     * @throws UnknownHostException if the given dstName is invalid
     * @throws IOException if the socket is already connected or an error occurs
     *                     while connecting
     */
    public void connect(String dstName, int dstPort)
            throws UnknownHostException, IOException {
        if (DBG) log("connect(dstName, dstPort, timeout) EX");
    }

    /**
     * Connects this socket to the given remote host address and port specified
     * by the SocketAddress with the specified timeout.
     * @deprecated Use {@code connect(String dstName, int dstPort, int timeout)}
     *             instead.  Using this method may result in reduced functionality.
     * @param remoteAddr the address and port of the remote host to connect to
     * @throws IllegalArgumentException if the given SocketAddress is invalid
     * @throws IOException if the socket is already connected or an error occurs
     *                     while connecting
     * @throws SocketTimeoutException if the timeout expires
     */
    @Override
    @Deprecated
    public void connect(SocketAddress remoteAddr, int timeout)
            throws IOException, SocketTimeoutException {
        if (DBG) log("connect(remoteAddr, timeout) EX DEPRECATED");
    }

    /**
     * Connects this socket to the given remote host address and port specified
     * by the SocketAddress.
     * TODO add comment on all these that the network selection happens during connect
     * and may take 30 seconds
     * @deprecated Use {@code connect(String dstName, int dstPort)}
     *             Using this method may result in reduced functionality.
     * @param remoteAddr the address and port of the remote host to connect to.
     * @throws IllegalArgumentException if the SocketAddress is invalid or not supported.
     * @throws IOException if the socket is already connected or an error occurs
     *                     while connecting
     */
    @Override
    @Deprecated
    public void connect(SocketAddress remoteAddr) throws IOException {
        if (DBG) log("connect(remoteAddr) EX DEPRECATED");
    }

    /**
     * Connect a duplicate socket socket to the same remote host address and port
     * as the original with a timeout parameter.
     * @param timeout the timeout value in milliseconds or 0 for infinite timeout
     * @throws IOException if the socket is already connected or an error occurs
     *                     while connecting
     */
    public void connect(int timeout) throws IOException {
        if (DBG) log("connect(timeout) EX");
    }

    /**
     * Connect a duplicate socket socket to the same remote host address and port
     * as the original.
     * @throws IOException if the socket is already connected or an error occurs
     *                     while connecting
     */
    public void connect() throws IOException {
        if (DBG) log("connect() EX");
    }

    /**
     * Closes the socket.  It is not possible to reconnect or rebind to this
     * socket thereafter which means a new socket instance has to be created.
     * @throws IOException if an error occurs while closing the socket
     */
    @Override
    public synchronized void close() throws IOException {
        if (DBG) log("close() EX");
    }

    /**
     * Request that a new LinkSocket be created using a different radio
     * (such as WiFi or 3G) than the current LinkSocket.  If a different
     * radio is available a call back will be made via {@code onBetterLinkAvail}.
     * If unable to find a better radio, application will be notified via
     * {@code onNewLinkUnavailable}
     * @see LinkSocketNotifier#onBetterLinkAvailable(LinkSocket, LinkSocket)
     * @param linkRequestReason reason for requesting a new link.
     */
    public void requestNewLink(LinkRequestReason linkRequestReason) {
        if (DBG) log("requestNewLink(linkRequestReason) EX");
    }

    /**
     * @deprecated LinkSocket will automatically pick the optimum interface
     *             to bind to
     * @param localAddr the specific address and port on the local machine
     *                  to bind to
     * @throws IOException always as this method is deprecated for LinkSocket
     */
    @Override
    @Deprecated
    public void bind(SocketAddress localAddr) throws UnsupportedOperationException {
        if (DBG) log("bind(localAddr) EX throws IOException");
        throw new UnsupportedOperationException("bind is deprecated for LinkSocket");
    }

    /**
     * Reason codes an application can specify when requesting for a new link.
     * TODO: need better documentation
     */
    public static final class LinkRequestReason {
        /** No constructor */
        private LinkRequestReason() {}

        /** This link is working properly */
        public static final int LINK_PROBLEM_NONE = 0;
        /** This link has an unknown issue */
        public static final int LINK_PROBLEM_UNKNOWN = 1;
    }

    /**
     * Debug logging
     */
    protected static void log(String s) {
        Log.d(TAG, s);
    }
}
