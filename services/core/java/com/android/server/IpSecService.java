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

package com.android.server;

import static android.Manifest.permission.DUMP;
import static android.net.IpSecManager.INVALID_RESOURCE_ID;
import static android.net.IpSecManager.KEY_RESOURCE_ID;
import static android.net.IpSecManager.KEY_SPI;
import static android.net.IpSecManager.KEY_STATUS;

import android.content.Context;
import android.net.IIpSecService;
import android.net.INetd;
import android.net.IpSecAlgorithm;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.util.NetdService;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;

/** @hide */
public class IpSecService extends IIpSecService.Stub {
    private static final String TAG = "IpSecService";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String NETD_SERVICE_NAME = "netd";
    private static final int[] DIRECTIONS =
            new int[] {IpSecTransform.DIRECTION_OUT, IpSecTransform.DIRECTION_IN};

    /** Binder context for this service */
    private final Context mContext;

    private Object mLock = new Object();

    private static final int NETD_FETCH_TIMEOUT = 5000; //ms

    private AtomicInteger mNextResourceId = new AtomicInteger(0x00FADED0);

    private abstract class ManagedResource implements IBinder.DeathRecipient {
        final int pid;
        final int uid;
        private IBinder mBinder;

        ManagedResource(IBinder binder) {
            super();
            mBinder = binder;
            pid = Binder.getCallingPid();
            uid = Binder.getCallingUid();

            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        /**
         * When this record is no longer needed for managing system resources this function should
         * unlink all references held by the record to allow efficient garbage collection.
         */
        public final void release() {
            //Release all the underlying system resources first
            releaseResources();

            if (mBinder != null) {
                mBinder.unlinkToDeath(this, 0);
            }
            mBinder = null;

            //remove this record so that it can be cleaned up
            nullifyRecord();
        }

        /**
         * If the Binder object dies, this function is called to free the system resources that are
         * being managed by this record and to subsequently release this record for garbage
         * collection
         */
        public final void binderDied() {
            release();
        }

        /**
         * Implement this method to release all object references contained in the subclass to allow
         * efficient garbage collection of the record. This should remove any references to the
         * record from all other locations that hold a reference as the record is no longer valid.
         */
        protected abstract void nullifyRecord();

        /**
         * Implement this method to release all system resources that are being protected by this
         * record. Once the resources are released, the record should be invalidated and no longer
         * used by calling releaseRecord()
         */
        protected abstract void releaseResources();
    };

    private final class TransformRecord extends ManagedResource {
        private IpSecConfig mConfig;
        private int mResourceId;

        TransformRecord(IpSecConfig config, int resourceId, IBinder binder) {
            super(binder);
            mConfig = config;
            mResourceId = resourceId;
        }

        public IpSecConfig getConfig() {
            return mConfig;
        }

        @Override
        protected void releaseResources() {
            for (int direction : DIRECTIONS) {
                try {
                    getNetdInstance()
                            .ipSecDeleteSecurityAssociation(
                                    mResourceId,
                                    direction,
                                    (mConfig.getLocalAddress() != null)
                                            ? mConfig.getLocalAddress().getHostAddress()
                                            : "",
                                    (mConfig.getRemoteAddress() != null)
                                            ? mConfig.getRemoteAddress().getHostAddress()
                                            : "",
                                    mConfig.getSpi(direction));
                } catch (ServiceSpecificException e) {
                    // FIXME: get the error code and throw is at an IOException from Errno Exception
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to delete SA with ID: " + mResourceId);
                }
            }
        }

        @Override
        protected void nullifyRecord() {
            mConfig = null;
            mResourceId = INVALID_RESOURCE_ID;
        }
    }

    private final class SpiRecord extends ManagedResource {
        private final int mDirection;
        private final String mLocalAddress;
        private final String mRemoteAddress;
        private final IBinder mBinder;
        private int mSpi;
        private int mResourceId;

        SpiRecord(
                int resourceId,
                int direction,
                String localAddress,
                String remoteAddress,
                int spi,
                IBinder binder) {
            super(binder);
            mResourceId = resourceId;
            mDirection = direction;
            mLocalAddress = localAddress;
            mRemoteAddress = remoteAddress;
            mSpi = spi;
            mBinder = binder;
        }

        protected void releaseResources() {
            try {
                getNetdInstance()
                        .ipSecDeleteSecurityAssociation(
                                mResourceId, mDirection, mLocalAddress, mRemoteAddress, mSpi);
            } catch (ServiceSpecificException e) {
                // FIXME: get the error code and throw is at an IOException from Errno Exception
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to delete SPI reservation with ID: " + mResourceId);
            }
        }

        protected void nullifyRecord() {
            mSpi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;
            mResourceId = INVALID_RESOURCE_ID;
        }
    }

