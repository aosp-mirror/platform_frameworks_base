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

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.annotations.PolicyDirection;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.AndroidException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import dalvik.system.CloseGuard;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;

/**
 * This class contains methods for managing IPsec sessions. Once configured, the kernel will apply
 * confidentiality (encryption) and integrity (authentication) to IP traffic.
 *
 * <p>Note that not all aspects of IPsec are permitted by this API. Applications may create
 * transport mode security associations and apply them to individual sockets. Applications looking
 * to create an IPsec VPN should use {@link VpnManager} and {@link Ikev2VpnProfile}.
 *
 * @see <a href="https://tools.ietf.org/html/rfc4301">RFC 4301, Security Architecture for the
 *     Internet Protocol</a>
 */
@SystemService(Context.IPSEC_SERVICE)
public final class IpSecManager {
    private static final String TAG = "IpSecManager";

    /**
     * Used when applying a transform to direct traffic through an {@link IpSecTransform}
     * towards the host.
     *
     * <p>See {@link #applyTransportModeTransform(Socket, int, IpSecTransform)}.
     */
    public static final int DIRECTION_IN = 0;

    /**
     * Used when applying a transform to direct traffic through an {@link IpSecTransform}
     * away from the host.
     *
     * <p>See {@link #applyTransportModeTransform(Socket, int, IpSecTransform)}.
     */
    public static final int DIRECTION_OUT = 1;

    /**
     * Used when applying a transform to direct traffic through an {@link IpSecTransform} for
     * forwarding between interfaces.
     *
     * <p>See {@link #applyTransportModeTransform(Socket, int, IpSecTransform)}.
     *
     * @hide
     */
    public static final int DIRECTION_FWD = 2;

    /**
     * The Security Parameter Index (SPI) 0 indicates an unknown or invalid index.
     *
     * <p>No IPsec packet may contain an SPI of 0.
     *
     * @hide
     */
    @TestApi public static final int INVALID_SECURITY_PARAMETER_INDEX = 0;

    /** @hide */
    public interface Status {
        public static final int OK = 0;
        public static final int RESOURCE_UNAVAILABLE = 1;
        public static final int SPI_UNAVAILABLE = 2;
    }

    /** @hide */
    public static final int INVALID_RESOURCE_ID = -1;

    /**
     * Thrown to indicate that a requested SPI is in use.
     *
     * <p>The combination of remote {@code InetAddress} and SPI must be unique across all apps on
     * one device. If this error is encountered, a new SPI is required before a transform may be
     * created. This error can be avoided by calling {@link
     * IpSecManager#allocateSecurityParameterIndex}.
     */
    public static final class SpiUnavailableException extends AndroidException {
        private final int mSpi;

        /**
         * Construct an exception indicating that a transform with the given SPI is already in use
         * or otherwise unavailable.
         *
         * @param msg description indicating the colliding SPI
         * @param spi the SPI that could not be used due to a collision
         */
        SpiUnavailableException(String msg, int spi) {
            super(msg + " (spi: " + spi + ")");
            mSpi = spi;
        }

        /** Get the SPI that caused a collision. */
        public int getSpi() {
            return mSpi;
        }
    }

    /**
     * Thrown to indicate that an IPsec resource is unavailable.
     *
     * <p>This could apply to resources such as sockets, {@link SecurityParameterIndex}, {@link
     * IpSecTransform}, or other system resources. If this exception is thrown, users should release
     * allocated objects of the type requested.
     */
    public static final class ResourceUnavailableException extends AndroidException {

        ResourceUnavailableException(String msg) {
            super(msg);
        }
    }

    private final Context mContext;
    private final IIpSecService mService;

    /**
     * This class represents a reserved SPI.
     *
     * <p>Objects of this type are used to track reserved security parameter indices. They can be
     * obtained by calling {@link IpSecManager#allocateSecurityParameterIndex} and must be released
     * by calling {@link #close()} when they are no longer needed.
     */
    public static final class SecurityParameterIndex implements AutoCloseable {
        private final IIpSecService mService;
        private final InetAddress mDestinationAddress;
        private final CloseGuard mCloseGuard = CloseGuard.get();
        private int mSpi = INVALID_SECURITY_PARAMETER_INDEX;
        private int mResourceId = INVALID_RESOURCE_ID;

