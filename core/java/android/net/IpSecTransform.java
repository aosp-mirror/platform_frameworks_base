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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import com.android.internal.util.Preconditions;
import dalvik.system.CloseGuard;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;

/**
 * This class represents an IpSecTransform, which encapsulates both properties and state of IPsec.
 *
 * <p>IpSecTransforms must be built from an IpSecTransform.Builder, and they must persist throughout
 * the lifetime of the underlying transform. If a transform object leaves scope, the underlying
 * transform may be disabled automatically, with likely undesirable results.
 *
 * <p>An IpSecTransform may either represent a tunnel mode transform that operates on a wide array
 * of traffic or may represent a transport mode transform operating on a Socket or Sockets.
 *
 * @hide
 */
public final class IpSecTransform implements AutoCloseable {
    private static final String TAG = "IpSecTransform";

    /**
     * For direction-specific attributes of an IpSecTransform, indicates that an attribute applies
     * to traffic towards the host.
     */
    public static final int DIRECTION_IN = 0;

    /**
     * For direction-specific attributes of an IpSecTransform, indicates that an attribute applies
     * to traffic from the host.
     */
    public static final int DIRECTION_OUT = 1;

    /** @hide */
    @IntDef(value = {DIRECTION_IN, DIRECTION_OUT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransformDirection {}

    /** @hide */
    private static final int MODE_TUNNEL = 0;

    /** @hide */
    private static final int MODE_TRANSPORT = 1;

    /** @hide */
    public static final int ENCAP_NONE = 0;

    /**
     * IpSec traffic will be encapsulated within a UDP header with an additional 8-byte header pad
     * (of '0'-value bytes) that prevents traffic from being interpreted as IKE or as ESP over UDP.
     *
     * @hide
     */
    public static final int ENCAP_ESPINUDP_NON_IKE = 1;

    /**
     * IpSec traffic will be encapsulated within UDP as per <a
     * href="https://tools.ietf.org/html/rfc3948">RFC3498</a>.
     *
     * @hide
     */
    public static final int ENCAP_ESPINUDP = 2;

    /** @hide */
    @IntDef(value = {ENCAP_NONE, ENCAP_ESPINUDP, ENCAP_ESPINUDP_NON_IKE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface EncapType {}

    private IpSecTransform(Context context, IpSecConfig config) {
        mContext = context;
        mConfig = config;
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

    private void checkResultStatusAndThrow(int status)
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
                IpSecTransformResponse result =
                        svc.createTransportModeTransform(mConfig, new Binder());
                int status = result.status;
                checkResultStatusAndThrow(status);
                mResourceId = result.resourceId;

                /* Keepalive will silently fail if not needed by the config; but, if needed and
                 * it fails to start, we need to bail because a transform will not be reliable
                 * to use if keepalive is expected to offload and fails.
                 */
                // FIXME: if keepalive fails, we need to fail spectacularly
                startKeepalive(mContext);
                Log.d(TAG, "Added Transform with Id " + mResourceId);
                mCloseGuard.open("build");
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        return this;
    }

    /**
     * Deactivate an IpSecTransform and free all resources for that transform that are managed by
     * the system for this Transform.
     *
     * <p>Deactivating a transform while it is still applied to any Socket will result in sockets
     * refusing to send or receive data. This method will silently succeed if the specified
     * transform has already been removed; thus, it is always safe to attempt cleanup when a
     * transform is no longer needed.
     */
    public void close() {
        Log.d(TAG, "Removing Transform with Id " + mResourceId);

        // Always safe to attempt cleanup
        if (mResourceId == INVALID_RESOURCE_ID) {
            mCloseGuard.close();
            return;
        }
        try {
            /* Order matters here because the keepalive is best-effort but could fail in some
             * horrible way to be removed if the wifi (or cell) subsystem has crashed, and we
             * still want to clear out the transform.
             */
            IIpSecService svc = getIpSecService();
            svc.deleteTransportModeTransform(mResourceId);
            stopKeepalive();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        } finally {
            mResourceId = INVALID_RESOURCE_ID;
            mCloseGuard.close();
        }
    }

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
    private int mKeepaliveStatus = ConnectivityManager.PacketKeepalive.NO_KEEPALIVE;
    private Object mKeepaliveSyncLock = new Object();
    private ConnectivityManager.PacketKeepaliveCallback mKeepaliveCallback =
            new ConnectivityManager.PacketKeepaliveCallback() {

                @Override
                public void onStarted() {
                    synchronized (mKeepaliveSyncLock) {
                        mKeepaliveStatus = ConnectivityManager.PacketKeepalive.SUCCESS;
                        mKeepaliveSyncLock.notifyAll();
                    }
                }

                @Override
                public void onStopped() {
                    synchronized (mKeepaliveSyncLock) {
                        mKeepaliveStatus = ConnectivityManager.PacketKeepalive.NO_KEEPALIVE;
                        mKeepaliveSyncLock.notifyAll();
                    }
                }

                @Override
                public void onError(int error) {
                    synchronized (mKeepaliveSyncLock) {
                        mKeepaliveStatus = error;
                        mKeepaliveSyncLock.notifyAll();
                    }
                }
            };

    /* Package */
    void startKeepalive(Context c) {
        // FIXME: NO_KEEPALIVE needs to be a constant
        if (mConfig.getNattKeepaliveInterval() == 0) {
            return;
        }

        ConnectivityManager cm =
                (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (mKeepalive != null) {
            Log.wtf(TAG, "Keepalive already started for this IpSecTransform.");
            return;
        }

        synchronized (mKeepaliveSyncLock) {
            mKeepalive =
                    cm.startNattKeepalive(
                            mConfig.getNetwork(),
                            mConfig.getNattKeepaliveInterval(),
                            mKeepaliveCallback,
                            mConfig.getLocalAddress(),
                            0x1234, /* FIXME: get the real port number again,
                                    which we need to retrieve from the provided
                                    EncapsulationSocket, and which isn't currently
                                    stashed in IpSecConfig */
                            mConfig.getRemoteAddress());
            try {
                // FIXME: this is still a horrible way to fudge the synchronous callback
                mKeepaliveSyncLock.wait(2000);
            } catch (InterruptedException e) {
            }
        }
        if (mKeepaliveStatus != ConnectivityManager.PacketKeepalive.SUCCESS) {
            throw new UnsupportedOperationException("Packet Keepalive cannot be started");
        }
    }

    /* Package */
    int getResourceId() {
        return mResourceId;
    }

    /* Package */
    void stopKeepalive() {
        if (mKeepalive == null) {
            return;
        }
        mKeepalive.stop();
        synchronized (mKeepaliveSyncLock) {
            if (mKeepaliveStatus == ConnectivityManager.PacketKeepalive.SUCCESS) {
                try {
                    mKeepaliveSyncLock.wait(2000);
                } catch (InterruptedException e) {
                }
            }
        }
    }

    /**
     * Builder object to facilitate the creation of IpSecTransform objects.
     *
     * <p>Apply additional properties to the transform and then call a build() method to return an
     * IpSecTransform object.
     *
     * @see Builder#buildTransportModeTransform(InetAddress)
     */
    public static class Builder {
        private Context mContext;
        private IpSecConfig mConfig;

        /**
         * Add an encryption algorithm to the transform for the given direction.
         *
         * <p>If encryption is set for a given direction without also providing an SPI for that
         * direction, creation of an IpSecTransform will fail upon calling a build() method.
         *
         * @param direction either {@link #DIRECTION_IN or #DIRECTION_OUT}
         * @param algo {@link IpSecAlgorithm} specifying the encryption to be applied.
         */
        public IpSecTransform.Builder setEncryption(
                @TransformDirection int direction, IpSecAlgorithm algo) {
            mConfig.flow[direction].encryption = algo;
            return this;
        }

        /**
         * Add an authentication/integrity algorithm to the transform.
         *
         * <p>If authentication is set for a given direction without also providing an SPI for that
         * direction, creation of an IpSecTransform will fail upon calling a build() method.
         *
         * @param direction either {@link #DIRECTION_IN or #DIRECTION_OUT}
         * @param algo {@link IpSecAlgorithm} specifying the authentication to be applied.
         */
        public IpSecTransform.Builder setAuthentication(
                @TransformDirection int direction, IpSecAlgorithm algo) {
            mConfig.flow[direction].authentication = algo;
            return this;
        }

        /**
         * Set the SPI, which uniquely identifies a particular IPsec session from others. Because
         * IPsec operates at the IP layer, this 32-bit identifier uniquely identifies packets to a
         * given destination address.
         *
         * <p>Care should be chosen when selecting an SPI to ensure that is is as unique as
         * possible. To reserve a value call {@link IpSecManager#reserveSecurityParameterIndex(int,
         * InetAddress, int)}. Otherwise, SPI collisions would prevent a transform from being
         * activated. IpSecManager#reserveSecurityParameterIndex(int, InetAddres$s, int)}.
         *
         * <p>Unless an SPI is set for a given direction, traffic in that direction will be
         * sent/received without any IPsec applied.
         *
         * @param direction either {@link #DIRECTION_IN or #DIRECTION_OUT}
         * @param spi a unique {@link IpSecManager.SecurityParameterIndex} to identify transformed
         *     traffic
         */
        public IpSecTransform.Builder setSpi(
                @TransformDirection int direction, IpSecManager.SecurityParameterIndex spi) {
            // TODO: convert to using the resource Id of the SPI. Then build() can validate
            // the owner in the IpSecService
            mConfig.flow[direction].spiResourceId = spi.getResourceId();
            return this;
        }

        /**
         * Specify the network on which this transform will emit its traffic; (otherwise it will
         * emit on the default network).
         *
         * <p>Restricts the transformed traffic to a particular {@link Network}. This is required in
         * tunnel mode.
         *
         * @hide
         */
        @SystemApi
        public IpSecTransform.Builder setUnderlyingNetwork(Network net) {
            mConfig.network = net;
            return this;
        }

        /**
         * Add UDP encapsulation to an IPv4 transform
         *
         * <p>This option allows IPsec traffic to pass through NAT. Refer to RFC 3947 and 3948 for
         * details on how UDP should be applied to IPsec.
         *
         * @param localSocket a {@link IpSecManager.UdpEncapsulationSocket} for sending and
         *     receiving encapsulating traffic.
         * @param remotePort the UDP port number of the remote that will send and receive
         *     encapsulated traffic. In the case of IKE, this is likely port 4500.
         */
        public IpSecTransform.Builder setIpv4Encapsulation(
                IpSecManager.UdpEncapsulationSocket localSocket, int remotePort) {
            // TODO: check encap type is valid.
            mConfig.encapType = ENCAP_ESPINUDP;
            mConfig.encapLocalPortResourceId = localSocket.getResourceId();
            mConfig.encapRemotePort = remotePort;
            return this;
        }

        // TODO: Decrease the minimum keepalive to maybe 10?
        // TODO: Probably a better exception to throw for NATTKeepalive failure
        // TODO: Specify the needed NATT keepalive permission.
        /**
         * Send a NATT Keepalive packet with a given maximum interval. This will create an offloaded
         * request to do power-efficient NATT Keepalive. If NATT keepalive is requested but cannot
         * be activated, then the transform will fail to activate and throw an IOException.
         *
         * @param intervalSeconds the maximum number of seconds between keepalive packets, no less
         *     than 20s and no more than 3600s.
         * @hide
         */
        @SystemApi
        public IpSecTransform.Builder setNattKeepalive(int intervalSeconds) {
            mConfig.nattKeepaliveInterval = intervalSeconds;
            return this;
        }

        /**
         * Build and return an active {@link IpSecTransform} object as a Transport Mode Transform.
         * Some parameters have interdependencies that are checked at build time. If a well-formed
         * transform cannot be created from the supplied parameters, this method will throw an
         * Exception.
         *
         * <p>Upon a successful return from this call, the provided IpSecTransform will be active
         * and may be applied to sockets. If too many IpSecTransform objects are active for a given
         * user this operation will fail and throw ResourceUnavailableException. To avoid these
         * exceptions, unused Transform objects must be cleaned up by calling {@link
         * IpSecTransform#close()} when they are no longer needed.
         *
         * @param remoteAddress the {@link InetAddress} that, when matched on traffic to/from this
         *     socket will cause the transform to be applied.
         *     <p>Note that an active transform will not impact any network traffic until it has
         *     been applied to one or more Sockets. Calling this method is a necessary precondition
         *     for applying it to a socket, but is not sufficient to actually apply IPsec.
         * @throws IllegalArgumentException indicating that a particular combination of transform
         *     properties is invalid.
         * @throws IpSecManager.ResourceUnavailableException in the event that no more Transforms
         *     may be allocated
         * @throws SpiUnavailableException if the SPI collides with an existing transform
         *     (unlikely).
         * @throws ResourceUnavailableException if the current user currently has exceeded the
         *     number of allowed active transforms.
         */
        public IpSecTransform buildTransportModeTransform(InetAddress remoteAddress)
                throws IpSecManager.ResourceUnavailableException,
                        IpSecManager.SpiUnavailableException, IOException {
            //FIXME: argument validation here
            //throw new IllegalArgumentException("Natt Keepalive requires UDP Encapsulation");
            mConfig.mode = MODE_TRANSPORT;
            mConfig.remoteAddress = remoteAddress;
            return new IpSecTransform(mContext, mConfig).activate();
        }

        /**
         * Build and return an {@link IpSecTransform} object as a Tunnel Mode Transform. Some
         * parameters have interdependencies that are checked at build time.
         *
         * @param localAddress the {@link InetAddress} that provides the local endpoint for this
         *     IPsec tunnel. This is almost certainly an address belonging to the {@link Network}
         *     that will originate the traffic, which is set as the {@link #setUnderlyingNetwork}.
         * @param remoteAddress the {@link InetAddress} representing the remote endpoint of this
         *     IPsec tunnel.
         * @throws IllegalArgumentException indicating that a particular combination of transform
         *     properties is invalid.
         * @hide
         */
        public IpSecTransform buildTunnelModeTransform(
                InetAddress localAddress, InetAddress remoteAddress) {
            //FIXME: argument validation here
            //throw new IllegalArgumentException("Natt Keepalive requires UDP Encapsulation");
            mConfig.localAddress = localAddress;
            mConfig.remoteAddress = remoteAddress;
            mConfig.mode = MODE_TUNNEL;
            return new IpSecTransform(mContext, mConfig);
        }

        /**
         * Create a new IpSecTransform.Builder to construct an IpSecTransform
         *
         * @param context current Context
         */
        public Builder(@NonNull Context context) {
            Preconditions.checkNotNull(context);
            mContext = context;
            mConfig = new IpSecConfig();
        }
    }
}
