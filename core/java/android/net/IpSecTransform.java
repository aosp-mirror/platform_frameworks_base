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

import static android.net.IpSecManager.INVALID_RESOURCE_ID;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import dalvik.system.CloseGuard;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;

/**
 * This class represents a transform, which roughly corresponds to an IPsec Security Association.
 *
 * <p>Transforms are created using {@link IpSecTransform.Builder}. Each {@code IpSecTransform}
 * object encapsulates the properties and state of an IPsec security association. That includes,
 * but is not limited to, algorithm choice, key material, and allocated system resources.
 *
 * @see <a href="https://tools.ietf.org/html/rfc4301">RFC 4301, Security Architecture for the
 *     Internet Protocol</a>
 */
public final class IpSecTransform implements AutoCloseable {
    private static final String TAG = "IpSecTransform";

    /** @hide */
    public static final int MODE_TRANSPORT = 0;

    /** @hide */
    public static final int MODE_TUNNEL = 1;

    /** @hide */
    public static final int ENCAP_NONE = 0;

    /**
     * IPsec traffic will be encapsulated within UDP, but with 8 zero-value bytes between the UDP
     * header and payload. This prevents traffic from being interpreted as ESP or IKEv2.
     *
     * @hide
     */
    public static final int ENCAP_ESPINUDP_NON_IKE = 1;

    /**
     * IPsec traffic will be encapsulated within UDP as per
     * <a href="https://tools.ietf.org/html/rfc3948">RFC 3498</a>.
     *
     * @hide
     */
    public static final int ENCAP_ESPINUDP = 2;