        /** Get the underlying SPI held by this object. */
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
            } catch (Exception e) {
                // On close we swallow all random exceptions since failure to close is not
                // actionable by the user.
                Log.e(TAG, "Failed to close " + this + ", Exception=" + e);
            } finally {
                mResourceId = INVALID_RESOURCE_ID;
                mCloseGuard.close();
            }
        }

        /** Check that the SPI was closed properly. */
        @Override
        protected void finalize() throws Throwable {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
        }

        private SecurityParameterIndex(
                @NonNull IIpSecService service, InetAddress destinationAddress, int spi)
                throws ResourceUnavailableException, SpiUnavailableException {
            mService = service;
            mDestinationAddress = destinationAddress;
            try {
                IpSecSpiResponse result =
                        mService.allocateSecurityParameterIndex(
                                destinationAddress.getHostAddress(), spi, new Binder());

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
        @VisibleForTesting
        public int getResourceId() {
            return mResourceId;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                .append("SecurityParameterIndex{spi=")
                .append(mSpi)
                .append(",resourceId=")
                .append(mResourceId)
                .append("}")
                .toString();
        }
    }

    /**
     * Reserve a random SPI for traffic bound to or from the specified destination address.
     *
     * <p>If successful, this SPI is guaranteed available until released by a call to {@link
     * SecurityParameterIndex#close()}.
     *
     * @param destinationAddress the destination address for traffic bearing the requested SPI.
     *     For inbound traffic, the destination should be an address currently assigned on-device.
     * @return the reserved SecurityParameterIndex
     * @throws {@link #ResourceUnavailableException} indicating that too many SPIs are
     *     currently allocated for this user
     */
    @NonNull
    public SecurityParameterIndex allocateSecurityParameterIndex(
                @NonNull InetAddress destinationAddress) throws ResourceUnavailableException {
        try {
            return new SecurityParameterIndex(
                    mService,
                    destinationAddress,
                    IpSecManager.INVALID_SECURITY_PARAMETER_INDEX);
        } catch (ServiceSpecificException e) {
            throw rethrowUncheckedExceptionFromServiceSpecificException(e);
        } catch (SpiUnavailableException unlikely) {
            // Because this function allocates a totally random SPI, it really shouldn't ever
            // fail to allocate an SPI; we simply need this because the exception is checked.
            throw new ResourceUnavailableException("No SPIs available");
        }
    }

    /**
     * Reserve the requested SPI for traffic bound to or from the specified destination address.
     *
     * <p>If successful, this SPI is guaranteed available until released by a call to {@link
     * SecurityParameterIndex#close()}.
     *
     * @param destinationAddress the destination address for traffic bearing the requested SPI.
     *     For inbound traffic, the destination should be an address currently assigned on-device.
     * @param requestedSpi the requested SPI. The range 1-255 is reserved and may not be used. See
     *     RFC 4303 Section 2.1.
     * @return the reserved SecurityParameterIndex
     * @throws {@link #ResourceUnavailableException} indicating that too many SPIs are
     *     currently allocated for this user
     * @throws {@link #SpiUnavailableException} indicating that the requested SPI could not be
     *     reserved
     */
    @NonNull
    public SecurityParameterIndex allocateSecurityParameterIndex(
            @NonNull InetAddress destinationAddress, int requestedSpi)
            throws SpiUnavailableException, ResourceUnavailableException {
        if (requestedSpi == IpSecManager.INVALID_SECURITY_PARAMETER_INDEX) {
            throw new IllegalArgumentException("Requested SPI must be a valid (non-zero) SPI");
        }
        try {
            return new SecurityParameterIndex(mService, destinationAddress, requestedSpi);
        } catch (ServiceSpecificException e) {
            throw rethrowUncheckedExceptionFromServiceSpecificException(e);
        }
    }

    /**
     * Apply an IPsec transform to a stream socket.
     *
     * <p>This applies transport mode encapsulation to the given socket. Once applied, I/O on the
     * socket will be encapsulated according to the parameters of the {@code IpSecTransform}. When
     * the transform is removed from the socket by calling {@link #removeTransportModeTransforms},
     * unprotected traffic can resume on that socket.
     *
     * <p>For security reasons, the destination address of any traffic on the socket must match the
     * remote {@code InetAddress} of the {@code IpSecTransform}. Attempts to send traffic to any
     * other IP address will result in an IOException. In addition, reads and writes on the socket
     * will throw IOException if the user deactivates the transform (by calling {@link
     * IpSecTransform#close()}) without calling {@link #removeTransportModeTransforms}.
     *
     * <p>Note that when applied to TCP sockets, calling {@link IpSecTransform#close()} on an
     * applied transform before completion of graceful shutdown may result in the shutdown sequence
     * failing to complete. As such, applications requiring graceful shutdown MUST close the socket
     * prior to deactivating the applied transform. Socket closure may be performed asynchronously
     * (in batches), so the returning of a close function does not guarantee shutdown of a socket.
     * Setting an SO_LINGER timeout results in socket closure being performed synchronously, and is
     * sufficient to ensure shutdown.
     *
     * Specifically, if the transform is deactivated (by calling {@link IpSecTransform#close()}),
     * prior to the socket being closed, the standard [FIN - FIN/ACK - ACK], or the reset [RST]
     * packets are dropped due to the lack of a valid Transform. Similarly, if a socket without the
     * SO_LINGER option set is closed, the delayed/batched FIN packets may be dropped.
     *
     * <h4>Rekey Procedure</h4>
     *
     * <p>When applying a new tranform to a socket in the outbound direction, the previous transform
     * will be removed and the new transform will take effect immediately, sending all traffic on
     * the new transform; however, when applying a transform in the inbound direction, traffic
     * on the old transform will continue to be decrypted and delivered until that transform is
     * deallocated by calling {@link IpSecTransform#close()}. This overlap allows lossless rekey
     * procedures where both transforms are valid until both endpoints are using the new transform
     * and all in-flight packets have been received.
     *
     * @param socket a stream socket
     * @param direction the direction in which the transform should be applied
     * @param transform a transport mode {@code IpSecTransform}
     * @throws IOException indicating that the transform could not be applied
     */
    public void applyTransportModeTransform(@NonNull Socket socket,
            @PolicyDirection int direction, @NonNull IpSecTransform transform) throws IOException {
        // Ensure creation of FD. See b/77548890 for more details.
        socket.getSoLinger();

        applyTransportModeTransform(socket.getFileDescriptor$(), direction, transform);
    }

    /**
     * Apply an IPsec transform to a datagram socket.
     *
     * <p>This applies transport mode encapsulation to the given socket. Once applied, I/O on the
     * socket will be encapsulated according to the parameters of the {@code IpSecTransform}. When
     * the transform is removed from the socket by calling {@link #removeTransportModeTransforms},
     * unprotected traffic can resume on that socket.
     *
     * <p>For security reasons, the destination address of any traffic on the socket must match the
     * remote {@code InetAddress} of the {@code IpSecTransform}. Attempts to send traffic to any
     * other IP address will result in an IOException. In addition, reads and writes on the socket
     * will throw IOException if the user deactivates the transform (by calling {@link
     * IpSecTransform#close()}) without calling {@link #removeTransportModeTransforms}.
     *
     * <h4>Rekey Procedure</h4>
     *
     * <p>When applying a new tranform to a socket in the outbound direction, the previous transform
     * will be removed and the new transform will take effect immediately, sending all traffic on
     * the new transform; however, when applying a transform in the inbound direction, traffic
     * on the old transform will continue to be decrypted and delivered until that transform is
     * deallocated by calling {@link IpSecTransform#close()}. This overlap allows lossless rekey
     * procedures where both transforms are valid until both endpoints are using the new transform
     * and all in-flight packets have been received.
     *
     * @param socket a datagram socket
     * @param direction the direction in which the transform should be applied
     * @param transform a transport mode {@code IpSecTransform}
     * @throws IOException indicating that the transform could not be applied
     */
    public void applyTransportModeTransform(@NonNull DatagramSocket socket,
            @PolicyDirection int direction, @NonNull IpSecTransform transform) throws IOException {
        applyTransportModeTransform(socket.getFileDescriptor$(), direction, transform);
    }

    /**
     * Apply an IPsec transform to a socket.
     *
     * <p>This applies transport mode encapsulation to the given socket. Once applied, I/O on the
     * socket will be encapsulated according to the parameters of the {@code IpSecTransform}. When
     * the transform is removed from the socket by calling {@link #removeTransportModeTransforms},
     * unprotected traffic can resume on that socket.
     *
     * <p>For security reasons, the destination address of any traffic on the socket must match the
     * remote {@code InetAddress} of the {@code IpSecTransform}. Attempts to send traffic to any
     * other IP address will result in an IOException. In addition, reads and writes on the socket
     * will throw IOException if the user deactivates the transform (by calling {@link
     * IpSecTransform#close()}) without calling {@link #removeTransportModeTransforms}.
     *
     * <p>Note that when applied to TCP sockets, calling {@link IpSecTransform#close()} on an
     * applied transform before completion of graceful shutdown may result in the shutdown sequence
     * failing to complete. As such, applications requiring graceful shutdown MUST close the socket
     * prior to deactivating the applied transform. Socket closure may be performed asynchronously
     * (in batches), so the returning of a close function does not guarantee shutdown of a socket.
     * Setting an SO_LINGER timeout results in socket closure being performed synchronously, and is
     * sufficient to ensure shutdown.
     *
     * Specifically, if the transform is deactivated (by calling {@link IpSecTransform#close()}),
     * prior to the socket being closed, the standard [FIN - FIN/ACK - ACK], or the reset [RST]
     * packets are dropped due to the lack of a valid Transform. Similarly, if a socket without the
     * SO_LINGER option set is closed, the delayed/batched FIN packets may be dropped.
     *
     * <h4>Rekey Procedure</h4>
     *
     * <p>When applying a new tranform to a socket in the outbound direction, the previous transform
     * will be removed and the new transform will take effect immediately, sending all traffic on
     * the new transform; however, when applying a transform in the inbound direction, traffic
     * on the old transform will continue to be decrypted and delivered until that transform is
     * deallocated by calling {@link IpSecTransform#close()}. This overlap allows lossless rekey
     * procedures where both transforms are valid until both endpoints are using the new transform
     * and all in-flight packets have been received.
     *
     * @param socket a socket file descriptor
     * @param direction the direction in which the transform should be applied
     * @param transform a transport mode {@code IpSecTransform}
     * @throws IOException indicating that the transform could not be applied
     */
    public void applyTransportModeTransform(@NonNull FileDescriptor socket,
            @PolicyDirection int direction, @NonNull IpSecTransform transform) throws IOException {
        // We dup() the FileDescriptor here because if we don't, then the ParcelFileDescriptor()
        // constructor takes control and closes the user's FD when we exit the method.
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(socket)) {
            mService.applyTransportModeTransform(pfd, direction, transform.getResourceId());
        } catch (ServiceSpecificException e) {
            throw rethrowCheckedExceptionFromServiceSpecificException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove an IPsec transform from a stream socket.
     *
     * <p>Once removed, traffic on the socket will not be encrypted. Removing transforms from a
     * socket allows the socket to be reused for communication in the clear.
     *
     * <p>If an {@code IpSecTransform} object applied to this socket was deallocated by calling
     * {@link IpSecTransform#close()}, then communication on the socket will fail until this method
     * is called.
     *
     * @param socket a socket that previously had a transform applied to it
     * @throws IOException indicating that the transform could not be removed from the socket
     */
    public void removeTransportModeTransforms(@NonNull Socket socket) throws IOException {
        // Ensure creation of FD. See b/77548890 for more details.
        socket.getSoLinger();

        removeTransportModeTransforms(socket.getFileDescriptor$());
    }

    /**
     * Remove an IPsec transform from a datagram socket.
     *
     * <p>Once removed, traffic on the socket will not be encrypted. Removing transforms from a
     * socket allows the socket to be reused for communication in the clear.
     *
     * <p>If an {@code IpSecTransform} object applied to this socket was deallocated by calling
     * {@link IpSecTransform#close()}, then communication on the socket will fail until this method
     * is called.
     *
     * @param socket a socket that previously had a transform applied to it
     * @throws IOException indicating that the transform could not be removed from the socket
     */
    public void removeTransportModeTransforms(@NonNull DatagramSocket socket) throws IOException {
        removeTransportModeTransforms(socket.getFileDescriptor$());
    }

    /**
     * Remove an IPsec transform from a socket.
     *
     * <p>Once removed, traffic on the socket will not be encrypted. Removing transforms from a
     * socket allows the socket to be reused for communication in the clear.
     *
     * <p>If an {@code IpSecTransform} object applied to this socket was deallocated by calling
     * {@link IpSecTransform#close()}, then communication on the socket will fail until this method
     * is called.
     *
     * @param socket a socket that previously had a transform applied to it
     * @throws IOException indicating that the transform could not be removed from the socket
     */
    public void removeTransportModeTransforms(@NonNull FileDescriptor socket) throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(socket)) {
            mService.removeTransportModeTransforms(pfd);
        } catch (ServiceSpecificException e) {
            throw rethrowCheckedExceptionFromServiceSpecificException(e);
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
     * <p>TODO: Update javadoc for tunnel mode APIs at the same time the APIs are re-worked.
     *
     * @param net a network that currently has transform applied to it.
     * @param transform a Tunnel Mode IPsec Transform that has been previously applied to the given
     *     network
     * @hide
     */
    public void removeTunnelModeTransform(Network net, IpSecTransform transform) {}

    /**
     * This class provides access to a UDP encapsulation Socket.
     *
     * <p>{@code UdpEncapsulationSocket} wraps a system-provided datagram socket intended for IKEv2
     * signalling and UDP encapsulated IPsec traffic. Instances can be obtained by calling {@link
     * IpSecManager#openUdpEncapsulationSocket}. The provided socket cannot be re-bound by the
     * caller. The caller should not close the {@code FileDescriptor} returned by {@link
     * #getFileDescriptor}, but should use {@link #close} instead.
     *
     * <p>Allowing the user to close or unbind a UDP encapsulation socket could impact the traffic
     * of the next user who binds to that port. To prevent this scenario, these sockets are held
     * open by the system so that they may only be closed by calling {@link #close} or when the user
     * process exits.
     */
    public static final class UdpEncapsulationSocket implements AutoCloseable {
        private final ParcelFileDescriptor mPfd;
        private final IIpSecService mService;
        private int mResourceId = INVALID_RESOURCE_ID;
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

        /** Get the encapsulation socket's file descriptor. */
        public FileDescriptor getFileDescriptor() {
            if (mPfd == null) {
                return null;
            }
            return mPfd.getFileDescriptor();
        }

        /** Get the bound port of the wrapped socket. */
        public int getPort() {
            return mPort;
        }

        /**
         * Close this socket.
         *
         * <p>This closes the wrapped socket. Open encapsulation sockets count against a user's
         * resource limits, and forgetting to close them eventually will result in {@link
         * ResourceUnavailableException} being thrown.
         */
        @Override
        public void close() throws IOException {
            try {
                mService.closeUdpEncapsulationSocket(mResourceId);
                mResourceId = INVALID_RESOURCE_ID;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (Exception e) {
                // On close we swallow all random exceptions since failure to close is not
                // actionable by the user.
                Log.e(TAG, "Failed to close " + this + ", Exception=" + e);
            } finally {
                mResourceId = INVALID_RESOURCE_ID;
                mCloseGuard.close();
            }

            try {
                mPfd.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close UDP Encapsulation Socket with Port= " + mPort);
                throw e;
            }
        }

        /** Check that the socket was closed properly. */
        @Override
        protected void finalize() throws Throwable {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            close();
        }

        /** @hide */
        @SystemApi(client = MODULE_LIBRARIES)
        public int getResourceId() {
            return mResourceId;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                .append("UdpEncapsulationSocket{port=")
                .append(mPort)
                .append(",resourceId=")
                .append(mResourceId)
                .append("}")
                .toString();
        }
    };

    /**
     * Open a socket for UDP encapsulation and bind to the given port.
     *
     * <p>See {@link UdpEncapsulationSocket} for the proper way to close the returned socket.
     *
     * @param port a local UDP port
     * @return a socket that is bound to the given port
     * @throws IOException indicating that the socket could not be opened or bound
     * @throws ResourceUnavailableException indicating that too many encapsulation sockets are open
     */
    // Returning a socket in this fashion that has been created and bound by the system
    // is the only safe way to ensure that a socket is both accessible to the user and
    // safely usable for Encapsulation without allowing a user to possibly unbind from/close
    // the port, which could potentially impact the traffic of the next user who binds to that
    // socket.
    @NonNull
    public UdpEncapsulationSocket openUdpEncapsulationSocket(int port)
            throws IOException, ResourceUnavailableException {
        /*
         * Most range checking is done in the service, but this version of the constructor expects
         * a valid port number, and zero cannot be checked after being passed to the service.
         */
        if (port == 0) {
            throw new IllegalArgumentException("Specified port must be a valid port number!");
        }
        try {
            return new UdpEncapsulationSocket(mService, port);
        } catch (ServiceSpecificException e) {
            throw rethrowCheckedExceptionFromServiceSpecificException(e);
        }
    }

    /**
     * Open a socket for UDP encapsulation.
     *
     * <p>See {@link UdpEncapsulationSocket} for the proper way to close the returned socket.
     *
     * <p>The local port of the returned socket can be obtained by calling {@link
     * UdpEncapsulationSocket#getPort()}.
     *
     * @return a socket that is bound to a local port
     * @throws IOException indicating that the socket could not be opened or bound
     * @throws ResourceUnavailableException indicating that too many encapsulation sockets are open
     */
    // Returning a socket in this fashion that has been created and bound by the system
    // is the only safe way to ensure that a socket is both accessible to the user and
    // safely usable for Encapsulation without allowing a user to possibly unbind from/close
    // the port, which could potentially impact the traffic of the next user who binds to that
    // socket.
    @NonNull
    public UdpEncapsulationSocket openUdpEncapsulationSocket()
            throws IOException, ResourceUnavailableException {
        try {
            return new UdpEncapsulationSocket(mService, 0);
        } catch (ServiceSpecificException e) {
            throw rethrowCheckedExceptionFromServiceSpecificException(e);
        }
    }

    /**
     * This class represents an IpSecTunnelInterface
     *
     * <p>IpSecTunnelInterface objects track tunnel interfaces that serve as
     * local endpoints for IPsec tunnels.
     *
     * <p>Creating an IpSecTunnelInterface creates a device to which IpSecTransforms may be
     * applied to provide IPsec security to packets sent through the tunnel. While a tunnel
     * cannot be used in standalone mode within Android, the higher layers may use the tunnel
     * to create Network objects which are accessible to the Android system.
     * @hide
     */
    @SystemApi
    public static final class IpSecTunnelInterface implements AutoCloseable {
        private final String mOpPackageName;
        private final IIpSecService mService;
        private final InetAddress mRemoteAddress;
        private final InetAddress mLocalAddress;
        private final Network mUnderlyingNetwork;
        private final CloseGuard mCloseGuard = CloseGuard.get();
        private String mInterfaceName;
        private int mResourceId = INVALID_RESOURCE_ID;

        /** Get the underlying SPI held by this object. */
        @NonNull
        public String getInterfaceName() {
            return mInterfaceName;
        }

        /**
         * Add an address to the IpSecTunnelInterface
         *
         * <p>Add an address which may be used as the local inner address for
         * tunneled traffic.
         *
         * @param address the local address for traffic inside the tunnel
         * @param prefixLen length of the InetAddress prefix
         * @hide
         */
        @SystemApi
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        @RequiresPermission(android.Manifest.permission.MANAGE_IPSEC_TUNNELS)
        public void addAddress(@NonNull InetAddress address, int prefixLen) throws IOException {
            try {
                mService.addAddressToTunnelInterface(
                        mResourceId, new LinkAddress(address, prefixLen), mOpPackageName);
            } catch (ServiceSpecificException e) {
                throw rethrowCheckedExceptionFromServiceSpecificException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Remove an address from the IpSecTunnelInterface
         *
         * <p>Remove an address which was previously added to the IpSecTunnelInterface
         *
         * @param address to be removed
         * @param prefixLen length of the InetAddress prefix
         * @hide
         */
        @SystemApi
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        @RequiresPermission(android.Manifest.permission.MANAGE_IPSEC_TUNNELS)
        public void removeAddress(@NonNull InetAddress address, int prefixLen) throws IOException {
            try {
                mService.removeAddressFromTunnelInterface(
                        mResourceId, new LinkAddress(address, prefixLen), mOpPackageName);
            } catch (ServiceSpecificException e) {
                throw rethrowCheckedExceptionFromServiceSpecificException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Update the underlying network for this IpSecTunnelInterface.
         *
         * <p>This new underlying network will be used for all transforms applied AFTER this call is
         * complete. Before new {@link IpSecTransform}(s) with matching addresses are applied to
         * this tunnel interface, traffic will still use the old SA, and be routed on the old
         * underlying network.
         *
         * <p>To migrate IPsec tunnel mode traffic, a caller should:
         *
         * <ol>
         *   <li>Update the IpSecTunnelInterface’s underlying network.
         *   <li>Apply {@link IpSecTransform}(s) with matching addresses to this
         *       IpSecTunnelInterface.
         * </ol>
         *
         * @param underlyingNetwork the new {@link Network} that will carry traffic for this tunnel.
         *     This network MUST never be the network exposing this IpSecTunnelInterface, otherwise
         *     this method will throw an {@link IllegalArgumentException}. If the
         *     IpSecTunnelInterface is later added to this network, all outbound traffic will be
         *     blackholed.
         */
        // TODO: b/169171001 Update the documentation when transform migration is supported.
        // The purpose of making updating network and applying transforms separate is to leave open
        // the possibility to support lossless migration procedures. To do that, Android platform
        // will need to support multiple inbound tunnel mode transforms, just like it can support
        // multiple transport mode transforms.
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        @RequiresPermission(android.Manifest.permission.MANAGE_IPSEC_TUNNELS)
        public void setUnderlyingNetwork(@NonNull Network underlyingNetwork) throws IOException {
            try {
                mService.setNetworkForTunnelInterface(
                        mResourceId, underlyingNetwork, mOpPackageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        private IpSecTunnelInterface(@NonNull Context ctx, @NonNull IIpSecService service,
                @NonNull InetAddress localAddress, @NonNull InetAddress remoteAddress,
                @NonNull Network underlyingNetwork)
                throws ResourceUnavailableException, IOException {
            mOpPackageName = ctx.getOpPackageName();
            mService = service;
            mLocalAddress = localAddress;
            mRemoteAddress = remoteAddress;
            mUnderlyingNetwork = underlyingNetwork;

            try {
                IpSecTunnelInterfaceResponse result =
                        mService.createTunnelInterface(
                                localAddress.getHostAddress(),
                                remoteAddress.getHostAddress(),
                                underlyingNetwork,
                                new Binder(),
                                mOpPackageName);
                switch (result.status) {
                    case Status.OK:
                        break;
                    case Status.RESOURCE_UNAVAILABLE:
                        throw new ResourceUnavailableException(
                                "No more tunnel interfaces may be allocated by this requester.");
                    default:
                        throw new RuntimeException(
                                "Unknown status returned by IpSecService: " + result.status);
                }
                mResourceId = result.resourceId;
                mInterfaceName = result.interfaceName;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mCloseGuard.open("constructor");
        }

        /**
         * Delete an IpSecTunnelInterface
         *
         * <p>Calling close will deallocate the IpSecTunnelInterface and all of its system
         * resources. Any packets bound for this interface either inbound or outbound will
         * all be lost.
         */
        @Override
        public void close() {
            try {
                mService.deleteTunnelInterface(mResourceId, mOpPackageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (Exception e) {
                // On close we swallow all random exceptions since failure to close is not
                // actionable by the user.
                Log.e(TAG, "Failed to close " + this + ", Exception=" + e);
            } finally {
                mResourceId = INVALID_RESOURCE_ID;
                mCloseGuard.close();
            }
        }

        /** Check that the Interface was closed properly. */
        @Override
        protected void finalize() throws Throwable {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            close();
        }

        /** @hide */
        @VisibleForTesting
        public int getResourceId() {
            return mResourceId;
        }

        @NonNull
        @Override
        public String toString() {
            return new StringBuilder()
                .append("IpSecTunnelInterface{ifname=")
                .append(mInterfaceName)
                .append(",resourceId=")
                .append(mResourceId)
                .append("}")
                .toString();
        }
    }

    /**
     * Create a new IpSecTunnelInterface as a local endpoint for tunneled IPsec traffic.
     *
     * <p>An application that creates tunnels is responsible for cleaning up the tunnel when the
     * underlying network goes away, and the onLost() callback is received.
     *
     * @param localAddress The local addres of the tunnel
     * @param remoteAddress The local addres of the tunnel
     * @param underlyingNetwork the {@link Network} that will carry traffic for this tunnel.
     *        This network should almost certainly be a network such as WiFi with an L2 address.
     * @return a new {@link IpSecManager#IpSecTunnelInterface} with the specified properties
     * @throws IOException indicating that the socket could not be opened or bound
     * @throws ResourceUnavailableException indicating that too many encapsulation sockets are open
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
    @RequiresPermission(android.Manifest.permission.MANAGE_IPSEC_TUNNELS)
    public IpSecTunnelInterface createIpSecTunnelInterface(@NonNull InetAddress localAddress,
            @NonNull InetAddress remoteAddress, @NonNull Network underlyingNetwork)
            throws ResourceUnavailableException, IOException {
        try {
            return new IpSecTunnelInterface(
                    mContext, mService, localAddress, remoteAddress, underlyingNetwork);
        } catch (ServiceSpecificException e) {
            throw rethrowCheckedExceptionFromServiceSpecificException(e);
        }
    }

    /**
     * Apply an active Tunnel Mode IPsec Transform to a {@link IpSecTunnelInterface}, which will
     * tunnel all traffic for the given direction through the underlying network's interface with
     * IPsec (applies an outer IP header and IPsec Header to all traffic, and expects an additional
     * IP header and IPsec Header on all inbound traffic).
     * <p>Applications should probably not use this API directly.
     *
     *
     * @param tunnel The {@link IpSecManager#IpSecTunnelInterface} that will use the supplied
     *        transform.
     * @param direction the direction, {@link DIRECTION_OUT} or {@link #DIRECTION_IN} in which
     *        the transform will be used.
     * @param transform an {@link IpSecTransform} created in tunnel mode
     * @throws IOException indicating that the transform could not be applied due to a lower
     *         layer failure.
     * @hide
     */
    @SystemApi
    @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
    @RequiresPermission(android.Manifest.permission.MANAGE_IPSEC_TUNNELS)
    public void applyTunnelModeTransform(@NonNull IpSecTunnelInterface tunnel,
            @PolicyDirection int direction, @NonNull IpSecTransform transform) throws IOException {
        try {
            mService.applyTunnelModeTransform(
                    tunnel.getResourceId(), direction,
                    transform.getResourceId(), mContext.getOpPackageName());
        } catch (ServiceSpecificException e) {
            throw rethrowCheckedExceptionFromServiceSpecificException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Construct an instance of IpSecManager within an application context.
     *
     * @param context the application context for this manager
     * @hide
     */
    public IpSecManager(Context ctx, IIpSecService service) {
        mContext = ctx;
        mService = checkNotNull(service, "missing service");
    }

    private static void maybeHandleServiceSpecificException(ServiceSpecificException sse) {
        // OsConstants are late binding, so switch statements can't be used.
        if (sse.errorCode == OsConstants.EINVAL) {
            throw new IllegalArgumentException(sse);
        } else if (sse.errorCode == OsConstants.EAGAIN) {
            throw new IllegalStateException(sse);
        } else if (sse.errorCode == OsConstants.EOPNOTSUPP
                || sse.errorCode == OsConstants.EPROTONOSUPPORT) {
            throw new UnsupportedOperationException(sse);
        }
    }

    /**
     * Convert an Errno SSE to the correct Unchecked exception type.
     *
     * This method never actually returns.
     */
    // package
    static RuntimeException
            rethrowUncheckedExceptionFromServiceSpecificException(ServiceSpecificException sse) {
        maybeHandleServiceSpecificException(sse);
        throw new RuntimeException(sse);
    }

    /**
     * Convert an Errno SSE to the correct Checked or Unchecked exception type.
     *
     * This method may throw IOException, or it may throw an unchecked exception; it will never
     * actually return.
     */
    // package
    static IOException rethrowCheckedExceptionFromServiceSpecificException(
            ServiceSpecificException sse) throws IOException {
        // First see if this is an unchecked exception of a type we know.
        // If so, then we prefer the unchecked (specific) type of exception.
        maybeHandleServiceSpecificException(sse);
        // If not, then all we can do is provide the SSE in the form of an IOException.
        throw new ErrnoException(
                "IpSec encountered errno=" + sse.errorCode, sse.errorCode).rethrowAsIOException();
    }
}
