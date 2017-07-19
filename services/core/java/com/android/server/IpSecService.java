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
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.content.Context;
import android.net.IIpSecService;
import android.net.INetd;
import android.net.IpSecAlgorithm;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecSpiResponse;
import android.net.IpSecTransform;
import android.net.IpSecTransformResponse;
import android.net.IpSecUdpEncapResponse;
import android.net.util.NetdService;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;
import libcore.io.IoUtils;

/** @hide */
public class IpSecService extends IIpSecService.Stub {
    private static final String TAG = "IpSecService";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String NETD_SERVICE_NAME = "netd";
    private static final int[] DIRECTIONS =
            new int[] {IpSecTransform.DIRECTION_OUT, IpSecTransform.DIRECTION_IN};

    private static final int NETD_FETCH_TIMEOUT = 5000; //ms
    private static final int MAX_PORT_BIND_ATTEMPTS = 10;
    private static final InetAddress INADDR_ANY;

    static {
        try {
            INADDR_ANY = InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    static final int FREE_PORT_MIN = 1024; // ports 1-1023 are reserved
    static final int PORT_MAX = 0xFFFF; // ports are an unsigned 16-bit integer

    /* Binder context for this service */
    private final Context mContext;

    /** Should be a never-repeating global ID for resources */
    private static AtomicInteger mNextResourceId = new AtomicInteger(0x00FADED0);

    @GuardedBy("this")
    private final ManagedResourceArray<SpiRecord> mSpiRecords = new ManagedResourceArray<>();

    @GuardedBy("this")
    private final ManagedResourceArray<TransformRecord> mTransformRecords =
            new ManagedResourceArray<>();

    @GuardedBy("this")
    private final ManagedResourceArray<UdpSocketRecord> mUdpSocketRecords =
            new ManagedResourceArray<>();

    /**
     * The ManagedResource class provides a facility to cleanly and reliably release system
     * resources. It relies on two things: an IBinder that allows ManagedResource to automatically
     * clean up in the event that the Binder dies and a user-provided resourceId that should
     * uniquely identify the managed resource. To use this class, the user should implement the
     * releaseResources() method that is responsible for releasing system resources when invoked.
     */
    private abstract class ManagedResource implements IBinder.DeathRecipient {
        final int pid;
        final int uid;
        private IBinder mBinder;
        protected int mResourceId;

        private AtomicInteger mReferenceCount = new AtomicInteger(0);

        ManagedResource(int resourceId, IBinder binder) {
            super();
            mBinder = binder;
            mResourceId = resourceId;
            pid = Binder.getCallingPid();
            uid = Binder.getCallingUid();

            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        public void addReference() {
            mReferenceCount.incrementAndGet();
        }

        public void removeReference() {
            if (mReferenceCount.decrementAndGet() < 0) {
                Log.wtf(TAG, "Programming error: negative reference count");
            }
        }

        public boolean isReferenced() {
            return (mReferenceCount.get() > 0);
        }

        public void checkOwnerOrSystemAndThrow() {
            if (uid != Binder.getCallingUid()
                    && android.os.Process.SYSTEM_UID != Binder.getCallingUid()) {
                throw new SecurityException("Only the owner may access managed resources!");
            }
        }

        /**
         * When this record is no longer needed for managing system resources this function should
         * clean up all system resources and nullify the record. This function shall perform all
         * necessary cleanup of the resources managed by this record.
         *
         * <p>NOTE: this function verifies ownership before allowing resources to be freed.
         */
        public final void release() throws RemoteException {
            synchronized (IpSecService.this) {
                if (isReferenced()) {
                    throw new IllegalStateException(
                            "Cannot release a resource that has active references!");
                }

                if (mResourceId == INVALID_RESOURCE_ID) {
                    return;
                }

                releaseResources();
                if (mBinder != null) {
                    mBinder.unlinkToDeath(this, 0);
                }
                mBinder = null;

                mResourceId = INVALID_RESOURCE_ID;
            }
        }

        /**
         * If the Binder object dies, this function is called to free the system resources that are
         * being managed by this record and to subsequently release this record for garbage
         * collection
         */
        public final void binderDied() {
            try {
                release();
            } catch (Exception e) {
                Log.e(TAG, "Failed to release resource: " + e);
            }
        }

        /**
         * Implement this method to release all system resources that are being protected by this
         * record. Once the resources are released, the record should be invalidated and no longer
         * used by calling release(). This should NEVER be called directly.
         *
         * <p>Calls to this are always guarded by IpSecService#this
         */
        protected abstract void releaseResources() throws RemoteException;
    };

    /**
     * Minimal wrapper around SparseArray that performs ownership
     * validation on element accesses.
     */
    private class ManagedResourceArray<T extends ManagedResource> {
        SparseArray<T> mArray = new SparseArray<>();

        T get(int key) {
            T val = mArray.get(key);
            // The value should never be null unless the resource doesn't exist
            // (since we do not allow null resources to be added).
            if (val != null) {
                val.checkOwnerOrSystemAndThrow();
            }
            return val;
        }

        void put(int key, T obj) {
            checkNotNull(obj, "Null resources cannot be added");
            mArray.put(key, obj);
        }

        void remove(int key) {
            mArray.remove(key);
        }
    }

    private final class TransformRecord extends ManagedResource {
        private final IpSecConfig mConfig;
        private final SpiRecord[] mSpis;
        private final UdpSocketRecord mSocket;

        TransformRecord(
                int resourceId,
                IBinder binder,
                IpSecConfig config,
                SpiRecord[] spis,
                UdpSocketRecord socket) {
            super(resourceId, binder);
            mConfig = config;
            mSpis = spis;
            mSocket = socket;

            for (int direction : DIRECTIONS) {
                mSpis[direction].addReference();
                mSpis[direction].setOwnedByTransform();
            }

            if (mSocket != null) {
                mSocket.addReference();
            }
        }

        public IpSecConfig getConfig() {
            return mConfig;
        }

        public SpiRecord getSpiRecord(int direction) {
            return mSpis[direction];
        }

        /** always guarded by IpSecService#this */
        @Override
        protected void releaseResources() {
            for (int direction : DIRECTIONS) {
                int spi = mSpis[direction].getSpi();
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
                                    spi);
                } catch (ServiceSpecificException e) {
                    // FIXME: get the error code and throw is at an IOException from Errno Exception
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to delete SA with ID: " + mResourceId);
                }
            }

            for (int direction : DIRECTIONS) {
                mSpis[direction].removeReference();
            }

            if (mSocket != null) {
                mSocket.removeReference();
            }
        }
    }

    private final class SpiRecord extends ManagedResource {
        private final int mDirection;
        private final String mLocalAddress;
        private final String mRemoteAddress;
        private int mSpi;

        private boolean mOwnedByTransform = false;

        SpiRecord(
                int resourceId,
                IBinder binder,
                int direction,
                String localAddress,
                String remoteAddress,
                int spi) {
            super(resourceId, binder);
            mDirection = direction;
            mLocalAddress = localAddress;
            mRemoteAddress = remoteAddress;
            mSpi = spi;
        }

        /** always guarded by IpSecService#this */
        @Override
        protected void releaseResources() {
            if (mOwnedByTransform) {
                Log.d(TAG, "Cannot release Spi " + mSpi + ": Currently locked by a Transform");
                // Because SPIs are "handed off" to transform, objects, they should never be
                // freed from the SpiRecord once used in a transform. (They refer to the same SA,
                // thus ownership and responsibility for freeing these resources passes to the
                // Transform object). Thus, we should let the user free them without penalty once
                // they are applied in a Transform object.
                return;
            }

            try {
                getNetdInstance()
                        .ipSecDeleteSecurityAssociation(
                                mResourceId, mDirection, mLocalAddress, mRemoteAddress, mSpi);
            } catch (ServiceSpecificException e) {
                // FIXME: get the error code and throw is at an IOException from Errno Exception
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to delete SPI reservation with ID: " + mResourceId);
            }

            mSpi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;
        }

        public int getSpi() {
            return mSpi;
        }

        public void setOwnedByTransform() {
            if (mOwnedByTransform) {
                // Programming error
                throw new IllegalStateException("Cannot own an SPI twice!");
            }

            mOwnedByTransform = true;
        }
    }

    private final class UdpSocketRecord extends ManagedResource {
        private FileDescriptor mSocket;
        private final int mPort;

        UdpSocketRecord(int resourceId, IBinder binder, FileDescriptor socket, int port) {
            super(resourceId, binder);
            mSocket = socket;
            mPort = port;
        }

        /** always guarded by IpSecService#this */
        @Override
        protected void releaseResources() {
            Log.d(TAG, "Closing port " + mPort);
            IoUtils.closeQuietly(mSocket);
            mSocket = null;
        }

        public int getPort() {
            return mPort;
        }

        public FileDescriptor getSocket() {
            return mSocket;
        }
    }

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
        new Thread() {
            @Override
            public void run() {
                synchronized (IpSecService.this) {
                    NetdService.get(NETD_FETCH_TIMEOUT);
                }
            }
        }.start();
    }