    /** @hide */
    @IntDef(value = {ENCAP_NONE, ENCAP_ESPINUDP, ENCAP_ESPINUDP_NON_IKE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface EncapType {}

    /** @hide */
    @VisibleForTesting
    public IpSecTransform(Context context, IpSecConfig config) {
        mContext = context;
        mConfig = new IpSecConfig(config);
        mResourceId = INVALID_RESOURCE_ID;
    }

    private IIpSecService getIpSecService() {
        IBinder b = ServiceManager.getService(android.content.Context.IPSEC_SERVICE);
        if (b == null) {
            throw new RemoteException("Failed to connect to IpSecService")
                    .rethrowAsRuntimeException();
        }

        return IIpSecService.Stub.asInterface(b);
    }

    /**
     * Checks the result status and throws an appropriate exception if the status is not Status.OK.
     */
    private void checkResultStatus(int status)
            throws IOException, IpSecManager.ResourceUnavailableException,
                    IpSecManager.SpiUnavailableException {
        switch (status) {
            case IpSecManager.Status.OK:
                return;
                // TODO: Pass Error string back from bundle so that errors can be more specific
            case IpSecManager.Status.RESOURCE_UNAVAILABLE:
                throw new IpSecManager.ResourceUnavailableException(
                        "Failed to allocate a new IpSecTransform");
            case IpSecManager.Status.SPI_UNAVAILABLE:
                Log.wtf(TAG, "Attempting to use an SPI that was somehow not reserved");
                // Fall through
            default:
                throw new IllegalStateException(
                        "Failed to Create a Transform with status code " + status);
        }
    }

    private IpSecTransform activate()
            throws IOException, IpSecManager.ResourceUnavailableException,
                    IpSecManager.SpiUnavailableException {
        synchronized (this) {
            try {
                IIpSecService svc = getIpSecService();
                IpSecTransformResponse result = svc.createTransform(
                        mConfig, new Binder(), mContext.getOpPackageName());
                int status = result.status;
                checkResultStatus(status);
                mResourceId = result.resourceId;
                Log.d(TAG, "Added Transform with Id " + mResourceId);
                mCloseGuard.open("build");
            } catch (ServiceSpecificException e) {
                throw IpSecManager.rethrowUncheckedExceptionFromServiceSpecificException(e);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        return this;
    }

    /**
     * Equals method used for testing
     *
     * @hide
     */
    @VisibleForTesting
    public static boolean equals(IpSecTransform lhs, IpSecTransform rhs) {
        if (lhs == null || rhs == null) return (lhs == rhs);
        return IpSecConfig.equals(lhs.getConfig(), rhs.getConfig())
                && lhs.mResourceId == rhs.mResourceId;
    }

    /**
     * Deactivate this {@code IpSecTransform} and free allocated resources.
     *
     * <p>Deactivating a transform while it is still applied to a socket will result in errors on
     * that socket. Make sure to remove transforms by calling {@link
     * IpSecManager#removeTransportModeTransforms}. Note, removing an {@code IpSecTransform} from a
     * socket will not deactivate it (because one transform may be applied to multiple sockets).
     *
     * <p>It is safe to call this method on a transform that has already been deactivated.
     */
    public void close() {
        Log.d(TAG, "Removing Transform with Id " + mResourceId);

        // Always safe to attempt cleanup
        if (mResourceId == INVALID_RESOURCE_ID) {
            mCloseGuard.close();
            return;
        }
        try {
            IIpSecService svc = getIpSecService();
            svc.deleteTransform(mResourceId);
            stopNattKeepalive();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        } catch (Exception e) {
            // On close we swallow all random exceptions since failure to close is not
            // actionable by the user.
            Log.e(TAG, "Failed to close " + this + ", Exception=" + e);
        } finally {
            mResourceId = INVALID_RESOURCE_ID;
            mCloseGuard.close();
        }
    }

    /** Check that the transform was closed properly. */
    @Override
    protected void finalize() throws Throwable {
        if (mCloseGuard != null) {
            mCloseGuard.warnIfOpen();
        }
        close();
    }

    /* Package */
    IpSecConfig getConfig() {
        return mConfig;
    }

    private final IpSecConfig mConfig;
    private int mResourceId;
    private final Context mContext;
    private final CloseGuard mCloseGuard = CloseGuard.get();
    private ConnectivityManager.PacketKeepalive mKeepalive;
    private Handler mCallbackHandler;
    private final ConnectivityManager.PacketKeepaliveCallback mKeepaliveCallback =
            new ConnectivityManager.PacketKeepaliveCallback() {

                @Override
                public void onStarted() {
                    synchronized (this) {
                        mCallbackHandler.post(() -> mUserKeepaliveCallback.onStarted());
                    }
                }

                @Override
                public void onStopped() {
                    synchronized (this) {
                        mKeepalive = null;
                        mCallbackHandler.post(() -> mUserKeepaliveCallback.onStopped());
                    }
                }

                @Override
                public void onError(int error) {
                    synchronized (this) {
                        mKeepalive = null;
                        mCallbackHandler.post(() -> mUserKeepaliveCallback.onError(error));
                    }
                }
            };

    private NattKeepaliveCallback mUserKeepaliveCallback;

    /** @hide */
    @VisibleForTesting
    public int getResourceId() {
        return mResourceId;
    }

    /**
     * A callback class to provide status information regarding a NAT-T keepalive session
     *
     * <p>Use this callback to receive status information regarding a NAT-T keepalive session
     * by registering it when calling {@link #startNattKeepalive}.
     *
     * @hide
     */
    public static class NattKeepaliveCallback {
        /** The specified {@code Network} is not connected. */
        public static final int ERROR_INVALID_NETWORK = 1;
        /** The hardware does not support this request. */
        public static final int ERROR_HARDWARE_UNSUPPORTED = 2;
        /** The hardware returned an error. */
        public static final int ERROR_HARDWARE_ERROR = 3;

        /** The requested keepalive was successfully started. */
        public void onStarted() {}
        /** The keepalive was successfully stopped. */
        public void onStopped() {}
        /** An error occurred. */
        public void onError(int error) {}
    }

    /**
     * Start a NAT-T keepalive session for the current transform.
     *
     * For a transform that is using UDP encapsulated IPv4, NAT-T offloading provides
     * a power efficient mechanism of sending NAT-T packets at a specified interval.
     *
     * @param userCallback a {@link #NattKeepaliveCallback} to receive asynchronous status
     *      information about the requested NAT-T keepalive session.
     * @param intervalSeconds the interval between NAT-T keepalives being sent. The
     *      the allowed range is between 20 and 3600 seconds.
     * @param handler a handler on which to post callbacks when received.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_IPSEC_TUNNELS,
            android.Manifest.permission.PACKET_KEEPALIVE_OFFLOAD
    })
    public void startNattKeepalive(@NonNull NattKeepaliveCallback userCallback,
            int intervalSeconds, @NonNull Handler handler) throws IOException {
        checkNotNull(userCallback);
        if (intervalSeconds < 20 || intervalSeconds > 3600) {
            throw new IllegalArgumentException("Invalid NAT-T keepalive interval");
        }
        checkNotNull(handler);
        if (mResourceId == INVALID_RESOURCE_ID) {
            throw new IllegalStateException(
                    "Packet keepalive cannot be started for an inactive transform");
        }

        synchronized (mKeepaliveCallback) {
            if (mKeepaliveCallback != null) {
                throw new IllegalStateException("Keepalive already active");
            }

            mUserKeepaliveCallback = userCallback;
            ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            mKeepalive = cm.startNattKeepalive(
                    mConfig.getNetwork(), intervalSeconds, mKeepaliveCallback,
                    NetworkUtils.numericToInetAddress(mConfig.getSourceAddress()),
                    4500, // FIXME urgently, we need to get the port number from the Encap socket
                    NetworkUtils.numericToInetAddress(mConfig.getDestinationAddress()));
            mCallbackHandler = handler;
        }
    }

    /**
     * Stop an ongoing NAT-T keepalive session.
     *
     * Calling this API will request that an ongoing NAT-T keepalive session be terminated.
     * If this API is not called when a Transform is closed, the underlying NAT-T session will
     * be terminated automatically.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_IPSEC_TUNNELS,
            android.Manifest.permission.PACKET_KEEPALIVE_OFFLOAD
    })
    public void stopNattKeepalive() {
        synchronized (mKeepaliveCallback) {
            if (mKeepalive == null) {
                Log.e(TAG, "No active keepalive to stop");
                return;
            }
            mKeepalive.stop();
        }
    }

    /** This class is used to build {@link IpSecTransform} objects. */
    public static class Builder {
        private Context mContext;
        private IpSecConfig mConfig;

        /**
         * Set the encryption algorithm.
         *
         * <p>Encryption is mutually exclusive with authenticated encryption.
         *
         * @param algo {@link IpSecAlgorithm} specifying the encryption to be applied.
         */
        @NonNull
        public IpSecTransform.Builder setEncryption(@NonNull IpSecAlgorithm algo) {
            // TODO: throw IllegalArgumentException if algo is not an encryption algorithm.
            Preconditions.checkNotNull(algo);
            mConfig.setEncryption(algo);
            return this;
        }

        /**
         * Set the authentication (integrity) algorithm.
         *
         * <p>Authentication is mutually exclusive with authenticated encryption.
         *
         * @param algo {@link IpSecAlgorithm} specifying the authentication to be applied.
         */
        @NonNull
        public IpSecTransform.Builder setAuthentication(@NonNull IpSecAlgorithm algo) {
            // TODO: throw IllegalArgumentException if algo is not an authentication algorithm.
            Preconditions.checkNotNull(algo);
            mConfig.setAuthentication(algo);
            return this;
        }

        /**
         * Set the authenticated encryption algorithm.
         *
         * <p>The Authenticated Encryption (AE) class of algorithms are also known as
         * Authenticated Encryption with Associated Data (AEAD) algorithms, or Combined mode
         * algorithms (as referred to in
         * <a href="https://tools.ietf.org/html/rfc4301">RFC 4301</a>).
         *
         * <p>Authenticated encryption is mutually exclusive with encryption and authentication.
         *
         * @param algo {@link IpSecAlgorithm} specifying the authenticated encryption algorithm to
         *     be applied.
         */
        @NonNull
        public IpSecTransform.Builder setAuthenticatedEncryption(@NonNull IpSecAlgorithm algo) {
            Preconditions.checkNotNull(algo);
            mConfig.setAuthenticatedEncryption(algo);
            return this;
        }

        /**
         * Add UDP encapsulation to an IPv4 transform.
         *
         * <p>This allows IPsec traffic to pass through a NAT.
         *
         * @see <a href="https://tools.ietf.org/html/rfc3948">RFC 3948, UDP Encapsulation of IPsec
         *     ESP Packets</a>
         * @see <a href="https://tools.ietf.org/html/rfc7296#section-2.23">RFC 7296 section 2.23,
         *     NAT Traversal of IKEv2</a>
         * @param localSocket a socket for sending and receiving encapsulated traffic
         * @param remotePort the UDP port number of the remote host that will send and receive
         *     encapsulated traffic. In the case of IKEv2, this should be port 4500.
         */
        @NonNull
        public IpSecTransform.Builder setIpv4Encapsulation(
                @NonNull IpSecManager.UdpEncapsulationSocket localSocket, int remotePort) {
            Preconditions.checkNotNull(localSocket);
            mConfig.setEncapType(ENCAP_ESPINUDP);
            if (localSocket.getResourceId() == INVALID_RESOURCE_ID) {
                throw new IllegalArgumentException("Invalid UdpEncapsulationSocket");
            }
            mConfig.setEncapSocketResourceId(localSocket.getResourceId());
            mConfig.setEncapRemotePort(remotePort);
            return this;
        }

        /**
         * Build a transport mode {@link IpSecTransform}.
         *
         * <p>This builds and activates a transport mode transform. Note that an active transform
         * will not affect any network traffic until it has been applied to one or more sockets.
         *
         * @see IpSecManager#applyTransportModeTransform
         * @param sourceAddress the source {@code InetAddress} of traffic on sockets that will use
         *     this transform; this address must belong to the Network used by all sockets that
         *     utilize this transform; if provided, then only traffic originating from the
         *     specified source address will be processed.
         * @param spi a unique {@link IpSecManager.SecurityParameterIndex} to identify transformed
         *     traffic
         * @throws IllegalArgumentException indicating that a particular combination of transform
         *     properties is invalid
         * @throws IpSecManager.ResourceUnavailableException indicating that too many transforms
         *     are active
         * @throws IpSecManager.SpiUnavailableException indicating the rare case where an SPI
         *     collides with an existing transform
         * @throws IOException indicating other errors
         */
        @NonNull
        public IpSecTransform buildTransportModeTransform(
                @NonNull InetAddress sourceAddress,
                @NonNull IpSecManager.SecurityParameterIndex spi)
                throws IpSecManager.ResourceUnavailableException,
                        IpSecManager.SpiUnavailableException, IOException {
            Preconditions.checkNotNull(sourceAddress);
            Preconditions.checkNotNull(spi);
            if (spi.getResourceId() == INVALID_RESOURCE_ID) {
                throw new IllegalArgumentException("Invalid SecurityParameterIndex");
            }
            mConfig.setMode(MODE_TRANSPORT);
            mConfig.setSourceAddress(sourceAddress.getHostAddress());
            mConfig.setSpiResourceId(spi.getResourceId());
            // FIXME: modifying a builder after calling build can change the built transform.
            return new IpSecTransform(mContext, mConfig).activate();
        }

        /**
         * Build and return an {@link IpSecTransform} object as a Tunnel Mode Transform. Some
         * parameters have interdependencies that are checked at build time.
         *
         * @param sourceAddress the {@link InetAddress} that provides the source address for this
         *     IPsec tunnel. This is almost certainly an address belonging to the {@link Network}
         *     that will originate the traffic, which is set as the {@link #setUnderlyingNetwork}.
         * @param spi a unique {@link IpSecManager.SecurityParameterIndex} to identify transformed
         *     traffic
         * @throws IllegalArgumentException indicating that a particular combination of transform
         *     properties is invalid.
         * @throws IpSecManager.ResourceUnavailableException indicating that too many transforms
         *     are active
         * @throws IpSecManager.SpiUnavailableException indicating the rare case where an SPI
         *     collides with an existing transform
         * @throws IOException indicating other errors
         * @hide
         */
        @SystemApi
        @NonNull
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        @RequiresPermission(android.Manifest.permission.MANAGE_IPSEC_TUNNELS)
        public IpSecTransform buildTunnelModeTransform(
                @NonNull InetAddress sourceAddress,
                @NonNull IpSecManager.SecurityParameterIndex spi)
                throws IpSecManager.ResourceUnavailableException,
                        IpSecManager.SpiUnavailableException, IOException {
            Preconditions.checkNotNull(sourceAddress);
            Preconditions.checkNotNull(spi);
            if (spi.getResourceId() == INVALID_RESOURCE_ID) {
                throw new IllegalArgumentException("Invalid SecurityParameterIndex");
            }
            mConfig.setMode(MODE_TUNNEL);
            mConfig.setSourceAddress(sourceAddress.getHostAddress());
            mConfig.setSpiResourceId(spi.getResourceId());
            return new IpSecTransform(mContext, mConfig).activate();
        }

        /**
         * Create a new IpSecTransform.Builder.
         *
         * @param context current context
         */
        public Builder(@NonNull Context context) {
            Preconditions.checkNotNull(context);
            mContext = context;
            mConfig = new IpSecConfig();
        }
    }

    @Override
    public String toString() {
        return new StringBuilder()
            .append("IpSecTransform{resourceId=")
            .append(mResourceId)
            .append("}")
            .toString();
    }
}
