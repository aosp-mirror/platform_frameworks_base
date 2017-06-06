/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.AndroidException;
import android.util.Log;
import dalvik.system.CloseGuard;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;

/**
 * This class contains methods for managing IPsec sessions, which will perform kernel-space
 * encryption and decryption of socket or Network traffic.
 *
 * @hide
 */
@SystemService(Context.IPSEC_SERVICE)
public final class IpSecManager {
    private static final String TAG = "IpSecManager";

    /**
     * The Security Parameter Index, SPI, 0 indicates an unknown or invalid index.
     *
     * <p>No IPsec packet may contain an SPI of 0.
     */
    public static final int INVALID_SECURITY_PARAMETER_INDEX = 0;

    /** @hide */
    public interface Status {
        public static final int OK = 0;
        public static final int RESOURCE_UNAVAILABLE = 1;
        public static final int SPI_UNAVAILABLE = 2;
    }

    /** @hide */
    public static final int INVALID_RESOURCE_ID = 0;

    /**
     * Indicates that the combination of remote InetAddress and SPI was non-unique for a given
     * request. If encountered, selection of a new SPI is required before a transform may be
     * created. Note, this should happen very rarely if the SPI is chosen to be sufficiently random
     * or reserved using reserveSecurityParameterIndex.
     */
    public static final class SpiUnavailableException extends AndroidException {
        private final int mSpi;

        /**
         * Construct an exception indicating that a transform with the given SPI is already in use
         * or otherwise unavailable.
         *
         * @param msg Description indicating the colliding SPI
         * @param spi the SPI that could not be used due to a collision
         */
        SpiUnavailableException(String msg, int spi) {
            super(msg + "(spi: " + spi + ")");
            mSpi = spi;
        }

        /** Retrieve the SPI that caused a collision */
        public int getSpi() {
            return mSpi;
        }
    }

    /**
     * Indicates that the requested system resource for IPsec, such as a socket or other system
     * resource is unavailable. If this exception is thrown, try releasing allocated objects of the
     * type requested.
     */
    public static final class ResourceUnavailableException extends AndroidException {

        ResourceUnavailableException(String msg) {
            super(msg);
        }
    }

    private final IIpSecService mService;

    public static final class SecurityParameterIndex implements AutoCloseable {
        private final IIpSecService mService;
        private final InetAddress mRemoteAddress;
        private final CloseGuard mCloseGuard = CloseGuard.get();
        private int mSpi = INVALID_SECURITY_PARAMETER_INDEX;
        private int mResourceId;

        /** Return the underlying SPI held by this object */
        public int getSpi() {
            return mSpi;
        }