    @GuardedBy("mSpiRecords")
    private final SparseArray<SpiRecord> mSpiRecords = new SparseArray<>();

    @GuardedBy("mTransformRecords")
    private final SparseArray<TransformRecord> mTransformRecords = new SparseArray<>();

    /**
     * Constructs a new IpSecService instance
     *
     * @param context Binder context for this service
     */
    private IpSecService(Context context) {
        mContext = context;
    }

    static IpSecService create(Context context) throws InterruptedException {
        final IpSecService service = new IpSecService(context);
        service.connectNativeNetdService();
        return service;
    }

    public void systemReady() {
        if (isNetdAlive()) {
            Slog.d(TAG, "IpSecService is ready");
        } else {
            Slog.wtf(TAG, "IpSecService not ready: failed to connect to NetD Native Service!");
        }
    }

    private void connectNativeNetdService() {
        // Avoid blocking the system server to do this
        Thread t =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                synchronized (mLock) {
                                    NetdService.get(NETD_FETCH_TIMEOUT);
                                }
                            }
                        });
        t.run();
    }

    INetd getNetdInstance() throws RemoteException {
        final INetd netd = NetdService.getInstance();
        if (netd == null) {
            throw new RemoteException("Failed to Get Netd Instance");
        }
        return netd;
    }

    boolean isNetdAlive() {
        synchronized (mLock) {
            try {
                final INetd netd = getNetdInstance();
                if (netd == null) {
                    return false;
                }
                return netd.isAlive();
            } catch (RemoteException re) {
                return false;
            }
        }
    }

    @Override
    /** Get a new SPI and maintain the reservation in the system server */
    public Bundle reserveSecurityParameterIndex(
            int direction, String remoteAddress, int requestedSpi, IBinder binder)
            throws RemoteException {
        int resourceId = mNextResourceId.getAndIncrement();

        int spi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;
        String localAddress = "";
        Bundle retBundle = new Bundle(3);
        try {
            spi =
                    getNetdInstance()
                            .ipSecAllocateSpi(
                                    resourceId,
                                    direction,
                                    localAddress,
                                    remoteAddress,
                                    requestedSpi);
            Log.d(TAG, "Allocated SPI " + spi);
            retBundle.putInt(KEY_STATUS, IpSecManager.Status.OK);
            retBundle.putInt(KEY_RESOURCE_ID, resourceId);
            retBundle.putInt(KEY_SPI, spi);
            synchronized (mSpiRecords) {
                mSpiRecords.put(
                        resourceId,
                        new SpiRecord(
                                resourceId, direction, localAddress, remoteAddress, spi, binder));
            }
        } catch (ServiceSpecificException e) {
            // TODO: Add appropriate checks when other ServiceSpecificException types are supported
            retBundle.putInt(KEY_STATUS, IpSecManager.Status.SPI_UNAVAILABLE);
            retBundle.putInt(KEY_RESOURCE_ID, resourceId);
            retBundle.putInt(KEY_SPI, spi);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return retBundle;
    }

    /** Release a previously allocated SPI that has been registered with the system server */
    @Override
    public void releaseSecurityParameterIndex(int resourceId) throws RemoteException {}

    /**
     * Open a socket via the system server and bind it to the specified port (random if port=0).
     * This will return a PFD to the user that represent a bound UDP socket. The system server will
     * cache the socket and a record of its owner so that it can and must be freed when no longer
     * needed.
     */
    @Override
    public Bundle openUdpEncapsulationSocket(int port, IBinder binder) throws RemoteException {
        return null;
    }

    /** close a socket that has been been allocated by and registered with the system server */
    @Override
    public void closeUdpEncapsulationSocket(ParcelFileDescriptor socket) {}

    /**
     * Create a transport mode transform, which represent two security associations (one in each
     * direction) in the kernel. The transform will be cached by the system server and must be freed
     * when no longer needed. It is possible to free one, deleting the SA from underneath sockets
     * that are using it, which will result in all of those sockets becoming unable to send or
     * receive data.
     */
    @Override
    public Bundle createTransportModeTransform(IpSecConfig c, IBinder binder)
            throws RemoteException {
        // TODO: Basic input validation here since it's coming over the Binder
        int resourceId = mNextResourceId.getAndIncrement();
        for (int direction : DIRECTIONS) {
            IpSecAlgorithm auth = c.getAuthentication(direction);
            IpSecAlgorithm crypt = c.getEncryption(direction);
            try {
                int result =
                        getNetdInstance()
                                .ipSecAddSecurityAssociation(
                                        resourceId,
                                        c.getMode(),
                                        direction,
                                        (c.getLocalAddress() != null)
                                                ? c.getLocalAddress().getHostAddress()
                                                : "",
                                        (c.getRemoteAddress() != null)
                                                ? c.getRemoteAddress().getHostAddress()
                                                : "",
                                        (c.getNetwork() != null)
                                                ? c.getNetwork().getNetworkHandle()
                                                : 0,
                                        c.getSpi(direction),
                                        (auth != null) ? auth.getName() : "",
                                        (auth != null) ? auth.getKey() : null,
                                        (auth != null) ? auth.getTruncationLengthBits() : 0,
                                        (crypt != null) ? crypt.getName() : "",
                                        (crypt != null) ? crypt.getKey() : null,
                                        (crypt != null) ? crypt.getTruncationLengthBits() : 0,
                                        c.getEncapType(),
                                        c.getEncapLocalPort(),
                                        c.getEncapRemotePort());
                if (result != c.getSpi(direction)) {
                    // TODO: cleanup the first SA if creation of second SA fails
                    Bundle retBundle = new Bundle(2);
                    retBundle.putInt(KEY_STATUS, IpSecManager.Status.SPI_UNAVAILABLE);
                    retBundle.putInt(KEY_RESOURCE_ID, INVALID_RESOURCE_ID);
                    return retBundle;
                }
            } catch (ServiceSpecificException e) {
                // FIXME: get the error code and throw is at an IOException from Errno Exception
            }
        }
        synchronized (mTransformRecords) {
            mTransformRecords.put(resourceId, new TransformRecord(c, resourceId, binder));
        }

        Bundle retBundle = new Bundle(2);
        retBundle.putInt(KEY_STATUS, IpSecManager.Status.OK);
        retBundle.putInt(KEY_RESOURCE_ID, resourceId);
        return retBundle;
    }

    /**
     * Delete a transport mode transform that was previously allocated by + registered with the
     * system server. If this is called on an inactive (or non-existent) transform, it will not
     * return an error. It's safe to de-allocate transforms that may have already been deleted for
     * other reasons.
     */
    @Override
    public void deleteTransportModeTransform(int resourceId) throws RemoteException {
        synchronized (mTransformRecords) {
            TransformRecord record;
            // We want to non-destructively get so that we can check credentials before removing
            // this from the records.
            record = mTransformRecords.get(resourceId);

            if (record == null) {
                throw new IllegalArgumentException(
                        "Transform " + resourceId + " is not available to be deleted");
            }

            if (record.pid != Binder.getCallingPid() || record.uid != Binder.getCallingUid()) {
                throw new SecurityException("Only the owner of an IpSec Transform may delete it!");
            }

            // TODO: if releaseResources() throws RemoteException, we can try again to clean up on
            // binder death. Need to make sure that path is actually functional.
            record.releaseResources();
            mTransformRecords.remove(resourceId);
            record.nullifyRecord();
        }
    }

    /**
     * Apply an active transport mode transform to a socket, which will apply the IPsec security
     * association as a correspondent policy to the provided socket
     */
    @Override
    public void applyTransportModeTransform(ParcelFileDescriptor socket, int resourceId)
            throws RemoteException {

        synchronized (mTransformRecords) {
            TransformRecord info;
            // FIXME: this code should be factored out into a security check + getter
            info = mTransformRecords.get(resourceId);

            if (info == null) {
                throw new IllegalArgumentException("Transform " + resourceId + " is not active");
            }

            // TODO: make this a function.
            if (info.pid != getCallingPid() || info.uid != getCallingUid()) {
                throw new SecurityException("Only the owner of an IpSec Transform may apply it!");
            }

            IpSecConfig c = info.getConfig();
            try {
                for (int direction : DIRECTIONS) {
                    getNetdInstance()
                            .ipSecApplyTransportModeTransform(
                                    socket.getFileDescriptor(),
                                    resourceId,
                                    direction,
                                    (c.getLocalAddress() != null)
                                            ? c.getLocalAddress().getHostAddress()
                                            : "",
                                    (c.getRemoteAddress() != null)
                                            ? c.getRemoteAddress().getHostAddress()
                                            : "",
                                    c.getSpi(direction));
                }
            } catch (ServiceSpecificException e) {
                // FIXME: get the error code and throw is at an IOException from Errno Exception
            }
        }
    }
    /**
     * Remove a transport mode transform from a socket, applying the default (empty) policy. This
     * will ensure that NO IPsec policy is applied to the socket (would be the equivalent of
     * applying a policy that performs no IPsec). Today the resourceId parameter is passed but not
     * used: reserved for future improved input validation.
     */
    @Override
    public void removeTransportModeTransform(ParcelFileDescriptor socket, int resourceId)
            throws RemoteException {
        try {
            getNetdInstance().ipSecRemoveTransportModeTransform(socket.getFileDescriptor());
        } catch (ServiceSpecificException e) {
            // FIXME: get the error code and throw is at an IOException from Errno Exception
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        pw.println("IpSecService Log:");
        pw.println("NetdNativeService Connection: " + (isNetdAlive() ? "alive" : "dead"));
        pw.println();
    }
}