    INetd getNetdInstance() throws RemoteException {
        final INetd netd = NetdService.getInstance();
        if (netd == null) {
            throw new RemoteException("Failed to Get Netd Instance");
        }
        return netd;
    }

    synchronized boolean isNetdAlive() {
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

    @Override
    /** Get a new SPI and maintain the reservation in the system server */
    public synchronized IpSecSpiResponse reserveSecurityParameterIndex(
            int direction, String remoteAddress, int requestedSpi, IBinder binder)
            throws RemoteException {
        int resourceId = mNextResourceId.getAndIncrement();

        int spi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;
        String localAddress = "";
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
            mSpiRecords.put(
                    resourceId,
                    new SpiRecord(resourceId, binder, direction, localAddress, remoteAddress, spi));
        } catch (ServiceSpecificException e) {
            // TODO: Add appropriate checks when other ServiceSpecificException types are supported
            return new IpSecSpiResponse(
                    IpSecManager.Status.SPI_UNAVAILABLE, IpSecManager.INVALID_RESOURCE_ID, spi);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return new IpSecSpiResponse(IpSecManager.Status.OK, resourceId, spi);
    }

    /* This method should only be called from Binder threads. Do not call this from
     * within the system server as it will crash the system on failure.
     */
    private synchronized <T extends ManagedResource> void releaseManagedResource(
            ManagedResourceArray<T> resArray, int resourceId, String typeName)
            throws RemoteException {
        // We want to non-destructively get so that we can check credentials before removing
        // this from the records.
        T record = resArray.get(resourceId);

        if (record == null) {
            throw new IllegalArgumentException(
                    typeName + " " + resourceId + " is not available to be deleted");
        }

        record.release();
        resArray.remove(resourceId);
    }

    /** Release a previously allocated SPI that has been registered with the system server */
    @Override
    public void releaseSecurityParameterIndex(int resourceId) throws RemoteException {
        releaseManagedResource(mSpiRecords, resourceId, "SecurityParameterIndex");
    }

    /**
     * This function finds and forcibly binds to a random system port, ensuring that the port cannot
     * be unbound.
     *
     * <p>A socket cannot be un-bound from a port if it was bound to that port by number. To select
     * a random open port and then bind by number, this function creates a temp socket, binds to a
     * random port (specifying 0), gets that port number, and then uses is to bind the user's UDP
     * Encapsulation Socket forcibly, so that it cannot be un-bound by the user with the returned
     * FileHandle.
     *
     * <p>The loop in this function handles the inherent race window between un-binding to a port
     * and re-binding, during which the system could *technically* hand that port out to someone
     * else.
     */
    private void bindToRandomPort(FileDescriptor sockFd) throws IOException {
        for (int i = MAX_PORT_BIND_ATTEMPTS; i > 0; i--) {
            try {
                FileDescriptor probeSocket = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
                Os.bind(probeSocket, INADDR_ANY, 0);
                int port = ((InetSocketAddress) Os.getsockname(probeSocket)).getPort();
                Os.close(probeSocket);
                Log.v(TAG, "Binding to port " + port);
                Os.bind(sockFd, INADDR_ANY, port);
                return;
            } catch (ErrnoException e) {
                // Someone miraculously claimed the port just after we closed probeSocket.
                if (e.errno == OsConstants.EADDRINUSE) {
                    continue;
                }
                throw e.rethrowAsIOException();
            }
        }
        throw new IOException("Failed " + MAX_PORT_BIND_ATTEMPTS + " attempts to bind to a port");
    }

    /**
     * Open a socket via the system server and bind it to the specified port (random if port=0).
     * This will return a PFD to the user that represent a bound UDP socket. The system server will
     * cache the socket and a record of its owner so that it can and must be freed when no longer
     * needed.
     */
    @Override
    public synchronized IpSecUdpEncapResponse openUdpEncapsulationSocket(int port, IBinder binder)
            throws RemoteException {
        if (port != 0 && (port < FREE_PORT_MIN || port > PORT_MAX)) {
            throw new IllegalArgumentException(
                    "Specified port number must be a valid non-reserved UDP port");
        }
        int resourceId = mNextResourceId.getAndIncrement();
        FileDescriptor sockFd = null;
        try {
            sockFd = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);

            if (port != 0) {
                Log.v(TAG, "Binding to port " + port);
                Os.bind(sockFd, INADDR_ANY, port);
            } else {
                bindToRandomPort(sockFd);
            }
            // This code is common to both the unspecified and specified port cases
            Os.setsockoptInt(
                    sockFd,
                    OsConstants.IPPROTO_UDP,
                    OsConstants.UDP_ENCAP,
                    OsConstants.UDP_ENCAP_ESPINUDP);

            mUdpSocketRecords.put(
                    resourceId, new UdpSocketRecord(resourceId, binder, sockFd, port));
            return new IpSecUdpEncapResponse(IpSecManager.Status.OK, resourceId, port, sockFd);
        } catch (IOException | ErrnoException e) {
            IoUtils.closeQuietly(sockFd);
        }
        // If we make it to here, then something has gone wrong and we couldn't open a socket.
        // The only reasonable condition that would cause that is resource unavailable.
        return new IpSecUdpEncapResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE);
    }

    /** close a socket that has been been allocated by and registered with the system server */
    @Override
    public void closeUdpEncapsulationSocket(int resourceId) throws RemoteException {

        releaseManagedResource(mUdpSocketRecords, resourceId, "UdpEncapsulationSocket");
    }

    /**
     * Create a transport mode transform, which represent two security associations (one in each
     * direction) in the kernel. The transform will be cached by the system server and must be freed
     * when no longer needed. It is possible to free one, deleting the SA from underneath sockets
     * that are using it, which will result in all of those sockets becoming unable to send or
     * receive data.
     */
    @Override
    public synchronized IpSecTransformResponse createTransportModeTransform(
            IpSecConfig c, IBinder binder) throws RemoteException {
        int resourceId = mNextResourceId.getAndIncrement();
        SpiRecord[] spis = new SpiRecord[DIRECTIONS.length];
        // TODO: Basic input validation here since it's coming over the Binder
        int encapType, encapLocalPort = 0, encapRemotePort = 0;
        UdpSocketRecord socketRecord = null;
        encapType = c.getEncapType();
        if (encapType != IpSecTransform.ENCAP_NONE) {
            socketRecord = mUdpSocketRecords.get(c.getEncapLocalResourceId());
            encapLocalPort = socketRecord.getPort();
            encapRemotePort = c.getEncapRemotePort();
        }

        for (int direction : DIRECTIONS) {
            IpSecAlgorithm auth = c.getAuthentication(direction);
            IpSecAlgorithm crypt = c.getEncryption(direction);

            spis[direction] = mSpiRecords.get(c.getSpiResourceId(direction));
            int spi = spis[direction].getSpi();
            try {
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
                                spi,
                                (auth != null) ? auth.getName() : "",
                                (auth != null) ? auth.getKey() : null,
                                (auth != null) ? auth.getTruncationLengthBits() : 0,
                                (crypt != null) ? crypt.getName() : "",
                                (crypt != null) ? crypt.getKey() : null,
                                (crypt != null) ? crypt.getTruncationLengthBits() : 0,
                                encapType,
                                encapLocalPort,
                                encapRemotePort);
            } catch (ServiceSpecificException e) {
                // FIXME: get the error code and throw is at an IOException from Errno Exception
                return new IpSecTransformResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE);
            }
        }
        // Both SAs were created successfully, time to construct a record and lock it away
        mTransformRecords.put(
                resourceId, new TransformRecord(resourceId, binder, c, spis, socketRecord));
        return new IpSecTransformResponse(IpSecManager.Status.OK, resourceId);
    }

    /**
     * Delete a transport mode transform that was previously allocated by + registered with the
     * system server. If this is called on an inactive (or non-existent) transform, it will not
     * return an error. It's safe to de-allocate transforms that may have already been deleted for
     * other reasons.
     */
    @Override
    public void deleteTransportModeTransform(int resourceId) throws RemoteException {
        releaseManagedResource(mTransformRecords, resourceId, "IpSecTransform");
    }

    /**
     * Apply an active transport mode transform to a socket, which will apply the IPsec security
     * association as a correspondent policy to the provided socket
     */
    @Override
    public synchronized void applyTransportModeTransform(
            ParcelFileDescriptor socket, int resourceId) throws RemoteException {
        // Synchronize liberally here because we are using ManagedResources in this block
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
                                info.getSpiRecord(direction).getSpi());
            }
        } catch (ServiceSpecificException e) {
            // FIXME: get the error code and throw is at an IOException from Errno Exception
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
        // TODO: Add dump code to print out a log of all the resources being tracked
        pw.println("IpSecService Log:");
        pw.println("NetdNativeService Connection: " + (isNetdAlive() ? "alive" : "dead"));
        pw.println();
    }
}
