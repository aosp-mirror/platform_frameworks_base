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

package android.net.rtp;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.SocketException;

/**
 * RtpStream represents the base class of streams which send and receive network
 * packets with media payloads over Real-time Transport Protocol (RTP).
 *
 * <p class="note">Using this class requires
 * {@link android.Manifest.permission#INTERNET} permission.</p>
 */
public class RtpStream {
    /**
     * This mode indicates that the stream sends and receives packets at the
     * same time. This is the initial mode for new streams.
     */
    public static final int MODE_NORMAL = 0;

    /**
     * This mode indicates that the stream only sends packets.
     */
    public static final int MODE_SEND_ONLY = 1;

    /**
     * This mode indicates that the stream only receives packets.
     */
    public static final int MODE_RECEIVE_ONLY = 2;

    private static final int MODE_LAST = 2;

    private final InetAddress mLocalAddress;
    private final int mLocalPort;

    private InetAddress mRemoteAddress;
    private int mRemotePort = -1;
    private int mMode = MODE_NORMAL;

    private int mNative;
    static {
        System.loadLibrary("rtp_jni");
    }

    /**
     * Creates a RtpStream on the given local address. Note that the local
     * port is assigned automatically to conform with RFC 3550.
     *
     * @param address The network address of the local host to bind to.
     * @throws SocketException if the address cannot be bound or a problem
     *     occurs during binding.
     */
    RtpStream(InetAddress address) throws SocketException {
        mLocalPort = create(address.getHostAddress());
        mLocalAddress = address;
    }

    private native int create(String address) throws SocketException;

    /**
     * Returns the network address of the local host.
     */
    public InetAddress getLocalAddress() {
        return mLocalAddress;
    }

    /**
     * Returns the network port of the local host.
     */
    public int getLocalPort() {
        return mLocalPort;
    }

    /**
     * Returns the network address of the remote host or {@code null} if the
     * stream is not associated.
     */
    public InetAddress getRemoteAddress() {
        return mRemoteAddress;
    }

    /**
     * Returns the network port of the remote host or {@code -1} if the stream
     * is not associated.
     */
    public int getRemotePort() {
        return mRemotePort;
    }

    /**
     * Returns {@code true} if the stream is busy. In this case most of the
     * setter methods are disabled. This method is intended to be overridden
     * by subclasses.
     */
    public boolean isBusy() {
        return false;
    }

    /**
     * Returns the current mode.
     */
    public int getMode() {
        return mMode;
    }

    /**
     * Changes the current mode. It must be one of {@link #MODE_NORMAL},
     * {@link #MODE_SEND_ONLY}, and {@link #MODE_RECEIVE_ONLY}.
     *
     * @param mode The mode to change to.
     * @throws IllegalArgumentException if the mode is invalid.
     * @throws IllegalStateException if the stream is busy.
     * @see #isBusy()
     */
    public void setMode(int mode) {
        if (isBusy()) {
            throw new IllegalStateException("Busy");
        }
        if (mode < 0 || mode > MODE_LAST) {
            throw new IllegalArgumentException("Invalid mode");
        }
        mMode = mode;
    }

    /**
     * Associates with a remote host. This defines the destination of the
     * outgoing packets.
     *
     * @param address The network address of the remote host.
     * @param port The network port of the remote host.
     * @throws IllegalArgumentException if the address is not supported or the
     *     port is invalid.
     * @throws IllegalStateException if the stream is busy.
     * @see #isBusy()
     */
    public void associate(InetAddress address, int port) {
        if (isBusy()) {
            throw new IllegalStateException("Busy");
        }
        if (!(address instanceof Inet4Address && mLocalAddress instanceof Inet4Address) &&
                !(address instanceof Inet6Address && mLocalAddress instanceof Inet6Address)) {
            throw new IllegalArgumentException("Unsupported address");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port");
        }
        mRemoteAddress = address;
        mRemotePort = port;
    }

    synchronized native int dup();

    /**
     * Releases allocated resources. The stream becomes inoperable after calling
     * this method.
     *
     * @throws IllegalStateException if the stream is busy.
     * @see #isBusy()
     */
    public void release() {
        if (isBusy()) {
            throw new IllegalStateException("Busy");
        }
        close();
    }

    private synchronized native void close();

    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }
}