        /**
         * Release an SPI that was previously reserved.
         *
         * <p>Release an SPI for use by other users in the system. If a SecurityParameterIndex is
         * applied to an IpSecTransform, it will become unusable for future transforms but should
         * still be closed to ensure system resources are released.
         */
        @Override
        public void close() {
            try {
                mService.releaseSecurityParameterIndex(mResourceId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mCloseGuard.close();
        }

        @Override
        protected void finalize() {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
        }

        private SecurityParameterIndex(
                @NonNull IIpSecService service, int direction, InetAddress remoteAddress, int spi)
                throws ResourceUnavailableException, SpiUnavailableException {
            mService = service;
            mRemoteAddress = remoteAddress;
            try {
                IpSecSpiResponse result =
                        mService.reserveSecurityParameterIndex(
                                direction, remoteAddress.getHostAddress(), spi, new Binder());

                if (result == null) {
                    throw new NullPointerException("Received null response from IpSecService");
                }

                int status = result.status;
                switch (status) {
                    case Status.OK:
                        break;
                    case Status.RESOURCE_UNAVAILABLE:
                        throw new ResourceUnavailableException(
                                "No more SPIs may be allocated by this requester.");
                    case Status.SPI_UNAVAILABLE:
                        throw new SpiUnavailableException("Requested SPI is unavailable", spi);
                    default:
                        throw new RuntimeException(
                                "Unknown status returned by IpSecService: " + status);
                }
                mSpi = result.spi;
                mResourceId = result.resourceId;

                if (mSpi == INVALID_SECURITY_PARAMETER_INDEX) {
                    throw new RuntimeException("Invalid SPI returned by IpSecService: " + status);
                }

                if (mResourceId == INVALID_RESOURCE_ID) {
                    throw new RuntimeException(
                            "Invalid Resource ID returned by IpSecService: " + status);
                }

            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mCloseGuard.open("open");
        }

        /** @hide */
        int getResourceId() {
            return mResourceId;
        }
    }

    /**
     * Reserve an SPI for traffic bound towards the specified remote address.
     *
     * <p>If successful, this SPI is guaranteed available until released by a call to {@link
     * SecurityParameterIndex#close()}.
     *
     * @param direction {@link IpSecTransform#DIRECTION_IN} or {@link IpSecTransform#DIRECTION_OUT}
     * @param remoteAddress address of the remote. SPIs must be unique for each remoteAddress.
     * @return the reserved SecurityParameterIndex
     * @throws ResourceUnavailableException indicating that too many SPIs are currently allocated
     *     for this user
     * @throws SpiUnavailableException indicating that a particular SPI cannot be reserved
     */
    public SecurityParameterIndex reserveSecurityParameterIndex(
            int direction, InetAddress remoteAddress) throws ResourceUnavailableException {
        try {
            return new SecurityParameterIndex(
                    mService,
                    direction,
                    remoteAddress,
                    IpSecManager.INVALID_SECURITY_PARAMETER_INDEX);
        } catch (SpiUnavailableException unlikely) {
            throw new ResourceUnavailableException("No SPIs available");
        }
    }

    /**
     * Reserve an SPI for traffic bound towards the specified remote address.
     *
     * <p>If successful, this SPI is guaranteed available until released by a call to {@link
     * SecurityParameterIndex#close()}.
     *
     * @param direction {@link IpSecTransform#DIRECTION_IN} or {@link IpSecTransform#DIRECTION_OUT}
     * @param remoteAddress address of the remote. SPIs must be unique for each remoteAddress.
     * @param requestedSpi the requested SPI, or '0' to allocate a random SPI.
     * @return the reserved SecurityParameterIndex
     * @throws ResourceUnavailableException indicating that too many SPIs are currently allocated
     *     for this user
     */
    public SecurityParameterIndex reserveSecurityParameterIndex(
            int direction, InetAddress remoteAddress, int requestedSpi)
            throws SpiUnavailableException, ResourceUnavailableException {
        if (requestedSpi == IpSecManager.INVALID_SECURITY_PARAMETER_INDEX) {
            throw new IllegalArgumentException("Requested SPI must be a valid (non-zero) SPI");
        }
        return new SecurityParameterIndex(mService, direction, remoteAddress, requestedSpi);
    }

    /**
     * Apply an active Transport Mode IPsec Transform to a stream socket to perform IPsec
     * encapsulation of the traffic flowing between the socket and the remote InetAddress of that
     * transform. For security reasons, attempts to send traffic to any IP address other than the
     * address associated with that transform will throw an IOException. In addition, if the
     * IpSecTransform is later deactivated, the socket will throw an IOException on any calls to
     * send() or receive() until the transform is removed from the socket by calling {@link
     * #removeTransportModeTransform(Socket, IpSecTransform)};
     *
     * @param socket a stream socket
     * @param transform an {@link IpSecTransform}, which must be an active Transport Mode transform.
     * @hide
     */
    public void applyTransportModeTransform(Socket socket, IpSecTransform transform)
            throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(socket)) {
            applyTransportModeTransform(pfd, transform);
        }
    }

