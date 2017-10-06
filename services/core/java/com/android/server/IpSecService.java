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
import android.net.NetworkUtils;
import android.net.util.NetdService;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

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

    private static final int NETD_FETCH_TIMEOUT_MS = 5000; // ms
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

    interface IpSecServiceConfiguration {
        INetd getNetdInstance() throws RemoteException;

        static IpSecServiceConfiguration GETSRVINSTANCE =
                new IpSecServiceConfiguration() {
                    @Override
                    public INetd getNetdInstance() throws RemoteException {
                        final INetd netd = NetdService.getInstance();
                        if (netd == null) {
                            throw new RemoteException("Failed to Get Netd Instance");
                        }
                        return netd;
                    }
                };
    }

    private final IpSecServiceConfiguration mSrvConfig;

    /* Very simple counting class that looks much like a counting semaphore */
    public static class ResourceTracker {
        private final int mMax;
        int mCurrent;

        ResourceTracker(int max) {
            mMax = max;
            mCurrent = 0;
        }

        synchronized boolean isAvailable() {
            return (mCurrent < mMax);
        }

        synchronized void take() {
            if (!isAvailable()) {
                Log.wtf(TAG, "Too many resources allocated!");
            }
            mCurrent++;
        }

        synchronized void give() {
            if (mCurrent <= 0) {
                Log.wtf(TAG, "We've released this resource too many times");
            }
            mCurrent--;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{mCurrent=")
                    .append(mCurrent)
                    .append(", mMax=")
                    .append(mMax)
                    .append("}")
                    .toString();
        }
    }

    private static final class UserQuotaTracker {
        /* Maximum number of UDP Encap Sockets that a single UID may possess */
        public static final int MAX_NUM_ENCAP_SOCKETS = 2;

        /* Maximum number of IPsec Transforms that a single UID may possess */
        public static final int MAX_NUM_TRANSFORMS = 4;

        /* Maximum number of IPsec Transforms that a single UID may possess */
        public static final int MAX_NUM_SPIS = 8;

        /* Record for one users's IpSecService-managed objects */
        public static class UserRecord {
            public final ResourceTracker socket = new ResourceTracker(MAX_NUM_ENCAP_SOCKETS);
            public final ResourceTracker transform = new ResourceTracker(MAX_NUM_TRANSFORMS);
            public final ResourceTracker spi = new ResourceTracker(MAX_NUM_SPIS);

            @Override
            public String toString() {
                return new StringBuilder()
                        .append("{socket=")
                        .append(socket)
                        .append(", transform=")
                        .append(transform)
                        .append(", spi=")
                        .append(spi)
                        .append("}")
                        .toString();
            }
        }

        private final SparseArray<UserRecord> mUserRecords = new SparseArray<>();

        /* a never-fail getter so that we can populate the list of UIDs as-needed */
        public synchronized UserRecord getUserRecord(int uid) {
            UserRecord r = mUserRecords.get(uid);
            if (r == null) {
                r = new UserRecord();
                mUserRecords.put(uid, r);
            }
            return r;
        }

        @Override
        public String toString() {
            return mUserRecords.toString();
        }
    }

    private final UserQuotaTracker mUserQuotaTracker = new UserQuotaTracker();

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
            if (resourceId == INVALID_RESOURCE_ID) {
                throw new IllegalArgumentException("Resource ID must not be INVALID_RESOURCE_ID");
            }
            mBinder = binder;
            mResourceId = resourceId;
            pid = Binder.getCallingPid();
            uid = Binder.getCallingUid();

            getResourceTracker().take();
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

        /**
         * Ensures that the caller is either the owner of this resource or has the system UID and
         * throws a SecurityException otherwise.
         */
        public void checkOwnerOrSystem() {
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
                getResourceTracker().give();
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

        /** Get the resource tracker for this resource */
        protected abstract ResourceTracker getResourceTracker();

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{mResourceId=")
                    .append(mResourceId)
                    .append(", pid=")
                    .append(pid)
                    .append(", uid=")
                    .append(uid)
                    .append(", mReferenceCount=")
                    .append(mReferenceCount.get())
                    .append("}")
                    .toString();
        }
    };

    /**
     * Minimal wrapper around SparseArray that performs ownership validation on element accesses.
     */
    private class ManagedResourceArray<T extends ManagedResource> {
        SparseArray<T> mArray = new SparseArray<>();

        T getAndCheckOwner(int key) {
            T val = mArray.get(key);
            // The value should never be null unless the resource doesn't exist
            // (since we do not allow null resources to be added).
            if (val != null) {
                val.checkOwnerOrSystem();
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

        @Override
        public String toString() {
            return mArray.toString();
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
                    mSrvConfig
                            .getNetdInstance()
                            .ipSecDeleteSecurityAssociation(
                                    mResourceId,
                                    direction,
                                    mConfig.getLocalAddress(),
                                    mConfig.getRemoteAddress(),
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

        protected ResourceTracker getResourceTracker() {
            return mUserQuotaTracker.getUserRecord(this.uid).transform;
        }

        @Override
        public String toString() {
            StringBuilder strBuilder = new StringBuilder();
            strBuilder
                    .append("{super=")
                    .append(super.toString())
                    .append(", mSocket=")
                    .append(mSocket)
                    .append(", mSpis[OUT].mResourceId=")
                    .append(mSpis[IpSecTransform.DIRECTION_OUT].mResourceId)
                    .append(", mSpis[IN].mResourceId=")
                    .append(mSpis[IpSecTransform.DIRECTION_IN].mResourceId)
                    .append(", mConfig=")
                    .append(mConfig)
                    .append("}");
            return strBuilder.toString();
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
                mSrvConfig
                        .getNetdInstance()
                        .ipSecDeleteSecurityAssociation(
                                mResourceId, mDirection, mLocalAddress, mRemoteAddress, mSpi);
            } catch (ServiceSpecificException e) {
                // FIXME: get the error code and throw is at an IOException from Errno Exception
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to delete SPI reservation with ID: " + mResourceId);
            }

            mSpi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;
        }

        @Override
        protected ResourceTracker getResourceTracker() {
            return mUserQuotaTracker.getUserRecord(this.uid).spi;
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

        @Override
        public String toString() {
            StringBuilder strBuilder = new StringBuilder();
            strBuilder
                    .append("{super=")
                    .append(super.toString())
                    .append(", mSpi=")
                    .append(mSpi)
                    .append(", mDirection=")
                    .append(mDirection)
                    .append(", mLocalAddress=")
                    .append(mLocalAddress)
                    .append(", mRemoteAddress=")
                    .append(mRemoteAddress)
                    .append(", mOwnedByTransform=")
                    .append(mOwnedByTransform)
                    .append("}");
            return strBuilder.toString();
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

        @Override
        protected ResourceTracker getResourceTracker() {
            return mUserQuotaTracker.getUserRecord(this.uid).socket;
        }

        public int getPort() {
            return mPort;
        }

        public FileDescriptor getSocket() {
            return mSocket;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{super=")
                    .append(super.toString())
                    .append(", mSocket=")
                    .append(mSocket)
                    .append(", mPort=")
                    .append(mPort)
                    .append("}")
                    .toString();
        }
    }

    /**
     * Constructs a new IpSecService instance
     *
     * @param context Binder context for this service
     */
    private IpSecService(Context context) {
        this(context, IpSecServiceConfiguration.GETSRVINSTANCE);
    }

    static IpSecService create(Context context) throws InterruptedException {
        final IpSecService service = new IpSecService(context);
        service.connectNativeNetdService();
        return service;
    }

    /** @hide */
    @VisibleForTesting
    public IpSecService(Context context, IpSecServiceConfiguration config) {
        mContext = context;
        mSrvConfig = config;
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
                    NetdService.get(NETD_FETCH_TIMEOUT_MS);
                }
            }
        }.start();
    }

    synchronized boolean isNetdAlive() {
        try {
            final INetd netd = mSrvConfig.getNetdInstance();
            if (netd == null) {
                return false;
            }
            return netd.isAlive();
        } catch (RemoteException re) {
            return false;
        }
    }

    /**
     * Checks that the provided InetAddress is valid for use in an IPsec SA. The address must not be
     * a wildcard address and must be in a numeric form such as 1.2.3.4 or 2001::1.
     */
    private static void checkInetAddress(String inetAddress) {
        if (TextUtils.isEmpty(inetAddress)) {
            throw new IllegalArgumentException("Unspecified address");
        }

        InetAddress checkAddr = NetworkUtils.numericToInetAddress(inetAddress);

        if (checkAddr.isAnyLocalAddress()) {
            throw new IllegalArgumentException("Inappropriate wildcard address: " + inetAddress);
        }
    }

    /**
     * Checks the user-provided direction field and throws an IllegalArgumentException if it is not
     * DIRECTION_IN or DIRECTION_OUT
     */
    private static void checkDirection(int direction) {
        switch (direction) {
            case IpSecTransform.DIRECTION_OUT:
            case IpSecTransform.DIRECTION_IN:
                return;
        }
        throw new IllegalArgumentException("Invalid Direction: " + direction);
    }

    @Override
    /** Get a new SPI and maintain the reservation in the system server */
    public synchronized IpSecSpiResponse reserveSecurityParameterIndex(
            int direction, String remoteAddress, int requestedSpi, IBinder binder)
            throws RemoteException {
        checkDirection(direction);
        checkInetAddress(remoteAddress);
        /* requestedSpi can be anything in the int range, so no check is needed. */
        checkNotNull(binder, "Null Binder passed to reserveSecurityParameterIndex");

        int resourceId = mNextResourceId.getAndIncrement();

        int spi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;
        String localAddress = "";

        try {
            if (!mUserQuotaTracker.getUserRecord(Binder.getCallingUid()).spi.isAvailable()) {
                return new IpSecSpiResponse(
                        IpSecManager.Status.RESOURCE_UNAVAILABLE, INVALID_RESOURCE_ID, spi);
            }
            spi =
                    mSrvConfig
                            .getNetdInstance()
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
                    IpSecManager.Status.SPI_UNAVAILABLE, INVALID_RESOURCE_ID, spi);
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
        T record = resArray.getAndCheckOwner(resourceId);

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
        checkNotNull(binder, "Null Binder passed to openUdpEncapsulationSocket");

        int resourceId = mNextResourceId.getAndIncrement();
        FileDescriptor sockFd = null;
        try {
            if (!mUserQuotaTracker.getUserRecord(Binder.getCallingUid()).socket.isAvailable()) {
                return new IpSecUdpEncapResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE);
            }

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
     * Checks an IpSecConfig parcel to ensure that the contents are sane and throws an
     * IllegalArgumentException if they are not.
     */
    private void checkIpSecConfig(IpSecConfig config) {
        if (config.getLocalAddress() == null) {
            throw new IllegalArgumentException("Invalid null Local InetAddress");
        }

        if (config.getRemoteAddress() == null) {
            throw new IllegalArgumentException("Invalid null Remote InetAddress");
        }

        switch (config.getMode()) {
            case IpSecTransform.MODE_TRANSPORT:
                if (!config.getLocalAddress().isEmpty()) {
                    throw new IllegalArgumentException("Non-empty Local Address");
                }
                // Must be valid, and not a wildcard
                checkInetAddress(config.getRemoteAddress());
                break;
            case IpSecTransform.MODE_TUNNEL:
                break;
            default:
                throw new IllegalArgumentException(
                        "Invalid IpSecTransform.mode: " + config.getMode());
        }

        switch (config.getEncapType()) {
            case IpSecTransform.ENCAP_NONE:
                break;
            case IpSecTransform.ENCAP_ESPINUDP:
            case IpSecTransform.ENCAP_ESPINUDP_NON_IKE:
                if (mUdpSocketRecords.getAndCheckOwner(
                            config.getEncapSocketResourceId()) == null) {
                    throw new IllegalStateException(
                            "No Encapsulation socket for Resource Id: "
                                    + config.getEncapSocketResourceId());
                }

                int port = config.getEncapRemotePort();
                if (port <= 0 || port > 0xFFFF) {
                    throw new IllegalArgumentException("Invalid remote UDP port: " + port);
                }
                break;
            default:
                throw new IllegalArgumentException("Invalid Encap Type: " + config.getEncapType());
        }

        for (int direction : DIRECTIONS) {
            IpSecAlgorithm crypt = config.getEncryption(direction);
            IpSecAlgorithm auth = config.getAuthentication(direction);
            if (crypt == null && auth == null) {
                throw new IllegalArgumentException("Encryption and Authentication are both null");
            }

            if (mSpiRecords.getAndCheckOwner(config.getSpiResourceId(direction)) == null) {
                throw new IllegalStateException("No SPI for specified Resource Id");
            }
        }
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
        checkIpSecConfig(c);
        checkNotNull(binder, "Null Binder passed to createTransportModeTransform");
        int resourceId = mNextResourceId.getAndIncrement();
        if (!mUserQuotaTracker.getUserRecord(Binder.getCallingUid()).transform.isAvailable()) {
            return new IpSecTransformResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE);
        }
        SpiRecord[] spis = new SpiRecord[DIRECTIONS.length];

        int encapType, encapLocalPort = 0, encapRemotePort = 0;
        UdpSocketRecord socketRecord = null;
        encapType = c.getEncapType();
        if (encapType != IpSecTransform.ENCAP_NONE) {
            socketRecord = mUdpSocketRecords.getAndCheckOwner(c.getEncapSocketResourceId());
            encapLocalPort = socketRecord.getPort();
            encapRemotePort = c.getEncapRemotePort();
        }

        for (int direction : DIRECTIONS) {
            IpSecAlgorithm auth = c.getAuthentication(direction);
            IpSecAlgorithm crypt = c.getEncryption(direction);

            spis[direction] = mSpiRecords.getAndCheckOwner(c.getSpiResourceId(direction));
            int spi = spis[direction].getSpi();
            try {
                mSrvConfig
                        .getNetdInstance()
                        .ipSecAddSecurityAssociation(
                                resourceId,
                                c.getMode(),
                                direction,
                                c.getLocalAddress(),
                                c.getRemoteAddress(),
                                (c.getNetwork() != null) ? c.getNetwork().getNetworkHandle() : 0,
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
        info = mTransformRecords.getAndCheckOwner(resourceId);

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
                mSrvConfig
                        .getNetdInstance()
                        .ipSecApplyTransportModeTransform(
                                socket.getFileDescriptor(),
                                resourceId,
                                direction,
                                c.getLocalAddress(),
                                c.getRemoteAddress(),
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
            mSrvConfig
                    .getNetdInstance()
                    .ipSecRemoveTransportModeTransform(socket.getFileDescriptor());
        } catch (ServiceSpecificException e) {
            // FIXME: get the error code and throw is at an IOException from Errno Exception
        }
    }

    @Override
    protected synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        pw.println("IpSecService dump:");
        pw.println("NetdNativeService Connection: " + (isNetdAlive() ? "alive" : "dead"));
        pw.println();

        pw.println("mUserQuotaTracker:");
        pw.println(mUserQuotaTracker);
        pw.println("mTransformRecords:");
        pw.println(mTransformRecords);
        pw.println("mUdpSocketRecords:");
        pw.println(mUdpSocketRecords);
        pw.println("mSpiRecords:");
        pw.println(mSpiRecords);
    }
}