    /**
     * Apply an active Transport Mode IPsec Transform to a datagram socket to perform IPsec
     * encapsulation of the traffic flowing between the socket and the remote InetAddress of that
     * transform. For security reasons, attempts to send traffic to any IP address other than the
     * address associated with that transform will throw an IOException. In addition, if the
     * IpSecTransform is later deactivated, the socket will throw an IOException on any calls to
     * send() or receive() until the transform is removed from the socket by calling {@link
     * #removeTransportModeTransform(DatagramSocket, IpSecTransform)};
     *
     * @param socket a datagram socket
     * @param transform an {@link IpSecTransform}, which must be an active Transport Mode transform.
     * @hide
     */
    public void applyTransportModeTransform(DatagramSocket socket, IpSecTransform transform)
            throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromDatagramSocket(socket)) {
            applyTransportModeTransform(pfd, transform);
        }
    }

    /**
     * Apply an active Transport Mode IPsec Transform to a stream socket to perform IPsec
     * encapsulation of the traffic flowing between the socket and the remote InetAddress of that
     * transform. For security reasons, attempts to send traffic to any IP address other than the
     * address associated with that transform will throw an IOException. In addition, if the
     * IpSecTransform is later deactivated, the socket will throw an IOException on any calls to
     * send() or receive() until the transform is removed from the socket by calling {@link
     * #removeTransportModeTransform(FileDescriptor, IpSecTransform)};
     *
     * @param socket a socket file descriptor
     * @param transform an {@link IpSecTransform}, which must be an active Transport Mode transform.
     */
    public void applyTransportModeTransform(FileDescriptor socket, IpSecTransform transform)
            throws IOException {
        // We dup() the FileDescriptor here because if we don't, then the ParcelFileDescriptor()
        // constructor takes control and closes the user's FD when we exit the method
        // This is behaviorally the same as the other versions, but the PFD constructor does not
        // dup() automatically, whereas PFD.fromSocket() and PDF.fromDatagramSocket() do dup().
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(socket)) {
            applyTransportModeTransform(pfd, transform);
        }
    }

    /* Call down to activate a transform */
    private void applyTransportModeTransform(ParcelFileDescriptor pfd, IpSecTransform transform) {
        try {
            mService.applyTransportModeTransform(pfd, transform.getResourceId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Apply an active Tunnel Mode IPsec Transform to a network, which will tunnel all traffic to
     * and from that network's interface with IPsec (applies an outer IP header and IPsec Header to
     * all traffic, and expects an additional IP header and IPsec Header on all inbound traffic).
     * Applications should probably not use this API directly. Instead, they should use {@link
     * VpnService} to provide VPN capability in a more generic fashion.
     *
     * @param net a {@link Network} that will be tunneled via IP Sec.
     * @param transform an {@link IpSecTransform}, which must be an active Tunnel Mode transform.
     * @hide
     */
    public void applyTunnelModeTransform(Network net, IpSecTransform transform) {}

    /**
     * Remove a transform from a given stream socket. Once removed, traffic on the socket will not
     * be encypted. This allows sockets that have been used for IPsec to be reclaimed for
     * communication in the clear in the event socket reuse is desired. This operation will succeed
     * regardless of the underlying state of a transform. If a transform is removed, communication
     * on all sockets to which that transform was applied will fail until this method is called.
     *
     * @param socket a socket that previously had a transform applied to it.
     * @param transform the IPsec Transform that was previously applied to the given socket
     * @hide
     */
    public void removeTransportModeTransform(Socket socket, IpSecTransform transform)
            throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(socket)) {
            removeTransportModeTransform(pfd, transform);
        }
    }

    /**
     * Remove a transform from a given datagram socket. Once removed, traffic on the socket will not
     * be encypted. This allows sockets that have been used for IPsec to be reclaimed for
     * communication in the clear in the event socket reuse is desired. This operation will succeed
     * regardless of the underlying state of a transform. If a transform is removed, communication
     * on all sockets to which that transform was applied will fail until this method is called.
     *
     * @param socket a socket that previously had a transform applied to it.
     * @param transform the IPsec Transform that was previously applied to the given socket
     * @hide
     */
    public void removeTransportModeTransform(DatagramSocket socket, IpSecTransform transform)
            throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromDatagramSocket(socket)) {
            removeTransportModeTransform(pfd, transform);
        }
    }

    /**
     * Remove a transform from a given stream socket. Once removed, traffic on the socket will not
     * be encypted. This allows sockets that have been used for IPsec to be reclaimed for
     * communication in the clear in the event socket reuse is desired. This operation will succeed
     * regardless of the underlying state of a transform. If a transform is removed, communication
     * on all sockets to which that transform was applied will fail until this method is called.
     *
     * @param socket a socket file descriptor that previously had a transform applied to it.
     * @param transform the IPsec Transform that was previously applied to the given socket
     */
    public void removeTransportModeTransform(FileDescriptor socket, IpSecTransform transform)
            throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(socket)) {
            removeTransportModeTransform(pfd, transform);
        }
    }

    /* Call down to activate a transform */
    private void removeTransportModeTransform(ParcelFileDescriptor pfd, IpSecTransform transform) {
        try {
            mService.removeTransportModeTransform(pfd, transform.getResourceId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove a Tunnel Mode IPsec Transform from a {@link Network}. This must be used as part of
     * cleanup if a tunneled Network experiences a change in default route. The Network will drop
     * all traffic that cannot be routed to the Tunnel's outbound interface. If that interface is
     * lost, all traffic will drop.
     *
     * @param net a network that currently has transform applied to it.
     * @param transform a Tunnel Mode IPsec Transform that has been previously applied to the given
     *     network
     * @hide
     */
    public void removeTunnelModeTransform(Network net, IpSecTransform transform) {}

    /**
     * Class providing access to a system-provided UDP Encapsulation Socket, which may be used for
     * IKE signalling as well as for inbound and outbound UDP encapsulated IPsec traffic.
     *
     * <p>The socket provided by this class cannot be re-bound or closed via the inner
     * FileDescriptor. Instead, disposing of this socket requires a call to close().
     */
    public static final class UdpEncapsulationSocket implements AutoCloseable {
        private final ParcelFileDescriptor mPfd;
        private final IIpSecService mService;
        private final int mResourceId;
        private final int mPort;
        private final CloseGuard mCloseGuard = CloseGuard.get();

        private UdpEncapsulationSocket(@NonNull IIpSecService service, int port)
                throws ResourceUnavailableException, IOException {
            mService = service;
            try {
                IpSecUdpEncapResponse result =
                        mService.openUdpEncapsulationSocket(port, new Binder());
                switch (result.status) {
                    case Status.OK:
                        break;
                    case Status.RESOURCE_UNAVAILABLE:
                        throw new ResourceUnavailableException(
                                "No more Sockets may be allocated by this requester.");
                    default:
                        throw new RuntimeException(
                                "Unknown status returned by IpSecService: " + result.status);
                }
                mResourceId = result.resourceId;
                mPort = result.port;
                mPfd = result.fileDescriptor;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mCloseGuard.open("constructor");
        }

        /** Access the inner UDP Encapsulation Socket */
        public FileDescriptor getSocket() {
            if (mPfd == null) {
                return null;
            }
            return mPfd.getFileDescriptor();
        }

        /** Retrieve the port number of the inner encapsulation socket */
        public int getPort() {
            return mPort;
        }

        @Override
        /**
         * Release the resources that have been reserved for this Socket.
         *
         * <p>This method closes the underlying socket, reducing a user's allocated sockets in the
         * system. This must be done as part of cleanup following use of a socket. Failure to do so
         * will cause the socket to count against a total allocation limit for IpSec and eventually
         * fail due to resource limits.
         *
         * @param fd a file descriptor previously returned as a UDP Encapsulation socket.
         */
        public void close() throws IOException {
            try {
                mService.closeUdpEncapsulationSocket(mResourceId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }

            try {
                mPfd.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close UDP Encapsulation Socket with Port= " + mPort);
                throw e;
            }
            mCloseGuard.close();
        }

        @Override
        protected void finalize() throws Throwable {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            close();
        }

        /** @hide */
        int getResourceId() {
            return mResourceId;
        }
    };

    /**
     * Open a socket that is bound to a free UDP port on the system.
     *
     * <p>By binding in this manner and holding the FileDescriptor, the socket cannot be un-bound by
     * the caller. This provides safe access to a socket on a port that can later be used as a UDP
     * Encapsulation port.
     *
     * <p>This socket reservation works in conjunction with IpSecTransforms, which may re-use the
     * socket port. Explicitly opening this port is only necessary if communication is desired on
     * that port.
     *
     * @param port a local UDP port to be reserved for UDP Encapsulation. is provided, then this
     *     method will bind to the specified port or fail. To retrieve the port number, call {@link
     *     android.system.Os#getsockname(FileDescriptor)}.
     * @return a {@link UdpEncapsulationSocket} that is bound to the requested port for the lifetime
     *     of the object.
     */
    // Returning a socket in this fashion that has been created and bound by the system
    // is the only safe way to ensure that a socket is both accessible to the user and
    // safely usable for Encapsulation without allowing a user to possibly unbind from/close
    // the port, which could potentially impact the traffic of the next user who binds to that
    // socket.
    public UdpEncapsulationSocket openUdpEncapsulationSocket(int port)
            throws IOException, ResourceUnavailableException {
        /*
         * Most range checking is done in the service, but this version of the constructor expects
         * a valid port number, and zero cannot be checked after being passed to the service.
         */
        if (port == 0) {
            throw new IllegalArgumentException("Specified port must be a valid port number!");
        }
        return new UdpEncapsulationSocket(mService, port);
    }

    /**
     * Open a socket that is bound to a port selected by the system.
     *
     * <p>By binding in this manner and holding the FileDescriptor, the socket cannot be un-bound by
     * the caller. This provides safe access to a socket on a port that can later be used as a UDP
     * Encapsulation port.
     *
     * <p>This socket reservation works in conjunction with IpSecTransforms, which may re-use the
     * socket port. Explicitly opening this port is only necessary if communication is desired on
     * that port.
     *
     * @return a {@link UdpEncapsulationSocket} that is bound to an arbitrarily selected port
     */
    // Returning a socket in this fashion that has been created and bound by the system
    // is the only safe way to ensure that a socket is both accessible to the user and
    // safely usable for Encapsulation without allowing a user to possibly unbind from/close
    // the port, which could potentially impact the traffic of the next user who binds to that
    // socket.
    public UdpEncapsulationSocket openUdpEncapsulationSocket()
            throws IOException, ResourceUnavailableException {
        return new UdpEncapsulationSocket(mService, 0);
    }

    /**
     * Retrieve an instance of an IpSecManager within you application context
     *
     * @param context the application context for this manager
     * @hide
     */
    public IpSecManager(IIpSecService service) {
        mService = checkNotNull(service, "missing service");
    }
}
